package org.inkweft.app

import android.view.MotionEvent
import android.view.InputDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test

class BrushB1InputTest {
    @Test fun singlePaperPencilDrawsWhileDownAndPersistsPageCoordinates(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view=InkCanvasView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.configure(false,PaperStyle.BLANK,null);view.layout(0,0,600,600);view.allowInput=true;view.fingerWrites=true;view.pen=InkPen.PENCIL
            var result:InkStroke?=null;view.onStroke={result=it}
            val bitmap=android.graphics.Bitmap.createBitmap(600,600,android.graphics.Bitmap.Config.ARGB_8888)
            listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).forEachIndexed{i,action->
                val e=MotionEvent.obtain(100,100+i*10L,action,200+i*80f,300f,0);view.onTouchEvent(e);e.recycle()
                view.draw(android.graphics.Canvas(bitmap))
            }
            val stroke=checkNotNull(result);assertFalse(stroke.world);assertTrue(stroke.samples.all{!it.world})
            assertEquals(stroke.samples,InkStrokeCodec.decode(InkStrokeCodec.encode(stroke)).samples);bitmap.recycle()
        }
    }
    @Test fun fingerFirstThenStylusUsesActivePointerAndFreezesRecipe(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view=InkCanvasView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.configure(true,PaperStyle.BLANK,CanvasViewport(0.0,0.0,1.0));view.layout(0,0,600,600)
            view.allowInput=true;view.pen=InkPen.PENCIL;view.brushRecipe=BrushRecipe(hardness=2)
            var result:InkStroke?=null;view.onStroke={result=it}
            fun event(action:Int,time:Long,penX:Float){
                val count=if(action==MotionEvent.ACTION_DOWN)1 else 2
                val props=Array(count){i->MotionEvent.PointerProperties().apply{id=if(i==0)4 else 9;toolType=if(i==0)MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_STYLUS}}
                val coords=Array(count){i->MotionEvent.PointerCoords().apply{x=if(i==0)50f else penX;y=if(i==0)50f else 350f;pressure=.5f;size=1f}}
                val e=MotionEvent.obtain(100,time,action,count,props,coords,0,0,1f,1f,-1,0,InputDevice.SOURCE_STYLUS,0)
                view.onTouchEvent(e);e.recycle()
            }
            event(MotionEvent.ACTION_DOWN,100,400f)
            event(MotionEvent.ACTION_POINTER_DOWN or(1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),110,400f)
            view.brushRecipe=BrushRecipe(hardness=0);view.pen=InkPen.PEN;view.penColor=0xffff0000.toInt()
            event(MotionEvent.ACTION_MOVE,120,420f)
            event(MotionEvent.ACTION_POINTER_UP or(1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),130,440f)
            val stroke=checkNotNull(result);assertEquals(InkTool.STYLUS,stroke.tool);assertEquals(InkPen.PENCIL,stroke.pen)
            assertEquals(2,stroke.appearance.recipe.hardness);assertNotEquals(0xffff0000.toInt(),stroke.color)
            assertTrue(stroke.samples.first().x>0);assertTrue(stroke.samples.last().x>stroke.samples.first().x)
            assertTrue(stroke.samples.all{it.pressure==-1f}) // Synthetic event has no hardware pressure range.
        }
    }
}
