// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test

class CanvasNavigationGestureTest {
    @Test fun twoFingersPanWithoutScaleAndCancelTheFirstFingerInk(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val view=InkCanvasView(InstrumentationRegistry.getInstrumentation().targetContext)
            view.configure(true,PaperStyle.BLANK,CanvasViewport(0.0,0.0,1.0));view.layout(0,0,600,600)
            view.allowInput=true;view.fingerWrites=true;view.pen=InkPen.PENCIL
            val saved=mutableListOf<InkStroke>();val cancelled=mutableListOf<String>()
            view.onStroke={saved+=it};view.onCheckpointCancel={cancelled+=it}
            var time=100L
            fun send(action:Int,vararg points:Pair<Float,Float>){
                val properties=Array(points.size){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coordinates=Array(points.size){i->MotionEvent.PointerCoords().apply{x=points[i].first;y=points[i].second;pressure=.5f;size=.1f}}
                val event=MotionEvent.obtain(100,time,action,points.size,properties,coordinates,0,0,1f,1f,-1,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try{view.onTouchEvent(event)}finally{event.recycle()};time+=20
            }
            send(MotionEvent.ACTION_DOWN,200f to 300f)
            send(MotionEvent.ACTION_MOVE,220f to 300f)
            send(MotionEvent.ACTION_POINTER_DOWN or(1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),220f to 300f,420f to 300f)
            val before=view.snapshotViewport()
            send(MotionEvent.ACTION_MOVE,270f to 330f,470f to 330f)
            val after=view.snapshotViewport();val density=view.resources.displayMetrics.density.toDouble()
            assertEquals(before.zoom,after.zoom,.00001)
            assertEquals(before.centerX-50/density,after.centerX,.001)
            assertEquals(before.centerY-30/density,after.centerY,.001)
            send(MotionEvent.ACTION_POINTER_UP or(1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),270f to 330f,470f to 330f)
            send(MotionEvent.ACTION_MOVE,280f to 340f)
            send(MotionEvent.ACTION_UP,280f to 340f)
            assertTrue(saved.isEmpty());assertEquals(1,cancelled.size)
            send(MotionEvent.ACTION_DOWN,200f to 300f);send(MotionEvent.ACTION_MOVE,230f to 310f);send(MotionEvent.ACTION_UP,240f to 320f)
            assertEquals(1,saved.size);assertEquals(InkTool.TOUCH,saved.single().tool)
        }
    }

    @Test fun appendRequiresDirectEndPullAndReleaseOnce(){
        val pull=LastPagePull(72f)
        pull.drag(200f,true,true);assertFalse(pull.release()) // No touch: fling/programmatic scroll.
        pull.begin();pull.drag(200f,false,true);assertFalse(pull.release()) // Middle page.
        pull.begin();pull.drag(200f,true,false);assertFalse(pull.release()) // Read-only/busy/recovery.
        pull.begin();pull.drag(80f,true,true);pull.drag(-20f,true,true);assertFalse(pull.release())
        pull.begin();pull.drag(40f,true,true);pull.drag(40f,true,true)
        assertTrue(pull.release());assertFalse(pull.release())
        pull.begin();pull.drag(80f,true,true);pull.cancel();assertFalse(pull.release())
    }
}
