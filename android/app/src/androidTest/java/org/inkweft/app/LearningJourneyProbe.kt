package org.inkweft.app

import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.UUID
import java.util.zip.*

/** One persistent book through process death and encrypted restore. Never reset between phases. */
class LearningJourneyProbe {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
    private val marker get()=File(app.filesDir,"learning-journey.json")
    private fun id()=UUID.randomUUID().toString()
    private fun persist(m:JSONObject){FileOutputStream(marker).use{it.write(m.toString(2).toByteArray());it.fd.sync()}}
    private fun step(m:JSONObject,name:String){m.getJSONArray("steps").put(name);persist(m)}
    private fun ready(){compose.waitUntil(20000){app.navigationReady.value}}
    private fun open(note:Note){compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.waitUntil(20000){compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()||compose.onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()};ready()}
    private fun canvas(page:String?=null):InkCanvasView{
        if(page!=null)return checkNotNull(compose.activity.window.decorView.findViewWithTag("ink-page-$page"))
        fun find(v:View):InkCanvasView?{if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun draw(v:InkCanvasView,points:List<Pair<Float,Float>>){
        val now=SystemClock.uptimeMillis();val vp=v.snapshotViewport();val d=v.resources.displayMetrics.density.toDouble()
        points.forEachIndexed{i,(x,y)->
            val p=vp.worldToScreen(x.toDouble(),y.toDouble(),v.width.toDouble(),v.height.toDouble(),d)
            val prop=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val coord=MotionEvent.PointerCoords().apply{this.x=p.x.toFloat();this.y=p.y.toFloat();pressure=.5f;size=.1f}
            val a=if(i==0)MotionEvent.ACTION_DOWN else if(i==points.lastIndex)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            MotionEvent.obtain(now,now+i*10L,a,1,arrayOf(prop),arrayOf(coord),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0).also{try{assertTrue(v.dispatchTouchEvent(it))}finally{it.recycle()}}
        }
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),"journey-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    @Test fun sameBookThroughRecoveryAndEncryptedRestore(){
        val args=InstrumentationRegistry.getArguments();require(args.getString("journeyProbe")=="dedicated-emulator")
        try{if(args.getString("phase")=="prepare")prepare()else finish()}
        catch(t:Throwable){runCatching{shot("failure");if(marker.exists())persist(JSONObject(marker.readText()).put("failure",t.javaClass.simpleName))};throw t}
    }
    private fun prepare(){
        require(!marker.exists());assertTrue(runBlocking{app.repository.observeNotes().first()}.isEmpty())
        val m=JSONObject().put("runId",id()).put("source",BuildConfig.SOURCE_COMMIT).put("steps",JSONArray())
        val json="""{"format":"inkweft.resource-pack.v1","id":"example.journey","title":"串行学习模板","author":"InkWeft","version":1,"resources":[{"id":"paper","title":"概率课堂","type":"paper","paper":"CORNELL"}]}"""
        val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry()}}.toByteArray()
        val pack=ResourcePackCodec.inspect(bytes);runBlocking{app.resourcePacks.install(pack)};m.put("template",pack.hash);step(m,"install-template")
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("new-note").performClick()
        compose.onNodeWithTag("new-title").performTextReplacement("同一本资料 · 概率论")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("new-notebook-screen"))).performScrollToIndex(1)
        compose.onNodeWithText("我的模板").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("installed-paper-paper").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("installed-paper-paper").performScrollTo().performClick();compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(15000){runBlocking{app.repository.observeNotes().first()}.size==1};ready()
        val n=runBlocking{app.repository.observeNotes().first()}.single();m.put("book",n.id);assertEquals(PaperStyle.CORNELL.ordinal,runBlocking{app.pages.activePages(n.id)}.single().paper);step(m,"create-from-template")
        compose.onNodeWithTag("document-add-page").performClick();compose.onNodeWithTag("insert-count-plus").performScrollTo().performClick();compose.onNodeWithTag("confirm-insert-pages").performClick()
        compose.waitUntil(15000){runBlocking{app.pages.activePages(n.id)}.size==3};ready()
        val pages=runBlocking{app.pages.activePages(n.id)};m.put("pages",JSONArray(pages.map{it.id}));step(m,"insert-pages")
        compose.runOnIdle{ViewModelProvider(compose.activity)["book-${n.id}",BookPagesViewModel::class.java].select(n.id)}
        compose.waitUntil(15000){var visible=false;compose.runOnIdle{visible=runCatching{canvas(n.id).inputReady}.getOrDefault(false)};visible&&app.navigationReady.value}
        shot("before-cross-page")
        app.inkRepository.groupFaultForTest={point->if(point=="after-group-commit"){step(m,"terminated-after-group-commit");android.os.Process.killProcess(android.os.Process.myPid())}}
        compose.runOnIdle{val v=canvas(n.id);v.pen=InkPen.PENCIL;draw(v,List(360){300f+it*.2f to 1300f+it*5f})}
        compose.waitUntil(20000){false};fail("Specified process-death point was not reached")
    }
    private fun finish(){
        val m=JSONObject(marker.readText());assertEquals(BuildConfig.SOURCE_COMMIT,m.getString("source"));assertEquals("terminated-after-group-commit",m.getJSONArray("steps").getString(m.getJSONArray("steps").length()-1))
        val book=m.getString("book");val note=runBlocking{checkNotNull(app.repository.read(book))};val pages=runBlocking{app.pages.activePages(book)}
        assertEquals((0 until m.getJSONArray("pages").length()).map{m.getJSONArray("pages").getString(it)},pages.map{it.id})
        open(note);compose.waitUntil(20000){app.navigationReady.value&&runBlocking{app.inkRepository.pendingGroups(book).isEmpty()}}
        val fragments=runBlocking{pages.flatMap{app.inkRepository.read(it.id).strokes.map{r->r.stroke}}};assertTrue(fragments.size>=2)
        m.put("crossPageInk",JSONArray(fragments.map{it.id}));compose.onNodeWithTag("ink-undo").performClick();compose.waitUntil(15000){runBlocking{pages.all{InkSession(app.inkRepository.read(it.id)).visibleDraft().isEmpty()}}}
        compose.onNodeWithTag("ink-redo").performClick();compose.waitUntil(15000){runBlocking{pages.sumOf{InkSession(app.inkRepository.read(it.id)).visibleDraft().size}==fragments.size}};step(m,"reopen-group-undo-redo")
        compose.singlePageEditor();compose.waitForSavedInk();compose.runOnIdle{canvas().fitPage()}
        val first=runBlocking{app.inkRepository.read(book)};val sourceInk=InkSession(first).visibleDraft();val bounds=sourceInk.map{it.bounds()}.reduce{a,b->a.union(b)}
        val card=id();val node=id();runBlocking{app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=node,title="跨页推导",body="样本空间与条件概率",source=StudySourceDraft(book,first.revision,bounds,sourceInk.map{it.id})))}
        m.put("card",card).put("node",node);step(m,"excerpt-into-main-map")
        val saved=runBlocking{app.study.cards(book).first()}.single();runBlocking{app.study.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=card,expectedRevision=saved.revision,title="条件概率与独立性",body=saved.body))}
        assertEquals("条件概率与独立性",runBlocking{app.study.cards(book).first().single().title});step(m,"rename-same-card")
        compose.onNodeWithTag("quick-study").performClick();compose.onNodeWithTag("study-content-search").performClick();compose.onNodeWithTag("map-content-query").performTextReplacement("独立性")
        val hit="map-hit-${MapRef(book).key}-$node";compose.waitUntil(15000){compose.onAllNodesWithTag(hit).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag(hit).performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("node-source").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("node-source").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("study-open-source").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("study-open-source").performScrollTo().performClick();ready();step(m,"search-and-return-to-source")
        val ref=MapRef(book);val embed=MapEmbed(ref);val embedId=id()
        compose.runOnIdle{ViewModelProvider(compose.activity)["study-panel-$book",StudyPanelSession::class.java].embedInsertion.value=EmbedInsertion(book,embedId,embed)}
        compose.waitUntil(10000){runBlocking{app.pageObjects.read(book).objects.any{it.id==embedId}}};m.put("embed",embedId);step(m,"insert-live-map")
        if(compose.onAllNodesWithTag("study-window-minimize").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("study-window-minimize").performClick()
        val hi=listOf(listOf(200f to 250f,200f to 350f),listOf(250f to 250f,250f to 350f),listOf(200f to 300f,250f to 300f),listOf(290f to 250f,340f to 250f),listOf(315f to 250f,315f to 350f),listOf(290f to 350f,340f to 350f))
        val oldIds=runBlocking{app.inkRepository.read(book).strokes.map{it.stroke.id}}.toSet()
        hi.forEach{points->compose.runOnIdle{canvas().pen=InkPen.PEN;draw(canvas(),points)};ready()}
        compose.waitUntil(15000){runBlocking{app.inkRepository.read(book).strokes.count{it.stroke.id !in oldIds}}==6}
        val ink=runBlocking{app.inkRepository.read(book)};val written=InkSession(ink).visibleDraft().filter{it.id !in oldIds}
        val objects=ViewModelProvider(compose.activity)["objects-$book",PageObjectViewModel::class.java]
        compose.runOnIdle{objects.beautify(SelectedInk(InkRegion(listOf(EraserPoint(170f,220f),EraserPoint(370f,380f))),ink.revision,written),BeautyOptions(keepInk=false),false,app)}
        compose.waitUntil(45000){objects.beautyReview.value!=null};compose.onNodeWithTag("beauty-review-text").performTextReplacement("HI");compose.onNodeWithTag("beauty-review-apply").performClick()
        compose.waitUntil(10000){runBlocking{app.pageObjects.read(book).objects.any{it.sourceStrokeIds.isNotEmpty()}}};val beauty=runBlocking{app.pageObjects.read(book).objects.single{it.sourceStrokeIds.isNotEmpty()}}
        m.put("beauty",beauty.id);step(m,"review-and-apply-beauty")
        compose.runOnIdle{val v=canvas();v.eraseMode=true;v.eraserWhole=false;v.eraserDiameterDp=8f;draw(v,listOf((beauty.x+beauty.width*.2f) to beauty.y,(beauty.x+beauty.width*.2f) to (beauty.y+beauty.height)))}
        compose.waitUntil(10000){runBlocking{app.pageObjects.read(book).objects.first{it.id==beauty.id}.erasures.isNotEmpty()}}
        compose.onNodeWithTag("ink-undo").performClick();compose.waitUntil(10000){runBlocking{app.pageObjects.read(book).objects.first{it.id==beauty.id}.erasures.isEmpty()}};ready();shot("before-backup");step(m,"local-erase-and-undo")
        val root=File(app.filesDir,"learning-journey-restore").apply{check(mkdirs())};val key=EncryptedBackupFile.random(32);val library=id();val encrypted=File(root,"encrypted.iwbk");val clear=File(root,"verified.iwbackup")
        val before=runBlocking{app.libraryBackup.snapshot()};before.use{EncryptedBackupFile.encrypt(it.file,encrypted,library,key)};EncryptedBackupFile.decrypt(encrypted,clear,library,key);step(m,"encrypted-backup")
        val restoredDb=NoteDatabase.open(app,File(root,"empty-restored.db").absolutePath)
        try{runBlocking{
            val restored=LibraryBackupRepository(app,restoredDb);clear.inputStream().use{restored.inspect(it)}.use{assertEquals(1,it.notes);assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restored.restore(it))}
            assertEquals(note,NoteRepository(restoredDb).read(book));assertEquals(app.pages.activePages(book),NotebookPages(restoredDb).activePages(book))
            pages.forEach{p->assertEquals(app.pageObjects.read(p.id),PageObjectRepository(restoredDb).read(p.id));val a=app.inkRepository.read(p.id);val b=InkRepository(restoredDb).read(p.id);assertEquals(a.revision,b.revision);assertEquals(a.strokes.map{ContentTransfer.hash(InkStrokeCodec.encode(it.stroke))},b.strokes.map{ContentTransfer.hash(InkStrokeCodec.encode(it.stroke))})}
            assertEquals(app.study.cards(book).first(),StudyRepository(restoredDb).cards(book).first());assertEquals(app.study.nodes(book).first(),StudyRepository(restoredDb).nodes(book).first())
            val source=checkNotNull(app.study.source(card));val copy=checkNotNull(StudyRepository(restoredDb).source(card));assertEquals(source.pageId,copy.pageId);assertArrayEquals(source.snapshot,copy.snapshot)
            val restoredEmbed=PageObjectRepository(restoredDb).read(book).objects.first{it.id==embedId}.mapEmbed!!;assertEquals(MapEmbedPolicy.LIVE,restoredEmbed.policy);assertEquals(embed.resolve(app.mapGraphs.read(book)),restoredEmbed.resolve(MapGraphAccess(restoredDb).read(book)))
        }}finally{restoredDb.close();clear.delete();key.fill(0)}
        step(m,"restore-empty-isolated-library-and-verify-closure");m.put("result","PASS");persist(m)
        File(app.getExternalFilesDir(null),"learning-journey-result.json").writeText(m.toString(2))
    }
}
