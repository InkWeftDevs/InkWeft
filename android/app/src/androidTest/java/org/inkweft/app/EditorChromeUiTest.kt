// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class EditorChromeUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun inputLockKeepsSettledIconPixelsWhileStillDisablingClicks(){
        val locked=mutableStateOf(false);val enabled=mutableStateOf(true)
        compose.setContent{InkTheme.Content{CompositionLocalProvider(LocalEditorTransientLock provides locked.value){
            Row{EditorTool("笔","pen",true,enabled.value,"pen"){};EditorAction("撤销","undo",false,"undo"){}}
        }}}
        fun pixels(tag:String):List<androidx.compose.ui.graphics.Color>{val p=compose.onNodeWithTag(tag).captureToImage().toPixelMap();return (0 until p.height).flatMap{y->(0 until p.width).map{x->p[x,y]}}}
        val selected=pixels("pen");val unavailable=pixels("undo")
        compose.runOnIdle{locked.value=true;enabled.value=false}
        compose.onNodeWithTag("pen").assertIsNotEnabled();compose.onNodeWithTag("undo").assertIsNotEnabled()
        assertEquals(selected,pixels("pen"));assertEquals(unavailable,pixels("undo"))
        compose.runOnIdle{locked.value=false}
        assertNotEquals(selected,pixels("pen"))
    }

    @Test fun largeTextShortcutsShareTargetsAndKeepNamesInTooltips(){
        compose.setContent{
            InkTheme.Content{
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.6f)){
                    Row{
                        EditorTool("笔","pen",true,true,"pen"){}
                        EditorAction("导图","mindmap",tag="map"){}
                        EditorAction("撤销","undo",false,"undo"){}
                    }
                }
            }
        }
        for(tag in listOf("pen","map","undo")){
            compose.onNodeWithTag(tag).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        }
        compose.onNodeWithTag("pen").assertIsSelected().assertContentDescriptionEquals("笔")
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithText("导图").assertDoesNotExist()
        compose.onNodeWithTag("map").performTouchInput{longClick()}
        compose.onNodeWithText("导图").assertIsDisplayed()
    }
}
