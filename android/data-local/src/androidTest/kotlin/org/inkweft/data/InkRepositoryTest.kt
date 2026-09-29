// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.inkweft.core.*
import org.json.JSONObject
import java.util.UUID
import android.database.sqlite.SQLiteDatabase

@RunWith(AndroidJUnit4::class)
class InkRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: NoteDatabase
    private lateinit var name: String
    private lateinit var note: Note
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff22342f.toInt(),3f,InkTool.STYLUS,
        listOf(InkSample(10f,20f,0,.2f),InkSample(100f,120f,100,.8f)))
    @Before fun setup()=runBlocking {
        context=ApplicationProvider.getApplicationContext();name="test-${id()}.db"
        db=NoteDatabase.open(context,name)
        val c=SaveNote(id(),id(),0,"测试笔迹","原文字")
        note=(NoteRepository(db).save(c) as SaveResult.Committed).note
    }
    @After fun cleanup(){db.close();context.deleteDatabase(name)}
    @Test fun strokePersistsAcrossActualDatabaseReopen()=runBlocking {
        val s=stroke();val command=CommitInk(id(),note.id,0,InkMutation.Add(s))
        assertEquals(InkCommitResult.Committed(1),InkRepository(db).save(command))
        db.close();db=NoteDatabase.open(context,name)
        val page=InkRepository(db).read(note.id);assertEquals(1,page.revision)
        assertEquals(s.samples,page.strokes.single().stroke.samples)
        assertEquals("原文字",NoteRepository(db).read(note.id)?.text)
    }
    @Test fun sameCommandReplaysAndDifferentPayloadRejected()=runBlocking {
        val repo=InkRepository(db);val c=CommitInk(id(),note.id,0,InkMutation.Add(stroke()))
        repo.save(c);assertEquals(InkCommitResult.Committed(1),repo.save(c))
        assertEquals(InkCommitResult.Rejected,repo.save(CommitInk(c.commandId,note.id,0,InkMutation.Add(stroke()))))
        assertEquals(1,repo.read(note.id).strokes.size)
    }
    @Test fun failureBeforeReceiptRollsBackInkAndPageRevision()=runBlocking {
        val repo=InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT)throw java.io.IOException("injected")}
        val c=CommitInk(id(),note.id,0,InkMutation.Add(stroke()))
        try{repo.save(c);fail()}catch(_:java.io.IOException){}
        assertEquals(0,InkRepository(db).read(note.id).revision)
        assertNull(db.ink().receipt(c.commandId));assertTrue(db.ink().strokes(note.id).isEmpty())
    }
    @Test fun commitThenLostReceiptReconcilesByOriginalCommand()=runBlocking {
        var once=true
        val repo=InkRepository(db){if(it==InkFaultPoint.AFTER_TRANSACTION && once){once=false;throw java.io.IOException("after commit")}}
        val c=CommitInk(id(),note.id,0,InkMutation.Add(stroke()))
        try{repo.save(c);fail()}catch(_:java.io.IOException){}
        assertEquals(1,repo.read(note.id).revision)
        assertEquals(InkCommitResult.Committed(1),repo.save(c));assertEquals(1,repo.read(note.id).strokes.size)
    }
    @Test fun twoDatabaseInstancesDoNotOverwriteSameBase()=runBlocking {
        val other=NoteDatabase.open(context,name)
        try{
            val commands=listOf(CommitInk(id(),note.id,0,InkMutation.Add(stroke())),CommitInk(id(),note.id,0,InkMutation.Add(stroke())))
            val results=coroutineScope {
                listOf(async(Dispatchers.IO){InkRepository(db).save(commands[0])},async(Dispatchers.IO){InkRepository(other).save(commands[1])}).awaitAll()
            }
            assertEquals(1,results.count { it is InkCommitResult.Committed });assertEquals(1,results.count { it==InkCommitResult.Conflict })
            assertEquals(1,InkRepository(db).read(note.id).strokes.size)
        }finally{other.close()}
    }
    @Test fun eraseAndUndoRetainExactlyOriginalBytes()=runBlocking {
        val repo=InkRepository(db);val s=stroke();repo.save(CommitInk(id(),note.id,0,InkMutation.Add(s)))
        val before=db.ink().stroke(s.id)!!.payload
        repo.save(CommitInk(id(),note.id,1,InkMutation.Visibility(listOf(s.id),false)))
        assertFalse(repo.read(note.id).strokes.single().visible)
        repo.save(CommitInk(id(),note.id,2,InkMutation.Visibility(listOf(s.id),true)))
        assertArrayEquals(before,db.ink().stroke(s.id)!!.payload);assertTrue(repo.read(note.id).strokes.single().visible)
    }
    @Test fun importedPageIsNewIdentityAndDoesNotOverwriteText()=runBlocking {
        val a=stroke();val b=stroke()
        val file=InkPageFile.decode(InkPageFile("页面","可编辑文字",listOf(a,b)).encode())
        val imported=InkRepository(db).importCopy(file)
        assertNotEquals(note.id,imported.id);assertEquals("原文字",NoteRepository(db).read(note.id)?.text)
        val rows=InkRepository(db).read(imported.id).strokes
        assertEquals(2,rows.size);assertNotEquals(a.id,rows[0].stroke.id);assertEquals(a.samples,rows[0].stroke.samples)
    }
    @Test fun v1DatabaseMigratesWithoutLosingOriginalTextAndReceipts()=runBlocking {
        db.close();context.deleteDatabase(name)
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { raw ->
            val tables=schema.getJSONArray("entities")
            for(i in 0 until tables.length()) {val entity=tables.getJSONObject(i);raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))}
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            raw.execSQL("INSERT INTO notes VALUES(?,1,'v1标题','v1正文',123)",arrayOf(note.id))
            raw.execSQL("INSERT INTO note_revisions VALUES(?,1,'v1标题','v1正文',123)",arrayOf(note.id))
            raw.execSQL("INSERT INTO command_receipts VALUES('v1command',?,'digest',1)",arrayOf(note.id))
            raw.version=1
        }
        db=NoteDatabase.open(context,name)
        assertEquals("v1正文",NoteRepository(db).read(note.id)?.text)
        assertNotNull(db.notes().receipt("v1command"));assertTrue(InkRepository(db).read(note.id).strokes.isEmpty())
    }
}
