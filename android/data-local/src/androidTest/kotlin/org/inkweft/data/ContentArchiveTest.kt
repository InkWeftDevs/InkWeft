// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

class ContentArchiveTest {
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,File)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="content-archive-${id()}.db"
        val directory=File(context.cacheDir,"archive-fixture-${id()}").also{check(it.mkdirs())};val db=NoteDatabase.open(context,name)
        try{block(db,directory)}finally{db.close();context.deleteDatabase(name);directory.deleteRecursively()}
    }
    private suspend fun prepare(file:File,root:File)=file.inputStream().use{input->
        ContentArchive.prepare(input,File(root,"stage-${id()}").also{check(it.mkdirs())})
    }
    private fun stroke(x:Float=20f,count:Int=2,world:Boolean=false)=InkStroke(id(),InkPen.PENCIL,0xff234567.toInt(),2f,InkTool.STYLUS,
        List(count){InkSample(x+(it%10)*.1f,30f,it.toLong(),.4f,.2f,.3f,world)},world,appearance=StrokeAppearance(BrushRecipe(),123))

    @Test fun masksHiddenLayersAndRemappedReferencesRoundTripWithoutUndoGeometry()=fixture{db,directory->
        val note=WorkspaceRepository(db).create("文件流副本",false,PaperStyle.GRID);val repo=InkRepository(db)
        val first=stroke();val hidden=stroke(70f)
        assertTrue(repo.save(CommitInk(id(),note.id,0,InkMutation.Add(first))) is InkCommitResult.Committed)
        assertTrue(repo.save(CommitInk(id(),note.id,1,InkMutation.Add(hidden))) is InkCommitResult.Committed)
        val cut=InkCut(id(),3f,listOf(EraserPoint(20f,30f)))
        assertTrue(repo.save(CommitInk(id(),note.id,2,InkMutation.Cut(EraseSelection(cut,listOf(first.id))))) is InkCommitResult.Committed)
        assertTrue(repo.save(CommitInk(id(),note.id,3,InkMutation.Visibility(listOf(hidden.id),false))) is InkCommitResult.Committed)
        val scope=AuthoringScope.page(note.id,note.id);val before=PageAuthoringRepository(db).read(scope)
        val layer=UserLayer(id(),"隐藏层",visible=false)
        val layers=UserLayers(before.state.layers.layers+layer,before.state.layers.currentId,listOf(LayerMembership(LayerContent(LayerContentKind.INK,first.id),layer.id),LayerMembership(LayerContent(LayerContentKind.INK,hidden.id),UserLayers.DEFAULT_ID)))
        PageAuthoringRepository(db).save(scope,before,id(),before.state.withLayers(layers))
        LibraryContentRepository(db).prepareExport(note.id).use{file->
            prepare(file.file,directory).use{prepared->
                val command=ImportNotebook(id(),id(),prepared.sha256);val target=LibraryContentRepository(db).import(command,prepared)
                val copied=repo.read(target.id);assertEquals(1,copied.strokes.size);assertTrue(copied.cuts.isEmpty())
                val ink=copied.strokes.single().stroke;assertNotEquals(first.id,ink.id);assertEquals(first.samples,ink.samples)
                assertEquals(cut.points,ink.cuts.single().points);assertNotEquals(cut.id,ink.cuts.single().id)
                assertFalse(PageAuthoringRepository(db).readPage(target.id).state.layers.visible(LayerContent(LayerContentKind.INK,ink.id)))
                assertEquals(target,LibraryContentRepository(db).import(command,prepared))
            }
        }
        assertEquals(2,repo.read(note.id).retainedStrokeCount)
    }

    /** A valid PDF with a large unused stream; validators need no giant rendered bitmap. */
    private fun largePdf(file:File,bytes:Int){
        RandomAccessFile(file,"rw").use{out->
            fun text(value:String)=out.write(value.toByteArray(Charsets.US_ASCII))
            val offsets=mutableListOf(0L);text("%PDF-1.7\n")
            fun obj(number:Int,body:String){offsets+=out.filePointer;text("$number 0 obj\n$body\nendobj\n")}
            obj(1,"<< /Type /Catalog /Pages 2 0 R >>")
            obj(2,"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
            obj(3,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1000 1414] >>")
            obj(4,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1000 1414] >>")
            offsets+=out.filePointer;text("5 0 obj\n<< /Length $bytes >>\nstream\n")
            val buffer=ByteArray(64*1024){42};var left=bytes
            while(left>0){val n=minOf(left,buffer.size);out.write(buffer,0,n);left-=n}
            text("\nendstream\nendobj\n");val xref=out.filePointer
            text("xref\n0 6\n0000000000 65535 f \n");offsets.drop(1).forEach{text("%010d 00000 n \n".format(java.util.Locale.ROOT,it))}
            text("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    @Test fun sharedLargeOriginalTravelsOnceAndImportsWithoutAnOriginalSizedArray()=fixture{db,directory->
        val sourceFile=File(directory,"large.pdf");largePdf(sourceFile,64*1024*1024)
        val source=PdfDocumentSource.fromFile(sourceFile,2);val prepared=ContentTransfer.document("大原件副本",source,source.sha256)
        val note=LibraryContentRepository(db).import(ImportNotebook(id(),id(),prepared.sha256),prepared)
        LibraryContentRepository(db).prepareExport(note.id).use{file->
            assertTrue(file.byteCount>InkPageFile.MAX_BYTES);assertTrue(file.byteCount<source.size+100_000L)
            prepare(file.file,directory).use{copy->
                val target=LibraryContentRepository(db).import(ImportNotebook(id(),id(),copy.sha256),copy)
                val pages=NotebookPages(db).activePages(target.id);assertEquals(2,pages.size)
                pages.forEachIndexed{index,page->val pdf=checkNotNull(DocumentRepository(db).read(page.id));assertEquals(index,pdf.page);assertEquals(source.sha256,pdf.document.sha256);assertEquals(source.size,pdf.document.size)}
                assertEquals(source.size.toLong(),db.originalStore.diskBytes())
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","CONTENT_ORIGINAL_EVIDENCE {\"sourceBytes\":${source.size},\"archiveBytes\":${file.byteCount},\"physicalBytes\":${db.originalStore.diskBytes()},\"pages\":2}\n")})
            }
        }
    }

    @Test fun fortyThousandStrokePageExportsAndImportsWithBoundedWindows()=fixture{db,directory->
        val note=WorkspaceRepository(db).create("大页文件流",true,PaperStyle.BLANK)
        db.withTransaction{
            if(db.ink().page(note.id)==null)db.ink().insertPage(InkPageRow(note.id,1))else check(db.ink().compareAndSet(note.id,0,1)==1)
            repeat(40_001){i->val value=stroke((i%200)*20f,51,true);db.ink().insertStroke(InkStrokeRow(value.id,note.id,InkStrokeCodec.encodeStored(value),51,true,1))}
        }
        val runtime=Runtime.getRuntime();var peak=runtime.totalMemory()-runtime.freeMemory()
        val sampler=CoroutineScope(Dispatchers.Default).launch{while(isActive){peak=maxOf(peak,runtime.totalMemory()-runtime.freeMemory());delay(10)}}
        try{LibraryContentRepository(db).prepareExport(note.id).use{file->
            prepare(file.file,directory).use{prepared->
                val target=LibraryContentRepository(db).import(ImportNotebook(id(),id(),prepared.sha256),prepared)
                val window=InkRepository(db).readForEditing(target.id,bounds=CanvasBounds(0.0,0.0,80.0,80.0),pointBudget=8192)
                assertEquals(40_001,window.retainedStrokeCount);assertTrue(window.strokes.sumOf{it.stroke.samples.size}<=8192)
                assertTrue(window.unloaded.strokePoints.isEmpty());assertTrue(window.unloaded.cutIds.isEmpty())
                assertEquals(40_001,db.ink().visibleCount(target.id))
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","CONTENT_PAGE_EVIDENCE {\"strokes\":40001,\"points\":2040051,\"archiveBytes\":${file.byteCount},\"peakJavaUsedBytes\":$peak,\"windowPoints\":${window.strokes.sumOf{it.stroke.samples.size}}}\n")})
            }
        }}finally{sampler.cancelAndJoin()}
    }

    @Test fun damagedArchiveAndCancellationRemoveStagingWithoutPublishingAuthorRows()=fixture{db,directory->
        val note=WorkspaceRepository(db).create("未写入损坏副本",true,PaperStyle.BLANK)
        LibraryContentRepository(db).prepareExport(note.id).use{file->
            val corrupt=File(directory,"damaged.iwpage");file.file.copyTo(corrupt)
            RandomAccessFile(corrupt,"rw").use{it.seek(it.length()-1);val value=it.read();it.seek(it.length()-1);it.write(value xor 1)}
            val stage=File(directory,"bad-stage").also{check(it.mkdirs())}
            assertTrue(runCatching{corrupt.inputStream().use{ContentArchive.prepare(it,stage)}}.isFailure);assertFalse(stage.exists())
            val cancelled=File(directory,"cancelled-stage").also{check(it.mkdirs())}
            val input=object:FilterInputStream(file.file.inputStream()){
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int{throw CancellationException("synthetic cancellation")}
            }
            assertTrue(runCatching{input.use{ContentArchive.prepare(it,cancelled)}}.exceptionOrNull() is CancellationException)
            assertFalse(cancelled.exists());assertNotNull(db.notes().note(note.id))
        }
    }
}
