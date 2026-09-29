package org.inkweft.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class InkCheckpointTest {
    private fun id()=UUID.randomUUID().toString()
    private fun ink(id:String,n:Int)=InkStroke(id,InkPen.PENCIL,0xff235685.toInt(),3f,InkTool.STYLUS,List(n){InkSample(100f+it%100,200f+it/100,it.toLong())})
    @Test fun crossPageStorageSliceKeepsPageIdentityAndPersistentCancellation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"group-checkpoint-${id()}").apply{mkdirs()};val db=NoteDatabase.open(context,File(directory,"group.db").absolutePath)
        try{
            val book=WorkspaceRepository(db).create("合成跨页长笔",false,PaperStyle.BLANK).id
            val next=NotebookPages(db).addAfter(book,book,id()).id
            val stroke=InkStroke(id(),InkPen.PENCIL,0xff335577.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,1400f,0,world=true),InkSample(150f,1450f,10,world=true)),world=true)
            val prefix=InkGroupPrefix(book,listOf(book,next),listOf(0,0),0,stroke)
            val journal=InkGroupCheckpoints(File(directory,"groups"));journal.save(prefix)
            for(cut in listOf("before-write","before-commit")){
                try{InkGroupCheckpoints(File(directory,"groups")){if(it==cut)error("injected")}.cancel(stroke.id);fail("Fault ignored")}catch(_:IllegalStateException){}
                assertEquals("OPEN",journal.read(stroke.id)!!.state)
            }
            val repo=InkRepository(db);val commands=journal.read(stroke.id)!!.commands()
            assertEquals(setOf(book,next),commands.map{it.noteId}.toSet());assertTrue(repo.saveGroup(commands).all{it is InkCommitResult.Committed})
            // Lost commit reply: frozen commands retain the original revisions and operation IDs.
            assertTrue(repo.saveGroup(commands).all{it is InkCommitResult.Committed})
            assertEquals(1,repo.read(book).strokes.size);assertEquals(1,repo.read(next).strokes.size)
            val undo=commands.map{c->CommitInk(id(),c.noteId,1,InkMutation.Visibility(repo.read(c.noteId).strokes.map{it.stroke.id},false))}
            assertTrue(repo.saveGroup(undo).all{it is InkCommitResult.Committed})
            assertTrue(InkSession(repo.read(book)).visibleDraft().isEmpty());assertTrue(InkSession(repo.read(next)).visibleDraft().isEmpty())
            val cancelled=prefix.copy(stroke=InkStroke(id(),stroke.pen,stroke.color,stroke.width,stroke.tool,stroke.samples,true))
            journal.save(cancelled);journal.cancel(cancelled.stroke.id)
            val reopened=InkGroupCheckpoints(File(directory,"groups"));assertEquals("CANCELLED",reopened.read(cancelled.stroke.id)!!.state);assertFalse(reopened.save(cancelled))
        }finally{db.close();require(directory.canonicalFile.parentFile==context.cacheDir.canonicalFile);directory.deleteRecursively()}
    }
    @Test fun checkpointCrashCutsKeepOneIdentityAndSealDeduplicates()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"checkpoint-test-${id()}").apply{mkdirs()}
        val db=NoteDatabase.open(context,File(directory,"test.db").absolutePath)
        db.checkpointRoot=File(directory,"parts")
        try{
            val page=WorkspaceRepository(db).create("合成长笔",false,PaperStyle.BLANK).id
            val stroke=id();val first=ink(stroke,128);val full=ink(stroke,256)
            val journal=InkCheckpoints(db.checkpointRoot!!);journal.save(page,128,first)
            for(cut in listOf("before-serialize","after-serialize","before-commit")){
                try{InkCheckpoints(db.checkpointRoot!!){if(it==cut)error("injected")}.save(page,256,full);fail("fault ignored")}catch(_:IllegalStateException){}
                assertArrayEquals(InkStrokeCodec.encode(first),InkStrokeCodec.encode(journal.read(page).single()))
            }
            try{InkCheckpoints(db.checkpointRoot!!){if(it=="after-commit")error("lost confirmation")}.save(page,256,full);fail("fault ignored")}catch(_:IllegalStateException){}
            journal.save(page,256,full);assertArrayEquals(InkStrokeCodec.encode(full),InkStrokeCodec.encode(journal.read(page).single()))
            val repo=InkRepository(db);val restored=repo.recoverCheckpoints(page).single()
            val session=InkSession(repo.read(page));session.enqueue(InkMutation.Add(restored));val command=session.nextCommand()!!
            val result=repo.save(command);session.complete(command,result)
            assertTrue(repo.recoverCheckpoints(page).isEmpty())
            // A delayed writer after the seal cannot resurrect or duplicate the saved stroke.
            repo.checkpoint(page,full);assertTrue(repo.recoverCheckpoints(page).isEmpty())
            session.requestUndo();val undo=session.nextCommand()!!;session.complete(undo,repo.save(undo))
            assertTrue(session.visibleDraft().isEmpty());assertEquals(1,repo.read(page).strokes.size)
        }finally{db.close();check(directory.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator));directory.deleteRecursively()}
    }
}
