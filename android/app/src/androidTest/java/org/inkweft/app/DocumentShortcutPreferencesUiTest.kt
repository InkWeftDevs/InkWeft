// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.InkPen
import org.inkweft.core.Note
import org.inkweft.core.PaperStyle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Real document chrome, toolbar customization and mode switches on synthetic notes. */
class DocumentShortcutPreferencesUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val editor get()=app.getSharedPreferences("inkweft-editor",0)
    private val saved=linkedMapOf<String,Map<String,Any?>>()
    private var ownedBook:String?=null
    private val tags=mapOf("map" to "quick-study","associations" to "document-associations","excerpts" to "read-excerpts")
    private fun notebook()=ViewModelProvider(compose.activity)[NotebookViewModel::class.java]
    private fun preferences(name:String)=app.getSharedPreferences(name,0).all.mapValues{(_,value)->if(value is Set<*>)value.toSet()else value}

    @Before fun isolatePreferences(){
        listOf("inkweft-editor","inkweft-reading","inkweft-study-window","inkweft-open-tabs","inkweft-learning")
            .forEach{saved[it]=preferences(it)}
        assertTrue(editor.edit().putString("toolbar-order-v32",EditorToolOrder.labels.keys.joinToString(","))
            .putStringSet("toolbar-hidden-v32",EditorToolOrder.defaultHidden)
            .putBoolean("case-collapsed",true).commit())
    }

    @After fun restorePreferences(){
        try{
            if(compose.onAllNodesWithTag("exit-fullscreen").fetchSemanticsNodes().isNotEmpty())tap("exit-fullscreen")
            compose.runOnIdle{
                ownedBook?.let{ViewModelProvider(compose.activity)["read-lock-$it",BookReadLockViewModel::class.java].request(false)}
                notebook().back();ownedBook?.let{notebook().closeTab(it)}
            }
            waitFor("new-note")
        }finally{
            saved.forEach{(name,values)->
                val prefs=app.getSharedPreferences(name,0)
                val restore=prefs.edit()
                (prefs.all.keys-values.keys).forEach{restore.remove(it)}
                values.forEach{(key,value)->put(restore,key,value)}
                assertTrue("Restore $name",restore.commit())
            }
        }
    }

    private fun put(edit:SharedPreferences.Editor,key:String,value:Any?){when(value){
        is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value)
        is Float->edit.putFloat(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value)
        is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        null->edit.remove(key);else->error("Unexpected preference type ${value.javaClass.name}")
    }}
    private fun waitFor(tag:String){
        compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun tap(tag:String){
        waitFor(tag)
        val node=compose.onNodeWithTag(tag)
        if(runCatching{node.assertIsDisplayed()}.isFailure)node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput{click()}
        compose.waitForIdle()
    }
    private fun openBook():Note{
        waitFor("new-note")
        val note=runBlocking{app.workspaceRepository.create("快捷入口合成验证 ${UUID.randomUUID().toString().take(8)}",false,PaperStyle.BLANK)}
        ownedBook=note.id
        assertTrue(app.getSharedPreferences("inkweft-reading",0).edit().putBoolean("continuous-v20-${note.id}",false).commit())
        assertTrue(app.getSharedPreferences("inkweft-study-window",0).edit().putString("${note.id}-mode","FOCUS").commit())
        compose.runOnIdle{notebook().select(note)}
        compose.singlePageEditor();compose.waitForSavedInk()
        return note
    }
    private fun customize(){tap("toolbar-more");tap("toolbar-customize")}
    private fun moveFirst(id:String){
        compose.onNodeWithTag("toolbar-drag-$id").performScrollTo().performClick()
        compose.onNodeWithText("移到最前").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    private fun fullScreen(){tap("document-more");tap("quick-fullscreen");waitFor("exit-fullscreen")}
    private fun assertDestinations(host:String,expected:List<String>){
        val expectedTags=expected.map{tags.getValue(it)}
        val nodes=compose.onAllNodes(hasAnyAncestor(hasTestTag(host)) and
            (hasTestTag("quick-study") or hasTestTag("document-associations") or hasTestTag("read-excerpts")))
            .fetchSemanticsNodes()
        assertEquals(expectedTags,nodes.map{it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]})
        expectedTags.forEach{compose.onNodeWithTag(it).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)}
        nodes.zipWithNext().forEach{(first,second)->
            val a=first.boundsInRoot;val b=second.boundsInRoot
            assertTrue("Destination order must match visible layout: $a then $b",a.bottom<=b.top+.5f||a.right<=b.left+.5f)
        }
    }
    private fun canvas():InkCanvasView{
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){
            val view=queue.removeFirst()
            if(view is InkCanvasView&&!view.preview&&!view.embeddedPage&&view.isShown)return view
            if(view is ViewGroup)repeat(view.childCount){queue.add(view.getChildAt(it))}
        }
        error("Visible single-page canvas missing")
    }

    @Test fun documentOrderAndVisibilityApplyImmediatelyAcrossFullScreenModesAndRecreation(){
        openBook()
        assertDestinations("document-toolbar",listOf("map","associations","excerpts"))
        compose.pinchCanvasOut()
        val viewport=compose.runOnIdle{canvas().snapshotViewport()}
        customize();moveFirst("map");moveFirst("excerpts")
        compose.onNodeWithTag("toolbar-visible-associations").performScrollTo().performClick()
        tap("toolbar-done")
        assertDestinations("document-toolbar",listOf("excerpts","map"))
        compose.onNodeWithTag("document-associations").assertDoesNotExist()
        compose.onNodeWithTag("top-excerpt").assertExists()
        compose.runOnIdle{assertEquals(viewport,canvas().snapshotViewport())}
        val order=editor.getString("toolbar-order-v32",null)
        val hidden=editor.getStringSet("toolbar-hidden-v32",null)?.toSet()
        compose.activityRule.scenario.recreate();compose.waitForSavedInk()
        assertDestinations("document-toolbar",listOf("excerpts","map"))
        assertEquals(order,editor.getString("toolbar-order-v32",null));assertEquals(hidden,editor.getStringSet("toolbar-hidden-v32",null))
        fullScreen()
        assertDestinations("editor-toolbar",listOf("excerpts","map"))
        tap("quick-readonly");waitFor("reading-toolbar")
        assertDestinations("reading-toolbar",listOf("excerpts","map"))
        compose.onNodeWithTag("quick-readonly").assertIsSelected()
        tap("exit-readonly");waitFor("editor-toolbar")
        tap("exit-fullscreen")
        customize();tap("toolbar-reset");tap("toolbar-done")
        assertDestinations("document-toolbar",listOf("map","associations","excerpts"))
        compose.onNodeWithTag("top-excerpt").assertExists()
    }

    @Test fun legacyToolOrderHiddenIdsAndPenSettingsSurviveNewDestinationPreferences(){
        val legacyIds=EditorToolOrder.labels.keys.filterNot{it in setOf("associations","excerpts")}
        val oldOrder=(listOf("camera","pen","map")+legacyIds.filterNot{it in setOf("camera","pen","map")}+"favorites")
        val oldHidden=(EditorToolOrder.defaultHidden+setOf("map","excerpt","favorites"))-setOf("camera","finger")
        assertTrue(editor.edit().putString("toolbar-order-v32",oldOrder.joinToString(","))
            .putStringSet("toolbar-hidden-v32",oldHidden).putBoolean("finger-writes",true).commit())
        val note=openBook()
        val penName="inkweft-pen-widths-book-${note.id}";saved[penName]=preferences(penName)
        val penStore=PenWidthStore(app,penName)
        runBlocking{assertTrue(penStore.savePreset(0,4.5f,0xff126b50.toInt(),InkPen.PENCIL))}
        val penBefore=preferences(penName)
        assertEquals(oldOrder.joinToString(","),editor.getString("toolbar-order-v32",null))
        assertEquals(oldHidden,editor.getStringSet("toolbar-hidden-v32",null))
        assertDestinations("document-toolbar",listOf("associations","excerpts"))
        compose.onNodeWithTag("top-excerpt").assertDoesNotExist()
        customize();compose.onNodeWithTag("toolbar-visible-excerpts").performScrollTo().performClick();tap("toolbar-done")
        assertDestinations("document-toolbar",listOf("associations"))
        val nextOrder=editor.getString("toolbar-order-v32","").orEmpty().split(',')
        assertEquals(oldOrder,nextOrder.filter{it in oldOrder})
        assertEquals(oldHidden+"excerpts",editor.getStringSet("toolbar-hidden-v32",null))
        compose.activityRule.scenario.recreate();compose.waitForSavedInk()
        assertDestinations("document-toolbar",listOf("associations"))
        compose.onNodeWithTag("top-excerpt").assertDoesNotExist()
        assertTrue(editor.getBoolean("finger-writes",false));assertEquals(penBefore,preferences(penName))
        assertEquals(nextOrder,editor.getString("toolbar-order-v32","").orEmpty().split(','))
    }

    @Test fun hiddenDestinationsRemainReachableFromDocumentWritingAndReadingMenus(){
        assertTrue(editor.edit().putStringSet("toolbar-hidden-v32",EditorToolOrder.defaultHidden+EditorToolOrder.destinations).commit())
        openBook()
        assertDestinations("document-toolbar",emptyList())
        val cases=listOf("excerpts" to "excerpt-panel-close","associations" to "knowledge-close","map" to "study-close")
        cases.forEach{(id,close)->
            tap("document-more");assertDestinations("document-more-menu",listOf("map","associations","excerpts"))
            tap(tags.getValue(id));waitFor(close);compose.onNodeWithTag("document-more-menu").assertDoesNotExist();tap(close)
        }
        fullScreen()
        assertDestinations("editor-toolbar",emptyList())
        tap("toolbar-more");assertDestinations("editor-more-menu",listOf("map","associations","excerpts"))
        tap("read-excerpts");waitFor("excerpt-panel-close");compose.onNodeWithTag("editor-more-menu").assertDoesNotExist();tap("excerpt-panel-close")
        tap("quick-readonly");waitFor("reading-toolbar")
        assertDestinations("reading-toolbar",emptyList())
        tap("toolbar-more");assertDestinations("reading-more-menu",listOf("map","associations","excerpts"))
        tap("document-associations");waitFor("knowledge-close");tap("knowledge-close")
        compose.onNodeWithTag("quick-readonly").assertIsSelected()
        assertEquals(EditorToolOrder.defaultHidden+EditorToolOrder.destinations,editor.getStringSet("toolbar-hidden-v32",null))
    }
}
