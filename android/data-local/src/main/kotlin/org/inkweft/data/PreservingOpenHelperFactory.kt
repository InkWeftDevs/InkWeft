// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile

/** Never let the framework's default corruption handler delete an author DB. */
internal class PreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                original.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                throw SQLiteDatabaseCorruptException("Database corruption reported; original files retained")
            }
        }
        // Requery uses Context.getDatabasePath(name) and does not read the no-backup flag.
        // An absolute name preserves the platform factory's directory choice.
        val name = if (configuration.useNoBackupDirectory && configuration.name != null)
            File(configuration.context.noBackupFilesDir, configuration.name).absolutePath else configuration.name
        val config = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(name)
            .callback(callback)
            .allowDataLossOnRecovery(false)
            .build()
        val helper = RequerySQLiteOpenHelperFactory().create(config)
        return object : SupportSQLiteOpenHelper by helper {
            @Volatile private var opened = false
            override val databaseName: String? get() = configuration.name
            override val writableDatabase: SupportSQLiteDatabase get() = open(true)
            override val readableDatabase: SupportSQLiteDatabase get() = open(false)
            private fun open(writable: Boolean): SupportSQLiteDatabase {
                fun read() = if (writable) helper.writableDatabase else helper.readableDatabase
                if (opened) return read()
                return synchronized(openLock) {
                    if (opened || name == null) read().also { opened = true }
                    else {
                        val target = configuration.context.getDatabasePath(name).canonicalFile
                        val directory = requireNotNull(target.parentFile)
                        check(directory.mkdirs() || directory.isDirectory)
                        // Every helper takes this stable lock, including when the target
                        // already exists. Never unlink it: another process may be waiting
                        // on its inode. The JVM lock prevents overlapping local file locks.
                        RandomAccessFile(File(directory, target.name + ".inkweft-open-lock"), "rw").use { lock ->
                            lock.channel.lock().use {
                                prepareNewDatabase(target)
                                read().also { opened = true }
                            }
                        }
                    }
                }
            }
            override fun close() = synchronized(openLock) { helper.close(); opened = false }
        }
    }

    private fun prepareNewDatabase(target: File) {
        // Nonempty files and recovery sidecars belong to SQLite's existing recovery
        // path. A lone zero-byte file contains no author data and can be an interrupted
        // exclusive creation; all cooperating openers are still behind the file lock.
        val sidecars = listOf("-wal", "-journal", "-shm").map { File(target.path + it) }
        if (target.length() != 0L || sidecars.any { it.exists() }) return
        try { Os.close(Os.open(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR)) }
        catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw failure
            if (target.length() != 0L || sidecars.any { it.exists() }) return
        }
        // Requery 3.49 writes its 1024-byte header before onConfigure. The platform
        // connection can configure an empty file before any schema/WAL writes, without
        // SELinux-forbidden hard links or a rename that could replace another creator.
        val options = SQLiteDatabase.OpenParams.Builder()
            .addOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("FULL")
            .setErrorHandler { throw SQLiteDatabaseCorruptException("Database corruption reported; original files retained") }
            .build()
        SQLiteDatabase.openDatabase(target, options).use { db ->
            db.pageSize = 4096
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
            db.beginTransaction()
            try {
                // Never reset another creator's version or schema. Parameters above
                // must precede BEGIN; SQLite fixes an empty database's page format then.
                check(db.version == 0 && db.rawQuery("SELECT COUNT(*) FROM sqlite_master", null).use { it.moveToFirst(); it.getLong(0) == 0L })
                db.version = 0
                check(db.pageSize == 4096L)
                check(db.rawQuery("PRAGMA auto_vacuum", null).use { it.moveToFirst(); it.getInt(0) == 2 })
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        // On failure retain the database and every journal; SQLite handles recovery.
    }

    private companion object { val openLock = Any() }
}
