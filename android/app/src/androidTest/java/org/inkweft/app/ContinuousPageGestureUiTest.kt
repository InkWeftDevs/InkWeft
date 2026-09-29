// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real repositories and native canvases; callback counts isolate the pull gesture from page insertion. */
class ContinuousPageGestureUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private var finger by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var writing by mutableStateOf(false)
    private var blocked by mutableStateOf(true)
    private var parentBusy by mutableStateOf(false)
    private var selected by mutableStateOf("")
    private var appended=0
    private var zoomed=0
    private var started=0
    private lateinit var pages:List<NotebookPageRow>
    private fun canvas(index:Int):InkCanvasView=checkNotNull(compose.activity.window.decorView.findViewWithTag("ink-page-${pages[index].id}"))
    private fun open(pageCount:Int=3){
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        pages=runBlocking{
            val note=app.workspaceRepository.create("连续触控定向验证",false,PaperStyle.BLANK)
            var previous=note.id
            repeat(pageCount-1){previous=app.pages.addAfter(note.id,previous,UUID.randomUUID().toString()).id}
            app.pages.activePages(note.id)
        }
        selected=pages.first().id
        compose.activity.setContent{InkTheme.Content{
            ContinuousPages(pages,selected,ContinuousTools(InkPen.PENCIL,0xff222222.toInt(),3f,false,false,false,28f,enabled,fingerWrites=finger),
                writing,{selected=it},{writing=it;if(it)started++},{blocked=it},{},{},
                onAppendPage=if(enabled&&!blocked&&!writing&&!parentBusy)({appended++})else null,onZoom={zoomed++})
        }}
        ready()
    }
    private fun ready(){compose.waitUntil(15000){!writing&&!blocked};compose.waitForIdle()}
    private fun count()=runBlocking{pages.sumOf{app.inkRepository.read(it.id).strokes.size}}
    private fun tail(){
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(pages.lastIndex)
        compose.onNodeWithTag("continuous-pages").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100000f)}
        ready()
    }
    private fun twoFingerPan(distance:Float,yFraction:Float=.8f){
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val a=Offset(width*.35f,height*yFraction);val b=Offset(width*.65f,height*yFraction)
            down(0,a);down(1,b)
            repeat(10){i->
                val dy=distance*(i+1)/10
                updatePointerTo(0,a-Offset(0f,dy));updatePointerTo(1,b-Offset(0f,dy));move(30)
            }
            up(1);up(0)
        }
        ready()
    }
    private fun holdLongFinger(){
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val y=height*.7f;val x=width*.35f
            down(0,Offset(x,y));repeat(140){i->moveTo(0,Offset(x+(i%20),y),16)}
        }
        compose.waitUntil(10000){runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isNotEmpty()}}
    }
    private fun cancelHeldFingerByEndPull(){
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val a=checkNotNull(currentPosition(0));val b=a+Offset(width*.3f,0f)
            down(1,b)
            repeat(10){i->val delta=Offset(0f,height*.5f*(i+1)/10);updatePointerTo(0,a-delta);updatePointerTo(1,b-delta);move(30)}
            up(1);up(0)
        }
    }

    @Test fun shortTwoFingerNavigationDoesNotCreateCancellationFiles(){
        open()
        val groups=File(app.filesDir,"ink-checkpoints/${ContentTransfer.hash("inkweft-a0.db".toByteArray())}/groups")
        fun markers()=groups.listFiles().orEmpty().filter{it.name.endsWith(".cancelled")||it.name.endsWith(".cancelled.bak")}.map{it.name}.toSet()
        val before=markers()
        compose.runOnIdle{finger=true}
        compose.waitUntil(10000){var available=false;compose.runOnIdle{available=canvas(0).inputReady};available}
        repeat(3){twoFingerPan(40f,.2f)}
        lateinit var recovery:ContinuousGroupSession
        compose.runOnIdle{
            recovery=ViewModelProvider(compose.activity)["continuous-recovery-${pages[0].notebookId}",ContinuousGroupSession::class.java]
            assertEquals(3,started)
        }
        runBlocking{withTimeout(10000){recovery.awaitCancellationSettled()}}
        assertEquals(before,markers());assertEquals(0,count())
        assertTrue(runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isEmpty()})
    }

    @Test fun longFingerCancellationBlocksAppendUntilItsJournalSettles(){
        open(2);tail()
        compose.runOnIdle{finger=true};compose.waitForIdle()
        compose.waitUntil(10000){var available=false;compose.runOnIdle{available=canvas(1).inputReady};available}
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        lateinit var recovery:ContinuousGroupSession
        compose.runOnIdle{recovery=ViewModelProvider(compose.activity)["continuous-recovery-${pages[0].notebookId}",ContinuousGroupSession::class.java]}
        app.inkRepository.groupFaultForTest={point->if(point=="after-cancel"){entered.countDown();check(release.await(15,TimeUnit.SECONDS)){"Cancellation gate timed out"}}}
        try{
            holdLongFinger();cancelHeldFingerByEndPull()
            check(entered.await(5,TimeUnit.SECONDS)){"Durable cancellation hook not reached"}
            compose.waitUntil(5000){blocked}
            compose.runOnIdle{assertTrue(recovery.busy.value);assertEquals(0,appended);assertFalse(writing)}
        }finally{
            release.countDown()
            runBlocking{withTimeout(10000){recovery.awaitCancellationSettled()}}
            app.inkRepository.groupFaultForTest={}
        }
        ready();assertEquals(0,appended);assertEquals(0,count())
        assertTrue(runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isEmpty()})
        twoFingerPan(compose.onNodeWithTag("continuous-pages").fetchSemanticsNode().boundsInRoot.height*.5f)
        compose.waitUntil(10000){appended==1};assertEquals(0,count())
    }

    @Test fun failedLongFingerCancellationRetriesCancelWithoutRevivingInk(){
        open(2);tail()
        compose.runOnIdle{finger=true};compose.waitForIdle()
        compose.waitUntil(10000){var available=false;compose.runOnIdle{available=canvas(1).inputReady};available}
        lateinit var recovery:ContinuousGroupSession
        compose.runOnIdle{recovery=ViewModelProvider(compose.activity)["continuous-recovery-${pages[0].notebookId}",ContinuousGroupSession::class.java]}
        holdLongFinger()
        app.inkRepository.groupFaultForTest={point->if(point=="journal-before-write")throw java.io.IOException("Synthetic cancellation failure")}
        try{
            cancelHeldFingerByEndPull()
            runBlocking{withTimeout(10000){recovery.awaitCancellationSettled()}}
            compose.waitUntil(5000){blocked&&recovery.problem.value!=null}
            compose.runOnIdle{assertFalse(recovery.busy.value);assertEquals(0,appended)}
            assertEquals(0,count())
            assertTrue(runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isNotEmpty()})
        }finally{app.inkRepository.groupFaultForTest={}}
        compose.runOnIdle{recovery.retry()};ready()
        assertNull(recovery.problem.value);assertEquals(0,count());assertEquals(0,appended)
        assertTrue(runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isEmpty()})
    }

    @Test fun fingerModeWritesAndTwoFingerTakeoverPreservesOnlyCommittedInk(){
        open()
        var firstY=0
        compose.runOnIdle{firstY=IntArray(2).also{canvas(0).getLocationOnScreen(it)}[1]}
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.7f),Offset(centerX,height*.3f),600)}
        ready();assertEquals(0,count());assertEquals(0,appended)
        compose.runOnIdle{assertTrue(IntArray(2).also{canvas(0).getLocationOnScreen(it)}[1]<firstY)}
        compose.onNodeWithTag("continuous-pages").performScrollToIndex(0)
        compose.runOnIdle{finger=true};compose.waitForIdle()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(width*.35f,height*.35f),Offset(width*.55f,height*.4f),400)}
        compose.waitUntil(15000){count()==1&&!blocked&&!writing}
        val original=runBlocking{app.inkRepository.read(pages[0].id).strokes.single().stroke}.let(InkStrokeCodec::encode)
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val a=Offset(width*.3f,height*.65f);val b=a+Offset(width*.3f,0f)
            down(0,a);moveTo(0,a+Offset(20f,0f),30);down(1,b)
            updatePointerTo(0,a+Offset(20f,-100f));updatePointerTo(1,b+Offset(0f,-100f));move(50)
            up(1);up(0)
        }
        ready();assertEquals(1,count())
        compose.waitUntil(10000){runBlocking{app.inkRepository.pendingGroups(pages[0].notebookId).isEmpty()}}
        var oldWidth=0
        compose.runOnIdle{oldWidth=canvas(0).width}
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val y=height*.5f;val x=width*.5f
            down(0,Offset(x-width*.12f,y));down(1,Offset(x+width*.12f,y))
            repeat(8){i->val d=width*(.12f+(i+1)*.008f);updatePointerTo(0,Offset(x-d,y));updatePointerTo(1,Offset(x+d,y));move(30)}
            up(1);up(0)
        }
        ready()
        compose.runOnIdle{assertTrue(canvas(0).width>oldWidth);assertTrue(zoomed>0)}
        assertEquals(1,count())
        assertArrayEquals(original,runBlocking{InkStrokeCodec.encode(app.inkRepository.read(pages[0].id).strokes.single().stroke)})
    }

    @Test fun endPullAppendsOnceAndIgnoresWritingDisabledBusyAndCancelledGestures(){
        open(2);tail();assertEquals(0,appended) // Programmatic scroll cannot append.
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.8f),Offset(centerX,height*.15f),650)}
        compose.waitUntil(10000){appended==1};ready();assertEquals(1,appended)
        compose.runOnIdle{finger=true};compose.waitForIdle()
        compose.onNodeWithTag("continuous-pages").performTouchInput{swipe(Offset(centerX,height*.8f),Offset(centerX,height*.2f),500)}
        compose.waitUntil(15000){count()==1&&!writing&&!blocked};assertEquals(1,appended)
        val distance=compose.onNodeWithTag("continuous-pages").fetchSemanticsNode().boundsInRoot.height*.6f
        twoFingerPan(distance);compose.waitUntil(10000){appended==2};assertEquals(1,count())
        compose.runOnIdle{enabled=false};compose.waitForIdle();twoFingerPan(distance);assertEquals(2,appended)
        compose.runOnIdle{enabled=true;parentBusy=true};compose.waitForIdle();twoFingerPan(distance);assertEquals(2,appended)
        compose.runOnIdle{parentBusy=false};compose.waitForIdle()
        compose.onNodeWithTag("continuous-pages").performTouchInput{
            val a=Offset(width*.35f,height*.8f);val b=Offset(width*.65f,height*.8f)
            down(0,a);down(1,b);updatePointerTo(0,a-Offset(0f,distance));updatePointerTo(1,b-Offset(0f,distance));move(200);cancel()
        }
        ready();assertEquals(2,appended);assertEquals(1,count())
    }
}
