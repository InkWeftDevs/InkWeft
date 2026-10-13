// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.*
import android.os.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.*

class DatabaseCreationProcessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val possiblyActivePeerPaths = ConcurrentHashMap.newKeySet<String>()
    private fun config(path: File, create: (SupportSQLiteDatabase) -> Unit) =
        SupportSQLiteOpenHelper.Configuration.builder(context).name(path.path)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build()
    private fun verify(db: SupportSQLiteDatabase, vacuum: Int, version: Int) {
        assertEquals(1024L, db.pageSize); assertEquals(version, db.version)
        db.query("PRAGMA auto_vacuum").use { assertTrue(it.moveToFirst()); assertEquals(vacuum, it.getInt(0)) }
        db.query("SELECT id,payload FROM author").use {
            assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0))
            assertArrayEquals(DatabaseCreationPeerService.PAYLOAD, it.getBlob(1)); assertFalse(it.moveToNext())
        }
    }
    private fun cleanup(path: File) {
        if (path.path in possiblyActivePeerPaths) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "DB_PROCESS cleanup=retained-unconfirmed-peer-stop\n")
            })
            return
        }
        context.deleteDatabase(path.path)
        File(path.path + ".inkweft-open-lock").delete()
    }
    private fun path() = context.getDatabasePath("process-creation-${UUID.randomUUID()}.db").also { it.parentFile!!.mkdirs() }

    private inner class Peer : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val disconnected = CountDownLatch(1)
        private val replies = LinkedBlockingQueue<Bundle>()
        private var active = false
        private var dead = false
        private var activePath: String? = null
        private fun finished() { active = false; activePath?.let { possiblyActivePeerPaths.remove(it) } }
        private lateinit var remote: Messenger
        private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) { replies.add(Bundle(message.data)) }
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = Messenger(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { disconnected.countDown() }
        }
        init {
            assertTrue(context.bindService(Intent(context, DatabaseCreationPeerService::class.java), connection, Context.BIND_AUTO_CREATE))
            try { assertTrue("Peer bind", connected.await(30, TimeUnit.SECONDS)) }
            catch (failure: Throwable) { context.unbindService(connection); throw failure }
        }
        fun start(path: File, legacy: Boolean, hold: Boolean = false, mode: Int = 1, journal: String = "DELETE", version: Int = 1, mutateCommitted: Boolean = false) {
            active = true
            activePath = path.path; possiblyActivePeerPaths.add(path.path)
            remote.send(Message.obtain(null, 1).apply {
                replyTo = receiver
                data = Bundle().apply {
                    putString("path", path.path); putBoolean("legacy", legacy); putBoolean("hold", hold)
                    putInt("mode", mode); putString("journal", journal); putInt("version", version)
                    putBoolean("mutateCommitted", mutateCommitted)
                }
            })
            await("started")
        }
        fun await(phase: String) {
            val reply = checkNotNull(replies.poll(30, TimeUnit.SECONDS)) { "Peer timed out waiting for $phase" }
            if (reply.getString("phase") in listOf("closed", "failed")) finished()
            assertNotEquals("Must be a different process", Process.myPid(), reply.getInt("pid"))
            assertEquals("Same application UID", Process.myUid(), reply.getInt("uid"))
            assertEquals(reply.getString("failure"), phase, reply.getString("phase"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "DB_PROCESS phase=$phase parentPid=${Process.myPid()} peerPid=${reply.getInt("pid")} uid=${reply.getInt("uid")}\n")
            })
        }
        fun release() = remote.send(Message.obtain(null, 2))
        fun kill() {
            remote.send(Message.obtain(null, 3)); assertTrue("Peer death", disconnected.await(30, TimeUnit.SECONDS))
            dead = true; finished()
        }
        override fun close() {
            try {
                if (active && !dead) {
                    release()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (active && System.nanoTime() < deadline) {
                        val reply = replies.poll(1, TimeUnit.SECONDS)
                        if (reply?.getString("phase") in listOf("closed", "failed")) finished()
                    }
                    if (active) kill()
                }
            } finally { context.unbindService(connection) }
        }
    }

    @Test fun lateLegacyCreatorKeepsFullAndNoneModesAndAuthorBytes() {
        // Start the candidate first, paused at its last context path resolution.
        // This is immediately before Requery opens the shared target. The old process
        // creates the database while the candidate holds the sidecar lock: it cannot
        // accidentally cooperate with that lock or the JVM monitor.
        for (vacuum in listOf(0, 1)) for (journal in listOf("DELETE", "WAL")) {
            val path = path(); val pool = Executors.newSingleThreadExecutor()
            val beforeOpen = CountDownLatch(1); val resume = CountDownLatch(1)
            var pathReads = 0
            val gatedContext = object : ContextWrapper(context) {
                override fun getDatabasePath(name: String): File {
                    if (++pathReads == 2) { beforeOpen.countDown(); check(resume.await(30, TimeUnit.SECONDS)) }
                    return super.getDatabasePath(name)
                }
            }
            val callback = object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) { verify(db, vacuum, 0); error("Retain unversioned author database") }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }
            try {
                Peer().use { peer ->
                    val candidate = pool.submit<Throwable?> {
                        PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(gatedContext)
                            .name(path.path).callback(callback).build()).use { helper ->
                            helper.setWriteAheadLoggingEnabled(journal == "WAL")
                            runCatching { helper.writableDatabase }.exceptionOrNull()
                        }
                    }
                    assertTrue("Candidate reached open boundary", beforeOpen.await(30, TimeUnit.SECONDS))
                    peer.start(path, legacy = true, mode = vacuum, journal = journal, version = 0); peer.await("closed")
                    val bytes = path.readBytes()
                    resume.countDown()
                    assertEquals("Retain unversioned author database", candidate.get(30, TimeUnit.SECONDS)?.message)
                    // No active connection or recovery here: compare the entire main file.
                    assertArrayEquals(bytes, path.readBytes())
                    SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
                        verify(it, vacuum, 0)
                        it.query("PRAGMA journal_mode").use { cursor ->
                            assertTrue(cursor.moveToFirst()); assertEquals(journal.lowercase(), cursor.getString(0))
                        }
                    }
                }
            } finally { resume.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS)); cleanup(path) }
        }
    }

    @Test fun twoNewProcessesWaitForCommittedSchema() {
        val path = path(); val pool = Executors.newSingleThreadExecutor()
        try {
            Peer().use { peer ->
                peer.start(path, legacy = false, hold = true); peer.await("uncommitted")
                val started = CountDownLatch(1)
                val candidate = pool.submit {
                    started.countDown()
                    PreservingOpenHelperFactory().create(config(path) { error("Second process must not create again") }).use { helper ->
                        helper.setWriteAheadLoggingEnabled(true); verify(helper.writableDatabase, 1, 1)
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                try { candidate.get(150, TimeUnit.MILLISECONDS); fail("Opened an unfinished schema") } catch (_: TimeoutException) { }
                peer.release(); peer.await("closed"); candidate.get(30, TimeUnit.SECONDS)
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS)); cleanup(path) }
    }

    @Test fun killedCreatorRollsBackAndReleasesItsProcessLock() {
        val path = path()
        try {
            Peer().use { peer ->
                peer.start(path, legacy = false, hold = true); peer.await("uncommitted"); peer.kill()
            }
            PreservingOpenHelperFactory().create(config(path) { db ->
                db.query("SELECT COUNT(*) FROM sqlite_master WHERE name='author'").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.execSQL("CREATE TABLE author(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
                db.execSQL("INSERT INTO author VALUES (7,?)", arrayOf<Any>(DatabaseCreationPeerService.PAYLOAD))
            }).use { helper -> helper.setWriteAheadLoggingEnabled(true); verify(helper.writableDatabase, 1, 1) }
        } finally { cleanup(path) }
    }

    @Test fun killedLegacyWriterKeepsCommittedAuthorBytesWithWalAndRollbackJournal() {
        for (journal in listOf("DELETE", "WAL")) {
            val path = path()
            try {
                Peer().use { peer ->
                    peer.start(path, legacy = true, hold = true, journal = journal, mutateCommitted = true)
                    peer.await("uncommitted"); peer.kill()
                }
                PreservingOpenHelperFactory().create(config(path) { error("Committed schema must survive writer death") }).use { helper ->
                    helper.setWriteAheadLoggingEnabled(journal == "WAL")
                    verify(helper.writableDatabase, 1, 1)
                    assertTrue(helper.writableDatabase.isDatabaseIntegrityOk)
                }
            } finally { cleanup(path) }
        }
    }
}
