// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.UUID

class CanvasViewportTest {
    private fun id()=UUID.randomUUID().toString()
    private fun board()=InkStroke(id(),InkPen.PEN,0xff24342f.toInt(),3f,InkTool.STYLUS,listOf(InkSample(-3500f,2700f,0,.3f,.2f,.1f,true),InkSample(2500f,-900f,45,.8f,.2f,.1f,true)),true)
    private fun rejected(f:()->Unit){try{f();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun boardCoordinatesSurviveCodec(){val s=board();val b=InkStrokeCodec.decode(InkStrokeCodec.encode(s));assertTrue(b.world);assertEquals(s.samples,b.samples)}
    @Test fun negativeRequiresExplicitWorld(){rejected{InkSample(-1f,0f,0)};assertEquals(-1f,InkSample(-1f,0f,0,world=true).x)}
    @Test fun pageCannotAcceptBoardSamples(){rejected{InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,board().samples)}}
    @Test fun boardCannotMixCoordinateModes(){rejected{InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.TOUCH,listOf(InkSample(1f,2f,0)),true)}}
    @Test fun finiteWorldBudget(){rejected{InkSample(BoardLimits.WORLD+1f,0f,0,world=true)};rejected{InkSample(0f,Float.NaN,0,world=true)}}
    @Test fun pageCopyKeepsModePaperAndCoordinates(){val p=InkPageFile("无界","解释",listOf(board()),true,PaperStyle.DOTS);val q=InkPageFile.decode(p.encode());assertTrue(q.world);assertEquals(PaperStyle.DOTS,q.paper);assertEquals(p.strokes.single().samples,q.strokes.single().samples)}
    @Test fun boardCopyCannotMislabelAsPaper(){rejected{InkPageFile("错误","",listOf(board()))}}
    @Test fun legacyEmptyPageFileReadable(){val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{d->d.writeInt(0x49575031);for(s in listOf("旧页","旧文字")){val b=s.toByteArray(Charsets.UTF_8);d.writeInt(b.size);d.write(b)};d.writeInt(0)};val body=bytes.toByteArray();val p=InkPageFile.decode(body+MessageDigest.getInstance("SHA-256").digest(body));assertFalse(p.world);assertEquals(PaperStyle.RULED,p.paper);assertEquals("旧文字",p.text)}
    @Test fun viewportRoundTripAtFarNegativePosition(){val v=CanvasViewport(-34000.0,27500.0,2.1);val q=v.worldToScreen(-33001.0,27666.0,1800.0,1200.0,3.0);val p=v.screenToWorld(q.x,q.y,1800.0,1200.0,3.0);assertEquals(-33001.0,p.x,1e-6);assertEquals(27666.0,p.y,1e-6)}
    @Test fun pinchPreservesFocusWorldCoordinate(){val v=CanvasViewport(0.0,0.0,.8);val before=v.screenToWorld(240.0,650.0,1800.0,1200.0,2.0);val next=v.zoomAt(1.7,240.0,650.0,1800.0,1200.0,2.0);val after=next.screenToWorld(240.0,650.0,1800.0,1200.0,2.0);assertEquals(before.x,after.x,1e-7);assertEquals(before.y,after.y,1e-7)}
    @Test fun panBeyondPaperNotClampedToPaper(){val v=CanvasViewport(0.0,0.0,.5).pan(-4000.0,3000.0,2.0);assertTrue(v.centerX>1000);assertTrue(v.centerY<0)}
    @Test fun viewDoesNotMutateAuthorSamples(){val s=board();val original=s.samples.toList();var v=CanvasViewport();repeat(100){v=v.pan(140.0,-20.0,3.0).zoomAt(1.01,300.0,200.0,1800.0,1200.0,3.0)};assertEquals(original,s.samples)}
    @Test fun viewportInputRejectsInfinity(){rejected{CanvasViewport(Double.POSITIVE_INFINITY,0.0,1.0)};rejected{CanvasViewport(0.0,0.0,0.0)}}
    @Test fun viewportSafeBounds(){val v=CanvasViewport.safe(2e6,-2e6,20.0);assertEquals(1e6,v.centerX,0.0);assertEquals(-1e6,v.centerY,0.0);assertEquals(8.0,v.zoom,0.0)}
    @Test fun fitContentContainsAllCorners(){val bounds=CanvasBounds(-5000.0,-5000.0,7000.0,9000.0);val v=CanvasViewport.fit(bounds,1200.0,800.0);val visible=v.visible(1200.0,800.0,1.0);assertTrue(visible.left<=bounds.left&&visible.right>=bounds.right&&visible.top<=bounds.top&&visible.bottom>=bounds.bottom)}
    @Test fun widthFitIsLargerThanPageOnLandscape(){val page=CanvasViewport.fit(CanvasBounds(0.0,0.0,1000.0,1414.0),1280.0,600.0);assertTrue(CanvasViewport.pageWidth(1280.0,600.0).zoom>page.zoom)}
    @Test fun cullingIncludesTouchingEdge(){assertTrue(CanvasBounds(-100.0,-100.0,0.0,0.0).intersects(CanvasBounds(0.0,0.0,20.0,20.0)))}
    @Test fun cullingRejectsFarStroke(){assertFalse(board().bounds().intersects(CanvasBounds(20000.0,20000.0,21000.0,21000.0)))}
    @Test fun boardEraserSweptIntersection(){val s=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.TOUCH,listOf(InkSample(-2000f,-2000f,0,world=true),InkSample(-1000f,-1000f,20,world=true)),true);assertTrue(InkHitTest.hits(s,listOf(InkSample(-2000f,-1000f,0,world=true),InkSample(-1000f,-2000f,20,world=true))))}
    @Test fun boardDigestDiffersFromPageForSamePoints(){val strokeId=id();val a=InkStroke(strokeId,InkPen.PEN,0xff000000.toInt(),2f,InkTool.TOUCH,listOf(InkSample(1f,2f,0)));val b=InkStroke(strokeId,InkPen.PEN,0xff000000.toInt(),2f,InkTool.TOUCH,listOf(InkSample(1f,2f,0,world=true)),true);val c=id();val n=id();assertNotEquals(CommitInk(c,n,0,InkMutation.Add(a)).digest(),CommitInk(c,n,0,InkMutation.Add(b)).digest())}
}
