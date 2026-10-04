// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class KnowledgeRelationEditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun id()=UUID.randomUUID().toString()
    @Test fun nativeEditorPersistsStyleDirectionAnnotationAndVisibilityWithoutChangingHierarchy(){
        val app=compose.activity.application as InkWeftApplication
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val book=runBlocking{app.workspaceRepository.create("关系样式合成测试",false,PaperStyle.BLANK)}
        val a=id();val b=id();val nodeA=id();val nodeB=id();val link=id()
        runBlocking{
            app.study.submit(StudyCommand(id(),book.id,StudyAction.CREATE,cardId=a,nodeId=nodeA,title="前提",body="甲"))
            app.study.submit(StudyCommand(id(),book.id,StudyAction.CREATE,cardId=b,nodeId=nodeB,parentId=nodeA,title="结论",body="乙"))
            app.knowledge.submit(KnowledgeCommand(id(),book.id,link,0,KnowledgeData.Link(TargetRef(TargetKind.CARD,a),TargetRef(TargetKind.CARD,b),RelationKind.DERIVATION)))
        }
        val db=NoteDatabase.open(app)
        try{
            val row=runBlocking{db.knowledge().get(link)!!};val closed=java.util.concurrent.atomic.AtomicBoolean(false)
            compose.runOnUiThread{compose.activity.setContent{InkWeftTheme{KnowledgeRelationEditor(row){closed.set(true)}}}}
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("relation-style-SOLID").assertIsEnabled()}.isSuccess}
            compose.onNodeWithTag("relation-style-SOLID").performScrollTo().performClick()
            compose.onNodeWithTag("relation-direction-BOTH").performScrollTo().performClick()
            compose.onNodeWithTag("relation-annotation").performScrollTo().performTextInput("仅在条件成立时适用")
            compose.onNodeWithTag("relation-visible").performScrollTo().performClick()
            compose.onNodeWithTag("relation-save").performClick()
            compose.waitUntil(15_000){closed.get()}
            val saved=runBlocking{db.knowledge().get(link)!!};val value=saved.data() as KnowledgeData.Link
            assertEquals(2L,saved.revision);assertEquals(RelationLineStyle.SOLID,value.lineStyle);assertEquals(RelationDirection.BOTH,value.direction)
            assertEquals("仅在条件成立时适用",value.annotation);assertFalse(value.visible)
            assertEquals(nodeA,runBlocking{db.study().node(nodeB)!!.parentId})
            assertEquals(row.payload.toList(),runBlocking{db.knowledge().revision(link,1)!!.payload}.toList())
            compose.runOnUiThread{compose.activity.setContent{InkWeftTheme{KnowledgeRelationEditor(saved){}}}}
            compose.waitUntil(15_000){runCatching{compose.onNodeWithTag("relation-style-SOLID").assertIsSelected()}.isSuccess}
            compose.onNodeWithTag("relation-annotation").assertTextContains("仅在条件成立时适用")
            compose.onNodeWithTag("relation-visible").assertIsOff()
        }finally{db.close()}
    }
}
