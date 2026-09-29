package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class LearningWorkbenchTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun shot(name:String){compose.waitForIdle();val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"learning-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun narrowLargeTextKeepsDirectoryAndConfigurationReachable(){
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(value:String){automation.executeShellCommand(value).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        try{
            shell("wm size 600x1000");shell("settings put system font_scale 1.5")
            compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
            val n=runBlocking{app.workspaceRepository.create("第三章 · 复杂事件的概率计算",false,PaperStyle.GRID)}
            val map=id();runBlocking{app.knowledge.submit(KnowledgeCommand(id(),n.id,map,0,KnowledgeData.MapDefinition("全概率公式与贝叶斯公式")))}
            app.learningStore.visit(StableTargetRef(LearningTargetKind.NOTE,n.id))
            app.learningStore.shortcut(StableTargetRef(LearningTargetKind.MAP,n.id,map),true)
            compose.onNodeWithText("学习",useUnmergedTree=true).performClick()
            compose.onNodeWithTag("widget-maps").performScrollTo()
            compose.onNodeWithTag("learning-map-search").performTextInput("贝叶斯")
            compose.onNode(hasTestTag("learning-target-$map") and hasAnyAncestor(hasTestTag("widget-maps"))).performScrollTo().assertIsDisplayed()
            shot("narrow-large-directory")
            compose.onNodeWithText("自定义").performClick()
            compose.onNodeWithTag("widget-maps").performScrollTo();shot("narrow-large-configuration")
            compose.onNodeWithText("完成").performClick()
        }finally{shell("wm size 1920x1200");shell("settings put system font_scale 1.0")}
    }
    @Test fun directoryUsesStableIdentitiesAndManualInboxState()=runBlocking {
        val n=app.workspaceRepository.create("逻辑与概率",false,PaperStyle.DOTS)
        assertTrue(app.learningDirectory.observe().first().maps.none{it.target.notebookId==n.id})
        assertTrue(app.learningDirectory.observe().first().resolve(StableTargetRef(LearningTargetKind.MAP,n.id)).available)
        val map=id();val card=id();val node=id();val property=id()
        app.knowledge.submit(KnowledgeCommand(id(),n.id,map,0,KnowledgeData.MapDefinition("条件概率")))
        app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=card,nodeId=node,title="P(A | B)",body="先缩小样本空间",mapId=map))
        val ref=StableTargetRef(LearningTargetKind.MAP,n.id,map)
        app.learningStore.shortcut(ref,true)
        var index=app.learningDirectory.observe().first()
        assertTrue(index.inbox.any{it.target.id==card}) // Being placed on a map is not sorting.
        app.knowledge.submit(KnowledgeCommand(id(),n.id,property,0,KnowledgeData.Properties(card,ManualState.UNDERSTOOD)))
        index=app.learningDirectory.observe().first();assertFalse(index.inbox.any{it.target.id==card})
        app.knowledge.submit(KnowledgeCommand(id(),n.id,map,1,KnowledgeData.MapDefinition("条件概率与独立性")))
        assertEquals("条件概率与独立性",app.learningDirectory.observe().first().resolve(ref).title)
        val row=app.workspaceRepository.get(n.id)
        app.workspaceRepository.organize(n.id,row.revision,row.folder,row.tags,true,true)
        assertFalse(app.learningDirectory.observe().first().resolve(ref).available)
        assertEquals(listOf(ref),app.learningStore.read().shortcuts)
        val trashed=app.workspaceRepository.get(n.id)
        app.workspaceRepository.organize(n.id,trashed.revision,trashed.folder,trashed.tags,false,false)
        assertTrue(app.learningDirectory.observe().first().resolve(ref).available)
        assertEquals(listOf(ref),app.learningStore.read().shortcuts)
        app.learningStore.shortcut(ref,false)
        assertTrue(app.learningDirectory.observe().first().resolve(ref).available)
        val shared=app.learningStore.sharedLayout();assertFalse(shared.contains(n.id));assertFalse(shared.contains(map));assertFalse(shared.contains("条件概率"))
    }
    @Test fun learningOpensMapWithoutOpeningNoteAndKeepsHostConfiguration(){
        compose.waitUntil(15000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val n=runBlocking{app.workspaceRepository.create("第二章 · 条件概率与独立性",false,PaperStyle.DOTS)}
        val map=id();val root=id()
        runBlocking{
            app.knowledge.submit(KnowledgeCommand(id(),n.id,map,0,KnowledgeData.MapDefinition("概率复习路线")))
            app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=id(),nodeId=root,title="条件概率",body="P(A | B) = P(A ∩ B) / P(B)",mapId=map))
            app.study.submit(StudyCommand(id(),n.id,StudyAction.CREATE,cardId=id(),nodeId=id(),parentId=root,title="独立性",body="P(A ∩ B) = P(A)P(B)",x=300.0,y=90.0,mapId=map))
        }
        app.learningStore.visit(StableTargetRef(LearningTargetKind.PAGE,n.id,n.id))
        compose.onNodeWithText("学习",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("learning-workbench").assertIsDisplayed()
        compose.onNodeWithTag("widget-maps").performScrollTo()
        compose.onNodeWithTag("learning-map-search").performTextInput("概率复习")
        shot("directory")
        compose.onNodeWithTag("learning-target-$map").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("study-map").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{
            assertNull(ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.selectedId)
            assertEquals(map,ViewModelProvider(compose.activity)["study-${n.id}",StudyViewModel::class.java].mapId.value)
        }
        shot("direct-map")
        compose.onNodeWithTag("study-close").performClick()
        compose.onNodeWithTag("widget-maps").performScrollTo()
        compose.onNodeWithTag("learning-map-search").assertTextContains("概率复习")
        val config=app.learningStore.read();val first=config.widgets.first()
        app.learningStore.configure(LearningWidgets.move(config.widgets,first.id,3).map{if(it.id==first.id)it.copy(visible=false,size=WidgetSize.LARGE)else it})
        compose.activityRule.scenario.recreate();compose.waitForIdle()
        assertEquals(first.id,app.learningStore.read().widgets.last().id)
        assertFalse(app.learningStore.read().widgets.last().visible)
        assertTrue(runBlocking{app.study.cards(n.id).first()}.size==2)
    }
}
