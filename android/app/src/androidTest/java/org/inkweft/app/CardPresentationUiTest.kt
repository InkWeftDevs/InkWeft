// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production MainActivity, native map and Room. Every source is a synthetic fixture. */
class CardPresentationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var h:SelectAwaitTestSupport
    @Before fun prepare(){h=SelectAwaitTestSupport(compose);h.captureSettings()}
    @After fun restore(){if(::h.isInitialized)h.closeAndRestoreSettings()}
    private fun id()=UUID.randomUUID().toString()
    private fun row(f:SelectAwaitTestSupport.Fixture)=runBlocking{h.app.knowledge.observeBook(f.note.id).first().firstOrNull{!it.removed&&(it.data() as? KnowledgeData.CardPresentation)?.cardId==f.card}}
    private fun openEditor(f:SelectAwaitTestSupport.Fixture){
        h.tap("card-edit-presentation");h.waitFor("card-annotation-input")
        compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("card-annotation-input").assertIsEnabled()}.isSuccess}
    }
    private fun waitSaved(f:SelectAwaitTestSupport.Fixture,text:String){
        compose.waitUntil(15_000){(row(f)?.data() as? KnowledgeData.CardPresentation)?.annotation==text}
        h.waitFor("study-card-details");compose.onNodeWithTag("card-full-annotation").assertTextEquals(text)
    }
    private fun screenshot(name:String){
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(h.app.getExternalFilesDir(null),name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun sharedAnnotationColorsRestoreSearchAndResetWithoutTouchingBodyOrLayout(){
        val f=h.seed();h.tap("exit-readonly");h.openBody(f)
        val cards=runBlocking{h.app.study.cards(f.note.id).first()};val nodes=runBlocking{h.app.study.nodes(f.note.id).first()}
        val source=h.source(f.card).snapshot.copyOf();val maps=runBlocking{h.app.mapGraphs.read(f.note.id)}
        openEditor(f);compose.onNodeWithTag("card-annotation-input").performTextInput("独立个人理解 SEARCH-ANNOTATION")
        h.tap("card-color-GREEN");h.tap("card-title-color-ROSE")
        compose.activityRule.scenario.recreate();h.waitFor("card-annotation-input")
        compose.onNodeWithTag("card-annotation-input").assertTextContains("独立个人理解 SEARCH-ANNOTATION")
        compose.onNodeWithTag("card-color-GREEN").assertIsSelected();compose.onNodeWithTag("card-title-color-ROSE").assertIsSelected()
        h.tap("card-presentation-save");waitSaved(f,"独立个人理解 SEARCH-ANNOTATION")
        compose.onNodeWithTag("card-full-body").assertTextEquals(f.body);h.tap("card-back")
        h.tap("study-content-search");compose.onNodeWithTag("map-content-query").performTextInput("SEARCH-ANNOTATION")
        h.tap("map-hit-${f.mapId}-${f.node}")
        compose.runOnIdle{assertEquals(f.node,h.native<MindMapView>().selectedNodeId)}
        h.openBody(f);screenshot("b1-card-four-zones.png")
        openEditor(f);h.tap("card-colors-reset");h.tap("card-presentation-save");waitSaved(f,"独立个人理解 SEARCH-ANNOTATION")
        val value=row(f)!!.data() as KnowledgeData.CardPresentation;assertEquals(CardTint.DEFAULT,value.cardColor);assertEquals(CardTint.DEFAULT,value.titleBarColor)
        assertEquals(cards,runBlocking{h.app.study.cards(f.note.id).first()});assertEquals(nodes,runBlocking{h.app.study.nodes(f.note.id).first()});assertArrayEquals(source,h.source(f.card).snapshot);assertEquals(maps,runBlocking{h.app.mapGraphs.read(f.note.id)})
        h.tap("card-back");h.tap("quick-readonly");h.openBody(f)
        compose.onNodeWithTag("card-edit-presentation").assertIsNotEnabled()
    }
    @Test fun concurrentEditRetainsDraftRequiresExplicitRebaseAndCancelWritesNothing(){
        val f=h.seed();val initial=KnowledgeCommand(id(),f.note.id,id(),0,KnowledgeData.CardPresentation(f.card,"最初注释"))
        runBlocking{h.app.knowledge.submit(initial)}
        h.tap("exit-readonly");h.openBody(f);openEditor(f)
        compose.onNodeWithTag("card-annotation-input").performTextReplacement("我的未提交草稿")
        runBlocking{h.app.knowledge.submit(KnowledgeCommand(id(),f.note.id,initial.id,1,KnowledgeData.CardPresentation(f.card,"另一处已保存",CardTint.BLUE)))}
        h.waitFor("card-presentation-conflict");compose.onNodeWithTag("card-annotation-input").assertTextContains("我的未提交草稿")
        compose.onNodeWithTag("card-presentation-save").assertIsNotEnabled()
        h.tap("card-presentation-rebase");h.tap("card-presentation-save");waitSaved(f,"我的未提交草稿")
        assertEquals(3L,row(f)!!.revision)
        openEditor(f);compose.onNodeWithTag("card-annotation-input").performTextReplacement("取消的内容")
        h.tap("card-presentation-cancel");h.waitFor("card-full-annotation")
        assertEquals(3L,row(f)!!.revision);compose.onNodeWithTag("card-full-annotation").assertTextEquals("我的未提交草稿")
    }
    @Test fun longLegacyBodyAndLongAnnotationRemainCompleteAfterReopen(){
        val seeded=h.seed();val body=("完整中文正文，含旧备注而不猜测拆分。\n").repeat(160)+"正文最终标记"
        val f=seeded.copy(body=body);val annotation="独立注释\n".repeat(160)+"注释最终标记"
        runBlocking{
            h.app.study.submit(StudyCommand(id(),f.note.id,StudyAction.EDIT,cardId=f.card,expectedRevision=1,title="长卡片",body=body))
            h.app.knowledge.submit(KnowledgeCommand(id(),f.note.id,id(),0,KnowledgeData.CardPresentation(f.card,annotation)))
        }
        h.openBody(f);compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)
        compose.activityRule.scenario.recreate();h.waitFor("card-full-body")
        compose.onNodeWithTag("card-full-body").assertTextEquals(body);compose.onNodeWithTag("card-full-annotation").assertTextEquals(annotation)
        compose.onNodeWithTag("card-source-heading").performScrollTo().assertIsDisplayed()
    }
}
