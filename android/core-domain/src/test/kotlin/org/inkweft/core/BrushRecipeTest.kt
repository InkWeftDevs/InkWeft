package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BrushRecipeTest {
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
