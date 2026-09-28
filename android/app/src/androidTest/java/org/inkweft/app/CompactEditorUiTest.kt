package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
class CompactEditorUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun tap(tag:String){if(tag in setOf("add-page","object-shape","page-objects","object-sticker","top-area-erase","object-camera")){compose.openEditorAction(tag);return};if(tag=="top-draw"){compose.openCurrentPen();return};val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick()}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
 @Test fun compactEditorBookmarksTagsAndLiveEraser(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val n=runBlocking{app.workspaceRepository.create("V32 界面验证",false,PaperStyle.BLANK)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();ready()
  compose.onNodeWithTag("page-counter").assertDoesNotExist();compose.onNodeWithTag("ink-zoom").assertDoesNotExist();compose.onNodeWithTag("ink-more").assertDoesNotExist()
  tap("tabs-list");compose.onNodeWithTag("tabs-filter").assertIsDisplayed();tap("tabs-list-${n.id}")
  tap("quick-overview");tap("page-bookmark-1")
  fun marks()=runBlocking{app.knowledge.observe().first().filter{it.notebookId==n.id&&!it.removed}}
  compose.waitUntil(10000){marks().size==1};tap("overview-tab-2");compose.onNodeWithText("第1页").assertExists()
  tap("overview-tab-0");tap("page-bookmark-1");compose.waitUntil(10000){marks().isEmpty()};tap("pages-directory-dialog-close")
  tap("quick-settings");tap("settings-tags");compose.onNodeWithTag("notebook-tags").performTextInput("公式");tap("tag-add");compose.onNodeWithTag("tag-chip-公式").assertExists()
  compose.onNodeWithTag("notebook-tags").performTextInput("复习");tap("tag-add");tap("tag-chip-公式");tap("save-notebook-tags");ready()
  tap("quick-settings");tap("settings-tags");compose.onNodeWithTag("tag-chip-复习").assertExists();compose.onNodeWithTag("tag-chip-公式").assertDoesNotExist();tap("save-notebook-tags")
  tap("ink-tool-3");tap("ink-tool-3");tap("eraser-whole");assertTrue(EraserSettingsStore(app).read().whole);compose.onNodeWithText("使用此橡皮").assertDoesNotExist();tap("eraser-local");tap("eraser-circle");compose.onNodeWithTag("selection-overlay").assertExists()
  tap("auto-beauty-toggle");tap("beauty-keep-ink");compose.onNodeWithTag("beauty-ink-strength").assertExists();tap("beauty-replace-font");compose.onNodeWithTag("beauty-font-picker").assertExists();tap("beauty-keep-ink");tap("beauty-close")
  tap("toolbar-customize");compose.onNodeWithTag("toolbar-row-pen").assertDoesNotExist();compose.onNodeWithTag("toolbar-row-favorites").assertDoesNotExist();tap("toolbar-done");compose.onNodeWithTag("notebook-tab-${n.id}").assertIsDisplayed()
 }
}
