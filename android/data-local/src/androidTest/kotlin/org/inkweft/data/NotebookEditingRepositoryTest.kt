// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Test definitions only until connectedDebugAndroidTest actually executes them. */
class NotebookEditingRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),10f,InkTool.TOUCH,listOf(InkSample(20f,80f,0),InkSample(200f,80f,100)))
    private fun cut()=InkCut(id(),15f,listOf(EraserPoint(90f,70f),EraserPoint(90f,90f)))
    @Test fun pageAdditionIsIdempotentAndDistinctFromNotebookIdentity()=runBlocking {
        val name="editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("多页测试",false,PaperStyle.GRID);val pages=NotebookPages(db);val newId=id()
            val p=pages.addAfter(n.id,n.id,newId)
            assertEquals(p,pages.addAfter(n.id,n.id,newId));assertEquals(2,db.pages().list(n.id).size)
            assertNull(db.notes().note(newId));assertEquals(n.id,db.pages().get(newId)?.notebookId)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun pageInkViewportAndSelectionSurviveDatabaseReopen()=runBlocking {
        val name="editor-${id()}.db";var db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("两页",false,PaperStyle.RULED)
            val p=NotebookPages(db).addAfter(n.id,n.id,id());val s=stroke()
            assertEquals(InkCommitResult.Committed(1),InkRepository(db).save(CommitInk(id(),p.id,0,InkMutation.Add(s))))
            WorkspaceRepository(db).saveViewport(p.id,CanvasViewport(400.0,600.0,1.3));NotebookPages(db).select(n.id,p.id)
            db.close();db=NoteDatabase.open(context,name)
            assertEquals(p.id,db.workspace().get(n.id)?.selectedPageId)
            assertEquals(s.samples,InkRepository(db).read(p.id).strokes.single().stroke.samples)
            assertTrue(InkRepository(db).read(n.id).strokes.isEmpty());assertEquals(1.3,db.pages().get(p.id)!!.zoom,0.0)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun partialEraseKeepsOriginalBytesAndInvalidatesManualIndex()=runBlocking {
        val name="editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("索引测试",false,PaperStyle.BLANK);val ink=InkRepository(db);val pages=NotebookPages(db);val s=stroke()
            ink.save(CommitInk(id(),n.id,0,InkMutation.Add(s)));val original=db.ink().stroke(s.id)!!.payload
            assertTrue(pages.saveSearchText(n.id,1,"需要重新核对的关键词"));assertEquals(1,pages.observeSearch().first().size)
            val m=cut();ink.save(CommitInk(id(),n.id,1,InkMutation.Cut(EraseSelection(m,listOf(s.id)))))
            assertArrayEquals(original,db.ink().stroke(s.id)!!.payload)
            assertEquals(m.points,InkSession(ink.read(n.id)).visibleDraft().single().cuts.single().points)
            assertTrue(pages.observeSearch().first().isEmpty());assertFalse(pages.saveSearchText(n.id,1,"迟到旧索引"))
            ink.save(CommitInk(id(),n.id,2,InkMutation.CutVisibility(m.id,false)))
            assertTrue(pages.observeSearch().first().isEmpty());assertTrue(pages.saveSearchText(n.id,3,"人工确认新版本"))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun cutAndReceiptRollbackTogetherBeforeCommit()=runBlocking {
        val name="editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("故障样例",false,PaperStyle.BLANK);val s=stroke()
            InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(s)))
            val command=CommitInk(id(),n.id,1,InkMutation.Cut(EraseSelection(cut(),listOf(s.id))))
            try{InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT)throw IllegalStateException("synthetic")}.save(command);fail("expected fault")}catch(_:IllegalStateException){}
            assertNull(db.ink().receipt(command.commandId));assertTrue(db.ink().cuts(n.id).isEmpty());assertEquals(1L,db.ink().page(n.id)!!.revision)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun cutAfterTransactionUncertaintyReplaysOriginalReceipt()=runBlocking {
        val name="editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("回执",false,PaperStyle.BLANK);val s=stroke();InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(s)))
            val command=CommitInk(id(),n.id,1,InkMutation.Cut(EraseSelection(cut(),listOf(s.id))))
            try{InkRepository(db){if(it==InkFaultPoint.AFTER_TRANSACTION)throw IllegalStateException("synthetic")}.save(command);fail("expected fault")}catch(_:IllegalStateException){}
            assertEquals(InkCommitResult.Committed(2),InkRepository(db).save(command));assertEquals(1,db.ink().cuts(n.id).size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun fullBookContentCopyRetainsPagesAndMaskUnderNewIdentities()=runBlocking {
        val name="editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val n=WorkspaceRepository(db).create("整本副本",false,PaperStyle.BLANK);val pages=NotebookPages(db);val p=pages.addAfter(n.id,n.id,id());val s=stroke();val ink=InkRepository(db)
            ink.save(CommitInk(id(),p.id,0,InkMutation.Add(s)));ink.save(CommitInk(id(),p.id,1,InkMutation.Cut(EraseSelection(cut(),listOf(s.id)))))
            val copy=pages.importBook(NotebookFile.decode(pages.exportBook(n.id).encode()));assertNotEquals(n.id,copy.id)
            val all=db.pages().list(copy.id);assertEquals(2,all.size);assertNotEquals(p.id,all[1].id)
            val restored=InkSession(ink.read(all[1].id)).visibleDraft().single()
            assertEquals(s.samples,restored.samples);assertEquals(1,restored.cuts.size);assertNotEquals(s.id,restored.id)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
