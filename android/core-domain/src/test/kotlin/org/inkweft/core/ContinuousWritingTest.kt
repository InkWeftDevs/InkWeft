package org.inkweft.core
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ContinuousWritingTest {
    @Test fun lassoDoesNotSelectObjectsOutsideItsActualPolygon(){
        val triangle=InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(300f,0f),EraserPoint(0f,300f)),false)
        assertTrue(triangle.selects(CanvasBounds(20.0,20.0,60.0,60.0)))
        assertFalse(triangle.selects(CanvasBounds(180.0,180.0,230.0,230.0)))
        assertFalse(triangle.selects(CanvasBounds(100.0,100.0,220.0,220.0)))
    }
    @Test fun concaveNotchRejectsObjectEvenWithFourCornersInside(){
        val notch=InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(100f,0f),EraserPoint(100f,100f),EraserPoint(60f,100f),EraserPoint(60f,40f),EraserPoint(40f,40f),EraserPoint(40f,100f),EraserPoint(0f,100f)),false)
        assertFalse(notch.selects(CanvasBounds(20.0,20.0,80.0,80.0)))
        assertTrue(notch.selects(CanvasBounds(5.0,20.0,30.0,80.0)))
    }
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(vararg y:Float)=InkStroke(id(),InkPen.PEN,0xff223344.toInt(),3f,InkTool.STYLUS,
        y.mapIndexed{i,v->InkSample(200f+i*10,v,i*100L,.2f+i*.1f,world=true)},true)
    @Test fun downwardSeamInterpolatesPositionTimeAndPressure(){
        val split=ContinuousInk.split(stroke(1400f,1428f),0,2)
        val bottom=split[0]!!.single().samples.last();val top=split[1]!!.single().samples.first()
        assertEquals(1414f,bottom.y);assertEquals(0f,top.y);assertEquals(bottom.x,top.x)
        assertEquals(50L,bottom.elapsedMs);assertEquals(.25f,bottom.pressure,0.0001f)
        assertFalse(top.world)
    }
    @Test fun upwardAndReentryDoNotConnectUnrelatedParts(){
        val split=ContinuousInk.split(stroke(20f,-20f,40f),1,3)
        assertEquals(2,split[1]!!.size);assertEquals(1,split[0]!!.size)
        assertEquals(0f,split[1]!!.first().samples.last().y)
        assertEquals(1414f,split[0]!!.single().samples.first().y)
        split.values.flatten().forEach{assertEquals(it.samples,InkStrokeCodec.decode(InkStrokeCodec.encode(it)).samples)}
    }
    @Test fun longSegmentTraversesAllSheetsAndClipsBookEnds(){
        val split=ContinuousInk.split(stroke(-100f,7000f),0,4)
        assertEquals(setOf(0,1,2,3),split.keys)
        assertEquals(0f,split[0]!!.first().samples.first().y)
        assertEquals(1414f,split[3]!!.last().samples.last().y)
    }
    @Test fun pointExactlyOnSeamAndHorizontalSeamAreValid(){
        assertEquals(setOf(1),ContinuousInk.split(stroke(1414f),0,2).keys)
        assertEquals(setOf(1),ContinuousInk.split(stroke(1414f,1414f),0,2).keys)
    }
    @Test fun finitePageCannotBePannedAwayAtAnyZoom(){
        for(z in listOf(.02,.5,2.0,8.0))for(sign in listOf(-1,1)){
            val v=CanvasViewport(sign*90000.0,sign*90000.0,z).constrainedToPaper(800.0,600.0)
            val box=v.visible(800.0,600.0,1.0)
            if(box.right-box.left<=1000.0){assertTrue(box.left>=-.001);assertTrue(box.right<=1000.001)}else assertEquals(500.0,v.centerX,0.0)
            if(box.bottom-box.top<=1414.0){assertTrue(box.top>=-.001);assertTrue(box.bottom<=1414.001)}else assertEquals(707.0,v.centerY,0.0)
        }
    }
    @Test fun deletedBeautyKeepsOriginalSuppressedAcrossExport(){
        val ink=ContinuousInk.split(stroke(200f,250f),0,1)[0]!!.single()
        val beauty=PageObject(id(),PageObjectKind.TEXT,text="文字",sourceStrokeIds=listOf(ink.id),hidden=true)
        val copy=InkPageFile.decode(InkPageFile("测试","",listOf(ink),objects=listOf(beauty)).encode())
        assertEquals(beauty,copy.objects.single());assertEquals(ink.id,copy.objects.single().sourceStrokeIds.single())
    }
}
