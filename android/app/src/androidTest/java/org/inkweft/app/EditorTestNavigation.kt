package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Tests of single-sheet tools explicitly choose that mode; production defaults to continuous. */
internal fun ComposeTestRule.singlePageEditor(){
    waitUntil(15_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()||onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
    if(onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()){
        waitUntil(15_000){runCatching{onNodeWithTag("quick-settings").assertIsEnabled()}.isSuccess}
        onNodeWithTag("quick-settings").performClick()
        onNodeWithTag("continuous-setting").performScrollTo().performClick()
        onNodeWithTag("document-settings-dialog-close").performClick()
        waitUntil(10_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()}
    }
}
internal fun ComposeTestRule.openCurrentPen(){onNodeWithTag("top-draw").performScrollTo().performClick()}
internal fun ComposeTestRule.selectPen(kind:String){
    if(onAllNodesWithTag("close-pen-settings").fetchSemanticsNodes().isNotEmpty())closePenSettings()
    if(onAllNodesWithTag("pen-kind-$kind").fetchSemanticsNodes().isEmpty())onNodeWithTag("case-collapse").performClick()
    val node=onNodeWithTag("pen-kind-$kind");if(runCatching{node.assertIsOff()}.isSuccess)node.performScrollTo().performClick()
}
/** Observe the actual canvas state; stroke totals are no longer user-facing UI. */
internal fun SemanticsNodeInteraction.assertInkCount(expected:Int):SemanticsNodeInteraction {
    var count:Int?=null
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        fun find(v:android.view.View):InkCanvasView?{
            if(v is InkCanvasView&&!v.preview&&!v.embeddedPage&&v.isShown)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        val a=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
        count=a?.window?.decorView?.let(::find)?.displayedStrokeCount
    }
    org.junit.Assert.assertEquals(expected,count);return this
}

internal fun ComposeTestRule.closePenSettings(){onNodeWithTag("close-pen-settings").performScrollTo().performClick()}
internal fun ComposeTestRule.openBeautySettings(){onNodeWithTag("auto-beauty-toggle").performScrollTo().performClick()}
