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

class BundledSqliteFactoryTest {
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
                assertEquals(1024L, db.pageSize)
                db.execSQL("INSERT INTO authors VALUES (7,?)", arrayOf<Any>(bytes))
            }
            PreservingOpenHelperFactory().create(config).use { helper ->
                helper.setWriteAheadLoggingEnabled(true)
                val db = helper.writableDatabase
                assertEquals(1024L, db.pageSize)
                assertTrue(db.isWriteAheadLoggingEnabled)
                db.query("SELECT id,payload FROM authors").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(7, cursor.getInt(0))
                    assertArrayEquals(bytes, cursor.getBlob(1)); assertFalse(cursor.moveToNext())
                }
            }
            assertEquals(seedsBefore, seeds())
        } finally {
            context.deleteDatabase(name)
        }
    }
}
