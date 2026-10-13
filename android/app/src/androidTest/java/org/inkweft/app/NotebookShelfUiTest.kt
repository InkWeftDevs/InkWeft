// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** All content is synthetic. Tests do not claim the user's flicker is reproduced. */
class NotebookShelfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(noteId:String?=null){compose.waitUntil(10_000){runCatching{
        compose.onNodeWithTag("new-note").assertIsEnabled()
        // Header readiness does not imply that the asynchronous shelf page is laid out.
        if(noteId!=null)compose.onNodeWithTag("note-menu-$noteId").assertIsDisplayed()
    }.isSuccess}}
    private fun noKeyboard(){compose.activityRule.scenario.onActivity{a->a.currentFocus?.clearFocus();WindowCompat.getInsetsController(a.window,a.window.decorView).hide(WindowInsetsCompat.Type.ime())};compose.waitUntil(10_000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false};compose.waitForIdle()}
    private fun saved(n:Int){compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("ink-surface").assertSavedInkCount(n)}.isSuccess}}
    private fun create():Note{
        val started=System.nanoTime();val title="封面测试-"+UUID.randomUUID().toString().take(6)
        var stage="shelf-ready"
        try{
            ready()
            stage="open-create";compose.onNodeWithTag("new-note").performClick()
            stage="choose-page";compose.onNodeWithTag("create-page").performClick()
            stage="enter-title";compose.onNodeWithTag("new-title").performTextInput(title)
            stage="choose-cover";compose.onNodeWithTag("cover-choice-forest").performScrollTo().performClick()
            stage="submit-create";compose.onNodeWithTag("create-note").performClick()
            stage="single-page-editor";compose.singlePageEditor()
            stage="empty-ink-saved";saved(0)
            stage="keyboard-hidden";noKeyboard()
            stage="read-created-note";return runBlocking{app.repository.observeNotes().first()}.single{it.title==title}
        }catch(failure:Throwable){
            runCatching{diagnoseCreateFailure(stage,title,(System.nanoTime()-started)/1_000_000)}
            throw failure
        }
    }
    /** Read only already-owned state after failure; do not wait for Compose idleness again. */
    private fun diagnoseCreateFailure(stage:String,title:String,elapsedMs:Long){
        fun emit(value:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","SHELF_CREATE_DIAGNOSTIC $value\n")
        })
        val main=android.os.Looper.getMainLooper().thread
        emit("stage=$stage elapsedMs=$elapsedMs mainState=${main.state} mainStack=${main.stackTrace.take(16).joinToString(" | ")}")
        val snapshot=AtomicReference("main-state-unavailable");val sampled=CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post{
            try{
                val store=compose.activity.viewModelStore
                val owned=store.keys().mapNotNull{store.get(it)}
                val notebook=owned.filterIsInstance<NotebookViewModel>().firstOrNull()?.ui?.value
                val workspace=owned.filterIsInstance<WorkspaceViewModel>().firstOrNull()
                val expectedId=workspace?.pendingCreate?.value?.firstOrNull()
                    ?:notebook?.notes?.firstOrNull{it.title==title}?.id
                    ?:notebook?.current?.base?.takeIf{it.title==title}?.id
                val pages=expectedId?.let{(store.get("book-$it") as? BookPagesViewModel)?.ui?.value}
                val ink=expectedId?.let{(store.get("ink-$it") as? InkViewModel)?.ui?.value}
                val objects=expectedId?.let{(store.get("objects-$it") as? PageObjectViewModel)?.ui?.value}
                val author=expectedId?.let{(store.get("authoring-$it") as? PageAuthoringViewModel)?.ui?.value}
                val recovery=expectedId?.let{store.get("continuous-recovery-$it") as? ContinuousGroupSession}
                val writer=expectedId?.let{store.get("continuous-writer-$it") as? ContinuousInkWriter}
                val readLock=expectedId?.let{store.get("read-lock-$it") as? BookReadLockViewModel}
                val study=expectedId?.let{(store.get("study-$it") as? StudyViewModel)?.ui?.value}
                val excerpts=expectedId?.let{(store.get("excerpt-$it") as? StudyViewModel)?.ui?.value}
                val canvases=mutableListOf<String>();val views=java.util.ArrayDeque<android.view.View>()
                views.add(compose.activity.window.decorView);var visited=0
                while(views.isNotEmpty()&&visited++<512){val view=views.removeFirst()
                    if(view is InkCanvasView)canvases+="fixture=${expectedId!=null&&view.displayedPageId==expectedId},preview=${view.preview},embedded=${view.embeddedPage},shown=${view.isShown},attached=${view.isAttachedToWindow},size=${view.width}x${view.height},documentReady=${view.documentContentReady},frameReady=${view.viewportFrameReady}"
                    if(view is android.view.ViewGroup)repeat(view.childCount){views.add(view.getChildAt(it))}
                }
                snapshot.set("notebookPresent=${notebook!=null} noteCount=${notebook?.notes?.size} loading=${notebook?.loading} readFailed=${notebook?.readFailed} " +
                    "fixtureKnown=${expectedId!=null} currentPresent=${notebook?.current!=null} selectedFixture=${expectedId!=null&&notebook?.selectedId==expectedId} currentFixture=${expectedId!=null&&notebook?.current?.base?.id==expectedId} " +
                    "createPending=${workspace?.pendingCreate?.value!=null} createBusy=${workspace?.busy?.value} workspaceError=${workspace?.error?.value!=null} " +
                    "bookPagesPresent=${pages!=null} pageCount=${pages?.pages?.size} pageLoading=${pages?.loading} pageError=${pages?.error!=null} firstPageSelected=${expectedId!=null&&pages?.selectedId==expectedId} " +
                    "pageBusy=${pages?.busy} pageActionUnknown=${pages?.actionUnknown} pageInsertionUnknown=${pages?.insertionUnknown} " +
                    "inkPresent=${ink!=null} inkLoading=${ink?.loading} inkReadFailed=${ink?.readFailed} inkQueued=${ink?.queued} inkBlocked=${ink?.blocked!=null} inkProcessing=${ink?.processing} inkWindowLoading=${ink?.windowLoading} " +
                    "objectsPresent=${objects!=null} objectsLoading=${objects?.loading} objectsBusy=${objects?.busy} objectsPending=${objects?.pending} " +
                    "authorPresent=${author!=null} authorReady=${author?.ready} authorLoading=${author?.loading} authorProjectionLoading=${author?.projectionLoading} authorBusy=${author?.busy} authorPending=${author?.pending} " +
                    "continuousWriterPresent=${writer!=null} continuousWriterBlocked=${writer?.blocked?.value} recoveryPresent=${recovery!=null} recoveryBusy=${recovery?.busy?.value} recoveryProblem=${recovery?.problem?.value!=null} hasAuthorDraft=${readLock?.hasDraft?.value} " +
                    "studyBusy=${study?.busy} studyUnknown=${study?.unknown} excerptBusy=${excerpts?.busy} excerptUnknown=${excerpts?.unknown} canvasCount=${canvases.size} canvases=${canvases.take(8)} " +
                    "navigationReady=${app.navigationReady.value} lifecycle=${compose.activity.lifecycle.currentState}")
            }catch(error:Throwable){snapshot.set("stateFailure=${error.javaClass.simpleName}")}finally{sampled.countDown()}
        }
        emit("sampled=${sampled.await(1,TimeUnit.SECONDS)} ${snapshot.get()}")
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun rename(value:String){compose.onNodeWithTag("rename-title").performTextReplacement(value);compose.onNodeWithTag("confirm-rename").performClick();compose.waitUntil(10_000){compose.onAllNodesWithTag("rename-dialog").fetchSemanticsNodes().isEmpty()};noKeyboard()}
    @Test fun shelfRenameCancelBlankAndPersistedIdentity(){
        val n=create();assertEquals("forest",runBlocking{app.workspaceRepository.get(n.id).coverKey});compose.onNodeWithTag("back-library").performClick();ready(n.id)
        compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("rename-note-${n.id}").performClick()
        compose.onNodeWithTag("rename-title").performTextReplacement("   ");compose.onNodeWithTag("confirm-rename").assertIsNotEnabled();compose.onNodeWithTag("cancel-rename").performClick();assertEquals(n.title,runBlocking{app.repository.read(n.id)?.title})
        val renamed="高数 · 公式与推导-${n.id.take(8)}"
        compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("rename-note-${n.id}").performClick();rename(renamed)
        assertEquals(renamed,runBlocking{app.repository.read(n.id)?.title});compose.onNodeWithTag("library-search").performTextInput("公式与推导")
        compose.waitUntil(10_000){compose.onAllNodesWithText(renamed).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(renamed).assertExists();compose.onNodeWithTag("library-search").performTextClearance();noKeyboard()
        runBlocking{for((i,c) in listOf(NotebookCover.INK,NotebookCover.SAND,NotebookCover.ROSE,NotebookCover.GRID,NotebookCover.WAVE).withIndex())app.workspaceRepository.create(listOf("英语 · 阅读积累","专业课 · 知识整理","每日复习","数学 · 易错题","灵感与草稿")[i],i==4,PaperStyle.DOTS,c)}
        compose.waitUntil(10_000){compose.onAllNodesWithText("英语 · 阅读积累").fetchSemanticsNodes().isNotEmpty()};shot("shelf-covers-emulator.png")
    }
    @Test fun coverChangesPersistWithoutChangingInkAndCanReturnToContentPreview(){
        val n=create();compose.openEditorAction("quick-finger");compose.onNodeWithTag("ink-surface").performTouchInput{swipe(Offset(width*.2f,height*.3f),Offset(width*.45f,height*.55f),230)};saved(1)
        val before=runBlocking{app.inkRepository.read(n.id).strokes.single().stroke};compose.onNodeWithTag("back-library").performClick();ready(n.id)
        fun pick(){compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("change-cover-${n.id}").performClick()}
        pick();compose.onNodeWithTag("cover-choice-wave").performScrollTo().performClick();noKeyboard();shot("cover-picker-emulator.png");compose.onNodeWithTag("confirm-cover").performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).coverKey}=="wave"};compose.activityRule.scenario.recreate();ready(n.id)
        assertEquals(before.samples,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.samples})
        pick();compose.onNodeWithTag("cover-choice-content").performScrollTo().performClick();compose.onNodeWithTag("confirm-cover").performClick()
        compose.waitUntil(10_000){runBlocking{app.workspaceRepository.get(n.id).coverKey}=="content"};assertEquals(before.id,runBlocking{app.inkRepository.read(n.id).strokes.single().stroke.id})
    }
    @Test fun editorRenameKeepsUnsavedBodyUntilExplicitTextSave(){
        val n=create();compose.revealAction("quick-settings");compose.onNodeWithTag("quick-settings").performClick()
        compose.onNodeWithTag("mode-text").performScrollTo()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("mode-text").assertIsDisplayed().assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("mode-text").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("note-body").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("note-body").performTextInput("未保存的个人理解，不随改名提交")
        compose.onNodeWithTag("rename-from-editor").performClick();rename("新的标题但保留草稿")
        assertEquals("",runBlocking{app.repository.read(n.id)?.text});compose.onNodeWithTag("note-body").assertTextEquals("未保存的个人理解，不随改名提交")
        compose.onNodeWithTag("save-text").performClick();compose.waitUntil(10_000){runBlocking{app.repository.read(n.id)?.text}=="未保存的个人理解，不随改名提交"}
        assertEquals("新的标题但保留草稿",runBlocking{app.repository.read(n.id)?.title})
    }
    @Test fun drawerUsesOneSearchAndClosesWithBackWithAnimationsEnabled(){
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(c:String):String=automation.executeShellCommand(c).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8).trim()}}
        val oldScale=shell("settings get global animator_duration_scale")
        try{
            shell("wm size 1256x1920");shell("settings put global animator_duration_scale 1");Thread.sleep(600);ready()
            repeat(3){compose.onNodeWithTag("open-library-drawer").performClick();compose.waitForIdle();compose.onNodeWithTag("library-drawer").assertIsDisplayed();compose.onAllNodesWithTag("library-search").assertCountEquals(1);compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle();compose.onNodeWithTag("new-note").assertIsDisplayed()}
            compose.onNodeWithTag("open-library-drawer").performClick();compose.waitForIdle();shot("shelf-drawer-emulator.png")
        }finally{if(oldScale.matches(Regex("[0-9.]+")))shell("settings put global animator_duration_scale $oldScale")else shell("settings delete global animator_duration_scale");shell("wm size 1920x1200");Thread.sleep(500)}
    }
}
