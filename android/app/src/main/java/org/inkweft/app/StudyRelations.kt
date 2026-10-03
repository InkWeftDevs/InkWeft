// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.KnowledgeData
import org.inkweft.core.RelationKind
import org.inkweft.core.TargetKind
import org.inkweft.core.TargetRef
import org.inkweft.data.KnowledgeRow
import org.inkweft.data.StudyNodeRow

internal data class StudyRelationEdge(
    val fromNodeId:String,val toNodeId:String,val labels:List<String>,val originalLinkIds:List<String>
)
internal data class StudyRelationProjection(val edges:List<StudyRelationEdge>,val outOfScopeCount:Int)

/** A view of authored links, anchored to one occurrence. The caller excludes structural topics. */
internal fun projectStudyRelations(
    selectedNodeId:String?,shownNodes:List<StudyNodeRow>,linkRows:List<KnowledgeRow>
):StudyRelationProjection {
    val selected=shownNodes.find{it.id==selectedNodeId&&!it.removed}
        ?:return StudyRelationProjection(emptyList(),0)
    val selectedRef=TargetRef(TargetKind.CARD,selected.cardId)
    val byCard=shownNodes.filter{!it.removed&&it.notebookId==selected.notebookId}.groupBy{it.cardId}
    val pairs=linkedMapOf<Pair<String,String>,MutableList<Pair<RelationKind,String>>>()
    val outside=mutableSetOf<String>()
    for(row in linkRows.filter{!it.removed}.distinctBy{it.id}.sortedBy{it.id}){
        val link=row.data() as? KnowledgeData.Link?:continue
        val outgoing=link.source==selectedRef
        if(!outgoing&&link.target!=selectedRef)continue
        val other=if(outgoing)link.target else link.source
        val counterparts=if(other.kind==TargetKind.CARD)byCard[other.id].orEmpty()else emptyList()
        if(counterparts.isEmpty()){outside+=row.id;continue}
        for(node in counterparts){
            val pair=if(outgoing)selected.id to node.id else node.id to selected.id
            pairs.getOrPut(pair){mutableListOf()}.add(link.relation to row.id)
        }
    }
    val edges=pairs.entries.sortedWith(compareBy({it.key.first},{it.key.second})).map{(pair,links)->
        StudyRelationEdge(pair.first,pair.second,links.map{it.first}.distinct().sortedBy{it.ordinal}.map{it.label},
            links.map{it.second}.distinct().sorted())
    }
    return StudyRelationProjection(edges,outside.size)
}
