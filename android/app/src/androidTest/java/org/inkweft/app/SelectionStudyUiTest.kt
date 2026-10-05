// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic author content. Native selection, rendering and shared UI, not a design mock. */
class SelectionStudyUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun awaitBeauty(){
        var last:Throwable?=null
        try{compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("apply-beautify").assertIsDisplayed().assertIsEnabled()}.onFailure{last=it}.isSuccess}}
        catch(error:Throwable){
            println("BEAUTIFY_CONTROL_FAILURE: $last")
            runCatching{shot("beautify-failure.png")}
            compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.forEach{i->runCatching{println(compose.onAllNodes(isRoot(),useUnmergedTree=true)[i].printToString())}}
            throw error
        }
    }
    private fun ready(){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun saved(n:Int){compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("ink-surface").assertSavedInkCount(n)}.isSuccess}}
    private fun seed():Pair<Note,InkStroke>{
        ready();val n=runBlocking{app.workspaceRepository.create("学习整合-${id().take(6)}",false,PaperStyle.BLANK)}
        val s=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(200f,600f,0,.5f),InkSample(300f,600.6f,30,.5f),InkSample(400f,600f,60,.5f),InkSample(600f,600f,100,.5f)))
        runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(s)))}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();saved(1)
        compose.frameCanvasFixture();return n to s
    }
    private fun nativeCanvas():InkCanvasView{
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun select(left:Double=170.0,top:Double=570.0,right:Double=630.0,bottom:Double=635.0){
        compose.onNodeWithTag("ink-select").performClick();compose.waitForIdle()
        compose.onNodeWithTag("ink-select").performClick();compose.onNodeWithTag("lasso-rectangle").performClick();compose.onNodeWithContentDescription("关闭套索").performClick()
        var a=Offset.Zero;var b=Offset.Zero
        compose.runOnIdle{val v=nativeCanvas();val vp=v.snapshotViewport();val d=v.resources.displayMetrics.density.toDouble();val p=vp.worldToScreen(left,top,v.width.toDouble(),v.height.toDouble(),d);val q=vp.worldToScreen(right,bottom,v.width.toDouble(),v.height.toDouble(),d);a=Offset(p.x.toFloat(),p.y.toFloat());b=Offset(q.x.toFloat(),q.y.toFloat())}
        compose.onNodeWithTag("selection-overlay").performTouchInput{swipe(a,b,300)};compose.waitForIdle()
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun addCard(title:String,body:String){
        if(compose.onAllNodesWithTag("capture-destination").fetchSemanticsNodes().isNotEmpty()){
            val currentBook=compose.runOnIdle{checkNotNull(ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)}
            val writer=compose.runOnIdle{ViewModelProvider(compose.activity)["study-$currentBook",StudyViewModel::class.java]}
            val panel=compose.runOnIdle{ViewModelProvider(compose.activity)["study-panel-$currentBook",StudyPanelSession::class.java]}
            val generation=compose.runOnIdle{writer.captureGeneration}
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val evidenceDirectory=instrumentation.targetContext.getExternalFilesDir(null)
            var stage="choose-main";var graphBefore:String?=null
            var cachedCaptureUi="No ready capture control was observed"
            val captureDeadline=android.os.SystemClock.elapsedRealtime()+10_000
            fun awaitCapture(condition:()->Boolean){
                val remaining=captureDeadline-android.os.SystemClock.elapsedRealtime()
                check(remaining>0){"Capture's original 10-second budget expired at $stage"}
                compose.waitUntil(remaining,condition)
            }
            fun tapReady(tag:String,captureBudget:Boolean=false){
                val enabled={runCatching{compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()}.isSuccess}
                if(captureBudget)awaitCapture(enabled)else compose.waitUntil(10_000,enabled)
                if(captureBudget)cachedCaptureUi=runCatching{
                    "READY_BEFORE_TOUCH $tag\n"+compose.onNodeWithTag(tag,useUnmergedTree=true).printToString().take(900)+"\n"+
                        compose.onNodeWithTag("capture-destination",useUnmergedTree=true).printToString().take(2_500)
                }.getOrElse{"Ready UI snapshot unavailable: ${it.javaClass.simpleName}"}
                compose.onNodeWithTag(tag).performTouchInput{click()}
            }
            try{
                tapReady("capture-map-main",captureBudget=true)
                stage="await-capture-write-ready"
                awaitCapture{val state=writer.ui.value;!state.loading&&!state.readFailed&&!state.busy&&!state.unknown}
                // The button uses CaptureDestination's selected available scene and both writers' busy/unknown guards.
                // Do not retry or replace its graphHash if a concurrent change makes the repository reject it.
                graphBefore=runBlocking{app.mapGraphs.read(currentBook).single{it.ref==MapRef(currentBook)}.graphHash}
                stage="send-once";tapReady("capture-send",captureBudget=true)
                stage="await-capture-result"
                awaitCapture{
                    val state=writer.ui.value
                    check(!state.unknown){"Capture result unknown: ${state.message}"}
                    check(state.busy||state.message==null){"Capture rejected: ${state.message}"}
                    writer.captureGeneration>generation&&compose.onAllNodesWithTag("capture-result").fetchSemanticsNodes().isNotEmpty()
                }
                compose.runOnIdle{
                    assertEquals("Exactly one native capture is committed",generation+1,writer.captureGeneration)
                    assertEquals(MapRef(currentBook) to null,panel.captureResult.value)
                    assertNull(panel.captureDraft.value)
                }
                stage="open-captured-card"
                val viewResult=compose.onNode(hasText("查看",substring=false) and hasAnyAncestor(hasTestTag("capture-result")))
                compose.waitUntil(10_000){runCatching{viewResult.assertIsDisplayed().assertIsEnabled()}.isSuccess}
                viewResult.performTouchInput{click()}
                compose.revealAction("study-tab-0");tapReady("study-tab-0")
                compose.waitUntil(10_000){runBlocking{app.study.cards(currentBook).first()}.isNotEmpty()}
                val cardId=runBlocking{app.study.cards(currentBook).first()}.single().id
                tapReady("study-card-$cardId")
                compose.onNodeWithTag("study-edit-card").performScrollTo();tapReady("study-edit-card")
            }catch(error:Throwable){
                // The failure itself may be non-idle UI or blocked IO. Do not await Compose or query Room here.
                runCatching{
                    val status=runCatching{
                        val state=writer.ui.value;val draft=panel.captureDraft.value
                        "stage=$stage generation=${writer.captureGeneration}/$generation loading=${state.loading} readFailed=${state.readFailed} busy=${state.busy} unknown=${state.unknown} message=${state.message} cards=${state.cards.size} nodes=${state.nodes.size} target=${panel.captureTarget.value} result=${panel.captureResult.value} sourceRevision=${draft?.source?.inkRevision} authoringRevision=${draft?.source?.authoringRevision} selectedStrokes=${draft?.source?.strokeIds?.size} writerGraph=${state.graph?.graphFingerprint}"
                    }.getOrElse{"State diagnostics unavailable: ${it.javaClass.simpleName}"}
                    val diagnostic=("SELECTION_CAPTURE_FAILURE ${status.take(2_000)}\ngraphBefore=$graphBefore\n$cachedCaptureUi").take(4_000)
                    runCatching{println(diagnostic)}
                    runCatching{File(checkNotNull(evidenceDirectory),"selection-capture-failure.txt").writeText(diagnostic)}
                        .onFailure{runCatching{println("Capture text unavailable: ${it.javaClass.simpleName}")}}
                    runCatching{
                        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try{File(checkNotNull(evidenceDirectory),"selection-capture-failure.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
                        finally{bitmap.recycle()}
                    }.onFailure{runCatching{println("Capture screenshot unavailable: ${it.javaClass.simpleName}")}}
                }.onFailure{runCatching{println("Capture diagnostics unavailable: ${it.javaClass.simpleName}")}}
                throw error
            }
        }
        compose.onNodeWithTag("study-card-title").performTextReplacement(title);compose.onNodeWithTag("study-card-body").performTextReplacement(body);compose.onNodeWithTag("study-save-card").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithTag("study-card-editor").fetchSemanticsNodes().isEmpty()}
        // The dialog can leave the semantics tree before the platform IME finishes
        // resizing the card list. Wait for that real input transition before tapping.
        compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(10_000){ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        compose.waitForIdle()
    }
    @Test fun regionEraseCutsOnlyInsideAndUndoRestoresOnRealCanvas(){
        val(n,s)=seed();select(380.0,570.0,420.0,640.0)
        if(compose.onAllNodesWithTag("selection-erase-inside").fetchSemanticsNodes().isEmpty())compose.onNodeWithTag("selection-more").performClick()
        compose.onNodeWithTag("selection-erase-inside").performScrollTo().assertIsEnabled().performClick();saved(1)
        val result=runBlocking{app.inkRepository.read(n.id)};val cut=result.cuts.single().selection.cut
        assertEquals(InkCutShape.RECTANGLE,cut.shape);assertEquals(s.samples,result.strokes.single().stroke.samples)
        compose.runOnIdle{
            val v=InkCanvasView(compose.activity);v.configure(false,PaperStyle.BLANK,null);v.layout(0,0,800,1000);v.showStrokes(InkSession(result).visibleDraft())
            val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888);try{v.draw(Canvas(image));val d=v.resources.displayMetrics.density.toDouble();val p=v.snapshotViewport().worldToScreen(400.0,600.0,800.0,1000.0,d);val c=image.getPixel(p.x.toInt(),p.y.toInt());assertTrue(Color.red(c)>220&&Color.green(c)>220&&Color.blue(c)>220)}finally{image.recycle()}
        }
        compose.onNodeWithTag("ink-undo").performClick();saved(1);compose.waitUntil(10_000){runBlocking{app.inkRepository.read(n.id).revision}==3L};assertTrue(InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().cuts.isEmpty())
    }
    @Test fun beautifyPreviewCancelThenApplyIsReversible(){
        val(n,s)=seed();select();compose.onNodeWithTag("selection-more").performClick();compose.onNodeWithTag("selection-beautify").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("beautify-dialog").assertIsDisplayed();awaitBeauty();shot("beautify-preview.png")
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick();assertEquals(1L,runBlocking{app.inkRepository.read(n.id).revision})
        compose.onNodeWithTag("selection-more").performClick();compose.onNodeWithTag("selection-beautify").performScrollTo().performClick();awaitBeauty()
        compose.onNodeWithTag("beautify-strength").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.2f)}
        compose.onNodeWithTag("beautify-strength").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.8f)}
        awaitBeauty();compose.onNodeWithTag("apply-beautify").performClick();saved(1)
        compose.waitUntil(10_000){runBlocking{app.inkRepository.read(n.id).revision}==2L}
        assertEquals(InkSelectionEdit.beautify(listOf(s),.8f).single().samples,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().samples)
        assertEquals(2,runBlocking{app.inkRepository.read(n.id).strokes.size});compose.onNodeWithTag("ink-undo").performClick();saved(1)
        assertEquals(s.samples,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().samples)
    }
    @Test fun selectionDuplicateThenDeleteDoesNotChangeOriginal(){
        val(n,s)=seed();select();shot("selection-actions.png");compose.onNodeWithTag("selection-copy").performScrollTo().performClick();saved(2)
        var hit=Offset.Zero;compose.runOnIdle{val v=nativeCanvas();val p=v.snapshotViewport().worldToScreen(400.0,600.0,v.width.toDouble(),v.height.toDouble(),v.resources.displayMetrics.density.toDouble());hit=Offset(p.x.toFloat(),p.y.toFloat())}
        compose.onNodeWithTag("selection-overlay").performTouchInput{click(hit)}
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("selection-delete").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("selection-delete").performScrollTo().performClick();saved(1)
        assertEquals(s.id,InkSession(runBlocking{app.inkRepository.read(n.id)}).visibleDraft().single().id)
    }
    @Test fun excerptCreatesSharedCardAndReturnsToSource(){
        val(n,s)=seed();select();compose.onNodeWithTag("selection-more").performClick();compose.onNodeWithTag("selection-map").performScrollTo().performClick()
        addCard("拉格朗日中值定理","先核对连续与可导条件")
        val card=runBlocking{app.study.cards(n.id).first()}.single();val snapshot=runBlocking{app.study.source(card.id)}!!
        assertEquals(s.id,InkPageFile.decode(snapshot.snapshot).strokes.single().id)
        assertArrayEquals(InkStrokeCodec.encode(s),InkStrokeCodec.encode(InkPageFile.decode(snapshot.snapshot).strokes.single()))
        assertEquals(card.id,runBlocking{app.study.nodes(n.id).first()}.single().cardId)
        assertEquals(1L,runBlocking{app.inkRepository.read(n.id).revision})
        compose.onNodeWithTag("study-card-${card.id}").performScrollTo().performClick()
        // Source snapshots load on Dispatchers.IO after the details dialog opens.
        compose.waitUntil(10000){compose.onAllNodesWithTag("card-source-section").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("card-source-section").performScrollTo().performClick()
        try{
            compose.waitUntil(10_000){compose.onAllNodesWithTag("study-card-details").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(10_000){compose.onAllNodesWithTag("study-open-source").fetchSemanticsNodes().isNotEmpty()}
        }catch(error:Throwable){
            runCatching{shot("excerpt-source-failure.png")}
            compose.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.forEach{i->runCatching{println(compose.onAllNodes(isRoot(),useUnmergedTree=true)[i].printToString())}}
            throw error
        }
        compose.onNodeWithTag("study-open-source").assertIsDisplayed().performTouchInput{click()};saved(1)
        compose.onAllNodesWithTag("study-card-details").assertCountEquals(0);assertEquals(n.id,snapshot.pageId)
    }
    @Test fun outlineAndMapReuseSingleEditableCard(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=runCatching{instrumentation.targetContext.getExternalFilesDir(null)}.getOrNull()
        val mainThread=android.os.Looper.getMainLooper().thread
        val phase=java.util.concurrent.atomic.AtomicReference("seed")
        val historyLock=Any();var history="";var lastObservation="";var beforeSaveUi="Not reached"
        var result="FAIL"
        var snapshot:()->String={"Study models not captured"}
        val observations=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        fun record(value:String){synchronized(historyLock){if(value!=lastObservation){
            lastObservation=value;history=(history+"\n${android.os.SystemClock.uptimeMillis()} phase=${phase.get()} thread=${Thread.currentThread().name} $value").takeLast(7_000)
        }}}
        fun observe(){runCatching{record(snapshot())}.onFailure{record("State snapshot unavailable: ${it.javaClass.simpleName}")}}
        fun stage(name:String){phase.set(name);record("begin $name");observe()}
        try{
            val(n,originalInk)=seed()
            stage("open-study")
            compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick();compose.onNodeWithTag("study-open").performScrollTo().performClick()
            // Cache mounted models before the risky operations; no new model or production callback is installed.
            runCatching{compose.runOnIdle{
                val store=compose.activity.viewModelStore
                val writer=checkNotNull(store.get("study-${n.id}") as? StudyViewModel)
                val mapWriter=store.get("study-map-writer-${n.id}") as? KnowledgeViewModel
                val portalWriter=store.get("map-portal-writer-${n.id}") as? KnowledgeViewModel
                val author=store.get("map-authoring-${n.id}-main") as? PageAuthoringViewModel
                val lock=store.get("read-lock-${n.id}") as? BookReadLockViewModel
                val pending=StudyViewModel::class.java.getDeclaredField("pending").apply{isAccessible=true}
                val graphRead=StudyViewModel::class.java.getDeclaredField("observation").apply{isAccessible=true}
                val owners=StudyViewModel::class.java.getDeclaredField("visibleOwners").apply{isAccessible=true}
                val writerJob=writer.viewModelScope.coroutineContext[Job]
                fun job(value:Job?)=value?.let{"${it.javaClass.simpleName}@${System.identityHashCode(it)} active=${it.isActive} completed=${it.isCompleted} cancelled=${it.isCancelled}"}
                fun knowledge(value:KnowledgeViewModel?):String{
                    val ui=value?.ui?.value
                    return "loading=${ui?.loading},readFailed=${ui?.readFailed},busy=${ui?.busy},unknown=${ui?.unknown},pending=${value?.pendingOperationId},completed=${ui?.completed},completedOperation=${ui?.completedOperation},rejected=${ui?.rejectedOperation},message=${ui?.message}"
                }
                snapshot={
                    val ui=writer.ui.value;val request=pending.get(writer) as? StudyCommand;val editor=writer.editorState.value;val a=author?.ui?.value
                    "study[map=${writer.mapId.value},tab=${writer.lastTab},loading=${ui.loading},readFailed=${ui.readFailed},busy=${ui.busy},unknown=${ui.unknown},completed=${ui.completed},message=${ui.message}] "+
                        "pending[id=${request?.id},action=${request?.action},revision=${request?.expectedRevision}] editor[exists=${editor!=null},card=${editor?.card?.id},revision=${editor?.card?.revision}] "+
                        "cards=${ui.cards.map{Triple(it.id,it.revision,it.title)}} nodes=${ui.nodes.map{it.id to it.cardId}} graph=${ui.graph?.graphFingerprint} "+
                        "author[loading=${a?.loading},busy=${a?.busy},pending=${a?.pending}] readOnly=${lock?.readOnly?.value} hasDraft=${lock?.hasDraft?.value} "+
                        "mapWriter[${knowledge(mapWriter)}] portalWriter[${knowledge(portalWriter)}] "+
                        "observationOwners=${owners.get(writer)} graphRead=${job(graphRead.get(writer) as? Job)} scope=${job(writerJob)} dispatcher=${writer.viewModelScope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]} children=${writerJob?.children?.take(6)?.map(::job)?.toList()}"
                }
                // Observe receipt-returned UI signals in memory; a missed/conflated emission is not proof of no receipt.
                observations.launch(start=CoroutineStart.UNDISPATCHED){writer.ui.collect{value->
                    record("StudyUi emission busy=${value.busy} unknown=${value.unknown} completed=${value.completed} message=${value.message}");observe()
                }}
            }}.onFailure{record("Model diagnostics unavailable: ${it.javaClass.simpleName}")}
            stage("create-shared-card")
            compose.revealAction("study-add-card");compose.onNodeWithTag("study-add-card").performClick();addCard("条件概率","按定义推导")
            val card=runBlocking{app.study.cards(n.id).first()}.single()
            stage("reuse-card")
            compose.onNodeWithTag("study-card-${card.id}").performClick();compose.onNodeWithTag("card-management-actions").performClick();compose.onNodeWithTag("study-reuse-card").performScrollTo().performClick()
            compose.waitUntil(10_000){observe();runBlocking{app.study.nodes(n.id).first().size}==2}
            stage("open-outline-editor")
            compose.revealAction("study-tab-1");compose.onNodeWithTag("study-tab-1").performClick();val nodes=runBlocking{app.study.nodes(n.id).first()}
            compose.onNodeWithTag("outline-node-${nodes.first().id}").performScrollTo().performClick();compose.onNodeWithTag("study-edit-card").performScrollTo().performClick()
            stage("replace-title")
            val title=compose.onNodeWithTag("study-card-title")
            title.performTextReplacement("概率公式整理")
            beforeSaveUi=runCatching{
                "TITLE ${title.printToString().take(900)}\nSAVE ${compose.onNodeWithTag("study-save-card").printToString().take(1_100)}"
            }.getOrElse{"Save controls unavailable: ${it.javaClass.simpleName}"}
            observe()
            title.assertTextContains("概率公式整理",substring=false)
            stage("save-once")
            compose.onNodeWithTag("study-save-card").assertIsDisplayed().assertIsEnabled().performClick()
            stage("await-editor-close")
            compose.waitUntil(10_000){observe();compose.onAllNodesWithTag("study-card-editor").fetchSemanticsNodes().isEmpty()}
            stage("verify-shared-revision")
            assertEquals("概率公式整理",runBlocking{app.study.cards(n.id).first().single().title});assertEquals(2,runBlocking{app.study.nodes(n.id).first().count{it.cardId==card.id}})
            val originalPage=runBlocking{app.inkRepository.read(n.id)}
            assertEquals(1L,originalPage.revision);assertArrayEquals(InkStrokeCodec.encode(originalInk),InkStrokeCodec.encode(originalPage.strokes.single().stroke))
            stage("open-map")
            shot("study-outline.png");compose.revealAction("study-tab-2");compose.onNodeWithTag("study-tab-2").performClick();compose.onNodeWithTag("study-map").assertIsDisplayed()
            compose.onNodeWithTag("study-close").assertIsDisplayed();shot("study-mindmap.png")
            result="PASS"
        }catch(error:Throwable){
            // The original failure may be blocked Main/IO: no Compose idle, fresh semantics, or Room queries here.
            runCatching{
                observe()
                val threads=runCatching{
                    val stacks=Thread.getAllStackTraces()
                    val relevant=(listOf(mainThread)+stacks.keys.filter{thread->thread!==mainThread&&stacks[thread].orEmpty().any{it.className.startsWith("org.inkweft.")||it.className.startsWith("androidx.room.")}}.take(3)).distinct()
                    relevant.joinToString("\n"){thread->"thread=${thread.name} state=${thread.state}\n"+stacks[thread].orEmpty().take(10).joinToString("\n")}.take(3_000)
                }.getOrElse{"Thread snapshot unavailable: ${it.javaClass.simpleName}"}
                val report=("SHARED_CARD_FAILURE phase=${phase.get()} error=${error.javaClass.simpleName}\n$beforeSaveUi\n"+synchronized(historyLock){history}+"\n$threads").take(13_000)
                runCatching{println(report);File(checkNotNull(directory),"selection-shared-card-failure.txt").writeText(report)}
                runCatching{
                    val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try{File(checkNotNull(directory),"selection-shared-card-failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                }
                runCatching{error.addSuppressed(AssertionError(report))}
            }
            throw error
        }finally{
            observations.cancel()
            // Export only the bounded history already collected; no new UI/Room observation or diagnostic screenshot.
            runCatching{
                val report=("SHARED_CARD_PHASE result=$result phase=${phase.get()}\n"+synchronized(historyLock){history}).take(8_000)
                File(checkNotNull(directory),"selection-shared-card-phase.txt").writeText(report)
            }
        }
    }
    @Test fun childThemeAndRemovingLeafKeepsCard(){
        val(n,_)=seed();compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick();compose.onNodeWithTag("study-open").performScrollTo().performClick();compose.revealAction("study-add-card");compose.onNodeWithTag("study-add-card").performClick();addCard("总论","根节点")
        val root=runBlocking{app.study.nodes(n.id).first()}.single();compose.revealAction("study-tab-1");compose.onNodeWithTag("study-tab-1").performClick();compose.onNodeWithTag("outline-node-${root.id}").performScrollTo().performClick();compose.onNodeWithTag("card-node-actions").performScrollTo().performClick();compose.onNodeWithTag("study-add-child").performScrollTo().performClick();addCard("必要条件","检查假设")
        val child=runBlocking{app.study.nodes(n.id).first()}.single{it.parentId==root.id}
        compose.onNodeWithText("必要条件",useUnmergedTree=true).performScrollTo().performClick()
        try{compose.onNodeWithTag("study-card-details").assertExists()}catch(e:Throwable){shot("child-node-failure.png");throw e}
        compose.onNodeWithTag("card-node-actions").performScrollTo().performClick();compose.onNodeWithTag("study-remove-node").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.study.nodes(n.id).first().single{it.id==child.id}.removed}}
        assertEquals(2,runBlocking{app.study.cards(n.id).first().size})
    }
    @Test fun outlineFoldFocusAndQuickAddShareOneGraph(){
        val(n,_)=seed()
        val root=id();val child=id();val leaf=id();val other=id()
        runBlocking{
            for((node,parent,title) in listOf(Triple(root,null,"总论"),Triple(child,root,"条件"),Triple(leaf,child,"例子"),Triple(other,null,"另一主题"))){
                app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=id(),nodeId=node,parentId=parent,title=title))
            }
        }
        compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick();compose.onNodeWithTag("study-open").performScrollTo().performClick();compose.revealAction("study-tab-1");compose.onNodeWithTag("study-tab-1").performClick()
        compose.onNodeWithTag("outline-fold-$root").performScrollTo().performClick()
        compose.onNodeWithTag("outline-node-$child").assertDoesNotExist()
        compose.revealAction("outline-focus-$root")
        compose.onNodeWithTag("outline-focus-$root").performScrollTo().performClick()
        compose.onNodeWithTag("outline-node-$other").assertDoesNotExist()
        compose.revealAction("study-tab-2");compose.onNodeWithTag("study-tab-2").performClick();compose.onNodeWithText("1 / 4 个主题").assertExists();shot("study-folded-map.png")
        compose.revealAction("study-expand-all");compose.onNodeWithTag("study-expand-all").performScrollTo().performClick()
        compose.onNodeWithText("3 / 4 个主题").assertExists()
        compose.revealAction("study-tab-1");compose.onNodeWithTag("study-tab-1").performClick()
        compose.onNodeWithTag("study-list").performScrollToIndex(1)
        compose.revealAction("outline-focus-$child")
        compose.onNodeWithTag("outline-focus-$child").performScrollTo()
        compose.onNodeWithTag("outline-focus-$child").assertIsDisplayed().performClick()
        compose.onNodeWithTag("outline-node-$root").assertDoesNotExist()
        compose.onNodeWithTag("study-breadcrumb-$root").assertExists()
        compose.revealAction("outline-child-$child")
        compose.onNodeWithTag("outline-child-$child").performScrollTo().performClick();compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertIsDisplayed();compose.onNodeWithTag("node-title-input").performTextInput("新子主题");compose.onNodeWithTag("node-title-save").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        val newChild=runBlocking{app.study.cards(n.id).first()}.single{it.title=="新子主题"}
        assertEquals(child,runBlocking{app.study.nodes(n.id).first()}.single{it.cardId==newChild.id}.parentId)
        compose.revealAction("study-focus-all");compose.onNodeWithTag("study-focus-all").performScrollTo().performClick()
        compose.onNodeWithTag("study-list").performScrollToIndex(1)
        compose.revealAction("outline-sibling-$child")
        compose.onNodeWithTag("outline-sibling-$child").performScrollTo().assertIsDisplayed().performClick();compose.onNodeWithTag("node-title-editor",useUnmergedTree=true).assertIsDisplayed();compose.onNodeWithTag("node-title-input").performTextInput("同级主题");compose.onNodeWithTag("node-title-save").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("node-title-editor",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        val sibling=runBlocking{app.study.cards(n.id).first()}.single{it.title=="同级主题"}
        val nodes=runBlocking{app.study.nodes(n.id).first()}
        assertEquals(root,nodes.single{it.cardId==sibling.id}.parentId)
        assertEquals(6,nodes.size);assertEquals(1L,nodes.single{it.id==root}.revision)
        shot("study-branch-navigation.png")
    }
    @Test fun cardSearchFindsBodyAndClearRestoresCards(){
        val(n,_)=seed();compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick();compose.onNodeWithTag("study-open").performScrollTo().performClick()
        compose.revealAction("study-add-card");compose.onNodeWithTag("study-add-card").performClick();addCard("概率","先验条件")
        compose.revealAction("study-add-card");compose.onNodeWithTag("study-add-card").performClick();addCard("微积分","连续可导")
        val cards=runBlocking{app.study.cards(n.id).first()}
        val probability=cards.single{it.title=="概率"};val calculus=cards.single{it.title=="微积分"}
        compose.onNodeWithTag("study-search").performTextInput("先验")
        compose.onNodeWithTag("study-card-${probability.id}").assertExists()
        compose.onNodeWithTag("study-card-${calculus.id}").assertDoesNotExist()
        compose.onNodeWithTag("study-search").performTextReplacement("未命中")
        compose.onNodeWithText("没有匹配的摘要卡").assertExists()
        compose.onNodeWithTag("study-search").performTextClearance()
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("study-panel"))).performScrollToNode(hasTestTag("study-card-${calculus.id}"))
        compose.onNodeWithTag("study-card-${calculus.id}").assertExists()
        assertEquals(2,runBlocking{app.study.cards(n.id).first().size})
    }
    @Test fun mindMapPaintStaysInsideItsViewport(){
        compose.runOnIdle{
            val v=MindMapView(compose.activity);v.layout(0,0,300,200)
            val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
            try{
                bitmap.eraseColor(Color.MAGENTA);val canvas=Canvas(bitmap);canvas.translate(50f,50f);v.draw(canvas)
                assertEquals("Map must not cover controls above it",Color.MAGENTA,bitmap.getPixel(100,25))
                assertEquals("Map must not cover controls below it",Color.MAGENTA,bitmap.getPixel(100,275))
                assertEquals(Color.MAGENTA,bitmap.getPixel(25,100))
                assertEquals(Color.MAGENTA,bitmap.getPixel(375,100))
                assertNotEquals(Color.MAGENTA,bitmap.getPixel(100,100))
            }finally{bitmap.recycle()}
        }
    }
    @Test fun previewFailureIsExplicitAndCanRetry()=runBlocking{
        var fail=true
        val preview=BeautifyPreview(emptyList()){_,_->if(fail)throw IllegalStateException("synthetic failure")else emptyList()}
        preview.compute(.5f);assertTrue(preview.state.value.error);assertNull(preview.state.value.strokes)
        fail=false;preview.compute(.5f);assertFalse(preview.state.value.error);assertNotNull(preview.state.value.strokes)
    }
    @Test fun latePreviewCannotReplaceNewerStrength()=runBlocking{
        val release=CompletableDeferred<Unit>()
        val preview=BeautifyPreview(emptyList()){_,strength->if(strength==.2f)release.await();emptyList()}
        val old=launch{preview.compute(.2f)};yield()
        preview.compute(.8f);release.complete(Unit);old.join()
        assertEquals(.8f,preview.state.value.strength);assertNotNull(preview.state.value.strokes)
    }
}
