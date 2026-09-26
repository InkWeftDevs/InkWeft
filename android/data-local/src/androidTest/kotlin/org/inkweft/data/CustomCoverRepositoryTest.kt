package org.inkweft.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class CustomCoverRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun payload():ByteArray{
        val b=Bitmap.createBitmap(30,40,Bitmap.Config.ARGB_8888);b.eraseColor(0xff468765.toInt())
        val out=ByteArrayOutputStream();try{b.compress(Bitmap.CompressFormat.JPEG,90,out)}finally{b.recycle()}
        return CustomCoverCodec.encode(CustomCover(title="独立封面",subtitle="数学",zoom=1.8f,focusY=.2f,image=out.toByteArray()))
    }
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val name="covers-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun customSaveIsIdempotentRejectsStaleChoiceAndPreservesInk()=fixture{db->
        val ws=WorkspaceRepository(db);val n=ws.create("旧笔记",false,PaperStyle.CORNELL)
        val stroke=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(20f,30f,0)))
        InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(stroke)));val before=db.ink().stroke(stroke.id)!!.payload
        val p=payload();assertTrue(ws.changeCover(n.id,0,NotebookCover.CUSTOM,p));assertTrue(ws.changeCover(n.id,0,NotebookCover.CUSTOM,p));assertEquals(1L,ws.get(n.id).revision)
        assertFalse(ws.changeCover(n.id,0,NotebookCover.CUSTOM,CustomCoverCodec.encode(CustomCover(title="过期"))))
        assertArrayEquals(before,db.ink().stroke(stroke.id)!!.payload);assertEquals(1,db.pages().list(n.id).size)
        assertTrue(ws.changeCover(n.id,1,NotebookCover.FOREST));assertArrayEquals(p,ws.customCover(n.id))
        assertTrue(ws.changeCover(n.id,2,NotebookCover.CUSTOM,p))
    }
    @Test fun creationReplayIncludesCustomPayloadAndDuplicateHasIndependentCopy()=fixture{db->
        val ws=WorkspaceRepository(db);val p=payload();val op=id()
        val n=ws.create("自定义",false,PaperStyle.BLANK,NotebookCover.CUSTOM,op,p)
        assertEquals(n,ws.create("自定义",false,PaperStyle.BLANK,NotebookCover.CUSTOM,op,p))
        try{ws.create("自定义",false,PaperStyle.BLANK,NotebookCover.CUSTOM,op,CustomCoverCodec.encode(CustomCover()));fail()}catch(_:IllegalArgumentException){}
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),n.id,id()));assertArrayEquals(p,ws.customCover(copy.id))
        assertTrue(ws.changeCover(copy.id,0,NotebookCover.CUSTOM,CustomCoverCodec.encode(CustomCover(title="副本封面"))))
        assertArrayEquals(p,ws.customCover(n.id))
    }
    @Test fun backupRestoresImageAndAppearanceWithoutExternalFiles()=fixture{db->
        val ws=WorkspaceRepository(db);val p=payload();val n=ws.create("图片封面",false,PaperStyle.GRID,NotebookCover.CUSTOM,customCover=p)
        val name="cover-restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{s->val repo=LibraryBackupRepository(context,target);s.file.inputStream().use{repo.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,repo.restore(it))}}
            target.close();target=NoteDatabase.open(context,name);assertEquals("custom",WorkspaceRepository(target).get(n.id).coverKey);assertArrayEquals(p,WorkspaceRepository(target).customCover(n.id))
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun malformedImageRejectedWithoutChangingPriorCover()=fixture{db->
        val ws=WorkspaceRepository(db);val n=ws.create("保持封面",false,PaperStyle.BLANK,NotebookCover.WAVE)
        try{ws.changeCover(n.id,0,NotebookCover.CUSTOM,CustomCoverCodec.encode(CustomCover(image=byteArrayOf(1,2,3))));fail()}catch(_:IllegalArgumentException){}
        assertEquals("wave",ws.get(n.id).coverKey);assertNull(ws.customCover(n.id));assertEquals(0L,ws.get(n.id).revision)
    }
}
