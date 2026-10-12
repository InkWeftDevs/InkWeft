// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageRecognitionReadTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(points:Int=2)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        List(points){InkSample(20f+it*.1f,30f,it.toLong(),.5f)})
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="recognition-read-${id()}.db"
        val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("识别读取合成页",false,PaperStyle.BLANK).id)}
        finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun largePointCountProducesOnlyIdentitiesAndBoundsWithoutNarrowingRecognition()=fixture{db,page->
        db.withTransaction{
            if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,160))else check(db.ink().compareAndSet(page,0,160)==1)
            repeat(160){i->val s=stroke(512);db.ink().insertStroke(InkStrokeRow(s.id,page,InkStrokeCodec.encodeStored(s),512,true,i+1L))}
        }
        val result=PageAuthoringRepository(db).readForRecognition(page)
        assertEquals(160,result.strokes.size);assertEquals(81920,db.ink().pointCount(page))
        assertEquals(1,HandwritingLines.fromBounds(result.strokes).size)
        assertEquals(160,db.ink().count(page));assertEquals(160L,result.authoring.inkRevision)
        assertNull(db.pages().search(page))
    }

    @Test fun oneBoundedInputPreservesVisibleLayerAndBeautySuppressionSemantics()=fixture{db,page->
        val original=stroke();val kept=stroke();val hidden=stroke();val ink=InkRepository(db)
        listOf(original,kept,hidden).forEachIndexed{i,s->assertEquals(InkCommitResult.Committed(i+1L),ink.save(CommitInk(id(),page,i.toLong(),InkMutation.Add(s))))}
        val objectRow=PageObject(id(),PageObjectKind.TEXT,text="已校对正文",sourceStrokeIds=listOf(original.id))
        PageObjectRepository(db).save(page,0,id(),listOf(objectRow))
        val repo=PageAuthoringRepository(db);val before=repo.readPage(page);val layer=UserLayer(id(),"暂时隐藏")
        val changed=before.state.layers.add(layer).transfer(listOf(LayerContent(LayerContentKind.INK,hidden.id)),layer.id)
            .update(layer.copy(visible=false))
        repo.save(AuthoringScope.page(page,page),before,id(),before.state.withLayers(changed))
        val result=repo.readForRecognition(page)
        assertEquals(listOf(kept.id),result.strokes.map{it.id});assertEquals(kept.bounds(),result.strokes.single().bounds)
        assertEquals(listOf(objectRow),result.objects);assertEquals(3L,result.authoring.inkRevision)
        assertEquals(3,db.ink().count(page));assertNull(db.pages().search(page))
    }
}
