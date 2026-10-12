// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

class PageObjectStorageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking {
        val name="object-storage-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }
    private fun image(color:Int=0xff123456.toInt()):String {
        val bitmap=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        return try{Base64.getEncoder().encodeToString(ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}.toByteArray())}finally{bitmap.recycle()}
    }
    private suspend fun book(db:NoteDatabase)=WorkspaceRepository(db).create("对象分行合成验证",false,PaperStyle.BLANK).id
    private fun count(db:NoteDatabase,table:String)=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}

    @Test fun movingOneObjectWritesOneRecordAndSharedPreviewLivesUntilItsLastReference()=fixture{db->
        val page=book(db);val other=book(db);val repo=PageObjectRepository(db)
        val a=PageObject(id(),PageObjectKind.IMAGE,image=image());val b=a.copy(id=id(),x=300f)
        val command=id();repo.save(page,0,command,listOf(a,b));repo.save(other,0,id(),listOf(a.copy(id=id())))
        assertEquals(1,count(db,"object_previews"));assertEquals(3,count(db,"page_object_records"))
        assertEquals(ContentTransfer.hash(("$page:0:null:").toByteArray()+PageObjectCodec.encode(listOf(a,b))),db.objects().receipt(command)!!.digest)
        val hash=db.objects().records(page).first().previewHash!!;val original=db.objects().preview(hash)!!.payload
        val sql=db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE test_object_writes(kind TEXT NOT NULL,id TEXT NOT NULL)")
        sql.execSQL("CREATE TRIGGER test_record_write AFTER INSERT ON page_object_records BEGIN INSERT INTO test_object_writes VALUES('record',NEW.id); END")
        sql.execSQL("CREATE TRIGGER test_preview_write AFTER INSERT ON object_previews BEGIN INSERT INTO test_object_writes VALUES('preview',NEW.digest); END")
        val moved=a.copy(x=140f);val move=id();repo.save(page,1,move,listOf(moved,b))
        sql.query("SELECT kind,id FROM test_object_writes").use{assertTrue(it.moveToFirst());assertEquals("record",it.getString(0));assertEquals(a.id,it.getString(1));assertFalse(it.moveToNext())}
        assertArrayEquals(original,db.objects().preview(hash)!!.payload)
        assertEquals(2L,repo.save(page,1,move,listOf(moved,b)));assertEquals(1,count(db,"test_object_writes"))
        repo.save(page,2,id(),emptyList());assertEquals(1,count(db,"object_previews"))
        repo.save(other,1,id(),emptyList());assertEquals(0,count(db,"object_previews"));assertEquals(0,count(db,"page_object_records"))
    }

    @Test fun receiptFailureRollsBackHeadChangedRowsNewPreviewAndAuthoringTogether()=fixture{db->
        val page=book(db);val repo=PageObjectRepository(db);val a=PageObject(id(),PageObjectKind.IMAGE,image=image())
        repo.save(page,0,id(),listOf(a));val before=repo.read(page);val author=db.authoring().get("PAGE",page)!!.payload
        val old=db.objects().records(page).single();val oldPreview=db.objects().preview(old.previewHash!!)!!.payload
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_object_receipt BEFORE INSERT ON object_receipts BEGIN SELECT RAISE(ABORT,'synthetic receipt failure'); END")
        val command=id();val changed=a.copy(image=image(0xffee4433.toInt()));val text=PageObject(id(),PageObjectKind.TEXT,text="一起回滚")
        assertTrue(runCatching{repo.save(page,1,command,listOf(changed,text))}.isFailure)
        assertEquals(before,repo.read(page));assertNull(db.objects().receipt(command));assertEquals(1,count(db,"object_previews"))
        assertArrayEquals(old.payload,db.objects().records(page).single().payload);assertArrayEquals(oldPreview,db.objects().preview(old.previewHash)!!.payload)
        assertArrayEquals(author,db.authoring().get("PAGE",page)!!.payload)
        assertEquals(listOf(a.id),db.authoring().memberships(page).map{it.contentId})
    }

    @Test fun authoringIdentityProjectionSkipsCorruptObjectAndPreviewPayloadsButFullReadsReject()=fixture{db->
        val page=book(db);val repo=PageObjectRepository(db);val item=PageObject(id(),PageObjectKind.IMAGE,image=image())
        repo.save(page,0,id(),listOf(item));val before=PageAuthoringRepository(db).readPage(page)
        db.openHelper.writableDatabase.execSQL("UPDATE page_object_records SET payload=zeroblob(3000000) WHERE pageId=?",arrayOf(page))
        db.openHelper.writableDatabase.execSQL("UPDATE object_previews SET payload=zeroblob(3000000)")
        val after=PageAuthoringRepository(db).readPage(page)
        assertEquals(before.revision,after.revision);assertEquals(before.objectRevision,after.objectRevision)
        assertArrayEquals(PageAuthoringCodec.encode(before.state),PageAuthoringCodec.encode(after.state));assertTrue(runCatching{repo.read(page)}.isFailure)
        assertEquals(listOf(item.id),db.objects().ids(page))
    }

    @Test fun concurrentSnapshotsAndObserversKeepRevisionOrderMetadataAndPreviewTogether()=fixture{db->coroutineScope{
        val page=book(db);val repo=PageObjectRepository(db);val first=image();val second=image(0xffcc8844.toInt())
        val a=PageObject(id(),PageObjectKind.IMAGE,image=first);val b=PageObject(id(),PageObjectKind.TEXT,text="版次 1")
        fun objects(revision:Long)=listOf(a.copy(x=100f+revision,image=if(revision%2L==1L)first else second),b.copy(text="版次 $revision"))
            .let{if(revision%2L==1L)it else it.reversed()}
        repo.save(page,0,id(),objects(1))
        val observed=async(start=CoroutineStart.UNDISPATCHED){withTimeout(15_000){repo.observe(page).first{it.revision==40L}}}
        val writer=async(Dispatchers.IO){for(revision in 2L..40L){repo.save(page,revision-1,id(),objects(revision));yield()}}
        repeat(120){val snapshot=repo.read(page);assertEquals(objects(snapshot.revision),snapshot.objects);yield()}
        writer.await();assertEquals(ObjectSnapshot(40,objects(40)),observed.await());assertEquals(1,count(db,"object_previews"))
    }}

    @Test fun sharedPreviewRestoreMergesByContentAndAnInjectedFailureRollsBackAllNewRows()=fixture{source->
        val page=book(source);val item=PageObject(id(),PageObjectKind.IMAGE,image=image());PageObjectRepository(source).save(page,0,id(),listOf(item))
        val name="object-storage-target-${id()}.db";val target=NoteDatabase.open(context,name)
        try{
            val existing=book(target);val existingItem=item.copy(id=id());PageObjectRepository(target).save(existing,0,id(),listOf(existingItem))
            LibraryBackupRepository(context,source).snapshot().use{snapshot->
                val failing=LibraryBackupRepository(context,target){if(it==LibraryBackupRepository.BackupFault.BEFORE_RESTORE_COMMIT)error("synthetic rollback")}
                snapshot.file.inputStream().use{failing.inspect(it)}.use{preview->assertTrue(runCatching{failing.restore(preview)}.isFailure)}
                assertNull(target.notes().note(page));assertEquals(1,count(target,"object_previews"));assertEquals(1,count(target,"page_object_records"))
                val restore=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))
                    assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restore.restore(preview))
                }
            }
            assertEquals(listOf(item),PageObjectRepository(target).read(page).objects);assertEquals(listOf(existingItem),PageObjectRepository(target).read(existing).objects)
            assertEquals(1,count(target,"object_previews"));assertEquals(2,count(target,"page_object_records"))
            target.openHelper.writableDatabase.execSQL("UPDATE object_previews SET payload=?",arrayOf(byteArrayOf(-1,-40,0,1,2)))
            assertTrue(runCatching{LibraryBackupRepository(context,target).snapshot().close()}.isFailure)
        }finally{target.close();context.deleteDatabase(name)}
    }

    @Test fun deduplicatedImagesStillRespectTheExistingAggregatePageBudget()=fixture{db->
        val page=book(db);val bytes=Base64.getDecoder().decode(image())+ByteArray(100_000)
        val picture=Base64.getEncoder().encodeToString(bytes)
        val content=List(16){PageObject(id(),PageObjectKind.IMAGE,image=picture)}
        assertTrue(runCatching{PageObjectRepository(db).save(page,0,id(),content)}.isFailure)
        assertTrue(PageObjectRepository(db).read(page).objects.isEmpty());assertEquals(0,count(db,"object_previews"))
    }

    private fun legacy(name:String,seed:(android.database.sqlite.SQLiteDatabase,String)->Unit):String {
        val page=id();val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/16.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        try{
            val entities=schema.getJSONArray("entities")
            for(index in 0 until entities.length()){
                val entity=entities.getJSONObject(index);val table=entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            sql.execSQL("INSERT INTO notes VALUES (?,1,'合成旧对象','原文',1234)",arrayOf(page))
            sql.execSQL("INSERT INTO note_revisions VALUES (?,1,'合成旧对象','原文',1234)",arrayOf(page))
            sql.execSQL("INSERT INTO notebook_workspace(noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision,coverKey,selectedPageId,pinned) VALUES (?,0,0,'','',0,NULL,500,707,0,0,'auto','',0)",arrayOf(page))
            sql.execSQL("INSERT INTO notebook_pages(id,notebookId,position,world,paper,centerX,centerY,zoom,createdAfterId,trashedAt) VALUES (?,?,0,0,0,500,707,0,NULL,NULL)",arrayOf(page,page))
            seed(sql,page);sql.version=16
        }finally{sql.close()};return page
    }

    @Test fun schema16AndItsArchivePromoteObjectsWithOrderIdentityAndReceiptUnchanged()=runBlocking{
        val name="object-storage-migration-${id()}.db";val command=id();val picture=image()
        val objects=listOf(PageObject(id(),PageObjectKind.IMAGE,image=picture),PageObject(id(),PageObjectKind.TEXT,text="保留顺序"),PageObject(id(),PageObjectKind.IMAGE,image=picture))
        val payload=PageObjectCodec.encode(objects);var digest=""
        val page=legacy(name){sql,page->
            digest=ContentTransfer.hash(("$page:0:null:").toByteArray()+payload)
            sql.execSQL("INSERT INTO page_objects VALUES (?,1,?)",arrayOf(page,payload))
            sql.execSQL("INSERT INTO object_receipts VALUES (?,?,?,1)",arrayOf(command,page,digest))
        }
        val old=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        val archive=try{ByteArrayOutputStream().also{output->
            val schema=LibraryBackupRepository.SCHEMA_V16
            LibraryArchive.write(output,schema,object:LibraryArchive.Rows{
                override fun count(table:Int)=old.rawQuery("SELECT COUNT(*) FROM `${schema[table].name}`",null).use{it.moveToFirst();it.getLong(0)}
                override fun visit(table:Int,consume:(List<Any?>)->Unit){val t=schema[table]
                    old.rawQuery("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"},null).use{c->while(c.moveToNext())consume(t.columns.mapIndexed{i,col->if(c.isNull(i))null else when(col.kind){'I'->c.getLong(i);'F'->c.getDouble(i);'B'->c.getBlob(i);else->c.getString(i)}})}
                }
            },0)
        }.toByteArray()}finally{old.close()}
        val db=NoteDatabase.open(context,name)
        try{
            assertEquals(ObjectSnapshot(1,objects),PageObjectRepository(db).read(page));assertEquals(19,db.openHelper.readableDatabase.version)
            assertEquals(1,count(db,"object_previews"));assertEquals(objects.map{it.id},db.objects().ids(page));assertEquals(digest,db.objects().receipt(command)!!.digest)
            assertEquals(1L,PageObjectRepository(db).save(page,0,command,objects));assertEquals(1234L,db.notes().note(page)!!.updatedAt)
            val restore=LibraryBackupRepository(context,db)
            restore.inspect(archive.inputStream()).use{preview->assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restore.restore(preview))}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun corruptSchema16ObjectAbortsMigrationAndKeepsTheOriginalDatabaseIntact()=runBlocking{
        val name="object-storage-bad-migration-${id()}.db";val bytes=byteArrayOf(1,2,3)
        val page=legacy(name){sql,page->sql.execSQL("INSERT INTO page_objects VALUES (?,7,?)",arrayOf(page,bytes))}
        val db=NoteDatabase.open(context,name)
        try{assertTrue(runCatching{db.objects().get(page)}.isFailure)}finally{db.close()}
        val sql=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try{
            assertEquals(16,sql.version)
            sql.rawQuery("SELECT revision,payload FROM page_objects WHERE pageId=?",arrayOf(page)).use{assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0));assertArrayEquals(bytes,it.getBlob(1))}
            sql.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('page_objects_legacy','page_object_records','object_previews')",null).use{assertFalse(it.moveToFirst())}
        }finally{sql.close();context.deleteDatabase(name)}
    }
}
