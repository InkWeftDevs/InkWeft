// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.inkweft.core.*

private val Green = Color(0xFF236653)
private val PaperDesk = Color(0xFFF3F5F4)
private val Ink = Color(0xFF24342F)

@Composable
fun NotebookApp(vm: NotebookViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var confirmExport by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = exportSnapshot
        exportSnapshot = null
        if (uri != null && text != null) scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.export_success else R.string.export_failed,
                Toast.LENGTH_LONG).show()
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Green, onPrimary = Color.White, background = PaperDesk,
        surface = Color.White, onSurface = Ink, onBackground = Ink,
    )) {
        BackHandler(enabled = ui.selectedId != null) { vm.back() }
        Scaffold(
            containerColor = PaperDesk,
            topBar = {
                Surface {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (ui.selectedId != null) TextButton(onClick = vm::back) {
                                Text(stringResource(R.string.back))
                            }
                            Text("墨织", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                color = Green, modifier = Modifier.padding(vertical = 17.dp).weight(1f))
                            if (ui.selectedId == null) FilledTonalButton(
                                onClick = { newTitle = ""; showCreate = true },
                                enabled = !ui.readFailed && !ui.loading,
                            ) { Text(stringResource(R.string.new_note)) }
                            else {
                                TextButton(onClick = { confirmExport = true }) {
                                    Text(stringResource(R.string.export_text))
                                }
                                val d = ui.current
                                Button(onClick = vm::save, enabled = d != null && d.title.isNotBlank() &&
                                    d.dirty && d.phase in listOf(SavePhase.EDITING, SavePhase.UNKNOWN)) {
                                    Text(stringResource(if (d?.phase == SavePhase.UNKNOWN)
                                        R.string.retry else R.string.save))
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFE2E8E4))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (ui.readFailed) {
                    Surface(color = Color(0xFFFFEDE8)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.error_load), Modifier.weight(1f), fontSize = 14.sp)
                            TextButton(onClick = vm::retryRead) { Text(stringResource(R.string.retry_load)) }
                        }
                    }
                }
                val draft = ui.current
                if (draft == null) {
                    Library(ui, vm::select, Modifier.weight(1f))
                } else {
                    val status = when (draft.phase) {
                        SavePhase.SAVING -> R.string.saving
                        SavePhase.UNKNOWN -> R.string.unknown
                        SavePhase.CONFLICT -> R.string.conflict
                        SavePhase.REJECTED -> R.string.rejected
                        SavePhase.EDITING -> if (draft.dirty) R.string.draft else R.string.saved
                    }
                    Text(stringResource(status), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = if (draft.dirty) Color(0xFF8A4A1B) else Green, fontSize = 13.sp)
                    if (draft.phase in listOf(SavePhase.CONFLICT, SavePhase.REJECTED)) {
                        TextButton(onClick = vm::discardDraftAndRead, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(stringResource(R.string.load_saved))
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        val pagePadding = if (maxWidth >= 700.dp) 40.dp else 22.dp
                        Surface(shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            shadowElevation = 2.dp, modifier = Modifier.widthIn(max = 860.dp)
                                .fillMaxSize().align(androidx.compose.ui.Alignment.TopCenter)) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding)) {
                                OutlinedTextField(value = draft.title,
                                    onValueChange = { vm.edit(it, draft.text) },
                                    enabled = draft.canEdit,
                                    label = { Text(stringResource(R.string.title)) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
                                Spacer(Modifier.height(24.dp))
                                BasicTextField(value = draft.text,
                                    onValueChange = { vm.edit(draft.title, it) },
                                    enabled = draft.canEdit,
                                    textStyle = TextStyle(fontSize = 18.sp, lineHeight = 30.sp, color = Ink),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
                                    decorationBox = { inner ->
                                        Box {
                                            if (draft.text.isEmpty()) Text(stringResource(R.string.body_hint),
                                                color = Color(0xFF65746D), fontSize = 17.sp, lineHeight = 30.sp)
                                            inner()
                                        }
                                    })
                                Spacer(Modifier.height(28.dp))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.preview_scope),
                    Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 12.sp, color = Color(0xFF596C61))
            }
        }
        if (showCreate) AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.new_note)) },
            text = { OutlinedTextField(value = newTitle, onValueChange = { if (it.length <= 120) newTitle = it },
                singleLine = true, label = { Text(stringResource(R.string.title)) }) },
            confirmButton = { TextButton(onClick = { vm.create(newTitle); showCreate = false },
                enabled = newTitle.isNotBlank()) { Text(stringResource(R.string.create)) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.cancel)) } },
        )
        if (confirmExport) AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.export_text)) },
            text = { Text(stringResource(R.string.export_notice)) },
            confirmButton = { TextButton(onClick = {
                val d = ui.current
                confirmExport = false
                if (d != null) {
                    exportSnapshot = d.title + "\n\n" + d.text
                    export.launch(context.getString(R.string.export_filename))
                }
            }) { Text(stringResource(R.string.export_text)) } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Library(ui: NotebookUi, open: (Note) -> Unit, modifier: Modifier) {
    val display = LinkedHashMap<String, Note>()
    ui.notes.forEach { display[it.id] = it }
    ui.drafts.values.forEach { d -> display[d.base.id] = d.base.copy(title = d.title, text = d.text) }
    Column(modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.library), fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 30.dp, bottom = 8.dp))
        Text(stringResource(R.string.library_hint), color = Color(0xFF586E62), fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (display.isEmpty() && !ui.loading) {
            Text(stringResource(R.string.empty), fontSize = 22.sp, modifier = Modifier.padding(top = 50.dp))
            Text(stringResource(R.string.empty_hint), fontSize = 15.sp,
                modifier = Modifier.padding(top = 12.dp), lineHeight = 24.sp)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(display.values.toList(), key = { it.id }) { note ->
                Card(onClick = { open(note) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp).heightIn(min = 200.dp)) {
                        Text("墨织 · 本地笔记", color = Green, fontSize = 12.sp)
                        Spacer(Modifier.height(22.dp))
                        Text(note.title.ifBlank { stringResource(R.string.untitled) },
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Spacer(Modifier.height(10.dp))
                        Text(note.text.ifBlank { "…" }, maxLines = 3, fontSize = 14.sp,
                            lineHeight = 22.sp, color = Color(0xFF586E62))
                        Spacer(Modifier.height(22.dp))
                        Text(stringResource(if (ui.drafts[note.id]?.dirty == true) R.string.draft else R.string.saved),
                            fontSize = 11.sp, color = Green)
                    }
                }
            }
        }
    }
}
