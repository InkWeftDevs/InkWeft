// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.UUID

data class StudyCardVersion(val cardId:String,val revision:Long,val trashedAt:Long?=null)
data class StudyGraphState(val ref:MapRef,val nodes:List<StudyNode>,val orderedNodeIds:List<String>,
    val orderRevision:Long=0,val definitionRevision:Long=0,val structuralNodeIds:Set<String> = emptySet(),
    val cardVersions:List<StudyCardVersion> = emptyList())
data class StudyNodeSize(val width:Double,val height:Double)
data class StudyNodePlacement(val nodeId:String,val parentId:String?,val x:Double,val y:Double)
data class StudyGraphPatch(val orderedNodeIds:List<String>,val placements:List<StudyNodePlacement>)
enum class StudyOrganizationKind { REORDER, REPARENT, MOVE, ARRANGE, RESTORE }
enum class StudyOrganizationAction { UP, DOWN, INDENT, OUTDENT }
data class StudyOrganizationPlan(val ref:MapRef,val kind:StudyOrganizationKind,val expectedGraph:String,
    val expectedAfterGraph:String,val before:StudyGraphPatch,val after:StudyGraphPatch) {
    fun command(operationId:String)=StudyCommand(operationId,ref.notebookId,StudyAction.ORGANIZE,
        expectedGraph=expectedGraph,mapId=ref.mapId,organization=this)
}

/** Author order is a DFS sequence of node identities; geometry never chooses an existing order. */
object StudyOrganization {
    const val MAX_BYTES=65_536
    private val hashPattern=Regex("[0-9a-f]{64}")
    private fun <T> frozen(values:List<T>):List<T> = Collections.unmodifiableList(values.toList())

    fun canonicalOrder(nodes:List<StudyNode>,order:List<String>):List<String> {
        StudyGraph.validate(nodes)
        val active=nodes.filterNot{it.removed};val byId=active.associateBy{it.id}
        require(order.size==active.size&&order.toSet()==byId.keys){"MAP_ORDER_MEMBERS"}
        val children=order.map{byId.getValue(it)}.groupBy{it.parentId}
        return buildList {
            fun visit(parent:String?){children[parent].orEmpty().forEach{add(it.id);visit(it.id)}}
            visit(null)
        }
    }

    /** Deterministic read-only fallback, materialized by the first author transaction. */
    fun legacyOrder(nodes:List<StudyNode>):List<String> = canonicalOrder(nodes,
        nodes.filterNot{it.removed}.sortedWith(compareBy<StudyNode>{it.y}.thenBy{it.x}.thenBy{it.id}).map{it.id})

    private fun validate(state:StudyGraphState){
        require(state.orderRevision in 0 until Long.MAX_VALUE&&state.definitionRevision in 0 until Long.MAX_VALUE)
        require(canonicalOrder(state.nodes,state.orderedNodeIds)==state.orderedNodeIds){"MAP_ORDER_DFS"}
        val byId=state.nodes.associateBy{it.id}
        require(state.structuralNodeIds.all{byId[it]?.let{n->!n.removed&&n.revision==state.definitionRevision}==true})
        require(state.structuralNodeIds.isEmpty()||state.ref.mapId!=null&&state.definitionRevision>0)
        require(state.cardVersions.size<=200&&state.cardVersions.map{it.cardId}.distinct().size==state.cardVersions.size)
        state.cardVersions.forEach{UUID.fromString(it.cardId);require(it.revision in 1 until Long.MAX_VALUE);require(it.trashedAt==null||it.trashedAt>=0)}
    }

    fun fingerprint(state:StudyGraphState):String {
        validate(state)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{d->
            d.writeUTF("study-graph.v2");d.writeUTF(state.ref.notebookId);d.writeUTF(state.ref.mapId.orEmpty())
            d.writeLong(state.orderRevision);d.writeLong(state.definitionRevision)
            d.writeInt(state.nodes.size)
            state.nodes.sortedBy{it.id}.forEach{n->
                d.writeUTF(n.id);d.writeUTF(n.cardId);d.writeUTF(n.parentId.orEmpty());d.writeDouble(n.x);d.writeDouble(n.y)
                d.writeLong(n.revision);d.writeBoolean(n.removed);d.writeBoolean(n.id in state.structuralNodeIds)
            }
            d.writeInt(state.orderedNodeIds.size);state.orderedNodeIds.forEach(d::writeUTF)
            val relatedCards=state.nodes.filter{!it.removed&&it.id !in state.structuralNodeIds}.map{it.cardId}.toSet()
            val versions=state.cardVersions.filter{it.cardId in relatedCards}.sortedBy{it.cardId}
            d.writeInt(versions.size);versions.forEach{
                d.writeUTF(it.cardId);d.writeLong(it.revision);d.writeLong(it.trashedAt?:-1)
            }
        }
        return ContentTransfer.hash(bytes.toByteArray())
    }

