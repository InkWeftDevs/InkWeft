// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.inkweft.data.StudySourceRow
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LayerSnapshotPreviewTest {
    private fun id()=UUID.randomUUID().toString()
    private fun <T> main(block:()->T):T {var result:T?=null;InstrumentationRegistry.getInstrumentation().runOnMainSync{result=block()};@Suppress("UNCHECKED_CAST")return result as T}
    @Test fun frozenMapThumbnailNeverRendersTheHiddenLayerFromItsFile(){
        fun stroke(x:Float,color:Int)=InkStroke(id(),InkPen.PEN,color,8f,InkTool.STYLUS,listOf(InkSample(x,40f,0),InkSample(x+50,90f,10)))
        val black=stroke(30f,0xff111111.toInt());val red=stroke(400f,0xffff0000.toInt());val blue=stroke(100f,0xff0000ee.toInt())
        val hidden=UserLayer(id(),"隐藏答案",visible=false);val locked=UserLayer(id(),"锁定原迹",locked=true)
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),hidden,locked),UserLayers.DEFAULT_ID,listOf(
            LayerMembership(LayerContent(LayerContentKind.INK,black.id),UserLayers.DEFAULT_ID),LayerMembership(LayerContent(LayerContentKind.INK,red.id),hidden.id),LayerMembership(LayerContent(LayerContentKind.INK,blue.id),locked.id))),listOf(DocumentWhitespace(id(),300.0),DocumentWhitespace(id(),800.0)))
        val card=id();val source=StudySourceRow(card,id(),1,0.0,0.0,800.0,800.0,"",InkPageFile("冻结隐藏图层","",listOf(black,red,blue),authoring=state).encode())
        val preview=main{MapSourcePreview({source},{})}
        try{
            main{preview.request(listOf(card to 1L))}
            val end=SystemClock.uptimeMillis()+15000
            while(main{preview.frames()[card]?.let{it.bitmap==null&&!it.unavailable}?:true}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(50)
            val bitmap=main{checkNotNull(preview.frames()[card]?.bitmap).copy(Bitmap.Config.ARGB_8888,false)}
            try{var dark=0;for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val pixel=bitmap.getPixel(x,y);val r=(pixel ushr 16)and 255;val g=(pixel ushr 8)and 255;val b=pixel and 255
                assertFalse("hidden red answer leaked into frozen thumbnail",r>180&&g<80&&b<80)
                if(r<120&&g<120)dark++
            };assertTrue(dark>10)}finally{bitmap.recycle()}
            assertEquals(3,InkPageFile.decode(source.snapshot).strokes.size);assertEquals(2,state.blanks.size)
        }finally{main{preview.clear()}}
    }
}
