// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.UUID

enum class StudyAction { CREATE, UNDO_CAPTURE, CREATE_EXCERPT, RECROP_EXCERPT, EDIT, REUSE, MOVE, REPARENT, REMOVE_NODE, TRASH_CARD, RESTORE_CARD, ARRANGE, ORGANIZE }
/** Stored cards and source snapshots retain their capacity charge while recycled. */
object StudyCapacity {
    const val MAX_CARDS_PER_NOTEBOOK=2000
    const val MAX_SNAPSHOT_BYTES=256_000_000L
    const val MAX_SOURCE_BYTES=1_800_000
}
class StudySourceDraft(val pageId:String,val inkRevision:Long,val bounds:CanvasBounds,ids:List<String>,preview:ByteArray?=null,val objectRevision:Long?=null,val authoringRevision:Long?=null){
    private val image=preview?.clone()
    fun previewBytes()=image?.clone()
    val strokeIds:List<String> = java.util.Collections.unmodifiableList(ids.sorted())
    init{UUID.fromString(pageId);require(inkRevision>=0);require(ids.size in (if(image==null)1 else 0)..InkSelectionEdit.MAX_SELECTED&&ids.distinct().size==ids.size);ids.forEach{UUID.fromString(it)}
        require(image==null||(bounds.right>bounds.left&&bounds.bottom>bounds.top));require(image==null||(image.size in 5..240_000&&image[0]==0xff.toByte()&&image[1]==0xd8.toByte()));require(objectRevision==null||objectRevision>=0);require(authoringRevision==null||authoringRevision>=0)
        require(bounds.left>=-BoardLimits.WORLD&&bounds.right<=BoardLimits.WORLD&&bounds.top>=-BoardLimits.WORLD&&bounds.bottom<=BoardLimits.WORLD)}
}
data class StudyNode(val id:String,val cardId:String,val parentId:String?,val x:Double,val y:Double,val revision:Long=1,val removed:Boolean=false)
/** Cards own content. Nodes own only placement and hierarchy. */
object StudyGraph {
    const val MAX_NODES=1024
    const val MAX_RECORDS=4096
    fun validate(nodes:List<StudyNode>){
        require(nodes.size<=MAX_RECORDS){"STUDY_NODE_RECORD_BUDGET"}
        require(nodes.map{it.id}.distinct().size==nodes.size)
        val all=nodes.associateBy{it.id};val active=nodes.filter{!it.removed};require(active.size<=MAX_NODES){"STUDY_NODE_BUDGET"}
        nodes.forEach{n->UUID.fromString(n.id);UUID.fromString(n.cardId);require(n.revision in 1 until Long.MAX_VALUE)
            require(n.x.isFinite()&&n.y.isFinite()&&n.x in -40000.0..40000.0&&n.y in -40000.0..40000.0)
            require(n.parentId==null||all[n.parentId]?.let{it.id!=n.id&&(!it.removed||n.removed)}==true)
        }
        // Resolve each parent chain once, including removed records. Deep maps must not
        // pay a quadratic cycle walk each time a node is moved.
        val resolved=mutableSetOf<String>()
        for(n in nodes){
            val path=mutableSetOf<String>();var id:String?=n.id
            while(id!=null&&id !in resolved){require(path.add(id)){"MAP_CYCLE"};id=all[id]?.parentId}
            resolved.addAll(path)
        }
    }
    fun orderHash(nodes:List<StudyNode>)=ContentTransfer.hash(nodes.sortedBy{it.id}.joinToString("\n"){"${it.id}:${it.revision}"}.toByteArray())
    fun arrange(nodes:List<StudyNode>):Map<String,CanvasPoint>{
        validate(nodes);val active=nodes.filter{!it.removed};val children=active.groupBy{it.parentId};var leaf=0
        if(active.isEmpty())return emptyMap()
        val depths=mutableMapOf<String,Int>();val pending=ArrayDeque<Pair<StudyNode,Int>>()
        children[null].orEmpty().asReversed().forEach{pending.addLast(it to 0)}
        while(pending.isNotEmpty()){val (n,depth)=pending.removeLast();depths[n.id]=depth;children[n.id].orEmpty().asReversed().forEach{pending.addLast(it to depth+1)}}
        val leaves=active.count{children[it.id].isNullOrEmpty()}
        val stepY=minOf(128.0,78000.0/leaves);val height=leaves*stepY
        val offsetY=if(height>39000.0)-height/2 else 0.0
        val stepX=minOf(260.0,39000.0/maxOf(1,depths.values.maxOrNull()?:0))
        val output=linkedMapOf<String,CanvasPoint>()
        // Iterative postorder retains the old sibling order without exhausting the
        // Java stack on long chains. Large layouts stay inside author coordinates.
        val stack=ArrayDeque<Pair<StudyNode,Boolean>>()
        children[null].orEmpty().asReversed().forEach{stack.addLast(it to false)}
        while(stack.isNotEmpty()){
            val (n,visited)=stack.removeLast();val kids=children[n.id].orEmpty()
            if(!visited&&kids.isNotEmpty()){
                stack.addLast(n to true);kids.asReversed().forEach{stack.addLast(it to false)}
            }else{
                val y=if(kids.isEmpty())offsetY+(++leaf)*stepY else kids.map{checkNotNull(output[it.id]).y}.average()
                output[n.id]=CanvasPoint(40.0+checkNotNull(depths[n.id])*stepX,y)
            }
        }
        return output
    }
}
class StudyCommand(val id:String,val notebookId:String,val action:StudyAction,val cardId:String?=null,
    val nodeId:String?=null,val expectedRevision:Long=0,val parentId:String?=null,val title:String="",val body:String="",
    val x:Double=40.0,val y:Double=80.0,val source:StudySourceDraft?=null,val expectedGraph:String="",val mapId:String?=null,
    organization:StudyOrganizationPlan?=null,val afterNodeId:String?=null,val expectedTrashImpact:String="") {
    private val frozenOrganization=organization?.let(StudyOrganization::encode)
    val organization get()=frozenOrganization?.let(StudyOrganization::decode)
    init{UUID.fromString(id);UUID.fromString(notebookId);listOfNotNull(cardId,nodeId,parentId,mapId,afterNodeId).forEach{UUID.fromString(it)}
        require(expectedTrashImpact.isEmpty()||(action==StudyAction.TRASH_CARD&&expectedTrashImpact.matches(Regex("[0-9a-f]{64}"))))
        require(expectedRevision in 0 until Long.MAX_VALUE);require(title.length<=120&&body.length<=20_000)
        require(x.isFinite()&&y.isFinite()&&x in -40000.0..40000.0&&y in -40000.0..40000.0)
        require((action==StudyAction.ORGANIZE)==(organization!=null))
        require(afterNodeId==null||action in setOf(StudyAction.CREATE,StudyAction.REUSE)&&afterNodeId!=nodeId)
        when(action){
            StudyAction.CREATE->{require(cardId!=null&&nodeId!=null&&title.isNotBlank()&&expectedRevision==0L)}
            StudyAction.UNDO_CAPTURE->{require(cardId!=null&&nodeId!=null&&expectedRevision>0&&source==null)}
            StudyAction.CREATE_EXCERPT->{require(cardId!=null&&nodeId==null&&title.isNotBlank()&&source!=null&&expectedRevision==0L&&mapId==null)}
            StudyAction.RECROP_EXCERPT->{require(cardId!=null&&nodeId==null&&expectedRevision>0&&source?.previewBytes()!=null&&mapId==null)}
            StudyAction.EDIT->{require(cardId!=null&&expectedRevision>0&&title.isNotBlank()&&source==null)}
            StudyAction.REUSE->{require(cardId!=null&&nodeId!=null&&source==null)}
            StudyAction.MOVE,StudyAction.REPARENT,StudyAction.REMOVE_NODE->{require(nodeId!=null&&expectedRevision>0&&source==null)}
            StudyAction.TRASH_CARD,StudyAction.RESTORE_CARD->{require(cardId!=null&&expectedRevision>0&&source==null)}
            StudyAction.ARRANGE->{require(expectedGraph.matches(Regex("[0-9a-f]{64}"))&&source==null)}
            StudyAction.ORGANIZE->{require(organization!=null&&organization.ref==MapRef(notebookId,mapId)&&organization.expectedGraph==expectedGraph&&source==null&&cardId==null&&nodeId==null)}
        }
    }
    fun digest():String {
        val b=ByteArrayOutputStream();DataOutputStream(b).use{d->
            d.writeUTF("inkweft.study.v1");listOf(id,notebookId,action.name,cardId.orEmpty(),nodeId.orEmpty(),parentId.orEmpty(),title,expectedGraph).forEach(d::writeUTF)
            val text=body.toByteArray(Charsets.UTF_8);d.writeInt(text.size);d.write(text);d.writeLong(expectedRevision);d.writeDouble(x);d.writeDouble(y)
            d.writeBoolean(source!=null);source?.let{s->d.writeUTF(s.pageId);d.writeLong(s.inkRevision);listOf(s.bounds.left,s.bounds.top,s.bounds.right,s.bounds.bottom).forEach(d::writeDouble);d.writeInt(s.strokeIds.size);s.strokeIds.forEach(d::writeUTF)}
            source?.previewBytes()?.let{d.writeUTF("region-preview");d.writeUTF(ContentTransfer.hash(it));d.writeLong(source.objectRevision?:-1)}
            source?.authoringRevision?.let{d.writeUTF("source-authoring-v1");d.writeLong(it)}
            mapId?.let{d.writeUTF("map");d.writeUTF(it)}
            frozenOrganization?.let{d.writeUTF("organization");d.writeInt(it.size);d.write(it)}
            afterNodeId?.let{d.writeUTF("after-node");d.writeUTF(it)}
            // Absent on legacy requests: keep their receipt digest readable, but require a preview for new writes.
            if(expectedTrashImpact.isNotEmpty()){d.writeUTF("trash-impact-v1");d.writeUTF(expectedTrashImpact)}
        };return ContentTransfer.hash(b.toByteArray())
    }
}
