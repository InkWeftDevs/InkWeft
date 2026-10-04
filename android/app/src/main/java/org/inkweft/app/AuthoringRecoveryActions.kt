// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AuthoringRecoveryActions(model:PageAuthoringViewModel,state:AuthoringUi){
    var discard by remember{mutableStateOf(false)}
    if(state.busy&&state.pending)Text("批注／图层尚未保存，正在写入恢复材料或核对事务")
    if(state.pending)FlowRow{
        TextButton(model::retry,enabled=!state.busy){Text("核对重试")}
        TextButton(model::reload,enabled=!state.busy){Text("核对已保存内容")}
        TextButton({discard=true},enabled=!state.busy,modifier=Modifier.testTag("authoring-discard-draft")){Text("放弃未确认草稿…")}
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未确认草稿？")},text={Text("已经提交的笔迹、图层和回执保留。尚未提交的本次批注或图层操作及其恢复材料会被删除，不能再恢复。")},confirmButton={TextButton({discard=false;model.discardPending()}){Text("确认放弃草稿")}},dismissButton={TextButton({discard=false}){Text("继续核对")}})
}
