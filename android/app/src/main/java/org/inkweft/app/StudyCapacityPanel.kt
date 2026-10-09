// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.StudyCapacity
import org.inkweft.core.KnowledgeCodec
import org.inkweft.core.StudyGraph
import java.util.Locale

internal data class StudyCapacityUsage(val activeNodes:Int,val nodeRecords:Int,val cards:Int,val trashedCards:Int=0)
internal data class StudySnapshotUsage(val bytes:Long?=null,val failed:Boolean=false)
internal enum class StudyCapacityAction { MAP, CARDS, TRASH, NEW_MAP }
internal const val STUDY_RESTORE_CAPACITY_MESSAGE="未恢复任何记录：合并后的已保存原迹会超过全库 256 MB 上限。原资料与备份保留；本版本不能安全自动回收已保存历史，缩小已有摘录范围也不会释放历史用量。"

internal fun StudyUi.capacityUsage(mapId:String?):StudyCapacityUsage? =
    graph?.takeIf{!loading&&!readFailed&&it.ref.mapId==mapId&&(mapId==null||it.definition?.removed==false)}?.let{snapshot->
        StudyCapacityUsage(snapshot.nodes.count{!it.removed},snapshot.nodes.size,
            snapshot.cards.size,snapshot.cards.count{it.trashedAt!=null})
    }

private fun nearCapacity(used:Long,limit:Long)=used>=limit||used*5>=limit*4
private fun capacityStatus(used:Long,limit:Long)=when{
    used>limit->"超过上限"
    used==limit->"已满"
    nearCapacity(used,limit)->"接近上限"
    else->"可继续使用"
}

internal fun studyCapacityWarning(usage:StudyCapacityUsage?,snapshot:StudySnapshotUsage):String?{
    val counts=buildList{
        usage?.let{
            add(Triple("本图活动主题",it.activeNodes.toLong(),StudyGraph.MAX_NODES.toLong()))
            add(Triple("本图节点记录",it.nodeRecords.toLong(),StudyGraph.MAX_RECORDS.toLong()))
            add(Triple("本笔记卡片",it.cards.toLong(),StudyCapacity.MAX_CARDS_PER_NOTEBOOK.toLong()))
        }
        if(!snapshot.failed)snapshot.bytes?.let{add(Triple("全库已保存原迹",it,StudyCapacity.MAX_SNAPSHOT_BYTES))}
    }
    val next=counts.filter{nearCapacity(it.second,it.third)}.maxByOrNull{it.second.toDouble()/it.third}?:return null
    return "${next.first}${capacityStatus(next.second,next.third)} · 容量与整理"
}

/** Only deterministic capacity refusals use these messages. Unknown writes keep their retry path. */
internal fun studyCapacityRejection(reason:String):String?=when(reason){
    "STUDY_NODE_BUDGET"->"未提交：每张图最多保留 ${StudyGraph.MAX_NODES} 个活动主题。可减少本次主题、移除不需要的叶主题，或切换新图继续。草稿已保留。"
    "STUDY_NODE_RECORD_BUDGET"->"未提交：本次操作会超过当前图 ${StudyGraph.MAX_RECORDS} 条节点记录上限（含已移除位置）。可切换或新建图；移除普通位置不会清空历史记录。草稿已保留。"
    "STUDY_CARD_BUDGET"->"未提交：本次操作会超过本笔记 ${StudyCapacity.MAX_CARDS_PER_NOTEBOOK} 张卡片上限（含回收区）。可编辑或复用已有卡片，或在另一笔记中新建。草稿已保留。"
    "STUDY_SNAPSHOT_BUDGET"->"未提交：本次保存会超过全库原迹 256 MB 上限。可用同步引用复用已有卡片，避免新增原迹快照。本版本不能安全自动回收已保存历史。草稿已保留。"
    "STUDY_SNAPSHOT_TOO_LARGE"->"未提交：本次原迹超过单份 1.8 MB 上限。请缩小框选范围或减少一次摘录的笔迹。草稿已保留。"
    "KNOWLEDGE_BUDGET"->"未提交：本笔记的知识记录会超过 ${KnowledgeCodec.MAX_RECORDS_PER_NOTEBOOK} 条上限（含已移除记录）。可在另一笔记继续整理；移除关联不会清除已保存历史。草稿已保留。"
    else->null
}

