// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.InkStrokeCodec

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Focused production-component regression; the full fixture still exercises the normal application route. */
class RecallAnswerPadUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun wideAndNarrowPadsAcceptBothEdgesAndKeepExistingCoordinates(){
        val available=mutableStateOf(960.dp);val answer=mutableStateOf(byteArrayOf())
        compose.runOnUiThread{compose.activity.setContent{InkTheme.Content{
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
                Column(Modifier.widthIn(max=available.value).fillMaxWidth().testTag("answer-pad-container")){
                    RecallAnswerPad("answer-pad-layout-regression",answer.value,true,{answer.value=it})
                }
            }
        }}}
        compose.onNodeWithTag("recall-answer-finger").assertIsDisplayed().performTouchInput{click()}
        var count=0
        for(availableWidth in listOf(960.dp,320.dp)){
            val before=compose.runOnIdle{answer.value.copyOf()}
            compose.runOnIdle{available.value=availableWidth};compose.waitForIdle()
            compose.runOnIdle{assertArrayEquals("Resize must not rewrite saved answer coordinates",before,answer.value)}
            val pad=compose.onNodeWithTag("recall-answer-ink-canvas").assertIsDisplayed()
            val bounds=pad.fetchSemanticsNode().boundsInRoot
            val parent=compose.onNodeWithTag("answer-pad-container").fetchSemanticsNode().boundsInRoot
            assertTrue("The visible pad must have the fixed answer aspect ratio",abs(bounds.width-bounds.height*1000f/420f)<=2f)
            assertEquals("The pad must be centered",parent.center.x,bounds.center.x,1f)
            assertTrue("The pad must stay at most 240dp tall",bounds.height<=with(compose.density){240.dp.toPx()}+1f)
            for(rightToLeft in listOf(false,true)){
                val old=compose.runOnIdle{RecallStudyViewModel.answerStrokes(answer.value)}
                pad.performTouchInput{
                    val start=if(rightToLeft).95f else .05f;val end=1f-start
                    down(Offset(width*start,height*.25f));moveTo(Offset(width*end,height*.75f),240);up()
                }
                count++
                compose.waitUntil(10_000){RecallStudyViewModel.answerStrokes(answer.value).size==count}
                compose.runOnIdle{
                    val strokes=RecallStudyViewModel.answerStrokes(answer.value)
                    assertEquals(old.size,strokes.size-1)
                    // InkStroke has identity equality; compare every persisted field after decoding.
                    old.forEachIndexed{i,stroke->assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(strokes[i]))}
                    val samples=strokes.last().samples
                    assertTrue("Left paper edge must accept ink",samples.minOf{it.x}<100f)
                    assertTrue("Right paper edge must accept ink",samples.maxOf{it.x}>900f)
                    assertTrue(samples.all{it.x in 0f..1000f&&it.y in 0f..420f})
                }
            }
        }
    }
}