    private fun patch(state:StudyGraphState):StudyGraphPatch {
        val byId=state.nodes.associateBy{it.id}
        return StudyGraphPatch(frozen(state.orderedNodeIds),frozen(state.orderedNodeIds.map{byId.getValue(it).let{n->StudyNodePlacement(n.id,n.parentId,n.x,n.y)}}))
    }
    private fun validate(patch:StudyGraphPatch){
        val nodes=patch.placements.map{StudyNode(it.nodeId,it.nodeId,it.parentId,it.x,it.y)}
        require(canonicalOrder(nodes,patch.orderedNodeIds)==patch.orderedNodeIds)
        require(patch.placements.map{it.nodeId}==patch.orderedNodeIds)
    }
    private fun validate(plan:StudyOrganizationPlan){
        require(hashPattern.matches(plan.expectedGraph)&&hashPattern.matches(plan.expectedAfterGraph))
        validate(plan.before);validate(plan.after)
        require(plan.before.orderedNodeIds.toSet()==plan.after.orderedNodeIds.toSet())
        val before=plan.before.placements.associateBy{it.nodeId}
        when(plan.kind){
            StudyOrganizationKind.REORDER->require(plan.after.placements.all{it==before[it.nodeId]})
            StudyOrganizationKind.REPARENT->require(plan.after.placements.all{it.x==before.getValue(it.nodeId).x&&it.y==before.getValue(it.nodeId).y})
            StudyOrganizationKind.MOVE,StudyOrganizationKind.ARRANGE->{
                require(plan.before.orderedNodeIds==plan.after.orderedNodeIds)
                require(plan.after.placements.all{it.parentId==before.getValue(it.nodeId).parentId})
            }
            StudyOrganizationKind.RESTORE->Unit
        }
    }
    private fun increment(revision:Long):Long {require(revision<Long.MAX_VALUE-1){"MAP_REVISION_LIMIT"};return revision+1}

    /** This reducer is also used to compute preview stamps. Persistence writes exactly this result. */
    private fun reduce(state:StudyGraphState,after:StudyGraphPatch):StudyGraphState {
        validate(state);validate(after)
        require(state.orderedNodeIds.toSet()==after.orderedNodeIds.toSet())
        val placements=after.placements.associateBy{it.nodeId}
        fun changed(n:StudyNode)=placements[n.id]?.let{it.parentId!=n.parentId||it.x!=n.x||it.y!=n.y}==true
        val structureChanged=state.nodes.any{it.id in state.structuralNodeIds&&changed(it)}
        val definitionRevision=if(structureChanged)increment(state.definitionRevision)else state.definitionRevision
        val changedNodes=state.nodes.associate{n->
            val p=placements[n.id]
            n.id to if(p==null)n else n.copy(parentId=p.parentId,x=p.x,y=p.y,
                revision=if(n.id in state.structuralNodeIds)definitionRevision else if(changed(n))increment(n.revision)else n.revision)
        }
        return state.copy(nodes=frozen(after.orderedNodeIds.map{changedNodes.getValue(it)}+state.nodes.filter{it.removed}),
            orderedNodeIds=frozen(after.orderedNodeIds),definitionRevision=definitionRevision,
            orderRevision=if(state.orderRevision==0L||after.orderedNodeIds!=state.orderedNodeIds)increment(state.orderRevision)else state.orderRevision)
    }

    private fun prepare(state:StudyGraphState,kind:StudyOrganizationKind,after:StudyGraphPatch):StudyOrganizationPlan {
        val frozenAfter=StudyGraphPatch(frozen(after.orderedNodeIds),frozen(after.placements))
        return StudyOrganizationPlan(state.ref,kind,fingerprint(state),fingerprint(reduce(state,frozenAfter)),patch(state),frozenAfter).also(::validate)
    }

    fun apply(state:StudyGraphState,plan:StudyOrganizationPlan):StudyGraphState {
        validate(plan)
        require(state.ref==plan.ref&&fingerprint(state)==plan.expectedGraph){"MAP_GRAPH_CONFLICT"}
        require(patch(state)==plan.before){"MAP_PATCH_CONFLICT"}
        return reduce(state,plan.after).also{require(fingerprint(it)==plan.expectedAfterGraph){"MAP_AFTER_CONFLICT"}}
    }

