package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Tests of single-sheet tools explicitly choose that mode; production defaults to continuous. */
internal fun ComposeTestRule.singlePageEditor(){
    waitUntil(15_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()||onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
    if(onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()){
        waitUntil(10_000){runCatching{onNodeWithText("单页缩放").assertIsEnabled()}.isSuccess}
        onNodeWithText("单页缩放").performScrollTo().performClick()
        waitUntil(10_000){onAllNodesWithTag("ink-surface").fetchSemanticsNodes().isNotEmpty()}
    }
}
internal fun ComposeTestRule.openCurrentPen(){
    val node=onAllNodes(isOn() and (hasTestTag("ink-tool-0") or hasTestTag("ink-tool-1") or hasTestTag("ink-tool-2")))
    node[0].performScrollTo().performClick()
}

internal fun ComposeTestRule.closePenSettings(){onNodeWithTag("close-pen-settings").performScrollTo().performClick()}
internal fun ComposeTestRule.openBeautySettings(){onNodeWithTag("auto-beauty-toggle").performScrollTo().performClick()}
