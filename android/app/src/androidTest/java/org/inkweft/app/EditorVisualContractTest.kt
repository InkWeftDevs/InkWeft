package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
import org.inkweft.core.MapSceneNode
import org.junit.*
import org.junit.Assert.*

class EditorVisualContractTest {
    @get:Rule val compose=createComposeRule()

    @Test fun changingLocatorCannotMoveOrResizePanel(){
        val tag=mutableStateOf("test-panel")
        compose.setContent{InkTheme.Content{EditorPanel("参数","",{},tag.value,kind=PanelKind.CONTENT){Text("常用参数",Modifier.height(100.dp))}}}
        val before=compose.onNodeWithTag("test-panel").fetchSemanticsNode().boundsInWindow
        compose.runOnIdle{tag.value="beauty-review"}
        assertEquals(before,compose.onNodeWithTag("beauty-review").fetchSemanticsNode().boundsInWindow)
        assertFalse(panelPresentation(PanelKind.CONTENT,375.dp,800.dp).bottom)
        assertTrue(panelPresentation(PanelKind.BEAUTY_REVIEW,375.dp,800.dp).bottom)
        assertFalse(panelPresentation(PanelKind.BEAUTY_REVIEW,1280.dp,800.dp).bottom)
    }

    @Test fun parameterSegmentsKeepSeparateTouchTargetsAndSelection(){
        val selected=mutableIntStateOf(0)
        compose.setContent{InkTheme.Content{Box(Modifier.width(280.dp)){EditorSegments(listOf("常用","高级"),selected.intValue,listOf("common","advanced")){selected.intValue=it}}}}
        val common=compose.onNodeWithTag("common");val advanced=compose.onNodeWithTag("advanced")
        common.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsSelected()
        advanced.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertFalse(common.fetchSemanticsNode().boundsInRoot.overlaps(advanced.fetchSemanticsNode().boundsInRoot))
        advanced.performClick().assertIsSelected();common.assertIsNotSelected()
    }

    @Test fun viewSelectionIsAbsentFromDocumentPainting(){
        val nodes=listOf(MapSceneNode("root",null,"card","概率条件","正文",20.0,20.0,1,1))
        fun paint(selected:String?=null,style:MapViewStyle?=null)=Bitmap.createBitmap(280,130,Bitmap.Config.ARGB_8888).also{
            val canvas=Canvas(it);canvas.drawColor(Color.WHITE);MapScenePainter.draw(canvas,nodes,selected,viewStyle=style)
        }
        val document=paint();val selectedWithoutView=paint("root")
        val interactive=paint("root",MapViewStyle(Color.RED,Color.YELLOW,Color.BLACK))
        val after=paint()
        try{
            assertTrue(document.sameAs(selectedWithoutView));assertFalse(document.sameAs(interactive));assertTrue(document.sameAs(after))
            assertEquals("概率条件",nodes.single().title);assertEquals(216,MapNodeMetrics.WIDTH);assertEquals(84,MapNodeMetrics.HEIGHT)
        }finally{listOf(document,selectedWithoutView,interactive,after).forEach{it.recycle()}}
    }

    @Test fun narrowMapActionsAvoidTheNeighbouringBranch(){
        val root=android.graphics.RectF(34f,307f,314f,423f)
        val neighbour=android.graphics.RectF(390f,386f,670f,501f)
        val at=mapActionPosition(root,listOf(root,neighbour),702f,610f,384f,96f,16f)
        val bar=android.graphics.RectF(at.x,at.y,at.x+384f,at.y+96f)
        assertFalse(android.graphics.RectF.intersects(bar,root));assertFalse(android.graphics.RectF.intersects(bar,neighbour))
        assertTrue(bar.left>=0f&&bar.right<=702f&&bar.top>=0f&&bar.bottom<=610f)
    }
    @Test fun narrowToolbarKeepsInputModeVisibleWithoutWrapping(){
        var hand by mutableStateOf(false)
        compose.setContent{InkTheme.Content{Box(Modifier.width(340.dp)){
            EditorToolbar{tool,_->IconToggleButton(hand,{hand=it},modifier=Modifier.size(48.dp).testTag("slot-$tool")){Text(if(tool=="finger")if(hand)"手"else"笔"else "·")}}
        }}}
        compose.onNodeWithTag("editor-toolbar").assertHeightIsEqualTo(48.dp)
        val mode=compose.onNodeWithTag("slot-finger");mode.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val before=mode.fetchSemanticsNode().boundsInRoot;mode.performClick().assertIsOn()
        assertEquals(before,mode.fetchSemanticsNode().boundsInRoot)
        assertFalse(before.overlaps(compose.onNodeWithTag("toolbar-more").fetchSemanticsNode().boundsInRoot))
    }

}
