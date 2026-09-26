// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(world:Boolean)=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,
        if(world)listOf(InkSample(-2500f,6500f,0,world=true),InkSample(-2400f,6600f,10,world=true))else listOf(InkSample(30f,40f,0)),world)
    @Test fun worldInkViewportSurviveCloseReopen()=runBlocking {
        val name="a2-${id()}.db";var db=NoteDatabase.open(context,name)
        try{val ws=WorkspaceRepository(db);val n=ws.create("独立无界",true,PaperStyle.DOTS);val s=stroke(true)
            assertEquals(InkCommitResult.Committed(1),InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(s))))
            ws.saveViewport(n.id,CanvasViewport(-3000.0,6000.0,1.2));db.close();db=NoteDatabase.open(context,name)
            val row=WorkspaceRepository(db).get(n.id);assertTrue(row.world);assertEquals(-3000.0,row.centerX,0.0);assertEquals(1.2,row.zoom,0.0)
            val page=InkRepository(db).read(n.id);assertEquals(s.samples,page.strokes.single().stroke.samples)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun trashRestoreNeverDeletesInk()=runBlocking {
        val name="a2-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val ws=WorkspaceRepository(db);val n=ws.create("回收测试",false,PaperStyle.RULED);val s=stroke(false);InkRepository(db).save(CommitInk(id(),n.id,0,InkMutation.Add(s)))
            assertTrue(ws.organize(n.id,0,"数学","微积分,复习",true,true));assertNotNull(ws.get(n.id).trashedAt);assertEquals(1,InkRepository(db).read(n.id).strokes.size)
            assertTrue(ws.organize(n.id,1,"数学","微积分",true,false));assertNull(ws.get(n.id).trashedAt);assertEquals(s.id,InkRepository(db).read(n.id).strokes.single().stroke.id)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun staleOrganizationDoesNotOverwriteViewport()=runBlocking {
        val name="a2-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val ws=WorkspaceRepository(db);val n=ws.create("分类",true,PaperStyle.GRID);val v=CanvasViewport(-2200.0,10.0,2.0);ws.saveViewport(n.id,v)
            assertTrue(ws.organize(n.id,0,"一","",true,false));assertFalse(ws.organize(n.id,0,"过期","",false,true))
            val r=ws.get(n.id);assertEquals("一",r.folder);assertEquals(v.centerX,r.centerX,0.0);assertNull(r.trashedAt)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun importWorldCopyCreatesNewIdentityAndKeepsMode()=runBlocking {
        val name="a2-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val s=stroke(true);val file=InkPageFile("无界","说明",listOf(s),true,PaperStyle.GRID);val n=InkRepository(db).importCopy(InkPageFile.decode(file.encode()))
            assertTrue(WorkspaceRepository(db).get(n.id).world);val copy=InkRepository(db).read(n.id).strokes.single().stroke
            assertNotEquals(s.id,copy.id);assertEquals(s.samples,copy.samples)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun paperRejectsWorldCommandWithoutReceipt()=runBlocking {
        val name="a2-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val n=WorkspaceRepository(db).create("纸张",false,PaperStyle.BLANK);val c=CommitInk(id(),n.id,0,InkMutation.Add(stroke(true)))
            assertEquals(InkCommitResult.Rejected,InkRepository(db).save(c));assertNull(db.ink().receipt(c.commandId));assertEquals(0,InkRepository(db).read(n.id).strokes.size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun additiveMigrationPreservesOriginalRowsAndInkBytes()=runBlocking {
        val name="a2-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val source=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/2.json").bufferedReader().use{it.readText()}
        val schema=org.json.JSONObject(source).getJSONObject("database")
        val sqlite=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null)
        val noteId=id();val payload=InkStrokeCodec.encode(stroke(false))
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices=entity.optJSONArray("indices");for(j in 0 until (indices?.length()?:0))sqlite.execSQL(checkNotNull(indices).getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length())sqlite.execSQL(queries.getString(i))
            sqlite.execSQL("INSERT INTO notes VALUES(?,1,'旧笔记','正文',1)",arrayOf(noteId))
            sqlite.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(noteId))
            val s=InkStrokeCodec.decode(payload)
            sqlite.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,1,1,1)",arrayOf(s.id,noteId,payload))
            sqlite.version=2
        } finally {sqlite.close()}
        val db=NoteDatabase.open(context,name)
        try {
            val row=WorkspaceRepository(db).get(noteId);assertFalse(row.world);assertEquals(1,row.paper);assertEquals(0.0,row.zoom,0.0)
            assertEquals("旧笔记",db.notes().note(noteId)?.title)
            assertArrayEquals(payload,db.ink().strokes(noteId).single().payload)
            assertEquals(1,InkRepository(db).read(noteId).strokes.size)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