    fun plan(state:StudyGraphState,nodeId:String,action:StudyOrganizationAction):StudyOrganizationPlan {
        validate(state)
        val byId=state.nodes.filterNot{it.removed}.associateBy{it.id};val node=byId.getValue(nodeId)
        val siblings=state.orderedNodeIds.filter{byId.getValue(it).parentId==node.parentId};val index=siblings.indexOf(nodeId)
        return when(action){
            StudyOrganizationAction.INDENT->{require(index>0){"MAP_NO_PREVIOUS_SIBLING"};reparent(state,nodeId,siblings[index-1])}
            StudyOrganizationAction.OUTDENT->{
                val parent=byId[node.parentId]?:error("MAP_NO_PARENT")
                val uncles=state.orderedNodeIds.filter{byId.getValue(it).parentId==parent.parentId}
                reparent(state,nodeId,parent.parentId,uncles.getOrNull(uncles.indexOf(parent.id)+1))
            }
            StudyOrganizationAction.UP,StudyOrganizationAction.DOWN->{
                val other=index+if(action==StudyOrganizationAction.UP)-1 else 1
                require(other in siblings.indices){"MAP_NO_SIBLING"}
                val swapped=state.orderedNodeIds.toMutableList();val a=swapped.indexOf(nodeId);val b=swapped.indexOf(siblings[other])
                Collections.swap(swapped,a,b)
                val order=canonicalOrder(state.nodes,swapped);val old=patch(state).placements.associateBy{it.nodeId}
                prepare(state,StudyOrganizationKind.REORDER,StudyGraphPatch(order,order.map{old.getValue(it)}))
            }
        }
    }

    fun reparent(state:StudyGraphState,nodeId:String,parentId:String?,beforeNodeId:String?=null):StudyOrganizationPlan {
        validate(state)
        val byId=state.nodes.filterNot{it.removed}.associateBy{it.id};require(nodeId in byId)
        require(parentId==null||parentId in byId);require(beforeNodeId==null||beforeNodeId in byId&&beforeNodeId!=nodeId&&byId.getValue(beforeNodeId).parentId==parentId)
        val nodes=state.nodes.map{if(it.id==nodeId)it.copy(parentId=parentId)else it}
        StudyGraph.validate(nodes)
        val siblings=state.orderedNodeIds.filter{it!=nodeId}.toMutableList()
        siblings.add(beforeNodeId?.let{siblings.indexOf(it)}?:siblings.size,nodeId)
        val order=canonicalOrder(nodes,siblings);val updated=nodes.associateBy{it.id}
        return prepare(state,StudyOrganizationKind.REPARENT,StudyGraphPatch(order,order.map{updated.getValue(it).let{n->StudyNodePlacement(n.id,n.parentId,n.x,n.y)}}))
    }

    fun move(state:StudyGraphState,nodeId:String,x:Double,y:Double):StudyOrganizationPlan {
        require(nodeId in state.orderedNodeIds)
        val before=patch(state)
        return prepare(state,StudyOrganizationKind.MOVE,before.copy(placements=before.placements.map{if(it.nodeId==nodeId)it.copy(x=x,y=y)else it}))
    }

