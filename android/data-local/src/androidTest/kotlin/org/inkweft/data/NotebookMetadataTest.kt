// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotebookMetadataTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,listOf(InkSample(30f,40f,0),InkSample(50f,90f,20)))
    @Test fun titleAndCoverRoundTripPreserveBodyInkIdentityAndOrganization()=runBlocking{
        val name="metadata-${id()}.db";var db=NoteDatabase.open(context,name)
        try{
            var ws=WorkspaceRepository(db);val n=ws.create("原始名称",false,PaperStyle.GRID,NotebookCover.INK)
            val content=NoteRepository(db).save(SaveNote(id(),n.id,1,n.title,"已经保存的正文")) as SaveResult.Committed
            val ink=stroke();InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(ink)))
            val raw=db.ink().strokes(n.id).single().payload.copyOf()
            assertTrue(ws.organize(n.id,0,"数学","考研",true,false));ws.saveViewport(n.id,CanvasViewport(400.0,500.0,1.2))
            val renamed=NoteRepository(db).rename(RenameNote(id(),n.id,content.note.revision,"新名称")) as SaveResult.Committed
            assertEquals(n.id,renamed.note.id);assertEquals("已经保存的正文",renamed.note.text)
            assertTrue(ws.changeCover(n.id,1,NotebookCover.WAVE));db.close();db=NoteDatabase.open(context,name);ws=WorkspaceRepository(db)
            assertEquals("新名称",NoteRepository(db).read(n.id)?.title)
            assertArrayEquals(raw,db.ink().strokes(n.id).single().payload)
            val r=ws.get(n.id);assertEquals("wave",r.coverKey);assertEquals("数学",r.folder);assertEquals("考研",r.tags);assertTrue(r.favorite);assertEquals(1.2,r.zoom,0.0);assertEquals(PaperStyle.GRID.ordinal,r.paper)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun duplicateRenameReplaysWithoutNewRevisionAndChangedPayloadRejects()=runBlocking{
        val name="rename-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("旧",false,PaperStyle.BLANK);val repo=NoteRepository(db);val c=RenameNote(id(),n.id,1,"新")
            val result=repo.rename(c);assertEquals(result,repo.rename(c));assertEquals(2L,repo.read(n.id)?.revision)
            assertEquals(SaveResult.ReusedCommandId,repo.rename(c.copy(title="不同载荷")));assertEquals("新",repo.read(n.id)?.title)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun staleTitleDoesNotOverwriteNewBody()=runBlocking{
        val name="stale-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("旧",false,PaperStyle.BLANK);val repo=NoteRepository(db)
            repo.save(SaveNote(id(),n.id,1,"另一标题","新的正文"));val c=RenameNote(id(),n.id,1,"过期改名")
            assertTrue(repo.rename(c) is SaveResult.Conflict);assertEquals("新的正文",repo.read(n.id)?.text);assertNull(db.notes().receipt(c.commandId))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun renameFailureBeforeReceiptRollsBackTitleAndHistory()=runBlocking{
        val name="fault-before-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("原文",false,PaperStyle.BLANK);val c=RenameNote(id(),n.id,1,"错误名称")
            try{NoteRepository(db){if(it==NoteFaultPoint.BEFORE_RENAME_RECEIPT)error("synthetic")}.rename(c);fail("expected fault")}catch(_:IllegalStateException){}
            assertEquals(n,NoteRepository(db).read(n.id));assertNull(db.notes().revision(n.id,2));assertNull(db.notes().receipt(c.commandId))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun afterCommitRetryUsesOriginalReceiptAndNeverOverwritesLaterHead()=runBlocking{
        val name="fault-after-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("旧",false,PaperStyle.BLANK);val c=RenameNote(id(),n.id,1,"第一次")
            try{NoteRepository(db){if(it==NoteFaultPoint.AFTER_RENAME_TRANSACTION)error("synthetic")}.rename(c);fail("expected fault")}catch(_:IllegalStateException){}
            val repo=NoteRepository(db);assertEquals("第一次",repo.read(n.id)?.title);repo.rename(RenameNote(id(),n.id,2,"第二次"))
            val replay=repo.rename(c) as SaveResult.Committed;assertEquals("第一次",replay.note.title);assertEquals("第二次",repo.read(n.id)?.title);assertEquals(3L,repo.read(n.id)?.revision)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun coverConflictAndTrashRestoreDoNotEraseChoice()=runBlocking{
        val name="cover-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val ws=WorkspaceRepository(db);val n=ws.create("封面",true,PaperStyle.DOTS)
            assertTrue(ws.changeCover(n.id,0,NotebookCover.ROSE));assertFalse(ws.changeCover(n.id,0,NotebookCover.INK));assertTrue(ws.changeCover(n.id,0,NotebookCover.ROSE))
            assertTrue(ws.organize(n.id,1,"","",false,true));assertFalse(ws.changeCover(n.id,2,NotebookCover.INK))
            assertTrue(ws.organize(n.id,2,"","",false,false));assertEquals("rose",ws.get(n.id).coverKey);assertEquals(0,InkRepository(db).read(n.id).strokes.size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun migration3To4PreservesAllOriginalInkBytesAndReferences()=runBlocking{
        val name="migration-cover-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/3.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null);val note=id();val ink=stroke();val raw=InkStrokeCodec.encode(ink)
        try{
            val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val e=entities.getJSONObject(i);sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices");for(j in 0 until (indices?.length()?:0))sql.execSQL(checkNotNull(indices).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))}
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES (?,1,'历史笔记','历史正文',1)",arrayOf(note));sql.execSQL("INSERT INTO ink_pages VALUES (?,1)",arrayOf(note))
            sql.execSQL("INSERT INTO ink_strokes VALUES (?,?,?,2,1,1)",arrayOf(ink.id,note,raw))
            sql.execSQL("INSERT INTO notebook_workspace (noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision) VALUES (?,0,1,'数学','复习',1,NULL,400,600,1.25,7)",arrayOf(note));sql.version=3
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name)
        try{val r=WorkspaceRepository(db).get(note);assertEquals("auto",r.coverKey);assertEquals(7L,r.revision);assertEquals("数学",r.folder);assertTrue(r.favorite);assertEquals(1.25,r.zoom,0.0);assertEquals("历史正文",NoteRepository(db).read(note)?.text);assertArrayEquals(raw,db.ink().strokes(note).single().payload);assertEquals(ink.id,InkRepository(db).read(note).strokes.single().stroke.id)}
        finally{db.close();context.deleteDatabase(name)}
    }
}
