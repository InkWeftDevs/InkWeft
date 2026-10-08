package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FormulaPersistenceTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun formulaCopyExportAndBackupRestoreRetainEditableSourceAndOwnedInk()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="formula-source-${id()}.db";val restoredName="formula-restored-${id()}.db"
        val db=NoteDatabase.open(context,name);var target=NoteDatabase.open(context,restoredName)
        try{
            val note=WorkspaceRepository(db).create("公式存储回归",false,PaperStyle.BLANK)
            val stroke=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,200f,0),InkSample(180f,260f,20)))
            InkRepository(db).save(CommitInk(id(),note.id,0,InkMutation.Add(stroke)))
            val formula=PageObject(id(),PageObjectKind.FORMULA,text="\\frac{1}{x^2}+\\alpha",sourceStrokeIds=listOf(stroke.id),erasures=listOf(TextErasePath(0,8,3f,listOf(TextErasePoint(10f,20f)))))
            val repo=PageObjectRepository(db);val command=id();repo.save(note.id,0,command,listOf(formula),expectedInk=1)
            val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),note.id,id()))
            val copied=repo.read(copy.id).objects.single();assertEquals(formula.text,copied.text);assertNotEquals(formula.id,copied.id)
            assertEquals(InkRepository(db).read(copy.id).strokes.map{it.stroke.id},copied.sourceStrokeIds);assertNotEquals(formula.sourceStrokeIds,copied.sourceStrokeIds)
            val exported=NotebookFile.decode(NotebookPages(db).exportBook(note.id).encode())
            assertEquals(formula,exported.pages.single().objects.single())
            val imported=NotebookPages(db).importBook(exported);val importedFormula=repo.read(imported.id).objects.single()
            assertEquals(formula.text,importedFormula.text);assertEquals(InkRepository(db).read(imported.id).strokes.map{it.stroke.id},importedFormula.sourceStrokeIds)
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val backup=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{backup.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,backup.restore(it))}
            }
            target.close();target=NoteDatabase.open(context,restoredName)
            assertEquals(formula,PageObjectRepository(target).read(note.id).objects.single())
            assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(InkRepository(target).read(note.id).strokes.single().stroke))
            assertEquals(1L,PageObjectRepository(target).save(note.id,0,command,listOf(formula),expectedInk=1))
        }finally{db.close();target.close();context.deleteDatabase(name);context.deleteDatabase(restoredName)}
    }
}
