package org.inkweft.app

import android.graphics.*
import androidx.ink.brush.*
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PencilMaterialProbeTest {
    @Test fun nativeTilingMaterialSurvivesSoftwareCanvas() {
        val texture=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        for(y in 0..7)for(x in 0..7)texture.setPixel(x,y,if((x+y)%2==0)0xffffffff.toInt() else 0x40ffffff)
        val paint=BrushPaint(textureLayers=listOf(BrushPaint.TilingTexture(clientTextureId="probe",sizeX=8f,sizeY=8f,
            sizeUnit=BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES,origin=BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN)),selfOverlap=SelfOverlap.DISCARD)
        val family=BrushFamily(BrushTip(),paint)
        val batch=MutableStrokeInputBatch().apply{add(InputToolType.STYLUS,20f,40f,0);add(InputToolType.STYLUS,180f,40f,100)}
        val stroke=Stroke(Brush.createWithColorIntArgb(family,Color.BLACK,30f,.1f),batch)
        val bitmap=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888)
        val renderer=CanvasStrokeRenderer.create(object:TextureBitmapStore { override fun get(clientTextureId:String)=texture })
        renderer.draw(Canvas(bitmap),stroke,Matrix())
        val alphas=(40..160).map{Color.alpha(bitmap.getPixel(it,40))}.toSet()
        assertTrue("Material lost: $alphas",alphas.size>1)
    }
}
