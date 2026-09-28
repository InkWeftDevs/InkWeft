package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
class OverviewTabsUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun tap(tag:String){compose.revealAction(tag);if(tag in setOf("add-page","object-shape","page-objects","object-sticker","top-area-erase","object-camera")){compose.openEditorAction(tag);return};if(tag=="top-draw"){compose.openCurrentPen();return};val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
 private fun ready(){compose.waitUntil(15000){app.navigationReady.value};compose.waitForIdle()}
 @Test fun fourTabsPersistMarksAndLocateExistingExcerpts(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val n=runBlocking{app.workspaceRepository.create("V31 四栏验收",false,PaperStyle.BLANK)}
  val stroke=InkStroke(id(),InkPen.PEN,0xffd32f2f.toInt(),3f,InkTool.STYLUS,listOf(InkSample(200f,250f,0),InkSample(240f,280f,10)))
  val card=id()
  runBlocking{app.inkRepository.save(CommitInk(id(),n.id,0,InkMutation.Add(stroke)));app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=card,nodeId=id(),title="红色摘录",body="复习重点",source=StudySourceDraft(n.id,1,stroke.bounds(),listOf(stroke.id))))}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)};compose.singlePageEditor();ready()
  tap("quick-settings");tap("settings-add-page");tap("confirm-insert-pages");compose.waitUntil(15000){runBlocking{app.pages.observe(n.id).first().size}==2};ready()
  tap("quick-overview");(0..3).forEach{compose.onNodeWithTag("overview-tab-$it").assertIsDisplayed()}
  tap("overview-tab-1");tap("overview-add-mark");compose.onNodeWithTag("overview-mark-title").performTextReplacement("第二章");tap("overview-mark-save")
  fun marks()=runBlocking{app.knowledge.observe().first().filter{it.notebookId==n.id&&!it.removed}}
  compose.waitUntil(10000){marks().size==1};val outline=marks().single()
  tap("overview-tab-2");tap("overview-add-mark");compose.onNodeWithTag("overview-mark-title").performTextReplacement("重点页");tap("overview-mark-save");compose.waitUntil(10000){marks().size==2};compose.onNodeWithTag("overview-add-mark").assertIsNotEnabled()
  val bookmark=marks().first{(it.data() as KnowledgeData.PageMark).bookmark}
  tap("mark-menu-${bookmark.id}");tap("mark-rename");compose.onNodeWithTag("overview-mark-title").performTextReplacement("考前复习");tap("overview-mark-save")
  compose.waitUntil(10000){marks().any{(it.data() as KnowledgeData.PageMark).title=="考前复习"}}
  compose.activityRule.scenario.recreate();ready();tap("quick-overview");tap("overview-tab-2");compose.onNodeWithText("考前复习").assertExists()
  tap("overview-tab-3");compose.waitUntil(10000){compose.onAllNodesWithTag("overview-excerpt-$card").fetchSemanticsNodes().isNotEmpty()};tap("overview-excerpt-$card");ready();compose.onNodeWithTag("overview-excerpt-$card").assertExists();assertEquals(n.id,compose.activity.let{androidx.lifecycle.ViewModelProvider(it)["book-${n.id}",BookPagesViewModel::class.java].ui.value.selectedId});compose.onNodeWithTag("pages-directory-dialog").assertIsDisplayed()
  tap("overview-tab-1");tap("overview-mark-${outline.id}");ready();assertEquals(1,compose.activity.let{androidx.lifecycle.ViewModelProvider(it)["book-${n.id}",BookPagesViewModel::class.java].ui.value.let{u->u.pages.first{it.id==u.selectedId}.position}})
  tap("overview-tab-2");tap("mark-menu-${bookmark.id}");tap("mark-remove");compose.waitUntil(10000){marks().size==1};assertEquals(2,runBlocking{app.pages.observe(n.id).first().size})
  tap("overview-tab-3");tap("excerpt-edit-$card");compose.onNodeWithTag("pages-directory-dialog").assertDoesNotExist()
 }
}
