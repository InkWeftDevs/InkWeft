// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Instrumented SQLite tests: compile != execute; must run on emulator/device. */
@RunWith(AndroidJUnit4::class)
class NoteRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "inkweft-test-${UUID.randomUUID()}.db"
    private lateinit var db: NoteDatabase
    private lateinit var repo: NoteRepository

    @Before fun open() { db = NoteDatabase.open(context, name); repo = NoteRepository(db) }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    private fun initial() = SaveNote(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0, "测试", "正文")

    @Test fun sameCommandReturnsSameCommittedRevision() = runBlocking {
        val c = initial()
        assertEquals(repo.save(c), repo.save(c))
        assertEquals(1L, repo.read(c.noteId)!!.revision)
        assertEquals(SaveResult.ReusedCommandId, repo.save(c.copy(text = "改变了")))
    }
    @Test fun staleEditorCannotOverwriteNewHead() = runBlocking {
        val c = initial(); repo.save(c)
        repo.save(c.copy(commandId = UUID.randomUUID().toString(), expectedRevision = 1, text = "先提交"))
        val stale = c.copy(commandId = UUID.randomUUID().toString(), expectedRevision = 1, text = "旧草稿")
        assertTrue(repo.save(stale) is SaveResult.Conflict)
        assertEquals("先提交", repo.read(c.noteId)!!.text)
    }
    @Test fun blankTextAndHistorySurviveReopen() = runBlocking {
        val c = initial(); repo.save(c)
        val blank = c.copy(commandId = UUID.randomUUID().toString(), expectedRevision = 1, text = "")
        repo.save(blank)
        db.close(); open()
        assertEquals("", repo.read(c.noteId)!!.text)
        assertEquals("正文", db.notes().revision(c.noteId, 1)!!.text)
        assertEquals(2L, (repo.save(blank) as SaveResult.Committed).note.revision)
    }
    @Test fun oldReceiptIsNotReplacedWithCurrentHead() = runBlocking {
        val c = initial(); val old = repo.save(c)
        repo.save(c.copy(commandId = UUID.randomUUID().toString(), expectedRevision = 1, text = "新版"))
        assertEquals(old, repo.save(c))
    }
}
