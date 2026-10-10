// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Android graphics, real writer lifecycle and visible template choices: NOT_RUN until a device executes them. */
class MapCompletionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<InkWeftApplication>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:(NoteDatabase,String,KnowledgeData.MapSummaryGroup)->Unit){
        val name="map-summary-state-${id()}.db";val db=NoteDatabase.open(app,name)
        try{
            val book=runBlocking{WorkspaceRepository(db).create("归纳历史",false,PaperStyle.BLANK).id}
            val repo=StudyRepository(db)
            fun create(parent:String?=null):String{val node=id();runBlocking{repo.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=node,title="主题",parentId=parent))};return node}
            val root=create();val a=create(root);val b=create(root)
            val value=runBlocking{MapSummaries.selection(repo.readGraph(book).state,setOf(a,b),"归纳")}
            block(db,book,value)
        }finally{compose.runOnIdle{compose.activity.viewModelStore.clear()};db.close();app.deleteDatabase(name)}
    }
    private fun restored(saved:SavedStateHandle)=SavedStateHandle(saved.keys().associateWith{key->when(val v=saved.get<Any?>(key)){is ByteArray->v.copyOf();is ArrayList<*>->ArrayList(v);else->v}})
    @Test fun summaryUndoRedoSurvivesSavedStateAndReadModeBlocksWrites()=fixture{db,book,value->
        var saved=SavedStateHandle();lateinit var writer:KnowledgeViewModel
        compose.runOnIdle{writer=KnowledgeViewModel(KnowledgeRepository(db),saved,app.resourcePacks);compose.activity.viewModelStore.put("summary",writer)}
        compose.waitUntil(15_000){!writer.ui.value.loading};compose.runOnIdle{writer.submit(book,value)}
        compose.waitUntil(15_000){writer.canUndoSummary(null)}
        compose.runOnIdle{saved=restored(saved);writer=KnowledgeViewModel(KnowledgeRepository(db),saved,app.resourcePacks);compose.activity.viewModelStore.put("summary",writer)}
        compose.waitUntil(15_000){writer.canUndoSummary(null)}
        compose.runOnIdle{writer.authorAllowed={false};writer.undoProperties()}
        assertEquals(1L,runBlocking{db.knowledge().all().single{it.data() is KnowledgeData.MapSummaryGroup}.revision})
        compose.runOnIdle{writer.authorAllowed={true};writer.undoProperties()};compose.waitUntil(15_000){writer.canRedoSummary(null)}
        assertTrue(runBlocking{StudyRepository(db).readGraph(book).state.summaryGroups.isEmpty()})
        compose.runOnIdle{writer.redoProperties()};compose.waitUntil(15_000){writer.canUndoSummary(null)}
        val current=runBlocking{StudyRepository(db).readGraph(book).state.summaryGroups.single()};assertEquals(3L,current.revision);assertEquals(value,current.data)
    }
    @Test fun pendingSummaryInverseKeepsReceiptWhenWriterIsRecreated()=fixture{db,book,value->
        val fail=AtomicBoolean(false);val repo=KnowledgeRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT&&fail.get())throw IOException("synthetic")}
        var saved=SavedStateHandle();lateinit var writer:KnowledgeViewModel
        compose.runOnIdle{writer=KnowledgeViewModel(repo,saved,app.resourcePacks);compose.activity.viewModelStore.put("summary",writer)}
        compose.waitUntil(15_000){!writer.ui.value.loading};compose.runOnIdle{writer.submit(book,value)};compose.waitUntil(15_000){writer.canUndoSummary(null)}
        fail.set(true);compose.runOnIdle{writer.undoProperties()};compose.waitUntil(15_000){writer.ui.value.unknown&&!writer.ui.value.busy}
        val operation=compose.runOnIdle{writer.pendingOperationId}
        compose.runOnIdle{saved=restored(saved);writer=KnowledgeViewModel(repo,saved,app.resourcePacks);compose.activity.viewModelStore.put("summary",writer)}
        assertEquals(operation,compose.runOnIdle{writer.pendingOperationId});fail.set(false)
        compose.runOnIdle{writer.retry()};compose.waitUntil(15_000){writer.canRedoSummary(null)}
        assertTrue(runBlocking{StudyRepository(db).readGraph(book).state.summaryGroups.isEmpty()})
    }
    @Test fun zoomResetKeepsCenterAnchorAndOverviewIncludesSummaryLabels(){
        val book=id();val root=id();val a=id();val b=id();val card=id()
        val nodes=listOf(StudyNodeRow(root,book,card,null,40.0,200.0),StudyNodeRow(a,book,card,root,360.0,80.0),StudyNodeRow(b,book,card,root,360.0,280.0))
        compose.runOnIdle{
            val view=MindMapView(app);view.layout(0,0,1200,700);view.show(nodes,listOf(StudyCardRow(card,book,1,"主题","正文")))
            view.summaryGroups=listOf(KnowledgeData.MapSummaryGroup(null,"归纳标题",listOf(a,b)))
            view.restoreViewport(MapViewport(.35f,200f,100f));val before=view.snapshotViewport()
            val worldX=(600-before.x)/before.scale;val worldY=(350-before.y)/before.scale
            view.zoomTo(1f);val after=view.snapshotViewport();assertEquals(1f,after.scale,0f)
            assertEquals(worldX,(600-after.x)/after.scale,.01f);assertEquals(worldY,(350-after.y)/after.scale,.01f)
            view.zoomTo(Float.NaN);view.zoom(-1f);assertEquals(after,view.snapshotViewport())
            view.fitOverview();val viewport=view.snapshotViewport()
            val scene=nodes.map{MapSceneNode(it.id,it.parentId,it.cardId,"主题","正文",it.x,it.y,1,1)}
            val measured=scene.associate{it.id to MapNodeMetrics.measure(it.title,it.body)}
            val visual=MapSummaryPainter.measure(scene,measured,view.summaryGroups).single().bounds
            val density=app.resources.displayMetrics.density
            assertTrue(visual.left*density*viewport.scale+viewport.x>=0);assertTrue(visual.right*density*viewport.scale+viewport.x<=1200)
            view.fitSelection(setOf(a));assertTrue(view.nodeBounds(a)!!.width()>0)
        }
    }
    @Test fun pngAndVectorPdfIncludeGroupsAndStayWithinAllocationBudget(){
        val book=id();val root=id();val a=id();val b=id()
        val nodes=listOf(MapSceneNode(root,null,null,"项目","",-400.0,200.0,1,1),MapSceneNode(a,root,null,"知识点甲","",20.0,80.0,1,1),MapSceneNode(b,root,null,"知识点乙","",20.0,280.0,1,1))
        val snapshot=MapDrawingSnapshot("导图",nodes,"right",listOf(KnowledgeData.MapSummaryGroup(null,"共有结论",listOf(a,b))),emptyMap())
        val png=ByteArrayOutputStream().also{MapDrawingExport.write(snapshot,MapDrawingFormat.PNG,it,app.cacheDir)}.toByteArray()
        val bitmap=BitmapFactory.decodeByteArray(png,0,png.size);assertNotNull(bitmap)
        try{assertTrue(bitmap.width<=4096&&bitmap.height<=4096&&bitmap.width.toLong()*bitmap.height<=MapExportSizing.MAX_PIXELS)
            assertTrue((0 until bitmap.height step 8).sumOf{y->(0 until bitmap.width step 8).count{x->bitmap.getPixel(x,y)!=Color.WHITE}}>20)
        }finally{bitmap.recycle()}
        val pdf=ByteArrayOutputStream().also{MapDrawingExport.write(snapshot,MapDrawingFormat.PDF,it,app.cacheDir)}.toByteArray();assertEquals("%PDF",pdf.take(4).toByteArray().toString(Charsets.US_ASCII))
        val file=File(app.cacheDir,"map-export-test-${id()}.pdf")
        try{file.writeBytes(pdf);ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->PdfRenderer(descriptor).use{renderer->assertEquals(1,renderer.pageCount);renderer.openPage(0).use{page->assertTrue(page.width>0&&page.height>0)}}}}
        finally{file.delete()}
    }
    @Test fun templateSearchRetainsStableIndicesAndCreationPreviewIsReadOnly(){
        val chosen=androidx.compose.runtime.mutableStateOf<KnowledgeData.MapTemplate?>(null)
        compose.runOnUiThread{compose.activity.setContent{org.inkweft.app.ui.designsystem.InkTheme.Content{Column(Modifier.verticalScroll(rememberScrollState())){
            val template=chosen.value
            if(template==null)MapTemplateChoices(MapTemplates.builtins,true){chosen.value=it}else MapTemplatePreview(template)
        }}}}
        compose.onNodeWithTag("map-template-search").performTextInput("项目")
        compose.onNodeWithTag("map-template-11").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("项目拆解",chosen.value?.title);assertEquals("organization",chosen.value?.layout)
        compose.onNodeWithTag("map-template-preview").assertIsDisplayed()
    }
    @Test fun pdfPreservesLiteralUnicodeIncludingSharedGlyphRadicalsAndVisibleEllipsis(){
        val book=id();val root=id();val a=id();val b=id();val alias=id();val long=id()
        val nodes=listOf(
            MapSceneNode(root,null,null,"进行中","",0.0,250.0,1,1),
            MapSceneNode(a,root,null,"依赖与风险","",300.0,0.0,1,1),
            MapSceneNode(b,root,null,"里程碑","",300.0,120.0,1,1),
            MapSceneNode(alias,root,null,"行⾏ 风⻛ 里⾥","",300.0,240.0,1,1),
            MapSceneNode(long,root,null,"可见标题".repeat(25)+"HIDDEN_TAIL","",300.0,360.0,1,1))
        val snapshot=MapDrawingSnapshot("Unicode export",nodes,"right",listOf(KnowledgeData.MapSummaryGroup(null,"归纳 行⾏ 风⻛",listOf(a,b))),emptyMap())
        val output=File(app.getExternalFilesDir(null),"fix-v85").apply{mkdirs()}
        val pdf=ByteArrayOutputStream().also{MapDrawingExport.write(snapshot,MapDrawingFormat.PDF,it,app.cacheDir)}.toByteArray()
        File(output,"unicode-map.pdf").writeBytes(pdf)
        File(output,"unicode-map.png").outputStream().use{MapDrawingExport.write(snapshot,MapDrawingFormat.PNG,it,app.cacheDir)}
        val document=com.artifex.mupdf.fitz.Document.openDocument(pdf,"application/pdf")
        try{
            assertEquals(1,document.countPages());val page=document.loadPage(0)
            try{val structured=page.toStructuredText()
                try{val text=buildString{structured.blocks.forEach{block->block.lines.forEach{line->line.chars.forEach{appendCodePoint(it.c)};append('\n')}}}
                    File(output,"unicode-map.txt").writeText(text)
                    listOf("进行中","依赖与风险","里程碑","行⾏ 风⻛ 里⾥","归纳 行⾏ 风⻛").forEach{assertTrue("Literal author text lost: $it in $text",text.contains(it))}
                    assertEquals(2,text.count{it=='⻛'});assertEquals(2,text.count{it=='⾏'});assertEquals(1,text.count{it=='⾥'})
                    assertEquals(1,text.count{it=='…'});assertFalse(text.contains("HIDDEN_TAIL"))
                }finally{structured.destroy()}
            }finally{page.destroy()}
        }finally{document.destroy()}
    }
}
