// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Always reachable from the shelf, editor and read-error screen; never requires a healthy DB query. */
@Composable
fun DiagnosticsApp() {
    val vm: NotebookViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as InkWeftApplication
    val current = ui.current
    val result = when {
        ui.readFailed -> DiagnosticResult.READ_FAILED
        ui.loading -> DiagnosticResult.LOADING
        current == null -> DiagnosticResult.OBSERVED
        current.phase == SavePhase.UNKNOWN -> DiagnosticResult.UNKNOWN
        current.phase == SavePhase.CONFLICT -> DiagnosticResult.CONFLICT
        current.phase == SavePhase.REJECTED -> DiagnosticResult.REJECTED
        current.phase == SavePhase.SAVING -> DiagnosticResult.SAVING
        current.dirty -> DiagnosticResult.EDITING
        else -> DiagnosticResult.SAVED
    }
    SideEffect { app.diagnostics.notebook(ui.loading, ui.readFailed, ui.notes.size, ui.drafts.size, ui.drafts.values.count { it.dirty }, result) }
    var open by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff236653))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { NotebookApp(vm) }
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("诊断预览 · ${BuildConfig.VERSION_NAME}", modifier = Modifier.weight(1f).padding(vertical = 14.dp), fontSize = 11.sp)
                    TextButton(onClick = { open = true }, modifier = Modifier.testTag("open-diagnostics")) { Text("诊断与导出") }
                }
            }
        }
        if (open) DiagnosticDialog(onClose = { open = false })
    }
}

@Composable
fun DiagnosticDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = (context.applicationContext as InkWeftApplication).diagnostics
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf("先重现问题，再导出；不需要找项目目录里的日志。") }
    var clearConfirm by remember { mutableStateOf(false) }
    fun exportName() = "墨织诊断-" + DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".zip"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val data = pending; pending = null
        if (uri == null) { busy = false; status = "已取消保存，笔记和诊断记录未删除。"; diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.CANCELLED) }
        else if (data == null) { busy = false; status = "页面已重建，待导出快照已丢弃。请重新导出；笔记未改动。" }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(data); it.flush() } }
                diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.OK, data.size.toLong())
                status = "已写入你选的位置。把这个 ZIP 上传到聊天；它不是笔记备份。"
            } catch (cancelled: CancellationException) { diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.CANCELLED); throw cancelled
            } catch (_: Exception) { diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.IO_FAILED); status = "写入失败，目标可能有不完整文件。请另选位置重试；原笔记未改动。"
            } finally { busy = false }
        }
    }
    fun startExport(share: Boolean) {
        if (busy) return
        busy = true; status = "正在整理受限诊断数据…"
        scope.launch {
            try {
                diagnostics.event(DiagnosticCode.EXPORT)
                val bytes = diagnostics.bundle()
                if (!share) { pending = bytes; save.launch(exportName()) }
                else {
                    val exported = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "diagnostics-export")
                        check(dir.isDirectory || dir.mkdirs())
                        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("report-") && it.extension == "zip" } ?: emptyList()
                        // Only own reports older than one day; do not race an open chooser by deleting the latest report.
                        files.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach { it.delete() }
                        check((dir.listFiles()?.count { it.isFile } ?: 0) < 10) { "export cache limit" }
                        File(dir, "report-${UUID.randomUUID()}.zip").also { it.outputStream().use { out -> out.write(bytes) } }
                    }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostics.files", exported)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, "墨织诊断包", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享诊断 ZIP"))
                    status = "已打开系统分享。请选聊天或文件应用；打开分享不等于对方已收到。"
                    diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.OBSERVED, bytes.size.toLong())
                    busy = false
                }
            } catch (cancelled: CancellationException) { busy = false; diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.CANCELLED); throw cancelled
            } catch (_: Exception) {
                busy = false; pending = null; diagnostics.event(DiagnosticCode.EXPORT, DiagnosticResult.IO_FAILED)
                status = "整理或分享失败，可改用“保存 ZIP”。应用不会删除笔记或自动上传。"
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("诊断与反馈") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("diagnostics-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${BuildConfig.VERSION_NAME}\n构建：${BuildConfig.BUILD_COMMIT.take(12)}", fontSize = 12.sp)
                Text("包含：应用版本、设备型号/系统、内存/电量/热状态快照、最近200条固定类型事件、当前已观察的保存状态和数量。")
                Text("不包含：笔记标题与正文、笔迹坐标、PDF、图片、数据库、系统logcat、异常正文、文件路径/URI、密码、API Key、设备序列号或账号。", color = Color(0xff236653))
                Text("文件是明文，仅你主动保存或分享。系统选择的位置可能是云盘。不能补录旧版本日志，也不等于自动运行Gradle、Room、Compose或Pencil3测试。", fontSize = 12.sp)
                Text(status, modifier = Modifier.testTag("diagnostics-status"), fontSize = 13.sp)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { startExport(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("diagnostics-save")) { Text("保存诊断 ZIP") }
                OutlinedButton(onClick = { startExport(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("diagnostics-share")) { Text("直接分享诊断 ZIP") }
                TextButton(onClick = { diagnostics.event(DiagnosticCode.USER_MARK); status = "已加时间标记。关闭此页重现问题，再回来导出。" }, enabled = !busy, modifier = Modifier.testTag("diagnostics-mark")) { Text("标记现在，开始重现问题") }
                TextButton(onClick = { clearConfirm = true }, enabled = !busy) { Text("清空本机诊断记录（不删笔记）") }
            }
        }, confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关闭") } })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("只清空诊断记录？") },
        text = { Text("不会删除或修改笔记。已经导出/分享的 ZIP 不会撤回。") },
        confirmButton = { TextButton(onClick = {
            clearConfirm = false; busy = true
            scope.launch { try { diagnostics.clear(); status = "内存中的诊断记录已清空。后续活动会继续产生新记录；落盘状态会写入下次诊断包。" } finally { busy = false } }
        }) { Text("清空诊断") } }, dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}
