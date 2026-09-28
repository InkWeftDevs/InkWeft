package org.inkweft.app
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
class TapeStylesUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun tap(tag:String){if(tag in setOf("add-page","object-shape","page-objects","object-sticker","top-area-erase","object-camera")){compose.openEditorAction(tag);return};if(tag=="top-draw"){compose.openCurrentPen();return};val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick()}
 @Test fun fixedNavigationAndTapeStylesAreImmediatelyReachableAndPersistent(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("V29 胶带设置",false,PaperStyle.BLANK)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();compose.waitUntil(15000){app.navigationReady.value}
  compose.onNodeWithTag("quick-overview").assertIsDisplayed();compose.onNodeWithTag("quick-settings").assertIsDisplayed()
  compose.onNodeWithTag("editor-toolbar").performTouchInput{swipeLeft()};compose.onNodeWithTag("quick-overview").assertIsDisplayed();compose.onNodeWithTag("quick-settings").assertIsDisplayed()
  tap("object-tape");tap("object-tape");tap("tape-pattern-sparkles");tap("tape-mode-0");tap("tape-color-fff4aebc");tap("tape-close")
  compose.onNodeWithTag("tape-overlay").performTouchInput{swipe(Offset(width*.3f,height*.35f),Offset(width*.6f,height*.35f),240)}
  fun objects()=runBlocking{app.pageObjects.read(note.id).objects}
  compose.waitUntil(10000){objects().size==1&&app.navigationReady.value};assertEquals(TapePattern.SPARKLES,objects().single().tapePattern);assertEquals(0xfff4aebc.toInt(),objects().single().color)
  tap("object-tape");tap("tape-show-all");compose.waitUntil(10000){objects().single().revealed&&app.navigationReady.value};tap("tape-show-all");compose.waitUntil(10000){!objects().single().revealed&&app.navigationReady.value};tap("tape-close")
  compose.activityRule.scenario.recreate();compose.waitUntil(15000){app.navigationReady.value};tap("object-tape");tap("object-tape")
  compose.onNodeWithTag("tape-pattern-sparkles").assertIsSelected();tap("tape-clear-page");compose.onNodeWithText("清除",useUnmergedTree=true).performClick();compose.waitUntil(10000){objects().isEmpty()&&app.navigationReady.value}
  tap("ink-undo");compose.waitUntil(10000){objects().size==1};assertEquals(TapePattern.SPARKLES,objects().single().tapePattern)
 }
}
