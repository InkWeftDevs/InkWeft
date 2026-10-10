// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LinkedCardReturnRegressionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun await(tag:String){compose.waitUntil(20_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(tag:String){compose.revealAction(tag);await(tag);compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}

    @Test fun bodyKeywordReturnsToOriginCardAndUnavailableOriginKeepsTheReturnRecord(){
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        val keys=listOf("finger-writes")
        val saved=keys.associateWith{if(prefs.contains(it))prefs.getBoolean(it,false)else null}
        prefs.edit().putBoolean("finger-writes",false).commit()
        try{
            await("new-note")
            val body="检查条件概率并返回原摘要。";val sourceCard=id();val targetCard=id()
            val (origin,target)=runBlocking {
                val a=app.workspaceRepository.create("合成正文关联起点",false,PaperStyle.BLANK)
                val b=app.workspaceRepository.create("合成正文关联目标",false,PaperStyle.BLANK)
                app.study.submit(StudyCommand(id(),a.id,StudyAction.CREATE,cardId=sourceCard,nodeId=id(),title="原摘要",body=body))
                app.study.submit(StudyCommand(id(),b.id,StudyAction.CREATE,cardId=targetCard,nodeId=id(),title="条件概率",body="关联目标正文"))
                app.knowledge.submit(KnowledgeCommand(id(),a.id,id(),0,KnowledgeData.Link(TargetRef(TargetKind.CARD,sourceCard),TargetRef(TargetKind.CARD,targetCard))))
                a to b
            }
            val provider=ViewModelProvider(compose.activity);val notebook=provider[NotebookViewModel::class.java];val workspace=provider[WorkspaceViewModel::class.java]
            compose.runOnIdle{notebook.select(origin)}
            compose.waitUntil(20_000){app.navigationReady.value};tap("quick-study");tap("study-tab-0");tap("study-card-$sourceCard");await("card-full-body")
            fun keyword(){
                val start=body.indexOf("条件概率");var layout:TextLayoutResult?=null
                compose.waitUntil(20_000){val results=mutableListOf<TextLayoutResult>();compose.onNodeWithTag("card-full-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
                    layout=results.singleOrNull();layout?.layoutInput?.text?.getLinkAnnotations(start,start+4)?.isNotEmpty()==true}
                compose.onNodeWithTag("card-full-body").performTouchInput{click(checkNotNull(layout).getBoundingBox(start+1).center)}
            }
            keyword();await("card-link-preview-body");compose.onNodeWithTag("card-link-preview-body").assertTextEquals("关联目标正文")
            tap("card-link-close-preview");compose.onNodeWithTag("card-full-body").assertTextEquals(body)
            assertTrue(compose.runOnIdle{workspace.knowledgeReturns.value.isEmpty()})
            keyword();tap("card-link-open-target")
            compose.waitUntil(20_000){compose.runOnIdle{notebook.ui.value.selectedId==target.id&&app.openKnowledgeTarget.value==null}}
            await("knowledge-return-context")
            val location=compose.runOnIdle{checkNotNull(workspace.peekKnowledgeReturn())}
            assertEquals(TargetRef(TargetKind.CARD,sourceCard),location.target);assertEquals(origin.id,location.book)
            val stack=compose.runOnIdle{workspace.knowledgeReturns.value.toList()}
            runBlocking{val row=app.workspaceRepository.get(origin.id);assertTrue(app.workspaceRepository.organize(origin.id,row.revision,row.folder,row.tags,row.favorite,true))}
            try{
                tap("knowledge-return-context");compose.waitUntil(20_000){app.openKnowledgeTarget.value==null}
                assertEquals(target.id,compose.runOnIdle{notebook.ui.value.selectedId})
                assertEquals(stack,compose.runOnIdle{workspace.knowledgeReturns.value.toList()})
            }finally{runBlocking{val row=app.workspaceRepository.get(origin.id);assertTrue(app.workspaceRepository.organize(origin.id,row.revision,row.folder,row.tags,row.favorite,false))}}
            tap("knowledge-return-context")
            compose.waitUntil(20_000){compose.runOnIdle{notebook.ui.value.selectedId==origin.id&&workspace.knowledgeReturns.value.size==stack.size-1}}
            await("card-full-body");compose.onNodeWithTag("card-full-body").assertTextEquals(body)
        }finally{
            val edit=prefs.edit();saved.forEach{(key,value)->if(value==null)edit.remove(key)else edit.putBoolean(key,value)};edit.commit()
        }
    }
}
