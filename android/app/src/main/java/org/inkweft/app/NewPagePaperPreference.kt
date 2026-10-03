// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.PaperStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local, per-book creation preference; null keeps the existing inheritance rule. */
internal class NewPagePaperPreference(private val prefs:SharedPreferences, bookId:String) {
    private val key="new-page-paper-$bookId"
    fun read():PaperStyle? = runCatching { prefs.getString(key,null)?.let(PaperStyle::valueOf) }.getOrNull()
    fun observe()=callbackFlow<PaperStyle?> {
        // Wait for commit/rollback before publishing, including a recreated screen's first read.
        fun refresh(){launch { writes.withLock { trySend(read()) } }}
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,changed->if(changed==key||changed==null)refresh()}
        prefs.registerOnSharedPreferenceChangeListener(listener);refresh()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    suspend fun save(paper:PaperStyle?):Boolean = withContext(NonCancellable+Dispatchers.IO) {
        writes.withLock {
            val previous=prefs.all[key] as? String
            fun write(value:String?)=prefs.edit().let { editor ->
                if(value==null)editor.remove(key)else editor.putString(key,value)
                editor.commit()
            }
            val saved=runCatching { write(paper?.name) }.getOrDefault(false)
            // commit() can update memory even when disk persistence fails. Restore the old choice.
            if(!saved)runCatching { write(previous) }
            saved
        }
    }
    private companion object { val writes=Mutex() }
}

@Composable
internal fun NewPagePaperDialog(current:PaperStyle?, enabled:Boolean, dismiss:()->Unit, save:suspend(PaperStyle?)->Boolean) {
    // A dismissed/recreated dialog discards its draft; only the confirm button writes preferences.
    var selected by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)dismiss()},modifier=Modifier.testTag("new-page-paper-dialog"),
        shape=InkTheme.FloatingShape,title={Text("本笔记新增页纸面",style=InkTheme.PanelTitle)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("仅本设备、仅本笔记。用于添加页面的初始选择及连续翻页末尾追加；沿用时分别跟随所选页／末页。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            FilterChip(selected=selected==null,onClick={selected=null},enabled=!busy,
                label={Text("沿用目标页纸面（恢复默认）")},modifier=Modifier.heightIn(min=48.dp).testTag("new-page-paper-inherit"))
            Text("或固定使用内置纸面",style=MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PaperStyle.entries.forEach { paper ->
                    Column(Modifier.width(100.dp)
                        .border(if(selected==paper)2.dp else 1.dp,if(selected==paper)Forest else Line,InkTheme.ToolShape)
                        .background(if(selected==paper)Leaf else Color.White,InkTheme.ToolShape)
                        .selectable(selected=selected==paper,enabled=!busy,role=Role.RadioButton,onClick={selected=paper})
                        .padding(6.dp).testTag("new-page-paper-${paper.name.lowercase()}"),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(72.dp)) { PaperThumbnail(false,paper) }
                        Text(paperLabel(paper),Modifier.padding(top=6.dp),style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text("每次插页可临时改选，不改变此设置。已有页面和笔迹不变。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            if(error)Text("保存未确认，请重试；原页面不变。",color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("new-page-paper-error"))
        }},
        confirmButton={Button(onClick={busy=true;error=false;scope.launch {
            try { if(save(selected))dismiss()else error=true }
            catch(c:CancellationException){throw c}
            catch(_:Exception){error=true}
            finally { busy=false }
        }},enabled=enabled&&!busy,modifier=Modifier.testTag("new-page-paper-save")){Text(if(busy)"正在保存…"else"保存设置")}},
        dismissButton={TextButton(onClick=dismiss,enabled=!busy,modifier=Modifier.testTag("new-page-paper-cancel")){Text("取消")}})
}
