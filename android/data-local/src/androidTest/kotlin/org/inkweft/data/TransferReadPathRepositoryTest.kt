// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class TransferReadPathRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val name="transfer-read-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("可见副本合成检查",false,PaperStyle.RULED).id)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private fun stroke()=InkStroke(id(),InkPen.PENCIL,0xff345678.toInt(),3f,InkTool.STYLUS,
        listOf(InkSample(80f,90f,0,.25f,.35f,.7f),InkSample(220f,90f,3_600_000,.9f,.6f,1.4f)),
        appearance=StrokeAppearance(BrushRecipe(grain=1.3f),42))
    private fun cut()=InkCut(id(),5f,listOf(EraserPoint(130f,80f),EraserPoint(130f,100f)))
    private suspend fun save(db:NoteDatabase,page:String,change:InkMutation) {
        val revision=db.ink().page(page)?.revision?:0
        assertEquals(InkCommitResult.Committed(revision+1),InkRepository(db).save(CommitInk(id(),page,revision,change)))
    }
    private fun pdf(pages:Int=2,width:Int=200):ByteArray {
        val document=PdfDocument()
        return try{
            repeat(pages){i->val page=document.startPage(PdfDocument.PageInfo.Builder(width,300,i+1).create());document.finishPage(page)}
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        }finally{document.close()}
    }
    private suspend fun pdfBook(db:NoteDatabase,bytes:ByteArray):Note {
        val source=PdfDocumentSource(bytes,2)
        return ContentTransfer.document("原件复制合成PDF",source,source.sha256).use{
            LibraryContentRepository(db).import(ImportNotebook(id(),id(),it.sha256),it)
        }
    }
    private fun poisonStroke(db:NoteDatabase,stroke:String)=db.openHelper.writableDatabase.execSQL(
        "UPDATE ink_strokes SET payload=zeroblob(3000000) WHERE id=?",arrayOf(stroke))
    private fun poisonCut(db:NoteDatabase,cut:String)=db.openHelper.writableDatabase.execSQL(
        "UPDATE ink_cuts SET payload=zeroblob(3000000) WHERE id=?",arrayOf(cut))

    @Test fun visibleAndSelectedSnapshotsSkipUnrelatedBlobsButKeepEffectiveMasks()=fixture{db,page->
        val ink=InkRepository(db);val kept=stroke();val hidden=stroke();val other=stroke()
        listOf(kept,hidden,other).forEach{save(db,page,InkMutation.Add(it))}
        val shared=cut();save(db,page,InkMutation.Cut(EraseSelection(shared,listOf(kept.id,hidden.id))))
        val hiddenOnly=cut();save(db,page,InkMutation.Cut(EraseSelection(hiddenOnly,listOf(hidden.id))))
        val undone=cut();save(db,page,InkMutation.Cut(EraseSelection(undone,listOf(kept.id))))
        save(db,page,InkMutation.CutVisibility(undone.id,false));save(db,page,InkMutation.Visibility(listOf(hidden.id),false))
        val revision=ink.read(page).revision
        poisonStroke(db,hidden.id);poisonCut(db,hiddenOnly.id);poisonCut(db,undone.id)
        val visible=ink.readVisibleSnapshot(page)
        assertEquals(revision,visible.revision);assertEquals(listOf(kept.id,other.id),visible.strokes.map{it.id})
        assertEquals(kept.samples,visible.strokes.first().samples);assertEquals(kept.appearance,visible.strokes.first().appearance)
        assertEquals(listOf(shared.id),visible.strokes.first().cuts.map{it.id});assertEquals(shared.points,visible.strokes.first().cuts.single().points)
        val timings=mutableListOf<ReadTiming>();ink.readObserver={timings.add(it)}
        ink.readVisibleSnapshot(page,listOf(kept.id));assertEquals(2,timings.single().entries)
        ink.readObserver={error("synthetic diagnostic failure")}
        assertEquals(revision,ink.readVisibleSnapshot(page,listOf(kept.id)).revision);ink.readObserver=null
        assertTrue(runCatching{ink.read(page)}.isFailure)
        poisonStroke(db,other.id)
        assertEquals(kept.id,ink.readVisibleSnapshot(page,listOf(kept.id)).strokes.single().id)
        assertTrue(ink.readVisibleSnapshot(page,listOf(hidden.id,id())).strokes.isEmpty())
        assertEquals(revision,ink.readVisibleSnapshot(page,emptyList()).revision)
        assertTrue(runCatching{ink.readVisibleSnapshot(page)}.isFailure)
    }

    @Test fun copiesAndVisibleExportsKeepMasksAxesAndCurrentManualIndexWithoutHiddenHistory()=fixture{db,page->
        val kept=stroke();val hidden=stroke();save(db,page,InkMutation.Add(kept));save(db,page,InkMutation.Add(hidden))
        val mask=cut();save(db,page,InkMutation.Cut(EraseSelection(mask,listOf(kept.id))))
        save(db,page,InkMutation.Visibility(listOf(hidden.id),false))
        assertTrue(NotebookPages(db).saveSearchText(page,4,"当前人工索引"))
        val objectId=id();PageObjectRepository(db).save(page,0,id(),listOf(PageObject(objectId,PageObjectKind.TEXT,text="保留来源",sourceStrokeIds=listOf(kept.id))))
        assertTrue(NotebookPages(db).saveSearchText(page,4,"当前人工索引"))
        poisonStroke(db,hidden.id)
        val book=LibraryContentRepository(db).duplicate(CopyNotebook(id(),page,id()))
        val copied=InkRepository(db).readVisible(book.id).single()
        assertNotEquals(kept.id,copied.id);assertEquals(kept.samples,copied.samples);assertEquals(kept.appearance,copied.appearance)
        assertEquals(mask.points,copied.cuts.single().points);assertNotEquals(mask.id,copied.cuts.single().id)
        assertEquals("当前人工索引",db.pages().search(book.id)?.text)
        assertEquals(listOf(copied.id),PageObjectRepository(db).read(book.id).objects.single().sourceStrokeIds)
        val pageCommand=EditPage(id(),page,page,PageEditKind.COPY,InsertPages.orderHash(listOf(page)),4,
            PageInsertLocation.END,null,id(),null,page)
        val result=PageEditingRepository(db).apply(pageCommand) as EditPageResult.Applied
        assertEquals(kept.samples,InkRepository(db).readVisible(result.resultPageId).single().samples)
        assertEquals("当前人工索引",db.pages().search(result.resultPageId)?.text)
        val exported=ContentTransfer.decode(LibraryContentRepository(db).export(page).bytes).content as ContentTransfer.Content.Book
        assertEquals(2,exported.value.pages.size);assertEquals(kept.samples,exported.value.pages.first().strokes.single().samples)
        assertEquals(kept.samples,PageAuthoringRepository(db).exportPage(page).ink.single().samples)
        assertTrue(runCatching{InkRepository(db).read(page)}.isFailure)
    }

    @Test fun selectedSourceCaptureKeepsFixedRevisionAndRejectsMissingOrChangedInk()=fixture{db,page->
        val kept=stroke();val unrelated=stroke();save(db,page,InkMutation.Add(kept));save(db,page,InkMutation.Add(unrelated))
        val mask=cut();save(db,page,InkMutation.Cut(EraseSelection(mask,listOf(kept.id))))
        poisonStroke(db,unrelated.id)
        val draft=StudySourceDraft(page,3,kept.bounds(),listOf(kept.id))
        val command=StudyCommand(id(),page,StudyAction.CREATE_EXCERPT,cardId=id(),title="固定原迹",source=draft)
        val study=StudyRepository(db);assertEquals(command.cardId,study.submit(command))
        val before=study.source(command.cardId!!)!!.snapshot
        assertEquals(kept.samples,InkPageFile.decode(before).strokes.single().samples)
        assertEquals(mask.id,InkPageFile.decode(before).strokes.single().cuts.single().id)
        val anchor=KnowledgeCommand(id(),page,id(),0,KnowledgeData.Anchor(page,3,kept.bounds(),listOf(kept.id)))
        KnowledgeRepository(db).submit(anchor)
        val missing=StudyCommand(id(),page,StudyAction.CREATE_EXCERPT,cardId=id(),title="缺失来源",source=StudySourceDraft(page,3,kept.bounds(),listOf(id())))
        assertTrue(study.outcome(missing) is StudyOutcome.Rejected)
        save(db,page,InkMutation.Visibility(listOf(kept.id),false))
        val stale=StudyCommand(id(),page,StudyAction.CREATE_EXCERPT,cardId=id(),title="旧版来源",source=draft)
        assertTrue(study.outcome(stale) is StudyOutcome.Rejected)
        assertEquals(command.cardId,study.submit(command));assertArrayEquals(before,study.source(command.cardId!!)!!.snapshot)
        assertTrue(KnowledgeRepository(db).outcome(KnowledgeCommand(id(),page,id(),0,anchor.data)) is KnowledgeOutcome.Rejected)
    }

    @Test fun relevantDamagedMasksAndForeignTargetsStillFail()=fixture{db,page->
        val kept=stroke();save(db,page,InkMutation.Add(kept));val mask=cut()
        save(db,page,InkMutation.Cut(EraseSelection(mask,listOf(kept.id))))
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE ink_cuts SET strokeIds=? WHERE id=?",arrayOf(kept.id+","+id(),mask.id))
        assertTrue(runCatching{InkRepository(db).readVisibleSnapshot(page,listOf(kept.id))}.isFailure)
        sql.execSQL("UPDATE ink_cuts SET strokeIds=? WHERE id=?",arrayOf(kept.id,mask.id));poisonCut(db,mask.id)
        assertTrue(runCatching{InkRepository(db).readVisibleSnapshot(page)}.isFailure)
        val copy=CopyNotebook(id(),page,id())
        assertTrue(runCatching{LibraryContentRepository(db).duplicate(copy)}.isFailure)
        assertNull(db.notes().note(copy.destinationId));assertNull(db.libraryContent().receipt(copy.commandId))
    }

    @Test fun snapshotRevisionAndVisibilityStayConsistentWithConcurrentEdits()=fixture{db,page->
        val first=stroke();val second=stroke();save(db,page,InkMutation.Add(first));save(db,page,InkMutation.Add(second))
        save(db,page,InkMutation.Visibility(listOf(second.id),false))
        coroutineScope{
            val writer=launch(Dispatchers.IO){repeat(24){index->
                val hide=if(index%2==0)first.id else second.id;val show=if(index%2==0)second.id else first.id
                save(db,page,InkMutation.Swap(listOf(hide),listOf(show)));yield()
            }}
            repeat(40){
                val snapshot=InkRepository(db).readVisibleSnapshot(page)
                assertEquals(if(snapshot.revision%2L==1L)first.id else second.id,snapshot.strokes.single().id)
                yield()
            }
            writer.join()
        }
        assertEquals(27L,InkRepository(db).readVisibleSnapshot(page).revision)
    }

    @Test fun copyPreparationReleasesWriterAndCommitIncludesCurrentSourceAndNewOriginal()=fixture{db,_->
        val original=pdf();val source=pdfBook(db,original);val extra=PdfDocumentSource(pdf(1,230),1)
        var observed=false
        val repo=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_COPY_VALIDATION){
            assertFalse(db.inTransaction());observed=true
            runBlocking{withTimeout(5000){db.withTransaction{
                assertTrue(NoteRepository(db).save(SaveNote(id(),source.id,1,"预校验后标题","新正文")) is SaveResult.Committed)
                val last=db.pages().list(source.id).last();val page=NotebookPages(db).addAfter(source.id,last.id,id())
                DocumentRepository(db).attach(page.id,PdfPageSource(extra,0))
            }}}
        }}
        val copy=repo.duplicate(CopyNotebook(id(),source.id,id()))
        assertTrue(observed);assertEquals("预校验后标题 · 副本",copy.title);assertEquals("新正文",copy.text)
        val pages=db.pages().list(copy.id);assertEquals(3,pages.size)
        assertEquals(OriginalBytes.memory(original).sha256,DocumentRepository(db).read(pages.first().id)!!.document.sha256)
        assertEquals(extra.sha256,DocumentRepository(db).read(pages.last().id)!!.document.sha256)
        assertEquals(2L*(original.size+extra.size),db.documents().totalBytes())
    }

    @Test fun newUnvalidatedOriginalAfterCopyPreparationCannotBypassFormatChecks()=fixture{db,source->
        val invalid=OriginalBytes.memory("invalid PDF original".toByteArray());val command=CopyNotebook(id(),source,id())
        val repo=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_COPY_VALIDATION)runBlocking{db.withTransaction{
            val document=id();db.documents().insertSource(DocumentSourceRow(document,source,invalid.sha256,1,invalid.size))
            db.documents().insertChunk(DocumentChunkRow(document,0,invalid.bytes(invalid.size)))
            db.documents().insertPage(DocumentPageRow(source,document,0))
        }}}
        assertTrue(runCatching{repo.duplicate(command)}.isFailure)
        assertNull(db.notes().note(command.destinationId));assertTrue(db.pages().list(command.destinationId).isEmpty())
        assertNull(db.libraryContent().receipt(command.commandId))
    }

    @Test fun changedOriginalRollsBackCopyAndCommittedReplaySkipsUnavailableSource()=fixture{db,_->
        val bytes=pdf();val source=pdfBook(db,bytes);val document=db.documents().page(source.id)!!.documentId
        val command=CopyNotebook(id(),source.id,id());val changed=bytes.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
        val repo=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_COPY_VALIDATION){
            assertFalse(db.inTransaction());db.openHelper.writableDatabase.execSQL("UPDATE document_chunks SET payload=? WHERE documentId=?",arrayOf(changed,document))
        }}
        assertEquals("ORIGINAL_CHECKSUM_CHANGED",runCatching{repo.duplicate(command)}.exceptionOrNull()?.message)
        assertNull(db.notes().note(command.destinationId));assertTrue(db.pages().list(command.destinationId).isEmpty());assertNull(db.libraryContent().receipt(command.commandId))
        db.openHelper.writableDatabase.execSQL("UPDATE document_chunks SET payload=? WHERE documentId=?",arrayOf(bytes,document))
        assertTrue(runCatching{LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_TRANSACTION)error("lost result")}.duplicate(command)}.isFailure)
        db.openHelper.writableDatabase.execSQL("UPDATE document_chunks SET payload=? WHERE documentId=?",arrayOf(changed,document))
        assertTrue(WorkspaceRepository(db).organize(source.id,0,"","",false,true))
        val replay=LibraryContentRepository(db){if(it==LibraryContentFault.AFTER_COPY_VALIDATION)fail("Replay must not prepare the retired source")}.duplicate(command)
        assertEquals(command.destinationId,replay.id);assertArrayEquals(bytes,DocumentRepository(db).read(replay.id)!!.document.bytes())
    }
}