@Composable internal fun StudyCapacityWarning(usage:StudyCapacityUsage?,snapshot:StudySnapshotUsage,enabled:Boolean,onOpen:()->Unit){
    val message=studyCapacityWarning(usage,snapshot)?:return
    TextButton(onClick=onOpen,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-warning")){
        Text(message,style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun CapacityCount(label:String,used:Int,limit:Int,description:String,tag:String){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,style=MaterialTheme.typography.titleSmall)
        Text("$used / $limit · ${capacityStatus(used.toLong(),limit.toLong())}",
            style=MaterialTheme.typography.bodyLarge,modifier=Modifier.testTag(tag))
        Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StudyCapacityPanel(
    embedded:Boolean,mapTitle:String,usage:StudyCapacityUsage?,snapshot:StudySnapshotUsage,
    graphFailed:Boolean,navigationEnabled:Boolean,createMapEnabled:Boolean,dismiss:()->Unit,retry:()->Unit,
    onAction:(StudyCapacityAction)->Unit,
){
    StudyDialog(embedded,onDismissRequest=dismiss,modifier=Modifier.testTag("capacity-panel"),
        title={Text("容量与整理",style=MaterialTheme.typography.titleLarge)},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("capacity-scroll"),
            verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("当前图：$mapTitle",style=MaterialTheme.typography.bodyLarge)
            if(usage==null){
                Text(if(graphFailed)"当前图容量暂不可用，请重新读取。"else"正在读取当前图和卡片用量…",
                    modifier=Modifier.testTag("capacity-graph-state"))
            }else{
                CapacityCount("本图活动主题",usage.activeNodes,StudyGraph.MAX_NODES,
                    "包括收起的下级主题和当前聚焦范围外的主题。折叠、聚焦和自动布局不减少用量。","capacity-map-active")
                CapacityCount("本图节点记录",usage.nodeRecords,StudyGraph.MAX_RECORDS,
                    "含 ${usage.nodeRecords-usage.activeNodes} 条已移除位置。移除普通卡片位置仍保留记录；命名图结构主题移除后不再计入当前结构数。","capacity-map-total")
                CapacityCount("本笔记卡片",usage.cards,StudyCapacity.MAX_CARDS_PER_NOTEBOOK,
                    "含回收区 ${usage.trashedCards} 张，同一卡片在多图复用仍只计一张。回收或恢复不改变总量。","capacity-book-cards")
            }
            HorizontalDivider()
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("全库已保存原迹",style=MaterialTheme.typography.titleSmall)
                val bytes=snapshot.bytes.takeUnless{snapshot.failed}
                if(bytes==null)Text(if(snapshot.failed)"全库用量暂不可用，请重新读取。"else"正在读取全库原迹用量…",
                    modifier=Modifier.testTag("capacity-snapshot-state"))
                else{
                    Text("${String.format(Locale.ROOT,"%.2f",bytes/1_000_000.0)} / ${String.format(Locale.ROOT,"%.2f",StudyCapacity.MAX_SNAPSHOT_BYTES/1_000_000.0)} MB · ${capacityStatus(bytes,StudyCapacity.MAX_SNAPSHOT_BYTES)}",
                        style=MaterialTheme.typography.bodyLarge,modifier=Modifier.testTag("capacity-snapshot-bytes"))
                    Text("$bytes / ${StudyCapacity.MAX_SNAPSHOT_BYTES} 字节",style=MaterialTheme.typography.bodyMedium)
                }
                Text("全库原迹额度为 256 MB，包含其他笔记和回收资料。单份上限 1.8 MB；回收、导出、新建笔记和缩小已有摘录范围不释放历史用量。本版本不能安全自动回收已保存历史。",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(graphFailed||snapshot.failed)TextButton(retry,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-retry")){Text("重新读取容量")}
            HorizontalDivider()
            Text("继续整理",style=MaterialTheme.typography.titleSmall)
            Text("可在当前图移除不需要的叶主题，或在新图复用已有卡片。原迹用量接近上限时，可用同步引用复用已有卡片，避免新增快照；已保存历史仍会占用空间。",
                style=MaterialTheme.typography.bodyMedium)
            TextButton({onAction(StudyCapacityAction.MAP)},enabled=navigationEnabled,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-open-map")){Text("查看当前图")}
            TextButton({onAction(StudyCapacityAction.CARDS)},enabled=navigationEnabled,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-open-cards")){Text("查看已有卡片")}
            TextButton({onAction(StudyCapacityAction.TRASH)},enabled=navigationEnabled,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-open-trash")){Text("查看卡片回收区")}
            TextButton({onAction(StudyCapacityAction.NEW_MAP)},enabled=createMapEnabled,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("capacity-new-map")){Text("新建导图")}
        }},
        confirmButton={TextButton(dismiss,modifier=Modifier.heightIn(min=48.dp).testTag("capacity-close")){Text("关闭")}})
}
