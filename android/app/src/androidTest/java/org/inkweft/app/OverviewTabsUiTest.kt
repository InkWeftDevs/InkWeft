package org.inkweft.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
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
 @Test fun outlineFoldsFollowStableRowsThroughSearchRestoreAndStructureChanges(){
  compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
  val note=runBlocking{app.workspaceRepository.create("大纲折叠验收",false,PaperStyle.BLANK)}
  val pages=runBlocking{buildList{add(app.pages.ensureFirst(note.id));repeat(5){add(app.pages.addAfter(note.id,last().id,id()))}}}
  val prefix=id().take(24)
  val rowIds=List(7){prefix+(it+1).toString().padStart(12,'0')}
  val pageIds=listOf(pages[0].id,pages[1].id,pages[2].id,pages[3].id,pages[4].id,pages[4].id,pages[5].id)
  val titles=listOf("第一章","子节","孙节暗号","同级节","第二章","同页子节","第二章孙节")
  val depths=listOf(0,1,2,1,0,1,2)
  runBlocking{rowIds.indices.forEach{i->app.knowledge.submit(KnowledgeCommand(id(),note.id,rowIds[i],0,KnowledgeData.PageMark(pageIds[i],titles[i],depth=depths[i])))}}
  val key="overview-${note.id}"
  var saved=SavedStateHandle()
  var overview=compose.runOnIdle{OverviewViewModel(note.id,app.knowledge,app.study,saved).also{compose.activity.viewModelStore.put(key,it)}}
  compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
  compose.singlePageEditor();ready();tap("quick-overview");tap("overview-tab-1")
  compose.waitUntil(15000){!overview.loading.value}
  val support=SelectAwaitTestSupport(compose)
  fun row(i:Int)="overview-mark-${rowIds[i]}"
  fun fold(i:Int)="outline-fold-${rowIds[i]}"
  fun show(i:Int){compose.onNodeWithTag("overview-marks").performScrollToNode(hasTestTag(row(i)));compose.onNodeWithTag(row(i)).assertIsDisplayed()}
  fun hidden(i:Int){
   compose.onNodeWithTag("overview-marks").assertExists()
   assertTrue("Folded row must be absent from the whole list",runCatching{compose.onNodeWithTag("overview-marks").performScrollToNode(hasTestTag(row(i)))}.exceptionOrNull() is AssertionError)
   compose.onNodeWithTag(row(i)).assertDoesNotExist()
  }
  fun folded(vararg indices:Int){
   val expected=indices.map{"${pageIds[it]}:${rowIds[it]}"}.toSet()
   compose.waitUntil(15000){overview.outlineFolds.value.toSet()==expected}
   compose.waitForIdle()
  }
  fun search(text:String){compose.onNodeWithTag("overview-filter").performTextReplacement(text);compose.waitForIdle()}
  fun selected()=compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].ui.value.selectedId}
  var authors=support.authorStamp(note.id)
  show(1);compose.onNodeWithTag(fold(1)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp);tap(fold(1))
  folded(1);hidden(2);show(3);show(4)
  show(0);tap(fold(0));folded(0,1);hidden(1);hidden(2);hidden(3);show(4)
  // These two parent rows share a page; folding the inner row must not fold its outer row.
  show(5);tap(fold(5));folded(0,1,5);show(4);show(5);hidden(6)
  assertEquals(note.id,selected()) // Fold clicks never open the parent page.
  search("孙节暗号");show(2);folded(0,1,5);tap(row(2));ready();assertEquals(pageIds[2],selected())
  search("");folded(0,1,5);hidden(2);show(4);show(5)
  search("第一章");compose.onNodeWithTag(fold(0)).assertIsNotEnabled();search("")
  tap("overview-tab-2");tap("overview-tab-1");folded(0,1,5);hidden(1)
  // Restore a fresh ViewModel from saved values as well as recreating the activity.
  compose.runOnIdle{
   saved=SavedStateHandle(saved.keys().associateWith{name->when(val value=saved.get<Any?>(name)){is ArrayList<*>->ArrayList(value);else->value}})
   overview=OverviewViewModel(note.id,app.knowledge,app.study,saved)
   compose.activity.viewModelStore.put(key,overview)
  }
  compose.activityRule.scenario.recreate();ready();tap("quick-overview");tap("overview-tab-1")
  compose.waitUntil(15000){!overview.loading.value};folded(0,1,5);hidden(1);hidden(2);hidden(3);show(4);show(5);hidden(6)
  show(0);tap(fold(0));folded(1,5);show(1);hidden(2);show(3)
  show(0);tap(fold(0));folded(0,1,5)
  assertEquals(authors,support.authorStamp(note.id))
  // Reindent while searching: prune the now-childless parent against the full outline.
  search("孙节暗号");tap("mark-menu-${rowIds[2]}");compose.onNodeWithText("减少缩进").performClick()
  compose.waitUntil(15000){overview.marks.value.any{it.id==rowIds[2]&&(it.data() as KnowledgeData.PageMark).depth==1}&&!overview.busy.value}
  folded(0,5);authors=support.authorStamp(note.id);search("");hidden(1);hidden(2)
  show(0);tap(fold(0));folded(5);show(1);show(2);compose.onNodeWithTag(fold(1)).assertDoesNotExist()
  show(0);tap(fold(0));folded(0,5);assertEquals(authors,support.authorStamp(note.id))
  fun editPage(pageId:String,kind:PageEditKind,location:PageInsertLocation=PageInsertLocation.END,trashedAt:Long?=null){
   val result=runBlocking{app.pages.edit(EditPage(id(),note.id,pageId,kind,InsertPages.orderHash(app.pages.activePages(note.id).map{it.id}),0,
    location=location,expectedTrashedAt=trashedAt,stayOnPageId=note.id))}
   assertTrue(result is EditPageResult.Applied)
   val expected=runBlocking{app.pages.activePages(note.id).map{it.id}}
   compose.waitUntil(15000){compose.runOnIdle{ViewModelProvider(compose.activity)["book-${note.id}",BookPagesViewModel::class.java].ui.value.pages.map{it.id}}==expected}
   ready()
  }
  editPage(pageIds[3],PageEditKind.MOVE,PageInsertLocation.START)
  folded(0,5);authors=support.authorStamp(note.id)
  compose.onNodeWithTag("overview-marks").performScrollToIndex(0)
  assertTrue(compose.onNodeWithTag(row(3)).fetchSemanticsNode().boundsInRoot.top<compose.onNodeWithTag(row(0)).fetchSemanticsNode().boundsInRoot.top)
  show(3);show(0);hidden(1);hidden(2);show(4);show(5);hidden(6)
  assertEquals(authors,support.authorStamp(note.id))
  editPage(pageIds[4],PageEditKind.TRASH)
  folded(0);authors=support.authorStamp(note.id);hidden(4);hidden(5);hidden(6);show(3);show(0)
  assertEquals(authors,support.authorStamp(note.id))
  val trashedAt=runBlocking{app.pages.observeAll(note.id).first().first{it.id==pageIds[4]}.trashedAt}
  editPage(pageIds[4],PageEditKind.RESTORE,trashedAt=trashedAt)
  folded(0);authors=support.authorStamp(note.id);show(4);show(5);compose.onNodeWithTag(fold(5)).assertDoesNotExist();hidden(6)
  show(0);tap(fold(0));folded();show(1);show(2);show(6);show(4);show(5)
  assertEquals(authors,support.authorStamp(note.id))
 }
}
