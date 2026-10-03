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
    val fontScale:Float,val expandedNodeId:String?,
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
                Text("按大纲顺序排布全部 ${nodes.size} 个主题，包含收起的分支。应用会更新位置，可撤销恢复手动布局。",style=MaterialTheme.typography.bodySmall)
                AndroidView(factory={MindMapView(it).apply{contentDescription="自动布局预览";authorEditing=false;map=this
                    addOnLayoutChangeListener{_,l,t,r,b,oldL,oldT,oldR,oldB->if(r>l&&b>t&&(oldR==oldL||oldB==oldT))fitOverview()}
                }},
                    update={v->v.authorEditing=false;v.expandedNodeId=preview.expandedNodeId;v.show(nodes,preview.cards,sources=preview.sources,structuralCardIds=preview.structuralIds)},
                    modifier=Modifier.fillMaxWidth().weight(1f).padding(vertical=8.dp).describedAs("自动布局预览").testTag("study-layout-canvas"))
                TextButton({map?.fitOverview()},modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-fit")){Text("适配全部主题")}
                Text(if(current)"双指缩放、拖动空白查看。取消后回到原视野。"else"内容或字号已变化，请取消后重新预览。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            }
        },confirmButton={TextButton(apply,enabled=current,modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-apply")){Text("应用布局")}},
        dismissButton={TextButton(cancel,modifier=Modifier.heightIn(min=48.dp).testTag("study-layout-cancel")){Text("取消")}})
}
