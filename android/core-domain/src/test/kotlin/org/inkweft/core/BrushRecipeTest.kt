package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BrushRecipeTest {
    private fun widthRatio(recipe:BrushRecipe,pressure:Float)=recipe.minimumWidth+(1f-recipe.minimumWidth)*recipe.penPressure(pressure)
    @Test fun pressureFloorPreservesLowPressureRangeAndMatchesRecordedStableEndpoints(){
        val current=BrushRecipe();val old=BrushRecipe(version=1)
        assertEquals(.4f,widthRatio(old,0f),.000001f)
        assertEquals(.4755355f,widthRatio(old,.1f),.000001f)
        assertEquals(.025f,widthRatio(current,0f),.000001f)
        assertEquals(.1258925f,widthRatio(current,.1f),.000001f)
        for(sensitivity in listOf(0f,.5f,1f)){
            val r=current.copy(sensitivity=sensitivity)
            val widths=(0..1000).map{widthRatio(r,it/1000f)}
            assertTrue(widths.all{it in .025f..1f});assertTrue(widths.zipWithNext().all{(a,b)->a<=b})
            assertEquals(1f,widths.last(),0f)
            assertEquals(widthRatio(r,.65f),widthRatio(r,-1f),0f)
        }
        // Uploaded recorded synthetic input, not physical pen measurements:
        // events SHA a10f260d...646d2; stable real-ink endpoints callId 43, 2827, 2841.
        // The native capture uses exponent .75; the UI sensitivity scales differ.
        val calibrated=current.copy(sensitivity=.75f)
        for((pressure,width) in listOf(.5f to 1.206298828125f,.1f to .36077880859375f,.9f to 1.87457275390625f))
            assertEquals(width,2.0287158489227295f*widthRatio(calibrated,pressure),.00005f)
    }
    @Test fun recipeVersionsRoundTripWithoutChangingAuthorSamplesAndPresetUpgradeIsSeparate(){
        for(version in 0..BrushRecipe.CURRENT_VERSION){
            val recipe=BrushRecipe(version=version,sensitivity=.75f)
            assertEquals(recipe,BrushRecipe.decode(recipe.encode()))
            val source=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff171717.toInt(),3f,InkTool.STYLUS,
                listOf(InkSample(10f,10f,0,.1f,.3f,.4f),InkSample(20f,10f,16,.9f,.5f,.6f)),appearance=StrokeAppearance(recipe))
            val canonical=InkStrokeCodec.encode(source)
            for(bytes in listOf(canonical,InkStrokeCodec.encodeStored(source))){
                val decoded=InkStrokeCodec.decode(bytes)
                assertEquals(version,decoded.appearance.recipe.version);assertEquals(source.samples,decoded.samples)
                assertArrayEquals(canonical,InkStrokeCodec.encode(decoded))
            }
            assertEquals(BrushRecipe.CURRENT_VERSION,recipe.currentPreset().version)
            assertEquals(version,recipe.version)
        }
    }
    private fun stroke(world:Boolean=false)=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,0xff171717.toInt(),12f,InkTool.STYLUS,
        listOf(InkSample(100f,if(world)1400f else 100f,0,.1f,1f,world=world),InkSample(150f,if(world)1428f else 150f,100,.8f,1f,world=world)),world,
        appearance=StrokeAppearance(BrushRecipe(hardness=2,grain=1.4f),987654321L,100f,if(world)1400f else 100f))
    @Test fun codecAndContentCopyKeepRecipeMasksAndRawAxes(){
        val s=stroke().withCuts(listOf(InkCut(UUID.randomUUID().toString(),3f,listOf(EraserPoint(120f,120f)))))
        val reopened=InkStrokeCodec.decode(InkStrokeCodec.encode(s))
        assertEquals(s.appearance,reopened.appearance);assertEquals(s.samples,reopened.samples);assertEquals(s.cuts.first().points,reopened.cuts.first().points)
        val page=InkPageFile.decode(InkPageFile("材质","",listOf(s)).encode())
        assertEquals(s.appearance,page.strokes.single().appearance)
        val bad=InkStrokeCodec.encode(s).also{it[it.lastIndex-2]=(it[it.lastIndex-2]+1).toByte()}
        try{InkStrokeCodec.decode(bad);fail("material digest accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun editingKeepsAppearanceAndTranslation(){
        val s=stroke();val copy=InkSelectionEdit.copy(listOf(s),20f,30f).single()
        assertNotEquals(s.id,copy.id);assertEquals(s.appearance.translated(20f,30f),copy.appearance)
        assertEquals(s.appearance,InkSelectionEdit.recolor(listOf(s),0xff224466.toInt()).single().appearance)
        assertEquals(s.appearance,InkSelectionEdit.beautify(listOf(s),.5f).single().appearance)
    }
    @Test fun crossPagePiecesShareMaterialSpaceAndConservativeCoverage(){
        val s=stroke(true);val pieces=ContinuousInk.split(s,0,2)
        assertEquals(2,pieces.size);assertEquals(s.appearance,pieces[0]!!.single().appearance.copy(leading=null,trailing=null))
        assertEquals(s.appearance.translated(0f,-1414f),pieces[1]!!.single().appearance.copy(leading=null,trailing=null))
        assertEquals(24f,s.coverageRadius(),0f)
        assertTrue(InkHitTest.hits(stroke(),listOf(InkSample(79f,100f,0)),1f))
    }
    @Test fun legacyBytesAndEnumIdentityRemainStable(){
        val s=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff112233.toInt(),3f,InkTool.TOUCH,listOf(InkSample(10f,10f,0)))
        val encoded=InkStrokeCodec.encode(s)
        assertEquals(0x31,encoded[3].toInt());assertEquals(0,InkStrokeCodec.decode(encoded).appearance.recipe.version)
        assertEquals(listOf(0,1,2,3,4,5),InkPen.entries.map{it.wireId})
        assertArrayEquals(encoded,InkStrokeCodec.encode(InkStrokeCodec.decode(encoded)))
    }
}
