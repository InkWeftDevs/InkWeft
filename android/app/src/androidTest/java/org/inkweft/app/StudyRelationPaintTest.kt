// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.RelationDirection
import org.inkweft.core.RelationLineStyle
import org.inkweft.data.StudyNodeRow
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

/** Native offscreen paint check; does not stand in for on-device legibility or touch review. */
class StudyRelationPaintTest {
    @Test fun relationModePaintsIndependentDashSolidBothArrowsAndShortAnnotation(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            fun id()=UUID.randomUUID().toString()
            val book=id();val first=StudyNodeRow(id(),book,id(),null,40.0,80.0)
            val second=StudyNodeRow(id(),book,id(),first.id,430.0,200.0)
            val view=MindMapView(ApplicationProvider.getApplicationContext()).apply{
                layout(0,0,1100,700);showRelations(listOf(first,second),mapOf(first.id to "源知识",second.id to "目标知识"),emptyList());fitOverview()
            }
            fun render(edge:StudyRelationEdge?):IntArray{
                view.setKnowledgeRelations(listOfNotNull(edge))
                val bitmap=Bitmap.createBitmap(1100,700,Bitmap.Config.ARGB_8888)
                return try{view.draw(Canvas(bitmap));IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}}finally{bitmap.recycle()}
            }
            val edge=StudyRelationEdge(first.id,second.id,listOf("归纳总结"),listOf(id()))
            val empty=render(null);val dashed=render(edge);val solid=render(edge.copy(lineStyle=RelationLineStyle.SOLID))
            val both=render(edge.copy(direction=RelationDirection.BOTH))
            val annotated=render(edge.copy(annotations=listOf("明确的归纳解释".repeat(60))))
            assertFalse(empty.contentEquals(dashed));assertFalse(dashed.contentEquals(solid))
            assertFalse(dashed.contentEquals(both));assertFalse(dashed.contentEquals(annotated))
        }
    }
}
