// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.sin

class BrushPressureRegressionTest {
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }

    @Test fun lightPressureUsesAWidthFloorAndKeepsRawSamples() {
        val samples=(0..40).map{InkSample(40f+it*6,80f,it*8L,.1f,.2f,.3f)}
        fun draw(recipe:BrushRecipe,pressure:Float):Long {
            val original=samples.map{it.copy(pressure=pressure)}
            val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,20f,InkTool.STYLUS,
                original,appearance=StrokeAppearance(recipe.copy(roundNib=true)))
            val bitmap=Bitmap.createBitmap(320,160,Bitmap.Config.ARGB_8888)
            return try {
                InkBrushes.renderer().draw(Canvas(bitmap),InkBrushes.stroke(stroke),Matrix())
                assertEquals(original,stroke.samples)
                assertEquals(original,InkStrokeCodec.decode(InkStrokeCodec.encodeStored(stroke)).samples)
                pixels(bitmap).sumOf{Color.alpha(it).toLong()}
            } finally { bitmap.recycle() }
        }
        val current=BrushRecipe();val light=draw(current,.1f);val heavy=draw(current,.9f)
        val oldLight=draw(current.copy(version=1),.1f)
        assertTrue("Low pressure must remain visible",light>0)
        assertTrue("The floor must not lift the entire low-pressure range",light<oldLight*.45)
        assertTrue("Pressure must retain a useful width range",heavy>light*4)
        assertTrue("Zero pressure retains the configured floor",draw(current,0f)>0)
    }

    @Test fun versionOneAuthorInkRendersIdenticallyAfterStorageRoundTrip() {
        val source=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,Color.BLACK,18f,InkTool.STYLUS,
            (0..100).map{n->InkSample(160+n*6f,150+20*sin(n*.12f),n*8L,.05f+.9f*n/100f)},
            appearance=StrokeAppearance(BrushRecipe(version=1),123))
        val decoded=InkStrokeCodec.decode(InkStrokeCodec.encodeStored(source))
        assertEquals(1,decoded.appearance.recipe.version)
        val first=Bitmap.createBitmap(900,640,Bitmap.Config.ARGB_8888)
        val second=Bitmap.createBitmap(900,640,Bitmap.Config.ARGB_8888)
        try {
            val renderer=InkBrushes.renderer()
            for((bitmap,stroke) in listOf(first to source,second to decoded)) {
                val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
                renderer.draw(canvas,InkBrushes.stroke(stroke),Matrix())
            }
            assertArrayEquals(pixels(first),pixels(second))
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"brush-pen-v1.png")
                .outputStream().use{first.compress(Bitmap.CompressFormat.PNG,100,it)}
        } finally { first.recycle();second.recycle() }
    }

    @Test fun mutablePresetsUpgradeWithoutRewritingAuthorRecipes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="pen-pressure-${UUID.randomUUID()}";val favoritesName="$name-favorites"
        val original=BrushRecipe(version=1,sensitivity=.75f,roundNib=true)
        val encoded=Base64.encodeToString(original.encode(),Base64.NO_WRAP)
        try {
            context.getSharedPreferences(name,0).edit().putString("recipe-1",encoded)
                .putString("pen-PEN-recipe",encoded).commit()
            val store=PenWidthStore(context,name)
            assertEquals(original.currentPreset(),store.readRecipes()[1])
            assertEquals(original.currentPreset(),store.readPen(InkPen.PEN).recipe)
            store.applyRecipe(1,BrushRecipe())
            store.savePen(InkPen.PEN,PenSettings(3f,Color.BLACK,BrushRecipe()))
            assertEquals(BrushRecipe.CURRENT_VERSION,store.readPen(InkPen.PEN).recipe.version)
            val row=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","PEN")
                .put("width",3).put("color",Color.BLACK).put("recipe",encoded)
            context.getSharedPreferences(favoritesName,0).edit().putString("pens",JSONArray().put(row).toString()).commit()
            assertEquals(original.currentPreset(),FavoritePenStore(context,favoritesName).read().single().recipe)
            assertEquals(original,BrushRecipe.decode(Base64.decode(encoded,Base64.NO_WRAP)))
        } finally { context.deleteSharedPreferences(name);context.deleteSharedPreferences(favoritesName) }
    }
}
