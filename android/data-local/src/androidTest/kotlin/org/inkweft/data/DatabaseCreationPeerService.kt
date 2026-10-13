// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.app.Service
import android.content.Intent
import android.os.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.requery.android.database.sqlite.SQLiteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Test APK only: the legacy branch deliberately never uses the sidecar lock. */
class DatabaseCreationPeerService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var release = CountDownLatch(0)
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                2 -> release.countDown()
                3 -> Process.killProcess(Process.myPid())
                1 -> {
                    val args = Bundle(message.data)
                    val recipient = message.replyTo
                    release = CountDownLatch(if (args.getBoolean("hold")) 1 else 0)
                    worker.execute {
                        fun reply(phase: String, failure: Throwable? = null) {
                            recipient.send(Message.obtain().apply {
                                data = Bundle().apply {
                                    putString("phase", phase); putInt("pid", Process.myPid()); putInt("uid", Process.myUid())
                                    failure?.let { putString("failure", it.toString()) }
                                }
                            })
                        }
                        try {
                            reply("started")
                            fun create(db: SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE author(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
                                db.execSQL("INSERT INTO author VALUES (7,?)", arrayOf<Any>(PAYLOAD))
                                if (args.getBoolean("hold") && !args.getBoolean("mutateCommitted")) {
                                    reply("uncommitted")
                                    check(release.await(30, TimeUnit.SECONDS)) { "Peer release not received" }
                                }
                            }
                            val path = requireNotNull(args.getString("path"))
                            if (args.getBoolean("legacy")) {
                                // Defaults and parameter changes belong to the old creator's
                                // private fixture setup, before the candidate is released.
                                SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                                    db.execSQL("PRAGMA auto_vacuum=" + args.getInt("mode"))
                                    db.execSQL("VACUUM")
                                    db.query("PRAGMA journal_mode=" + args.getString("journal", "DELETE")).use {
                                        check(it.moveToFirst() && it.getString(0).equals(args.getString("journal"), ignoreCase = true))
                                    }
                                    db.beginTransaction()
                                    try { create(db); db.version = args.getInt("version", 1); db.setTransactionSuccessful() }
                                    finally { db.endTransaction() }
                                    if (args.getBoolean("mutateCommitted")) {
                                        db.beginTransaction()
                                        try {
                                            db.execSQL("UPDATE author SET payload=x'0001' WHERE id=7")
                                            db.execSQL("INSERT INTO author VALUES (8,x'0002')")
                                            reply("uncommitted")
                                            check(release.await(30, TimeUnit.SECONDS))
                                            // Deliberately never mark this update successful.
                                        } finally { db.endTransaction() }
                                    }
                                }
                            } else {
                                val callback = object : SupportSQLiteOpenHelper.Callback(1) {
                                    override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
                                }
                                PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(this@DatabaseCreationPeerService)
                                    .name(path).callback(callback).build()).use { helper ->
                                    helper.setWriteAheadLoggingEnabled(true)
                                    helper.writableDatabase.query("SELECT payload FROM author WHERE id=7").use {
                                        check(it.moveToFirst() && it.getBlob(0).contentEquals(PAYLOAD))
                                    }
                                }
                            }
                            reply("closed")
                        } catch (failure: Throwable) { reply("failed", failure) }
                    }
                }
                else -> super.handleMessage(message)
            }
        }
    })
    override fun onBind(intent: Intent) = messenger.binder
    override fun onDestroy() { release.countDown(); worker.shutdownNow(); super.onDestroy() }

    companion object {
        val PAYLOAD = ByteArray(32 * 1024) { (it * 31 + (it ushr 8)).toByte() }
    }
}
