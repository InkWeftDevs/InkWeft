// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

class ExcerptLassoRegressionTest {
    @Test fun freehandExcerptExcludesTheOtherCornerOfItsBoundingBox(){
        val ins=InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            val image=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
            val bytes=try{image.eraseColor(Color.RED);ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{image.recycle()}
            val objectImage=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,100f,100f,300f,300f,image=Base64.getEncoder().encodeToString(bytes))
            val view=InkCanvasView(ins.targetContext).apply{preview=true;configure(false,PaperStyle.BLANK,null);layout(0,0,500,707);showObjects(listOf(objectImage))}
            val frame=Bitmap.createBitmap(500,707,Bitmap.Config.ARGB_8888)
            try{
                view.draw(Canvas(frame))
                val region=InkRegion(listOf(EraserPoint(100f,100f),EraserPoint(400f,100f),EraserPoint(100f,400f)),rectangle=false)
                fun pixel(preview:ByteArray,x:Double,y:Double):Int{
                    val bitmap=checkNotNull(BitmapFactory.decodeByteArray(preview,0,preview.size))
                    return try{bitmap.getPixel((bitmap.width*x).toInt(),(bitmap.height*y).toInt())}finally{bitmap.recycle()}
                }
                val lasso=view.excerptPreview(region)
                val inside=pixel(lasso,.2,.2);assertTrue(Color.red(inside)>220&&Color.green(inside)<40)
                val outside=pixel(lasso,.8,.8);assertTrue(Color.red(outside)>240&&Color.green(outside)>240&&Color.blue(outside)>240)
                val rectangle=pixel(view.excerptPreview(region.bounds),.8,.8)
                assertTrue(Color.red(rectangle)>220&&Color.green(rectangle)<40)
            }finally{view.showObjects(emptyList());frame.recycle()}
        }
    }
}
