// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.UUID

enum class StudyAction { CREATE, EDIT, REUSE, MOVE, REPARENT, REMOVE_NODE, TRASH_CARD, RESTORE_CARD, ARRANGE }
class StudySourceDraft(val pageId:String,val inkRevision:Long,val bounds:CanvasBounds,ids:List<String>){
    val strokeIds:List<String> = java.util.Collections.unmodifiableList(ids.sorted())
    init{UUID.fromString(pageId);require(inkRevision>=0);require(ids.size in 1..256&&ids.distinct().size==ids.size);ids.forEach{UUID.fromString(it)}
        require(bounds.left>=-BoardLimits.WORLD&&bounds.right<=BoardLimits.WORLD&&bounds.top>=-BoardLimits.WORLD&&bounds.bottom<=BoardLimits.WORLD)}
}
data class StudyNode(val id:String,val cardId:String,val parentId:String?,val x:Double,val y:Double,val revision:Long=1,val removed:Boolean=false)
/** Cards own content. Nodes own only placement and hierarchy. */
object StudyGraph {
    const val MAX_NODES=128
    fun validate(nodes:List<StudyNode>){
        require(nodes.size<=256&&nodes.map{it.id}.distinct().size==nodes.size)
        val all=nodes.associateBy{it.id};val active=nodes.filter{!it.removed};require(active.size<=MAX_NODES)
        nodes.forEach{n->UUID.fromString(n.id);UUID.fromString(n.cardId);require(n.revision in 1 until Long.MAX_VALUE)
            require(n.x.isFinite()&&n.y.isFinite()&&n.x in -40000.0..40000.0&&n.y in -40000.0..40000.0)
            require(n.parentId==null||all[n.parentId]?.let{it.id!=n.id&&(!it.removed||n.removed)}==true)
            val seen=mutableSetOf(n.id);var parent=n.parentId
            while(parent!=null){require(seen.add(parent)){"MAP_CYCLE"};parent=all[parent]?.parentId}
        }
    }
    fun orderHash(nodes:List<StudyNode>)=ContentTransfer.hash(nodes.sortedBy{it.id}.joinToString("\n"){"${it.id}:${it.revision}"}.toByteArray())
    fun arrange(nodes:List<StudyNode>):Map<String,CanvasPoint>{
        validate(nodes);val active=nodes.filter{!it.removed};val children=active.groupBy{it.parentId};var leaf=0
        val output=linkedMapOf<String,CanvasPoint>()
        fun walk(n:StudyNode,depth:Int):Double{
            val kids=children[n.id].orEmpty().sortedBy{it.id}
            val y=if(kids.isEmpty())(++leaf)*128.0 else kids.map{walk(it,depth+1)}.average()
            output[n.id]=CanvasPoint(40.0+depth*260.0,y);return y
        }
        children[null].orEmpty().sortedBy{it.id}.forEach{walk(it,0)};return output
    }
}
class StudyCommand(val id:String,val notebookId:String,val action:StudyAction,val cardId:String?=null,
    val nodeId:String?=null,val expectedRevision:Long=0,val parentId:String?=null,val title:String="",val body:String="",
    val x:Double=40.0,val y:Double=80.0,val source:StudySourceDraft?=null,val expectedGraph:String="") {
    init{UUID.fromString(id);UUID.fromString(notebookId);listOfNotNull(cardId,nodeId,parentId).forEach{UUID.fromString(it)}
        require(expectedRevision in 0 until Long.MAX_VALUE);require(title.length<=120&&body.length<=20_000)
        require(x.isFinite()&&y.isFinite()&&x in -40000.0..40000.0&&y in -40000.0..40000.0)
        when(action){
            StudyAction.CREATE->{require(cardId!=null&&nodeId!=null&&title.isNotBlank()&&expectedRevision==0L)}
            StudyAction.EDIT->{require(cardId!=null&&expectedRevision>0&&title.isNotBlank()&&source==null)}
            StudyAction.REUSE->{require(cardId!=null&&nodeId!=null&&source==null)}
            StudyAction.MOVE,StudyAction.REPARENT,StudyAction.REMOVE_NODE->{require(nodeId!=null&&expectedRevision>0&&source==null)}
            StudyAction.TRASH_CARD,StudyAction.RESTORE_CARD->{require(cardId!=null&&expectedRevision>0&&source==null)}
            StudyAction.ARRANGE->{require(expectedGraph.matches(Regex("[0-9a-f]{64}"))&&source==null)}
        }
    }
    fun digest():String {
        val b=ByteArrayOutputStream();DataOutputStream(b).use{d->
            d.writeUTF("inkweft.study.v1");listOf(id,notebookId,action.name,cardId.orEmpty(),nodeId.orEmpty(),parentId.orEmpty(),title,expectedGraph).forEach(d::writeUTF)
            val text=body.toByteArray(Charsets.UTF_8);d.writeInt(text.size);d.write(text);d.writeLong(expectedRevision);d.writeDouble(x);d.writeDouble(y)
            d.writeBoolean(source!=null);source?.let{s->d.writeUTF(s.pageId);d.writeLong(s.inkRevision);listOf(s.bounds.left,s.bounds.top,s.bounds.right,s.bounds.bottom).forEach(d::writeDouble);d.writeInt(s.strokeIds.size);s.strokeIds.forEach(d::writeUTF)}
        };return ContentTransfer.hash(b.toByteArray())
    }
}
