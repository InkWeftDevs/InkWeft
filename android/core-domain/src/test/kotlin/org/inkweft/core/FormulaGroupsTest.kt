// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FormulaGroupsTest {
    private fun stroke(x:Float,y:Float,w:Float,h:Float,pen:InkPen=InkPen.PEN)=InkStroke(
        UUID.randomUUID().toString(),pen,0xff222222.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(x,y,0),InkSample(x+w,y+h,10)))
    private fun fraction()=listOf(stroke(120f,100f,15f,20f),stroke(100f,132f,70f,0f),stroke(120f,144f,15f,20f))

    @Test fun numeratorBarAndDenominatorRemainOneTwoDimensionalCrop(){
        val ink=fraction();val block=FormulaGroups.block(ink)
        assertEquals(ink,block.strokes)
        ink.forEach{assertTrue(block.bounds.left<it.bounds().left&&block.bounds.bottom>it.bounds().bottom)}
        assertEquals(listOf(ink),FormulaGroups.automatic(ink).map{it.strokes})
    }
    @Test fun scriptsAndRootBarKeepInputOrderWhileDistantExpressionsStaySeparate(){
        val first=fraction()+stroke(180f,125f,25f,30f)+stroke(208f,113f,10f,12f)
        val second=fraction().map{InkSelectionEdit.copy(listOf(it),0f,300f).single()}
        assertEquals(listOf(first,second),FormulaGroups.automatic(first+second).map{it.strokes})
        assertEquals(first+second,FormulaGroups.block(first+second).strokes)
    }
    @Test fun highlightersNeverBecomeFormulaSymbolsAndRecognitionRemainsBounded(){
        val ink=fraction();val highlight=stroke(90f,100f,200f,0f,InkPen.HIGHLIGHTER)
        assertEquals(ink,FormulaGroups.block(ink+highlight).strokes)
        assertEquals(listOf(ink),FormulaGroups.automatic(ink+highlight).map{it.strokes})
        assertTrue(FormulaGroups.automatic(listOf(highlight)).isEmpty())
        assertThrows(IllegalArgumentException::class.java){FormulaGroups.block(List(FormulaGroups.MAX_STROKES+1){ink.first()})}
    }
}
