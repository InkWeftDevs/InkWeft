// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.database.sqlite.SQLiteDatabaseCorruptException
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
                                read().also { opened = true }
                            }
                        }
                    }
                }
            }
            override fun close() = synchronized(openLock) { helper.close(); opened = false }
        }
    }

    // Do not pre-initialize the shared target through another SQLite connection.
    // File existence/size checks and O_EXCL do not exclude older processes which
    // bypass our sidecar lock. A preflight PRAGMA could modify their committed DB
    // before schema checks reject it. Let Requery perform ordinary SQLite creation
    // and recovery; new DBs use its native page-size/auto-vacuum defaults. Existing
    // DBs are never reformatted or switched to INCREMENTAL by this factory.

    private companion object { val openLock = Any() }
}
