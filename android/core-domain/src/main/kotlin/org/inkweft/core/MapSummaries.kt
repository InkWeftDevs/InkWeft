// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

data class StudySummaryGroup(val id:String,val revision:Long,val data:KnowledgeData.MapSummaryGroup)

/** A bracket refers to exact sibling occurrences, never to shared card identities. */
object MapSummaries {
    const val MAX_GROUPS=128
    const val MAX_MEMBERS=128

    fun validate(nodes:List<StudyNode>,order:List<String>,groups:List<KnowledgeData.MapSummaryGroup>){
        require(groups.size<=MAX_GROUPS){"MAP_SUMMARY_BUDGET"}
        if(groups.isEmpty())return
        val byId=nodes.filterNot{it.removed}.associateBy{it.id}
        val siblings=order.map{byId.getValue(it)}.groupBy{it.parentId}
        val used=mutableSetOf<String>()
        for(group in groups){
            KnowledgeCodec.validate(group)
            val members=group.memberIds.map{requireNotNull(byId[it]){"MAP_SUMMARY_MEMBER_UNAVAILABLE"}}
            val parent=members.first().parentId
            require(parent!=null&&members.all{it.parentId==parent}){"MAP_SUMMARY_SIBLINGS"}
            val peers=siblings.getValue(parent).map{it.id}
            val first=peers.indexOf(group.memberIds.first())
            require(first>=0&&peers.drop(first).take(members.size)==group.memberIds){"MAP_SUMMARY_CONTIGUOUS"}
            require(group.memberIds.all{used.add(it)}){"MAP_SUMMARY_OVERLAP"}
        }
    }

    fun selection(state:StudyGraphState,ids:Set<String>,label:String):KnowledgeData.MapSummaryGroup{
        val members=state.orderedNodeIds.filter{it in ids}
        require(members.size==ids.size){"MAP_SUMMARY_MEMBER_UNAVAILABLE"}
        val group=KnowledgeData.MapSummaryGroup(state.ref.mapId,label,members)
        validate(state.nodes,state.orderedNodeIds,state.summaryGroups.map{it.data}+group)
        return group
    }

    /** Every prefix boundary except those inside a bracket is an eligible split. */
    fun splitAllowed(kids:List<String>,index:Int,groups:List<StudySummaryGroup>):Boolean{
        if(groups.isEmpty())return true
        val positions=kids.withIndex().associate{it.value to it.index}
        return groups.none{group->
            val start=positions[group.data.memberIds.first()]
            val end=positions[group.data.memberIds.last()]
            start!=null&&end!=null&&index>start&&index<=end
        }
    }

}
