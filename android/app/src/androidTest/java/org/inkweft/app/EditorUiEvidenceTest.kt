// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.AuthoringScope
import org.inkweft.core.UserLayers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Identical synthetic scenes for baseline/candidate native screenshots; no screenshot mocks. */
class EditorUiEvidenceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var h:SelectAwaitTestSupport
    @Before fun prepare(){h=SelectAwaitTestSupport(compose);h.captureSettings()}
    @After fun restore(){if(::h.isInitialized)h.closeAndRestoreSettings()}

    @Test fun landscape()=capture("1200x800",1f,"landscape")
    @Test fun portrait()=capture("800x1200",1f,"portrait")
    @Test fun narrowLargeText()=capture("375x800",1.6f,"narrow-font16")

    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
    private fun shot(name:String){
        compose.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(h.app.getExternalFilesDir(null),"editor-ui-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun capture(size:String,font:Float,name:String){
        val oldSize=Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity=Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val oldFont=shell("settings get system font_scale")
        try{
            shell("wm size $size");shell("wm density 160");shell("settings put system font_scale $font")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000){kotlin.math.abs(compose.activity.resources.configuration.fontScale-font)<.02f}
            val f=h.seed();val reference=h.authorStamp(f.unrelated.id)
            shot("$name-map")
            h.tap("exit-readonly");h.tap("study-close")
            compose.runOnIdle{h.pages(f.note.id).select(f.sourcePage)}
            compose.waitUntil(15_000){compose.runOnIdle{h.pages(f.note.id).ui.value.selectedId==f.sourcePage&&h.app.navigationReady.value}}
            compose.waitForSavedInk()
            shot("$name-toolbar")
            h.tap("page-layers-open")
            val scope=AuthoringScope.page(f.note.id,f.sourcePage)
            fun layers()=runBlocking{h.app.authoring.read(scope).state.layers}
            repeat(2){index->
                h.tap("layer-add")
                compose.waitUntil(15_000){layers().layers.size==index+2&&runCatching{compose.onNodeWithTag("layer-add").assertIsEnabled()}.isSuccess}
            }
            h.tap("layer-select-${UserLayers.DEFAULT_ID}")
            compose.waitUntil(15_000){layers().currentId==UserLayers.DEFAULT_ID&&runCatching{compose.onNodeWithTag("layer-add").assertIsEnabled()}.isSuccess}
            shot("$name-layers")
            assertEquals(reference,h.authorStamp(f.unrelated.id))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }finally{
            shell(if(oldSize==null)"wm size reset"else"wm size $oldSize")
            shell(if(oldDensity==null)"wm density reset"else"wm density $oldDensity")
            shell(if(oldFont=="null")"settings delete system font_scale"else"settings put system font_scale $oldFont")
            compose.activityRule.scenario.recreate()
        }
    }
}
