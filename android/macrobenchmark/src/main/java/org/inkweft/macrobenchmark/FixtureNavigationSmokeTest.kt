// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.macrobenchmark

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.inkweft.macrobenchmark.BenchmarkFixture.PACKAGE
import org.inkweft.macrobenchmark.BenchmarkFixture.awaitPage
import org.inkweft.macrobenchmark.BenchmarkFixture.book0
import org.inkweft.macrobenchmark.BenchmarkFixture.book1
import org.inkweft.macrobenchmark.BenchmarkFixture.click
import org.inkweft.macrobenchmark.BenchmarkFixture.pages0
import org.inkweft.macrobenchmark.BenchmarkFixture.openOverview
import org.inkweft.macrobenchmark.BenchmarkFixture.switchBook
import org.junit.Test
import org.junit.runner.RunWith

/** Functional navigation only: may run on an emulator, never emits benchmark scores. */
@RunWith(AndroidJUnit4::class)
class FixtureNavigationSmokeTest {
    @Test fun signedFixtureLargePageFlipAndSwitchReachRenderedViewports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        check(context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH)
        check(context.checkSelfPermission("$PACKAGE.permission.BENCHMARK_FIXTURE") == PackageManager.PERMISSION_GRANTED)
        BenchmarkFixture.prepare()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am force-stop $PACKAGE")
        context.startActivity(checkNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.click("note-cover-$book0"); awaitPage(book0)
        device.openOverview()
        device.click("jump-page-2"); awaitPage(pages0[1])
        device.click("jump-page-1"); awaitPage(book0)
        device.click("pages-directory-dialog-close")
        device.click("back-library")
        device.click("note-cover-$book1"); awaitPage(book1)
        device.switchBook(book0)
        device.switchBook(book1)
    }
}