    fun arrange(state:StudyGraphState,sizes:Map<String,StudyNodeSize>,layout:String="right"):StudyOrganizationPlan {
        validate(state);require(layout in setOf("right","bilateral"));require(sizes.keys==state.orderedNodeIds.toSet()){"MAP_NODE_SIZES"}
        sizes.values.forEach{require(it.width.isFinite()&&it.height.isFinite()&&it.width>0&&it.height>0&&it.width<=80_000&&it.height<=80_000)}
        val byId=state.nodes.associateBy{it.id};val children=state.orderedNodeIds.map{byId.getValue(it)}.groupBy{it.parentId}
        val widths=mutableMapOf<Int,Double>();val heights=mutableMapOf<String,Double>()
        fun groupHeight(nodes:List<StudyNode>)=nodes.sumOf{heights.getValue(it.id)}+48.0*(nodes.size-1).coerceAtLeast(0)
        fun measure(n:StudyNode,depth:Int){
            widths[depth]=maxOf(widths[depth]?:0.0,sizes.getValue(n.id).width)
            val kids=children[n.id].orEmpty();kids.forEach{measure(it,depth+1)}
            val childHeight=if(layout=="bilateral"&&depth==0)maxOf(groupHeight(kids.filterIndexed{i,_->i%2==0}),groupHeight(kids.filterIndexed{i,_->i%2==1}))else groupHeight(kids)
            heights[n.id]=maxOf(sizes.getValue(n.id).height,childHeight)
        }
        val roots=children[null].orEmpty();roots.forEach{measure(it,0)}
        val positions=mutableMapOf<String,CanvasPoint>()
        fun place(n:StudyNode,depth:Int,top:Double,left:Boolean){
            val size=sizes.getValue(n.id);val height=heights.getValue(n.id)
            val x=if(depth==0)40.0 else if(left)40.0-(1..depth).sumOf{widths.getValue(it)+96.0}
                else 40.0+(0 until depth).sumOf{widths.getValue(it)+96.0}
            positions[n.id]=CanvasPoint(x,top+(height-size.height)/2)
            val kids=children[n.id].orEmpty()
            fun placeGroup(group:List<StudyNode>,onLeft:Boolean){
                var next=top+(height-groupHeight(group))/2
                group.forEach{place(it,depth+1,next,onLeft);next+=heights.getValue(it.id)+48.0}
            }
            if(layout=="bilateral"&&depth==0){placeGroup(kids.filterIndexed{i,_->i%2==0},true);placeGroup(kids.filterIndexed{i,_->i%2==1},false)}
            else placeGroup(kids,left)
        }
        var top=80.0;roots.forEach{place(it,0,top,false);top+=heights.getValue(it.id)+96.0}
        val minX=positions.values.minOfOrNull{it.x}?:0.0;val minY=positions.values.minOfOrNull{it.y}?:0.0
        val maxX=positions.maxOfOrNull{(id,p)->p.x+sizes.getValue(id).width}?:0.0
        val maxY=positions.maxOfOrNull{(id,p)->p.y+sizes.getValue(id).height}?:0.0
        require(maxX-minX<=80_000&&maxY-minY<=80_000){"MAP_LAYOUT_BOUNDS"}
        val dx=if(maxX>40_000)40_000-maxX else if(minX< -40_000)-40_000-minX else 0.0
        val dy=if(maxY>40_000)40_000-maxY else if(minY< -40_000)-40_000-minY else 0.0
        val before=patch(state)
        return prepare(state,StudyOrganizationKind.ARRANGE,before.copy(placements=before.placements.map{p->positions.getValue(p.nodeId).let{p.copy(x=it.x+dx,y=it.y+dy)}}))
    }

    fun undo(current:StudyGraphState,appliedPlan:StudyOrganizationPlan):StudyOrganizationPlan {
        validate(appliedPlan)
        require(current.ref==appliedPlan.ref&&fingerprint(current)==appliedPlan.expectedAfterGraph&&patch(current)==appliedPlan.after){"MAP_UNDO_CONFLICT"}
        return prepare(current,StudyOrganizationKind.RESTORE,appliedPlan.before)
    }

    fun encode(plan:StudyOrganizationPlan):ByteArray {
        validate(plan)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{d->
            d.writeInt(0x49574f31);d.writeUTF(plan.ref.notebookId);d.writeUTF(plan.ref.mapId.orEmpty());d.writeUTF(plan.kind.name)
            d.writeUTF(plan.expectedGraph);d.writeUTF(plan.expectedAfterGraph)
            listOf(plan.before,plan.after).forEach{p->
                d.writeInt(p.orderedNodeIds.size);p.orderedNodeIds.forEach(d::writeUTF)
                d.writeInt(p.placements.size);p.placements.forEach{n->d.writeUTF(n.nodeId);d.writeUTF(n.parentId.orEmpty());d.writeDouble(n.x);d.writeDouble(n.y)}
            }
        }
        return bytes.toByteArray().also{require(it.size<=MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):StudyOrganizationPlan {
        require(bytes.size<=MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            require(d.readInt()==0x49574f31);val ref=MapRef(d.readUTF(),d.readUTF().ifEmpty{null});val kind=StudyOrganizationKind.valueOf(d.readUTF())
            val beforeHash=d.readUTF();val afterHash=d.readUTF()
            fun count()=d.readInt().also{require(it in 0..StudyGraph.MAX_NODES)}
            fun readPatch()=StudyGraphPatch(frozen(List(count()){d.readUTF()}),frozen(List(count()){StudyNodePlacement(d.readUTF(),d.readUTF().ifEmpty{null},d.readDouble(),d.readDouble())}))
            StudyOrganizationPlan(ref,kind,beforeHash,afterHash,readPatch(),readPatch()).also{require(d.read()==-1);validate(it)}
        }
    }
}
