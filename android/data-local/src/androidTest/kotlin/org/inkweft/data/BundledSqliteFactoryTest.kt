// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class BundledSqliteFactoryTest {
    private fun mode(db: SupportSQLiteDatabase) = db.query("PRAGMA auto_vacuum").use { it.moveToFirst(); it.getInt(0) }
    private fun lockFile(path: File) = File(path.canonicalPath + ".inkweft-open-lock")
    @Test fun nativeRtreeTransactionsReopenAndCorruptionKeepOriginalFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bundled-rtree-${UUID.randomUUID()}.db"
        val path = File(context.noBackupFilesDir, name)
        fun seeds() = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith(".inkweft-empty-") }.map { it.name }.toSet()
        val seedsBefore = seeds()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE bounds USING rtree(id,minX,maxX,minY,maxY)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            override fun onCorruption(db: SupportSQLiteDatabase) = error("Preserving factory must intercept corruption")
        }
        fun open() = PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(callback).noBackupDirectory(true).build()).also { it.setWriteAheadLoggingEnabled(true) }
        try {
            open().use { helper ->
                assertEquals(name, helper.databaseName)
                val sql = helper.writableDatabase
                assertEquals(path.canonicalPath, File(sql.path).canonicalPath)
                assertFalse(context.getDatabasePath(name).exists())
                assertEquals(4096L, sql.pageSize)
                assertEquals(2, mode(sql))
                assertTrue(sql.isWriteAheadLoggingEnabled)
                sql.execSQL("INSERT INTO bounds VALUES (1,10,20,30,40)")
                sql.beginTransaction()
                try { sql.execSQL("INSERT INTO bounds VALUES (2,100,200,300,400)") }
                finally { sql.endTransaction() }
                sql.query("SELECT id FROM bounds WHERE maxX>=15 AND minX<=15 AND maxY>=35 AND minY<=35").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L,it.getLong(0)); assertFalse(it.moveToNext())
                }
            }
            open().use { helper ->
                helper.writableDatabase.query("SELECT id FROM bounds ORDER BY id").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L,it.getLong(0)); assertFalse(it.moveToNext())
                }
            }
            val corrupted = path.readBytes().also { bytes -> "corrupt-database".toByteArray().copyInto(bytes) }
            path.writeBytes(corrupted)
            open().use { helper ->
                val failure = runCatching { helper.writableDatabase }.exceptionOrNull()
                assertTrue(failure is SQLiteDatabaseCorruptException)
                assertTrue(failure?.message.orEmpty().contains("original files retained"))
            }
            assertTrue(path.exists()); assertArrayEquals(corrupted,path.readBytes())
            assertEquals(seedsBefore, seeds())
        } finally {
            context.deleteDatabase(path.absolutePath)
            lockFile(path).delete()
        }
    }

    @Test fun existing1024ByteDatabaseRetainsPageSizeAndAuthorContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bundled-existing-${UUID.randomUUID()}.db"
        fun seeds() = context.getDatabasePath(name).parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(".inkweft-empty-") }.map { it.name }.toSet()
        val seedsBefore = seeds()
        val bytes = ByteArray(512 * 1024) { (it * 31 + (it ushr 8)).toByte() }
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE authors(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        try {
            RequerySQLiteOpenHelperFactory().create(config).use { helper ->
                helper.setWriteAheadLoggingEnabled(true)
                val db = helper.writableDatabase
                // The bundled SQLite defaults to FULL auto-vacuum. Explicitly build
                // the legacy NONE fixture before testing that opening preserves it.
                db.execSQL("PRAGMA auto_vacuum=NONE")
                db.execSQL("VACUUM")
                assertEquals(1024L, db.pageSize)
                assertEquals(0, mode(db))
                db.execSQL("INSERT INTO authors VALUES (7,?)", arrayOf<Any>(bytes))
            }
            PreservingOpenHelperFactory().create(config).use { helper ->
                helper.setWriteAheadLoggingEnabled(true)
                val db = helper.writableDatabase
                assertEquals(1024L, db.pageSize)
                assertEquals(0, mode(db))
                assertTrue(db.isWriteAheadLoggingEnabled)
                db.query("SELECT id,payload FROM authors").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(7, cursor.getInt(0))
                    assertArrayEquals(bytes, cursor.getBlob(1)); assertFalse(cursor.moveToNext())
                }
            }
            assertEquals(seedsBefore, seeds())
        } finally {
            context.deleteDatabase(name)
            lockFile(context.getDatabasePath(name)).delete()
        }
    }

    @Test fun independentHelpersWaitForCompleteCreationAndKeepTheSameAuthorRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bundled-concurrent-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val creations = AtomicInteger(); val pool = Executors.newFixedThreadPool(2)
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                creations.incrementAndGet()
                db.execSQL("CREATE TABLE author(value TEXT NOT NULL)")
                db.execSQL("INSERT INTO author VALUES ('complete author row')")
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
        }
        fun openAndRead(): String {
            val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
            return PreservingOpenHelperFactory().create(config).use { helper ->
                helper.setWriteAheadLoggingEnabled(true)
                val db = helper.writableDatabase
                assertEquals(4096L, db.pageSize); assertEquals(2, mode(db))
                db.query("SELECT value FROM author").use { assertTrue(it.moveToFirst()); val value = it.getString(0); assertFalse(it.moveToNext()); value }
            }
        }
        try {
            val first = pool.submit<String> { openAndRead() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = pool.submit<String> { secondStarted.countDown(); openAndRead() }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            try { second.get(150, TimeUnit.MILLISECONDS); fail("Second helper observed unfinished creation") }
            catch (_: TimeoutException) { }
            release.countDown()
            assertEquals("complete author row", first.get(10, TimeUnit.SECONDS))
            assertEquals("complete author row", second.get(10, TimeUnit.SECONDS))
            assertEquals(1, creations.get())
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            context.deleteDatabase(name); lockFile(path).delete()
        }
    }

    @Test fun emptyInterruptedCreationAndFailedSchemaCreationCanResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bundled-interrupted-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val attempts = AtomicInteger()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE author(value TEXT NOT NULL)")
                db.execSQL("INSERT INTO author VALUES ('resumed author row')")
                if (attempts.incrementAndGet() == 1) error("synthetic schema interruption")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
        }
        fun open() = PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build())
            .also { it.setWriteAheadLoggingEnabled(true) }
        try {
            // Models death after O_EXCL but before SQLite has committed its first header.
            path.parentFile!!.mkdirs(); assertTrue(path.createNewFile()); assertEquals(0L, path.length())
            open().use { helper -> assertEquals("synthetic schema interruption", runCatching { helper.writableDatabase }.exceptionOrNull()?.message) }
            assertTrue(path.exists()); assertTrue(path.length() > 0L)
            open().use { helper ->
                val db = helper.writableDatabase
                assertEquals(4096L, db.pageSize); assertEquals(2, mode(db)); assertEquals(1, db.version)
                db.query("SELECT value FROM author").use { assertTrue(it.moveToFirst()); assertEquals("resumed author row", it.getString(0)); assertFalse(it.moveToNext()) }
            }
            assertEquals(2, attempts.get())
        } finally { context.deleteDatabase(name); lockFile(path).delete() }
    }

    @Test fun nonemptyUnversionedDatabaseNeverEntersEmptyInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bundled-unversioned-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val bytes = byteArrayOf(7, 11, 19, 23)
        try {
            path.parentFile!!.mkdirs()
            io.requery.android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("PRAGMA auto_vacuum=NONE")
                db.execSQL("VACUUM")
                db.execSQL("CREATE TABLE author(payload BLOB NOT NULL)")
                db.execSQL("INSERT INTO author VALUES (?)", arrayOf<Any>(bytes)); assertEquals(0, db.version)
            }
            val original = path.readBytes()
            val callback = object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    assertEquals(1024L, db.pageSize); assertEquals(0, mode(db))
                    db.query("SELECT payload FROM author").use { assertTrue(it.moveToFirst()); assertArrayEquals(bytes, it.getBlob(0)) }
                    error("Existing unversioned database needs explicit recovery")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }
            PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()).use { helper ->
                assertEquals("Existing unversioned database needs explicit recovery", runCatching { helper.writableDatabase }.exceptionOrNull()?.message)
            }
            assertArrayEquals(original, path.readBytes())
        } finally { context.deleteDatabase(name); lockFile(path).delete() }
    }
}
