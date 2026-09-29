package org.inkweft.app
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
class OverviewRecoveryTest {
 @Test fun readFailureRetainsRowsAndReloadRestoresEditing(){runBlocking{
  val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as InkWeftApplication
  val book=app.workspaceRepository.create("概览读失败测试",false,PaperStyle.BLANK).id
  val c=KnowledgeCommand(UUID.randomUUID().toString(),book,UUID.randomUUID().toString(),0,KnowledgeData.PageMark(book,"原页签",true));app.knowledge.submit(c)
  var failRead=false;lateinit var vm:OverviewViewModel
  ins.runOnMainSync{vm=OverviewViewModel(book,app.knowledge,app.study,readRows={flow{if(failRead)error("synthetic read failure");emitAll(app.knowledge.observe())}})}
  withTimeout(10000){while(vm.loading.value)delay(20)};assertEquals(c.id,vm.marks.value.single().id)
  ins.runOnMainSync{failRead=true;vm.refresh()};withTimeout(10000){while(vm.loading.value)delay(20)}
  assertNotNull(vm.readError.value);assertEquals(c.id,vm.marks.value.single().id)
  ins.runOnMainSync{vm.save(null,KnowledgeData.PageMark(book,"不得写入",false))};assertNull(vm.draft.value)
  ins.runOnMainSync{failRead=false;vm.refresh()};withTimeout(10000){while(vm.loading.value)delay(20)}
  assertNull(vm.readError.value);ins.runOnMainSync{vm.save(null,KnowledgeData.PageMark(book,"恢复后目录",false))};withTimeout(10000){while(vm.busy.value)delay(20)}
  assertEquals(OverviewSaveState.SUCCESS,vm.state.value)
 }}
 @Test fun conflictAndDuplicateReleasePendingWithoutDroppingDraft(){runBlocking{
  val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as InkWeftApplication
  val book=app.workspaceRepository.create("概览恢复测试",false,PaperStyle.BLANK).id
  fun id()=UUID.randomUUID().toString()
  val mark=KnowledgeData.PageMark(book,"初始",true);val c=KnowledgeCommand(id(),book,id(),0,mark);app.knowledge.submit(c)
  lateinit var vm:OverviewViewModel;ins.runOnMainSync{vm=OverviewViewModel(book,app.knowledge,app.study)}
  withTimeout(10000){while(vm.loading.value)delay(20)};val old=vm.marks.value.single()
  app.knowledge.submit(KnowledgeCommand(id(),book,c.id,1,mark.copy(title="其他修改")))
  ins.runOnMainSync{vm.save(old,mark.copy(title="我的草稿"))}
  withTimeout(10000){while(vm.busy.value)delay(20)}
  assertEquals(OverviewSaveState.CONFLICT,vm.state.value);assertEquals("我的草稿",(vm.draft.value!!.data as KnowledgeData.PageMark).title)
  ins.runOnMainSync{vm.refresh()};withTimeout(10000){while(vm.loading.value||vm.marks.value.single().revision!=2L)delay(20)}
  ins.runOnMainSync{vm.save(vm.marks.value.single(),mark.copy(title="我的草稿"))};withTimeout(10000){while(vm.busy.value)delay(20)}
  assertEquals(OverviewSaveState.SUCCESS,vm.state.value);assertNull(vm.draft.value)
  ins.runOnMainSync{vm.save(null,mark)};withTimeout(10000){while(vm.busy.value)delay(20)}
  assertEquals(OverviewSaveState.REJECTED,vm.state.value)
  ins.runOnMainSync{vm.discardDraft();vm.save(null,mark.copy(bookmark=false,title="新目录"))};withTimeout(10000){while(vm.busy.value)delay(20)}
  assertEquals(OverviewSaveState.SUCCESS,vm.state.value)
 }}
}
