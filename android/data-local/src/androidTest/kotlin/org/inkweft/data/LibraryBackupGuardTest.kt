// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryBackupGuardTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,NoteDatabase,String)->Unit)=runBlocking {
        val a="backup-guard-${id()}.db";val b="backup-guard-${id()}.db"
        val s=NoteDatabase.open(context,a);val t=NoteDatabase.open(context,b)
        try{val n=WorkspaceRepository(s).create("校验资料",false,PaperStyle.RULED);block(s,t,n.id)}
        finally{s.close();t.close();context.deleteDatabase(a);context.deleteDatabase(b)}
    }
    @Test fun rawOutOfRangeIntegersCannotMasqueradeAsValidRoomValues()=fixture{s,_,note->
        s.openHelper.writableDatabase.execSQL("UPDATE notebook_pages SET position=4294967296 WHERE id=?",arrayOf(note))
        try{LibraryBackupRepository(context,s).snapshot();fail("wrapped page position accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun invalidViewportAndUnknownReceiptKindAreRejected()=fixture{s,_,note->
        val sql=s.openHelper.writableDatabase
        sql.execSQL("UPDATE notebook_workspace SET zoom=-1 WHERE noteId=?",arrayOf(note))
        try{LibraryBackupRepository(context,s).snapshot();fail("invalid view accepted")}catch(_:IllegalArgumentException){}
        sql.execSQL("UPDATE notebook_workspace SET zoom=0 WHERE noteId=?",arrayOf(note))
        sql.execSQL("INSERT INTO library_content_receipts VALUES(?,'UNSUPPORTED',?,?)",arrayOf(id(),"0".repeat(64),note))
        try{LibraryBackupRepository(context,s).snapshot();fail("invalid receipt accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun unrelatedCommandIdCollisionRollsBackRestoredLibrary()=fixture{s,t,note->
        val command=id();NoteRepository(s).save(SaveNote(command,note,1,"校验资料","正文"))
        val other=WorkspaceRepository(t).create("现有独立资料",false,PaperStyle.GRID)
        t.openHelper.writableDatabase.execSQL("INSERT INTO command_receipts VALUES(?,?,?,1)",arrayOf(command,other.id,"0".repeat(64)))
        val r=LibraryBackupRepository(context,t)
        LibraryBackupRepository(context,s).snapshot().use{f->f.file.inputStream().use{r.inspect(it)}.use{p->
            try{r.restore(p);fail("colliding history accepted")}catch(_:IllegalArgumentException){}
            assertNull(t.notes().note(note));assertEquals(1,t.notes().observeNotes().first().size);assertNotNull(t.notes().note(other.id))
        }}
    }
    @Test fun extraTableWhoseNameResemblesSQLitePrefixIsNotIgnored()=fixture{s,_,_->
        s.openHelper.writableDatabase.execSQL("CREATE TABLE sqliteXauthor_data(id TEXT)")
        try{LibraryBackupRepository(context,s).snapshot();fail("uncovered table ignored")}catch(_:IllegalArgumentException){}
    }
}
