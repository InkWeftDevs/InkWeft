// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.ContentProvider
import android.content.ContentValues
import android.app.Activity
import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import java.util.UUID
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Signed benchmark-only setup, outside every measured block. Never clears a library. */
class BenchmarkFixtureProvider : ContentProvider() {
    private var activity = WeakReference<Activity>(null)
    override fun onCreate(): Boolean {
        (requireNotNull(context).applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) activity = WeakReference(value) }
            override fun onActivityDestroyed(value: Activity) { if (activity.get() === value) activity.clear() }
            override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        })
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context).applicationContext as InkWeftApplication
        check(app.packageName == PACKAGE) { "Synthetic setup requires the isolated benchmark package" }
        // ContentProvider.call also needs explicit enforcement: read/write URI checks do not
        // protect arbitrary call() methods on all Android versions.
        app.enforceCallingPermission("$PACKAGE.permission.BENCHMARK_FIXTURE", "Signed benchmark required")
        if (method == "awaitReady") return awaitReady(requireNotNull(arg))
        require(method == "prepare" && arg == FIXTURE) { "Unknown benchmark fixture" }
        return synchronized(LOCK) {
            runBlocking(Dispatchers.IO) {
                val preferences = app.getSharedPreferences("macrobenchmark-fixture", 0)
                if (!preferences.getBoolean(FIXTURE, false)) {
                  repeat(2) { bookIndex ->
                    val bookId = id("book-$bookIndex")
                    app.workspaceRepository.create("Macrobenchmark ${bookIndex + 1}", false, PaperStyle.BLANK, operationId = bookId)
                    var previous = bookId
                    repeat(6) { pageIndex ->
                        val pageId = if (pageIndex == 0) bookId else id("book-$bookIndex-page-$pageIndex")
                        if (pageIndex > 0) app.pages.addAfter(bookId, previous, pageId)
                        val count = if (bookIndex == 0 && pageIndex == 0) 24_000 else 600
                        repeat(count / BATCH) { batch ->
                            val strokes = List(BATCH) { offset ->
                                val index = batch * BATCH + offset
                                val points = if (count == 24_000) 48 else 32
                                InkStroke(id("$pageId-stroke-$index"), InkPen.PEN, 0xff3159b8.toInt(), 1.2f, InkTool.STYLUS,
                                    List(points) { point ->
                                        InkSample(20f + (index % 40) * 24f + point * .25f,
                                            30f + (index / 40) * (if (count == 24_000) 2.2f else 60f) + (point % 4) * .15f,
                                            point * 8L, .6f, .3f)
                                    })
                            }
                            check(app.inkRepository.save(CommitInk(id("$pageId-batch-$batch"), pageId,
                                batch.toLong(), InkMutation.Replace(emptyList(), strokes))) is InkCommitResult.Committed) {
                                "Fixture write failed: book=$bookIndex page=$pageIndex batch=$batch"
                            }
                        }
                        previous = pageId
                    }
                  }
                  check(preferences.edit().putBoolean(FIXTURE, true).commit())
                }
                repeat(2) { book ->
                    val bookId = id("book-$book")
                    val pages = List(6) { page -> id(if (page == 0) "book-$book" else "book-$book-page-$page") }
                    check(app.pages.activePages(bookId).map { it.id } == pages) { "Fixture page order was edited" }
                    pages.forEachIndexed { page, pageId ->
                        check(app.pages.inkRevision(pageId) == if (book == 0 && page == 0) 120L else 3L) {
                            "Fixture was edited; reinstall only the dedicated benchmark application"
                        }
                    }
                    app.pages.select(bookId, bookId)
                    check(app.getSharedPreferences("inkweft-reading", 0).edit().putBoolean("continuous-v20-$bookId", false).commit())
                }
                Bundle().apply {
                    putString("fixture", FIXTURE)
                    putString("book0", id("book-0"))
                    putString("book1", id("book-1"))
                    putStringArrayList("pages0", ArrayList(List(6) { if (it == 0) id("book-0") else id("book-0-page-$it") }))
                    putInt("largePageStrokes", 24_000)
                    putInt("largePagePoints", 1_152_000)
                }
            }
        }
    }

    /** Complete on a real rendered viewport; main-thread callbacks never retain the View. */
    private fun awaitReady(pageId: String): Bundle {
        require((0..1).any { book -> (0..5).any { page ->
            pageId == id(if (page == 0) "book-$book" else "book-$book-page-$page")
        } })
        check(Looper.myLooper() != Looper.getMainLooper())
        val result = CompletableFuture<Boolean>()
        Handler(Looper.getMainLooper()).post {
            fun ready(view: View): Boolean {
                if (view is InkCanvasView && view.isShown && !view.preview &&
                    view.displayedPageId == pageId && view.viewportFrameReady) return true
                if (view is ViewGroup) for (index in 0 until view.childCount) if (ready(view.getChildAt(index))) return true
                return false
            }
            val frames = Choreographer.getInstance()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (result.isDone) return
                    if (activity.get()?.window?.decorView?.let(::ready) == true) result.complete(true)
                    else frames.postFrameCallback(this)
                }
            }
            frames.postFrameCallback(callback)
        }
        try {
            check(result.get(60, TimeUnit.SECONDS))
            return Bundle().apply { putBoolean("ready", true); putString("page", pageId) }
        } finally {
            result.cancel(false)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()

    companion object {
        private const val PACKAGE = "org.inkweft.app.a0.macrobenchmark"
        private const val FIXTURE = "viewport-v1"
        private const val BATCH = 200
        private val LOCK = Any()
        private fun id(value: String) = UUID.nameUUIDFromBytes("$FIXTURE-$value".toByteArray(Charsets.UTF_8)).toString()
    }
}
