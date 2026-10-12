// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real repository-backed previews. Sampling records geometry batches, never source identities. */
class ReadOnlyStreamedPreviewUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private data class Fixture(val page:NotebookPageRow,val source:StudySourceRow,val hidden:UserLayer)
    private suspend fun seed(db:NoteDatabase):Fixture {
        val page=WorkspaceRepository(db).create("合成分批来源",false,PaperStyle.BLANK).id
        val hidden=UserLayer(id(),"隐藏颜色",visible=false,locked=true);val members=mutableListOf<LayerMembership>();var first:InkStroke?=null
        db.withTransaction{
            db.ink().insertPage(InkPageRow(page,1))
            repeat(1002){index->
                val secret=index==1001;val x=if(secret)150f else 80f+(index%25)*32f;val y=if(secret)180f else 80f+(index/25)*28f
                val stroke=InkStroke(id(),InkPen.PEN,if(secret)Color.MAGENTA else Color.BLACK,if(secret)40f else 3f,InkTool.STYLUS,
                    listOf(InkSample(x,y,0),InkSample(x+20f,y+10f,10)))
                if(first==null)first=stroke
                db.ink().insertStroke(InkStrokeRow(stroke.id,page,InkStrokeCodec.encodeStored(stroke),2,true,1))
                members+=LayerMembership(LayerContent(LayerContentKind.INK,stroke.id),if(secret)hidden.id else UserLayers.DEFAULT_ID)
            }
            PageAuthoringRepository(db).storePage(page,page,0,PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),hidden),UserLayers.DEFAULT_ID,members)))
        }
        val stroke=checkNotNull(first)
        val source=StudySourceRow(id(),page,1,0.0,0.0,1000.0,1414.0,stroke.id,InkPageFile("合成摘录","",listOf(stroke)).encode())
        return Fixture(checkNotNull(db.pages().get(page)),source,hidden)
    }
    private fun native():InkCanvasView? {
        fun find(view:View):InkCanvasView? {
            if(view is InkCanvasView&&view.isShown)return view
            if(view is ViewGroup)repeat(view.childCount){find(view.getChildAt(it))?.let{return it}}
            return null
        }
        return WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.isShown}.firstNotNullOfOrNull(::find)
    }
    private fun ready(){compose.waitUntil(30_000){var done=false;compose.runOnIdle{done=native()?.viewportFrameReady==true};done};compose.waitForIdle()}
    private fun pixels(tag:String,predicate:(Int)->Boolean):Int {
        val bitmap=compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try{var count=0;for(y in 0 until bitmap.height)for(x in 0 until bitmap.width)if(predicate(bitmap.getPixel(x,y)))count++;return count}
        finally{bitmap.recycle()}
    }
    private fun magenta(color:Int)=Color.red(color)>150&&Color.green(color)<90&&Color.blue(color)>150
    private fun green(color:Int)=Color.red(color)<90&&Color.green(color)>150&&Color.blue(color)<90
    private fun evidence(kind:String,batches:Int,largest:Int){
        val value=org.json.JSONObject().put("kind",kind).put("sourceStrokes",1002).put("geometryBatches",batches)
            .put("largestGeometryBatch",largest).put("trackedBytes",RenderResources.snapshot().getValue("totalBytes"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","READONLY_STREAM_EVIDENCE $value\n")})
    }
    private fun clear(){compose.runOnIdle{compose.activity.setContent{}};compose.waitForIdle();compose.waitUntil(15_000){RenderResources.inFlightJobs.get()==0}}

    @Test fun reviewDialogStreamsOnlyItsShadowRepositoryAndKeepsHiddenLayerPixelsAbsent(){
        val replica=ShadowReplica.open(app,listOf("local","synthetic","readonly",id()),id(),mapOf(1 to EncryptedBackupFile.random(32)))
        val session=ShadowAuthorSession(app,replica);val previous=app.inkRepository.readObserver
        val appReads=AtomicInteger();val batches=AtomicInteger();val largest=AtomicInteger()
        try{
            val fixture=runBlocking{seed(replica.db)};assertNull(runBlocking{app.repository.read(fixture.page.id)})
            app.inkRepository.readObserver={appReads.incrementAndGet()}
            session.ink.readObserver={batches.incrementAndGet();largest.updateAndGet{old->maxOf(old,it.entries)}}
            compose.setContent{MaterialTheme{ReviewSourceDialog(fixture.source,{},session)}}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("review-source-canvas").fetchSemanticsNodes().isNotEmpty()};ready()
            assertTrue(pixels("review-source-canvas"){Color.red(it)<90&&Color.green(it)<90&&Color.blue(it)<90}>50)
            assertEquals(0,pixels("review-source-canvas",::magenta));assertEquals(0,appReads.get())
            assertTrue(batches.get()>1);assertTrue(largest.get() in 1..256)
            compose.onNodeWithText("全部内容").performClick();ready()
            evidence("shadow-review",batches.get(),largest.get())
        }finally{clear();app.inkRepository.readObserver=previous;session.close();check(replica.directory.deleteRecursively())}
    }

    @Test fun thumbnailKeepsLayersWhileObjectsAndAuthoringUpdateAtTheSameInkRevision(){
        val db=NoteDatabase.open(app);val previous=app.inkRepository.readObserver;val batches=AtomicInteger();val largest=AtomicInteger()
        try{
            val fixture=runBlocking{seed(db)}
            app.inkRepository.readObserver={batches.incrementAndGet();largest.updateAndGet{old->maxOf(old,it.entries)}}
            compose.setContent{MaterialTheme{PageThumb(fixture.page,Modifier.size(300.dp,424.dp).testTag("streamed-thumb"))}}
            ready();assertEquals(0,pixels("streamed-thumb",::magenta));assertTrue(batches.get()>1);assertTrue(largest.get() in 1..256)
            val tape=PageObject(id(),PageObjectKind.TAPE,x=300f,y=500f,width=400f,height=200f,color=Color.GREEN,tapePattern=TapePattern.SOLID)
            runBlocking{app.pageObjects.save(fixture.page.id,0,id(),listOf(tape),layerScope=LayerWriteScope(UserLayers.DEFAULT_ID,0))}
            compose.waitUntil(20_000){pixels("streamed-thumb",::green)>100};ready()
            assertEquals(0,pixels("streamed-thumb",::magenta));assertEquals(1L,runBlocking{app.inkRepository.revision(fixture.page.id)})
            runBlocking{val before=app.authoring.readPage(fixture.page.id);app.authoring.save(AuthoringScope.page(fixture.page.notebookId,fixture.page.id),before,id(),
                before.state.withLayers(before.state.layers.update(fixture.hidden.copy(visible=true))))}
            compose.waitUntil(20_000){pixels("streamed-thumb",::magenta)>10};ready()
            assertTrue(largest.get() in 1..256);evidence("live-thumbnail",batches.get(),largest.get())
        }finally{clear();app.inkRepository.readObserver=previous;db.close()}
    }

    @Test fun recallContextStreamsTheFixedRevisionAndRemovesItsCanvasWhenCluesAreHidden(){
        val db=NoteDatabase.open(app);val previous=app.inkRepository.readObserver;val batches=AtomicInteger();val largest=AtomicInteger()
        try{
            val fixture=runBlocking{seed(db)}
            val frozen=runBlocking{
                val card=fixture.source.cardId
                StudyRepository(db).submit(StudyCommand(id(),fixture.page.id,StudyAction.CREATE,card,id(),title="固定来源",body="固定答案",
                    source=StudySourceDraft(fixture.page.id,1,CanvasBounds(0.0,0.0,1000.0,1414.0),listOf(fixture.source.strokeIds),authoringRevision=0)))
                KnowledgeRepository(db).submit(KnowledgeCommand(id(),fixture.page.id,id(),0,KnowledgeData.Question(card,"合成问题")))
                val repo=BranchReviewRepository(db);val plan=repo.prepareCard(MapRef(fixture.page.id),card,1)
                Triple(plan,repo.load(plan).single(),checkNotNull(StudyRepository(db).source(card)))
            }
            val clues=mutableStateOf(true)
            app.inkRepository.readObserver={batches.incrementAndGet();largest.updateAndGet{old->maxOf(old,it.entries)}}
            compose.setContent{MaterialTheme{RecallContextPanel(frozen.first,frozen.second,clues.value,frozen.third,RecallContextTab.SOURCE,{})}}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("recall-context-source-canvas").fetchSemanticsNodes().isNotEmpty()};ready()
            assertTrue(batches.get()>1);assertTrue(largest.get() in 1..256);assertEquals(0,pixels("recall-context-source-canvas",::magenta))
            compose.runOnIdle{clues.value=false};compose.onNodeWithTag("recall-context-source-placeholder").assertExists()
            compose.onNodeWithTag("recall-context-source-canvas").assertDoesNotExist()
            compose.waitUntil(15_000){RenderResources.inFlightJobs.get()==0}
            runBlocking{db.ink().compareAndSet(fixture.page.id,1,2)}
            compose.runOnIdle{clues.value=true}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("recall-context-source-error").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("recall-context-source-error").assertTextContains("来源已变化",substring=true)
            compose.onNodeWithTag("recall-context-source-canvas").assertDoesNotExist()
            evidence("fixed-recall",batches.get(),largest.get())
        }finally{clear();app.inkRepository.readObserver=previous;db.close()}
    }
    @Test fun contentCoverTextFallbackTracksBoundedInkAndObjectMetadata(){
        val db=NoteDatabase.open(app)
        try{
            val page=runBlocking{val id=WorkspaceRepository(db).create("合成空封面",false,PaperStyle.BLANK).id;checkNotNull(db.pages().get(id))}
            compose.setContent{MaterialTheme{PageThumb(page,Modifier.size(300.dp,424.dp).testTag("empty-content-cover"),fallbackText="合成封面正文")}}
            compose.waitUntil(15_000){compose.onAllNodesWithText("合成封面正文").fetchSemanticsNodes().isNotEmpty()}
            val tape=PageObject(id(),PageObjectKind.TAPE,x=100f,y=100f,width=300f,height=100f,color=Color.GREEN,tapePattern=TapePattern.SOLID)
            runBlocking{app.pageObjects.save(page.id,0,id(),listOf(tape))}
            compose.waitUntil(15_000){compose.onAllNodesWithText("合成封面正文").fetchSemanticsNodes().isEmpty()};ready()
            runBlocking{app.pageObjects.save(page.id,1,id(),emptyList())}
            compose.waitUntil(15_000){compose.onAllNodesWithText("合成封面正文").fetchSemanticsNodes().isNotEmpty()}
            val stroke=InkStroke(id(),InkPen.PEN,Color.BLACK,3f,InkTool.STYLUS,listOf(InkSample(50f,50f,0)))
            runBlocking{assertTrue(app.inkRepository.save(CommitInk(id(),page.id,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)}
            compose.waitUntil(15_000){compose.onAllNodesWithText("合成封面正文").fetchSemanticsNodes().isEmpty()};ready()
        }finally{clear();db.close()}
    }

}
