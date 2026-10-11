// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class PageLayerStorageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun ink()=InkStroke(id(),InkPen.PEN,0xff223344.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(60f,80f,20)))
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking {
        val name="layer-storage-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db)}finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun invalidScopeOnTheFirstPenCannotCommitAnyImplicitAuthorState()=fixture{db->
        val page=WorkspaceRepository(db).create("合成首次错误图层",false,PaperStyle.BLANK).id
        val command=id();val stroke=ink();val repo=InkRepository(db)
        assertNull(db.ink().page(page));assertNull(db.authoring().get("PAGE",page))
        assertEquals(InkCommitResult.Conflict,repo.save(CommitInk(command,page,0,InkMutation.Add(stroke),LayerWriteScope(id(),0))))
        assertNull(db.ink().page(page));assertNull(db.ink().stroke(stroke.id));assertNull(db.ink().receipt(command))
        assertNull(db.authoring().get("PAGE",page));assertEquals(0L,db.authoring().membershipCount(page))
        assertEquals(InkCommitResult.Committed(1),repo.save(CommitInk(id(),page,0,InkMutation.Add(stroke))))
        assertEquals(1L,db.authoring().membershipCount(page))
    }

    @Test fun largeHistoricalOwnershipWritesOnlyTheNewMemberAndPendingStateExceedsTheOldFileLimit()=fixture{db->
        val page=WorkspaceRepository(db).create("合成大归属历史",false,PaperStyle.BLANK).id
        val repo=InkRepository(db);val original=ink()
        assertEquals(InkCommitResult.Committed(1),repo.save(CommitInk(id(),page,0,InkMutation.Add(original))))
        val meta=PageAuthoringRepository(db);val first=meta.readPage(page)
        // Removed objects may leave ownership history even after their payload rows are gone.
        val history=List(60_000){LayerContent(LayerContentKind.OBJECT,UUID(11,it+1L).toString())}
        val state=first.state.withLayers(first.state.layers.assignNew(history))
        meta.storePage(page,page,0,state)
        val head=db.authoring().get("PAGE",page)!!.payload
        assertTrue(head.size<1024);assertTrue(PageAuthoringCodec.encode(state).size>PageAuthoringCodec.LEGACY_MAX_BYTES)
        val sql=db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE test_layer_writes(id TEXT NOT NULL)")
        sql.execSQL("CREATE TRIGGER count_layer_insert AFTER INSERT ON page_layer_memberships BEGIN INSERT INTO test_layer_writes VALUES(new.contentId); END")
        sql.execSQL("CREATE TRIGGER count_layer_delete AFTER DELETE ON page_layer_memberships BEGIN INSERT INTO test_layer_writes VALUES(old.contentId); END")
        sql.execSQL("CREATE TRIGGER count_layer_update AFTER UPDATE ON page_layer_memberships BEGIN INSERT INTO test_layer_writes VALUES(new.contentId); END")
        val added=ink();assertEquals(InkCommitResult.Committed(2),repo.save(CommitInk(id(),page,1,InkMutation.Add(added))))
        sql.query("SELECT id FROM test_layer_writes").use{assertTrue(it.moveToFirst());assertEquals(added.id,it.getString(0));assertFalse(it.moveToNext())}
        assertArrayEquals(head,db.authoring().get("PAGE",page)!!.payload)
        val failed=ink();val command=id()
        try{InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT)error("synthetic receipt failure")}.save(CommitInk(command,page,2,InkMutation.Add(failed)));fail()}catch(_:IllegalStateException){}
        assertNull(db.ink().stroke(failed.id));assertNull(db.ink().receipt(command));assertEquals(60_002L,db.authoring().membershipCount(page))
        assertTrue(db.authoring().memberships(page,"INK",listOf(failed.id)).isEmpty())
        val before=meta.readPage(page);val after=before.state.withLayers(before.state.layers.update(UserLayer(UserLayers.DEFAULT_ID,"改名保留历史")))
        val pending=AuthoringPending(AuthoringScope.page(page,page),id(),before,after)
        try{
            meta.stagePending(pending)
            val file=java.io.File(db.checkpointRoot,"authoring/PAGE-$page.iwpending")
            assertTrue(file.length()>4_000_000)
            val restored=checkNotNull(meta.pending(pending.scope))
            assertArrayEquals(pending.afterPayload(),restored.afterPayload())
            assertEquals(before.state.layers.memberships.size,restored.before.state.layers.memberships.size)
        }finally{meta.clearPending(pending.scope,pending.commandId)}
    }

    @Test fun editableCopyAndBackupPreserveHiddenAndDeletedExternalMemberships()=fixture{db->
        val page=WorkspaceRepository(db).create("合成归属复制",false,PaperStyle.BLANK).id;val strokes=List(3){ink()}
        InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Replace(emptyList(),strokes)))
        val meta=PageAuthoringRepository(db);val before=meta.readPage(page);val hidden=UserLayer(id(),"隐藏原迹",visible=false)
        val refs=strokes.map{LayerContent(LayerContentKind.INK,it.id)}
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),hidden),UserLayers.DEFAULT_ID,
            listOf(LayerMembership(refs[0],UserLayers.DEFAULT_ID),LayerMembership(refs[1],hidden.id)),listOf(refs[2])))
        meta.save(AuthoringScope.page(page,page),before,id(),state)
        val copied=LibraryContentRepository(db).duplicate(CopyNotebook(id(),page,id()))
        val copy=meta.readPage(copied.id).state
        assertEquals(2,copy.layers.memberships.size);assertEquals(1,copy.layers.deleted.size)
        assertEquals(1,copy.layers.memberships.count{!copy.layers.visible(it.content)})
        assertEquals(3,InkRepository(db).read(copied.id).strokes.size)
        assertTrue(copy.layers.memberships.none{it.content in refs}&&copy.layers.deleted.none{it in refs})
        val name="layer-storage-restore-${id()}.db";val target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val restore=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))
                    assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restore.restore(preview))
                }
            }
            assertArrayEquals(PageAuthoringCodec.encode(state),PageAuthoringCodec.encode(PageAuthoringRepository(target).readPage(page).state))
            assertEquals(3L,target.authoring().membershipCount(copied.id))
        }finally{target.close();context.deleteDatabase(name)}
    }

    @Test fun schema17AndItsArchiveSplitOwnershipWithoutChangingAuthorReceipts()=runBlocking {
        val name="layer-schema17-${id()}.db";val page=id();val command=id();val strokes=List(3){ink()}
        val hidden=UserLayer(id(),"隐藏层",visible=false)
        val refs=strokes.map{LayerContent(LayerContentKind.INK,it.id)}
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),hidden),UserLayers.DEFAULT_ID,
            listOf(LayerMembership(refs[0],UserLayers.DEFAULT_ID),LayerMembership(refs[1],hidden.id)),listOf(refs[2])))
        val scope=AuthoringScope.page(page,page);val before=AuthoringSnapshot(6,PageAuthoring(),1,0)
        val payload=PageAuthoringCodec.encode(state)
        val digest=ContentTransfer.hash("$scope:6:1:0:null:".toByteArray()+payload)
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val old=SQLiteDatabase.openOrCreateDatabase(path,null)
        val archive=try{
            val schema=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/17.json").bufferedReader().use{it.readText()}).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(index in 0 until entities.length()){
                val entity=entities.getJSONObject(index);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO notes VALUES (?,1,'合成旧图层','原文',1234)",arrayOf(page))
            old.execSQL("INSERT INTO note_revisions VALUES (?,1,'合成旧图层','原文',1234)",arrayOf(page))
            old.execSQL("INSERT INTO notebook_workspace(noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision,coverKey,selectedPageId,pinned) VALUES (?,0,0,'','',0,NULL,500,707,0,0,'auto','',0)",arrayOf(page))
            old.execSQL("INSERT INTO notebook_pages(id,notebookId,position,world,paper,centerX,centerY,zoom,createdAfterId,trashedAt) VALUES (?,?,0,0,0,500,707,0,NULL,NULL)",arrayOf(page,page))
            old.execSQL("INSERT INTO ink_pages VALUES (?,1)",arrayOf(page))
            strokes.forEach{old.execSQL("INSERT INTO ink_strokes VALUES (?,?,?,?,1,1)",arrayOf(it.id,page,InkStrokeCodec.encode(it),it.samples.size))}
            old.execSQL("INSERT INTO canvas_authoring VALUES ('PAGE',?,?,7,?)",arrayOf(page,page,payload))
            old.execSQL("INSERT INTO authoring_receipts VALUES (?,?,'PAGE',?,?,7)",arrayOf(command,page,page,digest));old.version=17
            archive(old,LibraryBackupRepository.SCHEMA_V17)
        }finally{old.close()}
        val migrated=NoteDatabase.open(context,name)
        val targetName="layer-schema17-restore-${id()}.db";val target=NoteDatabase.open(context,targetName)
        try{
            val meta=PageAuthoringRepository(migrated);val restored=meta.readPage(page)
            assertEquals(7L,restored.revision);assertEquals(3L,migrated.authoring().membershipCount(page))
            assertTrue(PageAuthoringCodec.isHeader(migrated.authoring().get("PAGE",page)!!.payload))
            assertArrayEquals(payload,PageAuthoringCodec.encode(restored.state))
            assertEquals(digest,migrated.authoring().receipt(command)!!.digest)
            assertEquals(7L,meta.save(scope,before,command,state))
            val restore=LibraryBackupRepository(context,target)
            restore.inspect(archive.inputStream()).use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(it))}
            assertArrayEquals(payload,PageAuthoringCodec.encode(PageAuthoringRepository(target).readPage(page).state))
            assertEquals(3L,target.authoring().membershipCount(page));assertEquals(7L,PageAuthoringRepository(target).save(scope,before,command,state))
        }finally{migrated.close();target.close();context.deleteDatabase(name);context.deleteDatabase(targetName)}
    }

    private fun archive(sql:SQLiteDatabase,schema:List<LibraryArchive.Table>)=ByteArrayOutputStream().also{output->
        LibraryArchive.write(output,schema,object:LibraryArchive.Rows{
            override fun count(table:Int)=sql.rawQuery("SELECT COUNT(*) FROM `${schema[table].name}`",null).use{it.moveToFirst();it.getLong(0)}
            override fun visit(table:Int,consume:(List<Any?>)->Unit){val t=schema[table]
                sql.rawQuery("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`${it}`"},null).use{c->
                    while(c.moveToNext())consume(t.columns.mapIndexed{i,col->if(c.isNull(i))null else when(col.kind){'I'->c.getLong(i);'F'->c.getDouble(i);'B'->c.getBlob(i);else->c.getString(i)}})
                }
            }
        },0)
    }.toByteArray()
}
