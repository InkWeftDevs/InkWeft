// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.inkweft.core.*
import org.inkweft.data.*

/** A frozen visual proposal. Opening or cancelling it never changes author data or the main camera. */
internal data class StudyLayoutPreview(
    val state:StudyGraphState,val plan:StudyOrganizationPlan,val cards:List<StudyCardRow>,
    val sources:Map<String,MapSourceInfo>,val structuralIds:Set<String>,val sizes:Map<String,StudyNodeSize>,
    val fontScale:Float,val expandedNodeId:String?,val selection:Set<String> = emptySet(),
)

@Composable internal fun StudyLayoutPreviewDialog(preview:StudyLayoutPreview,embedded:Boolean,current:Boolean,
    cancel:()->Unit,apply:()->Unit){
    var map by remember(preview){mutableStateOf<MindMapView?>(null)}
    val placements=remember(preview){preview.plan.after.placements.associateBy{it.nodeId}}
    val nodes=remember(preview){preview.state.nodes.filterNot{it.removed}.map{n->
        val p=placements.getValue(n.id)
        StudyNodeRow(n.id,preview.state.ref.notebookId,n.cardId,p.parentId,p.x,p.y,n.revision)
    }}
    StudyDialog(embedded,onDismissRequest=cancel,modifier=Modifier.testTag("study-layout-preview"),
        title={Text("自动布局预览")},text={
            Column(if(embedded)Modifier.fillMaxSize()else Modifier.height(400.dp)){
                Text(if(preview.selection.isEmpty())"按大纲顺序排布全部 ${nodes.size} 个主题，包含收起的分支。应用会更新位置，可撤销恢复手动布局。"else"只排布所选整支 ${StudyOrganization.selectedBranchIds(preview.state,preview.selection).size} 个主题；其他主题与来源保持。应用后可撤销、重做。",style=MaterialTheme.typography.bodySmall)
                AndroidView(factory={MindMapView(it).apply{contentDescription="自动布局预览";authorEditing=false;map=this
                    addOnLayoutChangeListener{_,l,t,r,b,oldL,oldT,oldR,oldB->if(r>l&&b>t&&(oldR==oldL||oldB==oldT))fitOverview()}
                }},
                    update={v->v.authorEditing=false;v.selectedNodeIds=preview.selection;v.expandedNodeId=preview.expandedNodeId;v.show(nodes,preview.cards,sources=preview.sources,structuralCardIds=preview.structuralIds)},
                    modifier=Modifier.fillMaxWidth().weight(1f).padding(vertical=8.dp).describedAs("自动布局预览").testTag("study-layout-canvas"))
                TextButton({map?.fitOverview()},modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-fit")){Text("适配全部主题")}
                Text(if(current)"双指缩放、拖动空白查看。取消后回到原视野。"else"内容或字号已变化，请取消后重新预览。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            }
        },confirmButton={TextButton(apply,enabled=current,modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-apply")){Text("应用布局")}},
        dismissButton={TextButton(cancel,modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-cancel")){Text("取消")}})
}

internal enum class OutlineDropPosition { BEFORE, CHILD, AFTER }
internal data class OutlineDropPreview(val targetId:String,val position:OutlineDropPosition,val plan:StudyOrganizationPlan?,val message:String)
internal data class OutlineDrag(val state:StudyGraphState,val nodeId:String,val pointer:androidx.compose.ui.geometry.Offset,val preview:OutlineDropPreview?=null)

/** Resolve only the hovered row/zone. No author write occurs until the one pointer-up commit. */
internal fun outlineDropPreview(state:StudyGraphState,nodeId:String,targetId:String,position:OutlineDropPosition,title:String,collapsed:Boolean):OutlineDropPreview {
    val target=state.nodes.first{it.id==targetId&&!it.removed}
    val siblings=state.orderedNodeIds.filter{id->state.nodes.first{it.id==id}.parentId==target.parentId}
    val parent=if(position==OutlineDropPosition.CHILD)targetId else target.parentId
    val before=when(position){OutlineDropPosition.BEFORE->targetId;OutlineDropPosition.CHILD->null
        OutlineDropPosition.AFTER->siblings.drop(siblings.indexOf(targetId)+1).firstOrNull{it!=nodeId}}
    val plan=if(nodeId==targetId)null else runCatching{StudyOrganization.reparent(state,nodeId,parent,before)}.getOrNull()
    val action=when(position){OutlineDropPosition.BEFORE->"放在「$title」之前（同级）";OutlineDropPosition.AFTER->"放在「$title」之后（同级）"
        OutlineDropPosition.CHILD->"移入「$title」下级"+if(collapsed)"，松手后展开目标"else""}
    val message=when{nodeId==targetId->"停在原主题：松手取消";plan==null->"禁止落点：会形成循环或超过层级上限"
        plan.before==plan.after->"位置未改变：松手不写入";else->"松手：$action；整支一起移动"}
    return OutlineDropPreview(targetId,position,plan,message)
}
