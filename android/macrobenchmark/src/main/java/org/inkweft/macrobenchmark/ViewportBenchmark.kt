// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.inkweft.macrobenchmark.BenchmarkFixture.PACKAGE
import org.inkweft.macrobenchmark.BenchmarkFixture.TIMEOUT
import org.inkweft.macrobenchmark.BenchmarkFixture.awaitPage
import org.inkweft.macrobenchmark.BenchmarkFixture.book0
import org.inkweft.macrobenchmark.BenchmarkFixture.book1
import org.inkweft.macrobenchmark.BenchmarkFixture.click
import org.inkweft.macrobenchmark.BenchmarkFixture.pages0
import org.inkweft.macrobenchmark.BenchmarkFixture.openOverview
import org.inkweft.macrobenchmark.BenchmarkFixture.switchBook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device performance suite. Setup never runs inside a measured block. */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class ViewportBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { BenchmarkFixture.prepare(); pressHome() }
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("note-cover-$book0")), TIMEOUT)) { "Library did not load the fixture" }
    }

    @Test fun openLargePage() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = pageMetrics(), compilationMode = CompilationMode.Full(), iterations = 5,
        setupBlock = { library() }
    ) {
        openBook(book0)
    }

    @Test fun fastPageFlip() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = pageMetrics(), compilationMode = CompilationMode.Full(), iterations = 5,
        setupBlock = {
            library()
            openBook(book0)
            device.openOverview()
            check(device.wait(Until.hasObject(By.res("jump-page-2")), TIMEOUT))
        }
    ) {
        // Six successive transitions through real page navigation; no artificial dwell.
        repeat(3) {
            clickTag("jump-page-2"); awaitPage(pages0[1])
            clickTag("jump-page-1"); awaitPage(pages0[0])
        }
    }

    @Test fun switchNotebook() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = pageMetrics(), compilationMode = CompilationMode.Full(), iterations = 5,
        setupBlock = {
            library()
            openBook(book0)
            clickTag("back-library")
            openBook(book1)
        }
    ) {
        repeat(3) {
            device.switchBook(book0)
            device.switchBook(book1)
        }
    }

    private fun MacrobenchmarkScope.library() {
        // AndroidX may reinstall on API 31–33 while resetting compilation. Seed after that reset.
        BenchmarkFixture.prepare()
        killProcess()
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("note-cover-$book0")), TIMEOUT)) { "Fixture missing from library" }
    }

    private fun MacrobenchmarkScope.openBook(id: String) { clickTag("note-cover-$id"); awaitPage(id) }

    private fun MacrobenchmarkScope.clickTag(tag: String) {
        device.click(tag)
    }

    private fun pageMetrics() = listOf(
        FrameTimingMetric(),
        TraceSectionMetric("InkWeft.spatial.query", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("InkWeft.ink.window", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("InkWeft.ink.frame", TraceSectionMetric.Mode.Sum)
    )

}
