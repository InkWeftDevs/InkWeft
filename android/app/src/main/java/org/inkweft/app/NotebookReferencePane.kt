// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.core.*

/** Independent reading position; switching the editing side never duplicates a writer. */
@Composable internal fun NotebookReferencePane(id:String,title:String,enabled:Boolean,vertical:Boolean,rotate:()->Unit,edit:(String)->Unit,close:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val pages by remember(id){app.pages.observe(id)}.collectAsStateWithLifecycle(initialValue=emptyList())
    var index by rememberSaveable(id){mutableIntStateOf(0)}
    val page=pages.filter{it.trashedAt==null}.getOrNull(index.coerceAtMost((pages.size-1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().testTag("reference-pane")){
        val referenceActions:@Composable ()->Unit={
            TextButton({page?.id?.let(edit)},enabled=enabled&&page!=null,modifier=Modifier.testTag("split-edit")){Text("切换编辑")}
            IconButton(rotate,enabled=enabled,modifier=Modifier.testTag("split-rotate").describedAs(if(vertical)"改为左右分屏"else"改为上下分屏")){Glyph("grid")}
            IconButton(close,enabled=enabled,modifier=Modifier.testTag("split-close").describedAs("结束分屏")){Glyph("close")}
        }
        BoxWithConstraints(Modifier.fillMaxWidth()){
            if(maxWidth<248.dp)Column{
                Text(title,Modifier.fillMaxWidth().padding(horizontal=8.dp),maxLines=1,style=MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){referenceActions()}
            }else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(title,Modifier.weight(1f).padding(start=8.dp),maxLines=1,style=MaterialTheme.typography.labelLarge)
                referenceActions()
            }
        }
        if(page!=null)key(page.id){
            val inkVm:InkViewModel=viewModel(key="ink-${page.id}",factory=InkViewModel.Factory(page.id,app.inkRepository))
    DisposableEffect(inkVm){inkVm.attachPage();onDispose{inkVm.detachPage()}}
            val ink by inkVm.ui.collectAsStateWithLifecycle()
            val objects by remember(page.id){app.pageObjects.observe(page.id)}.collectAsStateWithLifecycle(initialValue=null)
            val strokes=ink.strokes
            val content=remember(objects){objects?.let{PageObjectCodec.decode(it.payload)}.orEmpty()}
            AndroidView(factory={InkCanvasView(it)},update={v->v.configure(page.world,PaperStyle.entries[page.paper],null);v.allowInput=false;v.showDocument(page.id);v.showStrokes(strokes);v.showObjects(content)},modifier=Modifier.weight(1f).fillMaxWidth().testTag("reference-canvas"))
        }else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text("正在载入笔记…")}
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){
            TextButton({index--},enabled=index>0){Text("上一页")};Text("${index+1} / ${pages.size}")
            TextButton({index++},enabled=index<pages.lastIndex){Text("下一页")}
        }
    }
}
