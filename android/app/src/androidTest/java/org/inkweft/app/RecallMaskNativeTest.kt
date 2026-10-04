// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.inkweft.core.*
import org.inkweft.data.StudySourceRevisionRow
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecallMaskNativeTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun fixedSourceMaskClipsAnswerPixelsAndChildAccessibilityUntilPersistedReveal(){
        val book=id();val stroke=InkStroke(id(),InkPen.PEN,0xffd02030.toInt(),40f,InkTool.STYLUS,listOf(InkSample(400f,500f,0),InkSample(600f,500f,20)))
        // Whitespace is saved but this projection explicitly renders original source coordinates.
        val file=InkPageFile("secret-title","secret-body",listOf(stroke),false,PaperStyle.BLANK,authoring=PageAuthoring(blanks=listOf(DocumentWhitespace(id(),300.0))))
        val source=StudySourceRevisionRow(id(),1,book,book,1,300.0,400.0,700.0,600.0,stroke.id,file.encode())
        val mask=RecallRegion(source.ref(),0.0,0.0,1.0,1.0)
        lateinit var view:RecallMaskedSourceView;lateinit var child:InkCanvasView
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->view=RecallMaskedSourceView(activity);child=view.getChildAt(0)as InkCanvasView
                activity.setContentView(FrameLayout(activity).apply{addView(view,FrameLayout.LayoutParams(800,400))});view.show(file,source,listOf(0 to mask))}
            fun draw():Bitmap {val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888);scenario.onActivity{view.draw(Canvas(bitmap))};return bitmap}
            val deadline=android.os.SystemClock.uptimeMillis()+15000;var pending=true
            while(pending&&android.os.SystemClock.uptimeMillis()<deadline){draw().recycle();android.os.SystemClock.sleep(60);scenario.onActivity{pending=child.rasterPending}}
            assertFalse(pending)
            draw().let{assertEquals(0xffdce7e1.toInt(),it.getPixel(400,200));it.recycle()}
            scenario.onActivity{assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,child.importantForAccessibility);assertFalse(view.contentDescription.contains("secret"))}
            // Changing local event callback is insufficient: only the new committed mask set reveals.
            scenario.onActivity{view.onReveal={};view.interactionsEnabled=true}
            draw().let{assertEquals(0xffdce7e1.toInt(),it.getPixel(400,200));it.recycle()}
            scenario.onActivity{view.show(file,source,emptyList())}
            draw().let{assertNotEquals(0xffdce7e1.toInt(),it.getPixel(400,200));assertTrue((it.getPixel(400,200) shr 16 and 255)>150);it.recycle()}
        }
    }
}
