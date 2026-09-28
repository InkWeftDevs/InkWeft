package org.inkweft.app
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
class DocumentPanelsUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun tap(tag:String){val n=compose.onNodeWithTag(tag);runCatching{n.performScrollTo()};n.performClick()}
 private lateinit var oldEditor:Map<String,*>
 @Before fun isolate(){val p=app.getSharedPreferences("inkweft-editor",0);oldEditor=p.all;p.edit().clear().commit()}
 @After fun restore(){val e=app.getSharedPreferences("inkweft-editor",0).edit().clear();oldEditor.forEach{(k,v)->when(v){is String->e.putString(k,v);is Float->e.putFloat(k,v);is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Set<*>->e.putStringSet(k,v.filterIsInstance<String>().toSet())}};e.commit()}
 private fun shot(name:String){compose.waitForIdle();val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();java.io.File(compose.activity.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value}}
 private fun open():Note{
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("V30 面板与胶带",false,PaperStyle.BLANK)}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)};compose.singlePageEditor();ready();return note
 }
 @Test fun tapeWidthChangesTheDrawnStrokeAndDoesNotChangeExistingTape(){
  val n=open();tap("object-tape");tap("object-tape");tap("tape-mode-2");tap("tape-width-12");tap("tape-close")
  fun draw(y:Float){compose.onNodeWithTag("tape-overlay").performTouchInput{swipe(Offset(width*.55f,height*y),Offset(width*.75f,height*y),240)}}
  fun objects()=runBlocking{app.pageObjects.read(n.id).objects}
  draw(.35f);compose.waitUntil(10000){objects().size==1&&app.navigationReady.value};val first=objects().single()
  tap("object-tape");tap("tape-width-96");shot("v30-tape-responsive.png");tap("tape-close");draw(.6f);compose.waitUntil(10000){objects().size==2&&app.navigationReady.value}
  assertEquals(12f,objects().first().lineWidth);assertEquals(96f,objects().last().lineWidth);assertEquals(first,objects().first());assertTrue(objects().all{it.tapePoints.isNotEmpty()})
  compose.activityRule.scenario.recreate();ready();tap("object-tape");tap("object-tape");compose.onNodeWithTag("tape-width-value").assertTextEquals("96");tap("tape-mode-1");tap("tape-width-24");tap("tape-close");draw(.8f);compose.waitUntil(10000){objects().size==3};assertEquals(24f,objects().last().lineWidth);assertTrue(objects().last().tapePoints.isNotEmpty())
 }
 @Test fun overviewSwitchesPagesWithoutClosingAndSettingsActionsReachTheRealFlows(){
  val n=open();tap("add-page");tap("insert-count-plus");tap("confirm-insert-pages");compose.waitUntil(15000){runBlocking{app.pages.observe(n.id).first().size}==3};ready()
  tap("quick-overview");tap("overview-layout");compose.onNodeWithTag("page-grid").performScrollToNode(hasTestTag("jump-page-1"));tap("jump-page-1");ready();compose.onNodeWithTag("pages-directory-dialog").assertIsDisplayed();compose.onNodeWithTag("page-counter",useUnmergedTree=true).assertTextEquals("第 1 / 3 页")
  tap("pages-directory-dialog-close");tap("quick-settings");shot("v30-settings-responsive.png");compose.onNodeWithTag("document-page-number").performTextInput("9");tap("document-page-go");compose.onNodeWithText("请输入1到3之间的页码").assertExists()
  compose.onNodeWithTag("document-page-number").performTextReplacement("2");tap("document-page-go");ready();compose.onNodeWithTag("page-counter",useUnmergedTree=true).assertTextEquals("第 2 / 3 页")
  tap("settings-paper");compose.onNodeWithTag("paper-picker").assertIsDisplayed();tap("paper-picker-cancel")
  tap("settings-copy-page");compose.onNodeWithTag("page-edit-dialog").assertExists();compose.onNodeWithText("取消").performClick()
  tap("quick-settings");tap("settings-deleted-pages");compose.onNodeWithTag("pages-directory-dialog").assertIsDisplayed();compose.onNodeWithText("没有已删除页面").assertExists();tap("pages-directory-dialog-close")
 }
}
