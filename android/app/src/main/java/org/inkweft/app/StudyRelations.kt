// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.KnowledgeData
import org.inkweft.core.RelationKind
import org.inkweft.core.RelationLineStyle
import org.inkweft.core.RelationDirection
import org.inkweft.core.TargetKind
import org.inkweft.core.TargetRef
import org.inkweft.data.KnowledgeRow
import org.inkweft.data.StudyNodeRow

internal data class StudyRelationEdge(
    val fromNodeId:String,val toNodeId:String,val labels:List<String>,val originalLinkIds:List<String>,
    val lineStyle:RelationLineStyle=RelationLineStyle.DASHED,
    val direction:RelationDirection=RelationDirection.FORWARD,val annotations:List<String> = emptyList()
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
    data class EdgeKey(val from:String,val to:String,val style:RelationLineStyle,val direction:RelationDirection)
    val pairs=linkedMapOf<EdgeKey,MutableList<Pair<KnowledgeData.Link,String>>>()
    val outside=mutableSetOf<String>()
    for(row in linkRows.filter{!it.removed}.distinctBy{it.id}.sortedBy{it.id}){
        val link=row.data() as? KnowledgeData.Link?:continue
        if(!link.visible)continue
        val outgoing=link.source==selectedRef
        if(!outgoing&&link.target!=selectedRef)continue
        val other=if(outgoing)link.target else link.source
        val counterparts=if(other.kind==TargetKind.CARD)byCard[other.id].orEmpty()else emptyList()
        if(counterparts.isEmpty()){outside+=row.id;continue}
        for(node in counterparts){
            val pair=if(outgoing)selected.id to node.id else node.id to selected.id
            pairs.getOrPut(EdgeKey(pair.first,pair.second,link.lineStyle,link.direction)){mutableListOf()}.add(link to row.id)
        }
    }
    val edges=pairs.entries.sortedWith(compareBy({it.key.from},{it.key.to},{it.key.style.ordinal},{it.key.direction.ordinal})).map{(pair,links)->
        StudyRelationEdge(pair.from,pair.to,links.map{it.first.relation}.distinct().sortedBy{it.ordinal}.map{it.label},
            links.map{it.second}.distinct().sorted(),pair.style,pair.direction,links.map{it.first.annotation}.filter{it.isNotBlank()}.distinct())
    }
    return StudyRelationProjection(edges,outside.size)
}
