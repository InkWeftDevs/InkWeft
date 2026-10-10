// SPDX-License-Identifier: AGPL-3.0-or-later
import org.inkweft.core.*
import java.util.UUID
import kotlin.random.Random
private var sequence=0L
private fun node(parent:StudyNode?=null)=StudyNode(UUID(0,++sequence).toString(),UUID(1,sequence).toString(),parent?.id,0.0,0.0)
private fun projectionHash(p:StudyOutline.Projection)=ContentTransfer.hash((p.rows.joinToString(";"){"${it.node.id}:${it.depth}:${it.descendants}"}+"|"+p.path.joinToString{it.id}).toByteArray())
fun main(){
    val chain=buildList<StudyNode>{repeat(StudyGraph.MAX_NODES){add(node(lastOrNull()))}}
    repeat(10){check(StudyOutline.project(chain).rows.size==chain.size)}
    val times=List(15){val start=System.nanoTime();val p=StudyOutline.project(chain);check(p.rows.size==chain.size&&p.rows.first().descendants==1023);(System.nanoTime()-start)/1e6}
    println("{\"warmup\":10,\"measurements\":15,\"nodes\":1024,\"deepOutlineMs\":[${times.joinToString()}],\"projectionHashes\":[")
    val hashes=mutableListOf<String>();val random=Random(84)
    repeat(20){iteration->
        val nodes=buildList<StudyNode>{repeat(40+iteration*4){i->add(node(if(i==0||i%11==0)null else get(random.nextInt(size))))}}
        for(collapsed in listOf(emptySet(),nodes.filterIndexed{i,_->i%4==0}.map{it.id}.toSet()))for(focus in listOf<String?>(null,nodes[nodes.size/2].id)){
            hashes.add(projectionHash(StudyOutline.project(nodes,collapsed,focus)))
        }
    }
    hashes.add(projectionHash(StudyOutline.project(chain)));hashes.add(projectionHash(StudyOutline.project(chain,focusId=chain[512].id)))
    println(hashes.joinToString{ "\"$it\"" }+"],\"layoutScenarios\":[")
    val scenarios=mutableListOf<String>()
    for(nested in listOf(false,true)){
        val root=node();val kids=List(4){node(root)};val nodes=buildList{add(root);kids.forEachIndexed{i,k->add(k);if(nested&&i%2==1)add(node(k))}}
        val sizes=nodes.associate{n->n.id to StudyNodeSize(200.0,if((!nested&&kids.indexOf(n)%2==1)||(nested&&n.parentId in kids.map{it.id}))900.0 else 80.0)}
        val state=StudyGraphState(MapRef(UUID(2,2).toString(),null),nodes,StudyOrganization.canonicalOrder(nodes,nodes.map{it.id}))
        for(layout in listOf("right","bilateral")){
            val plan=StudyOrganization.arrange(state,sizes,layout);val p=plan.after.placements;val rootX=p.first{it.nodeId==root.id}.x
            val extent=p.maxOf{it.y+sizes.getValue(it.nodeId).height}-p.minOf{it.y}
            check(plan.before.orderedNodeIds==plan.after.orderedNodeIds&&p.all{it.parentId==nodes.first{n->n.id==it.nodeId}.parentId})
            val coordinateHash=ContentTransfer.hash(p.joinToString{ "${it.nodeId}:${it.x}:${it.y}" }.toByteArray())
            scenarios.add("{\"nested\":$nested,\"layout\":\"$layout\",\"verticalExtent\":$extent,\"leftSiblings\":[${kids.joinToString{(p.first{v->v.nodeId==it.id}.x<rootX).toString()}}],\"coordinateSha256\":\"$coordinateHash\",\"logicalOrderAndParentsRetained\":true}")
        }
    }
    println(scenarios.joinToString()+"]}")
}
