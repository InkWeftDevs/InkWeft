// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.macrobenchmark

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** The smoke test exercises exactly the same fixture protocol and selectors as the benchmark. */
internal object BenchmarkFixture {
    const val PACKAGE = "org.inkweft.app.a0.macrobenchmark"
    const val TIMEOUT = 60_000L
    private val uri = Uri.parse("content://$PACKAGE.benchmark-fixture")
    private val resolver get() = InstrumentationRegistry.getInstrumentation().context.contentResolver
    lateinit var book0: String; private set
    lateinit var book1: String; private set
    lateinit var pages0: List<String>; private set

    fun prepare() {
        val fixture = checkNotNull(resolver.call(uri, "prepare", "viewport-v1", null))
        check(fixture.getInt("largePageStrokes") == 24_000 && fixture.getInt("largePagePoints") == 1_152_000)
        book0 = checkNotNull(fixture.getString("book0"))
        book1 = checkNotNull(fixture.getString("book1"))
        pages0 = checkNotNull(fixture.getStringArrayList("pages0"))
    }

    fun awaitPage(id: String) {
        val ready = resolver.call(uri, "awaitReady", id, null)
        check(ready?.getBoolean("ready") == true && ready.getString("page") == id) { "Viewport did not finish: $id" }
    }

    fun UiDevice.click(tag: String) {
        checkNotNull(wait(Until.findObject(By.res(tag).enabled(true)), TIMEOUT)) { "Missing or disabled UI: $tag" }.click()
    }

    fun UiDevice.openOverview() {
        if (!hasObject(By.res("quick-overview"))) click("document-more")
        click("quick-overview")
    }

    fun UiDevice.switchBook(id: String) {
        val title = when (id) {
            book0 -> "Macrobenchmark 1"
            book1 -> "Macrobenchmark 2"
            else -> error("Unknown fixture notebook: $id")
        }
        click("tabs-list")
        // The tab Popup has its own semantics tree, so use its visible fixture title.
        checkNotNull(wait(Until.findObject(By.text(title).enabled(true)), TIMEOUT)) { "Missing notebook tab: $title" }.click()
        awaitPage(id)
    }
}
