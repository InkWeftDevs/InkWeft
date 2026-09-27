package org.inkweft.data
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ContinuousWritingRepositoryTest {
    @Test fun mixedEraseRollsBackInkWhenObjectRevisionConflicts()=fixture{db->
        val a=WorkspaceRepository(db).create("一",false,PaperStyle.BLANK);val b=WorkspaceRepository(db).create("二",false,PaperStyle.BLANK)
        val repo=InkRepository(db);val add=command(a.id);repo.save(add)
        val ink=(add.mutation as InkMutation.Add).stroke
        val text=PageObject(id(),PageObjectKind.TEXT,text="原文")
        PageObjectRepository(db).save(b.id,0,id(),listOf(text))
        val erase=CommitInk(id(),a.id,1,InkMutation.Visibility(listOf(ink.id),false))
        assertNotNull(repo.saveCanvasBatch(listOf(erase),listOf(ObjectWrite(b.id,0,id(),emptyList()))).failure)
        assertTrue(repo.read(a.id).strokes.single().visible)
        val objects=ObjectWrite(b.id,1,id(),emptyList())
        repeat(2){assertNull(repo.saveCanvasBatch(listOf(erase),listOf(objects)).failure)}
        assertFalse(repo.read(a.id).strokes.single().visible);assertTrue(PageObjectRepository(db).read(b.id).objects.isEmpty())
    }
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="seam-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    private fun command(page:String,revision:Long=0)=CommitInk(id(),page,revision,InkMutation.Add(InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(10f,10f,0),InkSample(20f,20f,20)))))
    @Test fun groupRollsBackEarlierSheetOnLaterConflictAndReplaysOnce()=fixture{db->
        val a=WorkspaceRepository(db).create("一",false,PaperStyle.BLANK);val b=WorkspaceRepository(db).create("二",false,PaperStyle.BLANK)
        val repo=InkRepository(db);val first=command(a.id)
        assertTrue(repo.saveGroup(listOf(first,command(b.id,99))).all{it==InkCommitResult.Conflict})
        assertEquals(0L,repo.read(a.id).revision);assertNull(db.ink().receipt(first.commandId))
        val second=command(b.id);val commands=listOf(first,second)
        repeat(2){assertTrue(repo.saveGroup(commands).all{it is InkCommitResult.Committed})}
        assertEquals(1,repo.read(a.id).strokes.size);assertEquals(1,repo.read(b.id).strokes.size)
    }
    @Test fun exceptionInsideGroupDoesNotLeaveHalfAStroke()=fixture{db->
        val a=WorkspaceRepository(db).create("一",false,PaperStyle.BLANK);val b=WorkspaceRepository(db).create("二",false,PaperStyle.BLANK)
        var count=0;val repo=InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT&&++count==2)error("断电")}
        val commands=listOf(command(a.id),command(b.id))
        try{repo.saveGroup(commands);fail()}catch(_:IllegalStateException){}
        assertEquals(0L,repo.read(a.id).revision);assertEquals(0L,repo.read(b.id).revision)
        assertTrue(InkRepository(db).saveGroup(commands).all{it is InkCommitResult.Committed})
    }
    @Test fun hiddenBeautyAndRestorationRetainExactSourceAcrossBackup()=fixture{db->
        val n=WorkspaceRepository(db).create("美化删除",false,PaperStyle.BLANK);val ink=command(n.id)
        InkRepository(db).save(ink);val source=(ink.mutation as InkMutation.Add).stroke
        val objectRepo=PageObjectRepository(db);val o=PageObject(id(),PageObjectKind.TEXT,text="文字",sourceStrokeIds=listOf(source.id))
        objectRepo.save(n.id,0,id(),listOf(o));objectRepo.save(n.id,1,id(),listOf(o.copy(hidden=true)))
        val copy=NotebookPages(db).importBook(NotebookFile.decode(NotebookPages(db).exportBook(n.id).encode()))
        assertTrue(objectRepo.read(copy.id).objects.single().hidden)
        objectRepo.save(n.id,2,id(),listOf(o));assertFalse(objectRepo.read(n.id).objects.single().hidden)
        assertArrayEquals(InkStrokeCodec.encode(source),InkStrokeCodec.encode(InkRepository(db).read(n.id).strokes.single().stroke))
    }
}
