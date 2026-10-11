// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.io.File

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
            override val databaseName: String? get() = configuration.name
            override val writableDatabase: SupportSQLiteDatabase get() {
                prepareNewDatabase(configuration.context, name)
                return helper.writableDatabase
            }
            override val readableDatabase: SupportSQLiteDatabase get() {
                prepareNewDatabase(configuration.context, name)
                return helper.readableDatabase
            }
        }
    }

    private fun prepareNewDatabase(context: Context, name: String?) {
        if (name == null) return
        val target = context.getDatabasePath(name)
        if (target.exists()) return
        val directory = requireNotNull(target.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        val seed = File.createTempFile(".inkweft-empty-", ".db", directory)
        try {
            // Requery 3.49 defaults to 1024-byte pages. Persist an empty 4096-byte
            // header before it opens WAL; existing databases never enter this path.
            val options = SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .setJournalMode("DELETE").setSynchronousMode("FULL").build()
            SQLiteDatabase.openDatabase(seed, options).use { db ->
                db.pageSize = 4096
                db.version = 0
                check(db.pageSize == 4096L)
            }
            // link() publishes the completed file atomically and cannot overwrite a
            // database another helper or process created while this one was prepared.
            try { Os.link(seed.absolutePath, target.absolutePath) }
            catch (failure: ErrnoException) { if (failure.errno != OsConstants.EEXIST) throw failure }
        } finally {
            seed.delete()
        }
    }
}
