// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*

private data class ReviewPage(val title:String,val page:NotebookPageRow,val ink:InkPage,val objects:List<PageObject>,val authoring:PageAuthoring)

/** Read the actual source through existing repositories; return keeps the same review session. */
@Composable internal fun ReviewSourceDialog(source:StudySourceRow,dismiss:()->Unit,session:ShadowAuthorSession?=null,recallNotebookId:String?=null){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var loaded by remember(source,recallNotebookId){mutableStateOf<ReviewPage?>(null)};var error by remember(source,recallNotebookId){mutableStateOf<String?>(null)}
    var view by remember{mutableStateOf<InkCanvasView?>(null)}
    LaunchedEffect(source,recallNotebookId){try{loaded=withContext(Dispatchers.IO){
        val (note,_)= (session?.knowledge?:app.knowledge).resolve(TargetRef(TargetKind.PAGE,source.pageId))
        require(recallNotebookId==null||note.id==recallNotebookId){"REVIEW_SOURCE_SCOPE_CHANGED"}
        val page=requireNotNull((session?.pages?:app.pages).activePages(note.id).find{it.id==source.pageId})
        val objects=(session?.objects?:app.pageObjects).read(page.id).objects.let{items->
            if(recallNotebookId==null)items else items.filter{it.mapEmbed?.target?.notebookId?.let{book->book==recallNotebookId}!=false}
        }
        ReviewPage(note.title,page,(session?.ink?:app.inkRepository).read(page.id),objects,(session?.authoring?:app.authoring).readPage(page.id).state)
    }}catch(c:CancellationException){throw c}catch(_:Exception){error="来源页已回收、不可用或读取失败；已保存的摘录快照仍保留。"}}
    LaunchedEffect(loaded,view){if(loaded!=null)view?.post{view?.focusRegion(CanvasBounds(source.left,source.top,source.right,source.bottom))}}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        RecallWindowPermit()
        Surface(Modifier.fillMaxSize().testTag("review-source")){Column(Modifier.safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
                Text("来源页 · 只读",Modifier.weight(1f));TextButton(onClick=dismiss,modifier=Modifier.testTag("return-to-review")){Text(if(session==null)"返回此题"else"返回知识卡")}
            }
            val result=loaded
            if(result!=null){
                Text("${result.title} · 第 ${result.page.position+1} 页",Modifier.padding(horizontal=16.dp))
                if(result.ink.revision!=source.inkRevision)Text("来源已变化，以下显示当前原页；卡片摘录时的快照仍单独保留。",Modifier.padding(16.dp))
                AndroidView(factory={InkCanvasView(it).also{v->v.authorSession=session;view=v;v.allowInput=false;v.fingerWrites=false}},update={v->
                    v.configure(result.page.world,PaperStyle.entries[result.page.paper],null);v.allowInput=false;v.showAuthoring(result.authoring);v.showDocument(result.page.id);v.showObjects(result.objects);v.showStrokes(InkSession(result.ink).visibleDraft())
                },modifier=Modifier.fillMaxWidth().weight(1f).testTag("review-source-canvas"))
                Row{TextButton(onClick={view?.zoomBy(1/1.2)}){Text("缩小")};TextButton(onClick={view?.zoomBy(1.2)}){Text("放大")};TextButton(onClick={view?.fitContent()}){Text("全部内容")}}
            }else if(error!=null)Text(error!!,Modifier.padding(24.dp))else CircularProgressIndicator(Modifier.padding(24.dp))
        }}
    }
}
