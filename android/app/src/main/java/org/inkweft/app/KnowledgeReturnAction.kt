// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** The same bounded return stack is reachable inside a modal target, not only behind it. */
@Composable internal fun KnowledgeReturnAction(enabled:Boolean,beforeOpen:()->Boolean={true}){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val workspace:WorkspaceViewModel=viewModel()
    val stack by workspace.knowledgeReturns.collectAsStateWithLifecycle()
    val opening by app.openKnowledgeTarget.collectAsStateWithLifecycle()
    if(stack.isNotEmpty())TextButton({
        if(beforeOpen())workspace.requestKnowledgeReturn()?.let{app.openKnowledgeTarget.value=it}
    },enabled=enabled&&opening==null,modifier=Modifier.testTag("knowledge-return-context")){Text("返回关联前位置")}
}
