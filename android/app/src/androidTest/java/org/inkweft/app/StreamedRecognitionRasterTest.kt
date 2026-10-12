// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StreamedRecognitionRasterTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String,List<InkStroke>)->Unit)=runBlocking {
        val name="streamed-recognition-${id()}.db";val db=NoteDatabase.open(context,name)
        try{
            val page=WorkspaceRepository(db).create("分批识别合成页",false,PaperStyle.BLANK).id
            val strokes=List(300){i->
                val x=40f+(299-i)*.6f
                val cut=if(i%19==0)listOf(InkCut(id(),2f,listOf(EraserPoint(x-2,49f),EraserPoint(x+6,52f)),InkCutShape.RECTANGLE))else emptyList()
                InkStroke(id(),if(i%7==0)InkPen.PENCIL else InkPen.PEN,0xff234567.toInt(),2f,InkTool.STYLUS,
                    listOf(InkSample(x,40f,0,.25f),InkSample(x+4,65f,20,.8f)),cuts=cut)
            }
            db.withTransaction{
                if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else check(db.ink().compareAndSet(page,0,1)==1)
                strokes.forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),it.samples.size,true,1))}
            }
            block(db,page,strokes)
        }finally{db.close();context.deleteDatabase(name)}
    }
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}
    private fun release(bitmap:Bitmap){RenderResources.release(bitmap,"ocr");bitmap.recycle()}

    @Test fun batchedInkAndMasksProduceTheSameLineRasterInTheSameOrder()=fixture{db,page,strokes->
        val recognizer=HandwritingRecognizer(context);val input=PageAuthoringRepository(db).readForRecognition(page)
        val line=HandwritingLines.split(strokes).single();val stored=HandwritingLines.fromBounds(input.strokes).single()
        assertEquals(line.bounds,stored.bounds)
        val repo=InkRepository(db);var count=0;var largest=0;var batches=0
        repo.readObserver={count+=it.entries;largest=maxOf(largest,it.entries);batches++}
        val expected=recognizer.raster(line)
        try{val actual=recognizer.rasterStoredLine(page,input.authoring.inkRevision,stored,repo)
            try{assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height);assertArrayEquals(pixels(expected),pixels(actual))}
            finally{release(actual)}
        }finally{release(expected)}
        assertEquals(300,count);assertTrue(batches>=2);assertTrue(largest<=256)
    }

    @Test fun changedRevisionAbortsRasterAndReleasesItsTrackedBitmap()=fixture{db,page,_->
        val input=PageAuthoringRepository(db).readForRecognition(page);val line=HandwritingLines.fromBounds(input.strokes).single()
        val repo=InkRepository(db);var changed=false
        repo.readObserver={if(!changed){changed=true;db.openHelper.writableDatabase.execSQL("UPDATE ink_pages SET revision=2 WHERE noteId=?",arrayOf(page))}}
        val before=RenderResources.snapshot()["category.ocr"]?:0L
        val error=runCatching{HandwritingRecognizer(context).rasterStoredLine(page,1,line,repo)}.exceptionOrNull()
        assertEquals("INK_WINDOW_REVISION_CHANGED",error?.message)
        assertEquals(before,RenderResources.snapshot()["category.ocr"]?:0L)
        assertEquals(300,db.ink().count(page));assertNull(db.pages().search(page))
    }
}
