package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageObjectRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun text()=PageObject(id(),PageObjectKind.TEXT,text="对象保存与备份")
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking {
        val name="objects-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun unknownCommitCanReplayAndStaleWriterCannotOverwrite()=fixture{db->
        val note=WorkspaceRepository(db).create("对象",false,PaperStyle.BLANK)
        val repo=PageObjectRepository(db);val command=id();val content=listOf(text())
        try{PageObjectRepository(db){error("after commit")}.save(note.id,0,command,content);fail()}catch(_:IllegalStateException){}
        assertEquals(1L,repo.save(note.id,0,command,content));assertEquals(content,repo.read(note.id).objects)
        try{repo.save(note.id,0,id(),emptyList());fail()}catch(_:IllegalArgumentException){}
        try{repo.save(note.id,0,command,emptyList());fail()}catch(_:IllegalArgumentException){}
        assertEquals(content,repo.read(note.id).objects)
    }
    @Test fun duplicateAndContentImportCarryIndependentObjects()=fixture{db->
        val note=WorkspaceRepository(db).create("源",false,PaperStyle.GRID)
        val repo=PageObjectRepository(db);val item=text();repo.save(note.id,0,id(),listOf(item))
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),note.id,id()))
        val copied=repo.read(copy.id).objects.single();assertNotEquals(item.id,copied.id);assertEquals(item.text,copied.text)
        repo.save(copy.id,1,id(),listOf(copied.copy(text="修改副本")))
        assertEquals(item,repo.read(note.id).objects.single())
        val exported=NotebookPages(db).exportBook(note.id);val imported=NotebookPages(db).importBook(NotebookFile.decode(exported.encode()))
        assertEquals(item.text,repo.read(imported.id).objects.single().text)
    }
    @Test fun backupRestoresObjectsAndReceipts()=fixture{db->
        val note=WorkspaceRepository(db).create("备份",false,PaperStyle.BLANK);val content=listOf(text(),PageObject(id(),PageObjectKind.TAPE,revealed=true,tapePoints=listOf(TapePoint(20f,20f),TapePoint(180f,90f)),lineWidth=30f),PageObject(id(),PageObjectKind.SHAPE,shape=ObjectShape.ARROW,lineWidth=4f))
        val command=id();PageObjectRepository(db).save(note.id,0,command,content)
        val name="restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{s->val r=LibraryBackupRepository(context,target);s.file.inputStream().use{r.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,r.restore(it))}}
            target.close();target=NoteDatabase.open(context,name)
            assertEquals(content,PageObjectRepository(target).read(note.id).objects)
            assertEquals(1L,PageObjectRepository(target).save(note.id,0,command,content))
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun invalidBoundsAndRecycledPageCannotBeWritten()=fixture{db->
        val note=WorkspaceRepository(db).create("边界",false,PaperStyle.BLANK);val repo=PageObjectRepository(db)
        try{repo.save(note.id,0,id(),listOf(text().copy(x=900f)));fail()}catch(_:IllegalArgumentException){}
        assertTrue(repo.read(note.id).objects.isEmpty())
        db.pages().arrange(note.id,0,123L)
        try{repo.save(note.id,0,id(),listOf(text()));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun originalSaveReplayCopyBackupAndMissingAssetAreAtomic()=fixture{db->
        val note=WorkspaceRepository(db).create("原图",false,PaperStyle.BLANK)
        val bitmap=android.graphics.Bitmap.createBitmap(1800,1200,android.graphics.Bitmap.Config.ARGB_8888)
        val original=try{java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{bitmap.recycle()}
        val source=ImageSource(original)
        val previewBitmap=android.graphics.Bitmap.createBitmap(90,60,android.graphics.Bitmap.Config.ARGB_8888)
        val preview=try{java.io.ByteArrayOutputStream().also{previewBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it)}.toByteArray()}finally{previewBitmap.recycle()}
        val objectId=id();val command=id()
        val item=PageObject(objectId,PageObjectKind.IMAGE,image=java.util.Base64.getEncoder().encodeToString(preview),imageSource=source.sha256)
        val repo=PageObjectRepository(db)
        try{repo.save(note.id,0,id(),listOf(item));fail()}catch(_:IllegalArgumentException){}
        assertTrue(repo.read(note.id).objects.isEmpty())
        try{PageObjectRepository(db){error("after commit")}.save(note.id,0,command,listOf(item),originals=listOf(source));fail()}catch(_:IllegalStateException){}
        assertEquals(1L,repo.save(note.id,0,command,listOf(item),originals=listOf(source)))
        assertArrayEquals(original,repo.originals(note.id,listOf(item)).single().bytes())
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),note.id,id()))
        assertArrayEquals(original,repo.originals(copy.id,repo.read(copy.id).objects).single().bytes())
        val exported=NotebookFile.decode(NotebookPages(db).exportBook(note.id).encode())
        assertArrayEquals(original,exported.pages.single().imageSources.single().bytes())
        val restored=NotebookPages(db).importBook(exported)
        assertArrayEquals(original,repo.originals(restored.id,repo.read(restored.id).objects).single().bytes())
        val targetName="original-restore-${id()}.db";val target=NoteDatabase.open(context,targetName)
        try{
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val backup=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{backup.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,backup.restore(it))}
            }
            assertArrayEquals(original,PageObjectRepository(target).originals(note.id,listOf(item)).single().bytes())
        }finally{target.close();context.deleteDatabase(targetName)}
        val prior=repo.read(note.id)
        try{repo.save(note.id,prior.revision,id(),listOf(item.copy(x=900f)),originals=listOf(source));fail()}catch(_:IllegalArgumentException){}
        assertEquals(prior,repo.read(note.id))
    }

    @Test fun schema12UpgradesWithoutRewritingExistingAuthorRows()=runBlocking{
        val name="image-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val legacy=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/12.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        val book=id()
        try{
            val entities=legacy.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            sql.execSQL("INSERT INTO notes VALUES (?,1,'保留标题','保留原文',1234)",arrayOf(book))
            sql.execSQL("INSERT INTO note_revisions VALUES (?,1,'保留标题','保留原文',1234)",arrayOf(book))
            sql.version=12
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name)
        try{
            assertEquals("保留原文",db.notes().note(book)!!.text)
            assertEquals(1234L,db.notes().note(book)!!.updatedAt)
            assertEquals(16,db.openHelper.readableDatabase.version)
            assertEquals(0L,db.images().totalBytes())
        }finally{db.close();context.deleteDatabase(name)}
    }

}
