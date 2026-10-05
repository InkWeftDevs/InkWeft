// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Explicit diagnostic opt-in only; normal debug and release sessions stay silent. */
@Volatile internal var studyReadPhaseEnabled=false

/** Fixed phases only: never log page/card identities, content, bytes or exception messages. */
internal fun traceStudyRead(phase:String,job:Job?){
    if(BuildConfig.DEBUG&&studyReadPhaseEnabled){
        val thread=Thread.currentThread()
        android.util.Log.d("StudyReadPhase","phase=$phase job=${System.identityHashCode(job)} thread=${thread.id}:${thread.name} active=${job?.isActive}")
    }
}

/** Read the actual source through existing repositories; return keeps the same review session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReviewSourceDialog(source:StudySourceRow,dismiss:()->Unit,session:ShadowAuthorSession?=null,recallNotebookId:String?=null,
    onViewSnapshot:(()->Unit)?=null){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var loaded by remember(source,recallNotebookId){mutableStateOf<ReviewPage?>(null)};var error by remember(source,recallNotebookId){mutableStateOf<String?>(null)}
    var view by remember{mutableStateOf<InkCanvasView?>(null)}
    val readJob=remember(source,recallNotebookId){if(BuildConfig.DEBUG)java.util.concurrent.atomic.AtomicReference<Job?>(null)else null}
    LaunchedEffect(source,recallNotebookId){
        val job=coroutineContext[Job];readJob?.set(job);traceStudyRead("source.effect.start",job)
        try{
            val result=withContext(Dispatchers.IO){
                traceStudyRead("source.io.enter",job)
                val knowledge=session?.knowledge?:app.knowledge
                traceStudyRead("source.repository.ready",job)
                val (note,_)=knowledge.resolve(TargetRef(TargetKind.PAGE,source.pageId))
                traceStudyRead("source.resolve.done",job)
                require(recallNotebookId==null||note.id==recallNotebookId){"REVIEW_SOURCE_SCOPE_CHANGED"}
                traceStudyRead("source.scope.accepted",job)
                val page=requireNotNull((session?.pages?:app.pages).activePages(note.id).find{it.id==source.pageId})
                traceStudyRead("source.page.done",job)
                val objects=(session?.objects?:app.pageObjects).read(page.id).objects.let{items->
                    if(recallNotebookId==null)items else items.filter{it.mapEmbed?.target?.notebookId?.let{book->book==recallNotebookId}!=false}
                }
                traceStudyRead("source.objects.done",job)
                val ink=(session?.ink?:app.inkRepository).read(page.id)
                traceStudyRead("source.ink.done",job)
                val authoring=(session?.authoring?:app.authoring).readPage(page.id).state
                traceStudyRead("source.authoring.done",job)
                ReviewPage(note.title,page,ink,objects,authoring).also{traceStudyRead("source.io.return",job)}
            }
            traceStudyRead("source.publish.loaded.before",job);loaded=result;traceStudyRead("source.publish.loaded.after",job)
        }catch(c:CancellationException){traceStudyRead("source.effect.cancelled",job);throw c}
        catch(_:Exception){
            traceStudyRead("source.publish.error.before",job)
            error="来源页已回收、不可用或读取失败；已保存的摘录快照仍保留。"
            traceStudyRead("source.publish.error.after",job)
        }finally{traceStudyRead("source.effect.finally",job)}
    }
    LaunchedEffect(loaded,view){if(loaded!=null)view?.post{view?.focusRegion(CanvasBounds(source.left,source.top,source.right,source.bottom))}}
    // Observe success and failure here as well as forwarding their values into the child Dialog.
    val result=loaded;val failure=error
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        RecallWindowPermit()
        Surface(Modifier.fillMaxSize().testTag("review-source")){Column(Modifier.safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
                Text("来源页 · 只读",Modifier.weight(1f));TextButton(onClick=dismiss,modifier=Modifier.heightIn(min=48.dp).testTag("return-to-review")){Text(if(session==null)"返回此题"else"返回知识卡")}
            }
            val renderPhase=if(result!=null)"source.compose.loaded"else if(failure!=null)"source.compose.error"else "source.compose.loading"
            SideEffect{traceStudyRead(renderPhase,readJob?.get())}
            if(result!=null){
                Text("${result.title} · 第 ${result.page.position+1} 页",Modifier.padding(horizontal=16.dp))
                if(result.ink.revision!=source.inkRevision)Text("来源已变化，以下显示当前原页；卡片摘录时的快照仍单独保留。",Modifier.padding(16.dp))
                AndroidView(factory={InkCanvasView(it).also{v->v.authorSession=session;view=v;v.allowInput=false;v.fingerWrites=false}},update={v->
                    v.configure(result.page.world,PaperStyle.entries[result.page.paper],null);v.allowInput=false;v.showAuthoring(result.authoring);v.showDocument(result.page.id);v.showObjects(result.objects);v.showStrokes(InkSession(result.ink).visibleDraft())
                },modifier=Modifier.fillMaxWidth().weight(1f).testTag("review-source-canvas"))
            }else if(failure!=null)Text(failure,Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("review-source-unavailable"))
            else Box(Modifier.weight(1f)){CircularProgressIndicator(Modifier.padding(24.dp))}
            FlowRow(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
                if(result!=null){
                    TextButton(onClick={view?.zoomBy(1/1.2)},modifier=Modifier.heightIn(min=48.dp)){Text("缩小")}
                    TextButton(onClick={view?.zoomBy(1.2)},modifier=Modifier.heightIn(min=48.dp)){Text("放大")}
                    TextButton(onClick={view?.fitContent()},modifier=Modifier.heightIn(min=48.dp)){Text("全部内容")}
                }
                onViewSnapshot?.let{open->TextButton({traceStudyRead("source.snapshot.open",readJob?.get());open()},modifier=Modifier.heightIn(min=48.dp).testTag("review-source-fixed-snapshot")){Text("查看固定摘录快照")}}
            }
        }}
    }
}
