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
