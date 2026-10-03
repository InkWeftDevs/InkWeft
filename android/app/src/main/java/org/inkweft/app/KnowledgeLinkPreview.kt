// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOn
import org.inkweft.core.TargetRef
import org.inkweft.data.KnowledgeRejected
import org.inkweft.data.KnowledgeTextPreview

/** Both directions retain the clicked relationship's identity until explicitly closed. */
@Composable
internal fun KnowledgeLinkPreview(
    focus: TargetRef, linkId: String, linkRevision: Long, incoming: Boolean = false,
    enabled: Boolean, returnLabel: String = "返回摘要", dismiss: () -> Unit,
    onOpenTarget: (TargetRef) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InkWeftApplication
    var preview by remember { mutableStateOf<KnowledgeTextPreview?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val openTarget by rememberUpdatedState(onOpenTarget)
    fun failed(error: Exception) {
        failure = if (error is KnowledgeRejected) "关联已变化，请返回列表重新选择。" else "预览读取失败，请重试。"
        preview = null
    }
    LaunchedEffect(focus, linkId, linkRevision, incoming, attempt) {
        reading = true; failure = null; preview = null
        try {
            app.knowledgeText.observePreview(focus, linkId, linkRevision, incoming)
                .flowOn(Dispatchers.IO).collect { value ->
                    preview = value; failure = null; reading = false
                }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failed(error); reading = false }
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val contentHeight = with(LocalDensity.current) { (windowSize.height.toDp() * .5f).coerceAtLeast(100.dp) }
    AlertDialog(
        onDismissRequest = { if (!opening) dismiss() },
        modifier = Modifier.fillMaxWidth(.96f).widthIn(max = 640.dp).testTag("card-link-preview"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (incoming) "反向引用 · 来源预览" else "关联目标预览") },
        text = { Column(Modifier.heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            preview?.let { Text(it.title, style = MaterialTheme.typography.titleLarge) }
            Text(preview?.pinnedRevision?.let { "固定摘录 · 修订 $it" } ?: "当前内容", style = MaterialTheme.typography.labelLarge, color = Forest)
            if (reading) CircularProgressIndicator(Modifier.size(24.dp))
            preview?.let { value ->
                SelectionContainer { Text(value.body.ifBlank { "尚未填写内容" }, modifier = Modifier.testTag("card-link-preview-body")) }
                if (value.pinnedRevision != null && value.canOpen) Text("此处预览固定版本；打开目标会查看当前卡片。", style = MaterialTheme.typography.bodySmall, color = Quiet)
                if (!value.canOpen) Text("目标不可打开，预览仍保留可读取的内容。", style = MaterialTheme.typography.bodySmall, color = Quiet)
                Text("只读预览，不展开其他关联。", style = MaterialTheme.typography.bodySmall, color = Quiet)
            }
            failure?.let { Text(it, modifier = Modifier.testTag("card-link-preview-error"))
                TextButton(onClick = { attempt++ }, enabled = !opening, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试预览") }
            }
        } },
        confirmButton = { TextButton(onClick = {
            if (!opening && enabled) {
                opening = true
                scope.launch {
                    try {
                        val checked = withContext(Dispatchers.IO) { app.knowledgeText.preview(focus, linkId, linkRevision, incoming) }
                        preview = checked
                        if (checked.canOpen) openTarget(checked.target)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { failed(error) }
                    finally { opening = false }
                }
            }
        }, enabled = enabled && !reading && !opening && failure == null && preview?.canOpen == true,
            modifier = Modifier.heightIn(min = 48.dp).testTag("card-link-open-target")) { Text(if (incoming) "打开引用来源" else "打开目标") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !opening,
            modifier = Modifier.heightIn(min = 48.dp).testTag("card-link-close-preview")) { Text(returnLabel) } },
    )
}
