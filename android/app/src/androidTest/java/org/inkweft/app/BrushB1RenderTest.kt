package org.inkweft.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.File

class BrushB1RenderTest {
    private fun line(kind:InkPen=InkPen.PENCIL,p:Float=.5f,tilt:Float=-1f,recipe:BrushRecipe=BrushRecipe(),back:Boolean=false)=InkStroke(UUID.randomUUID().toString(),kind,Color.BLACK,20f,InkTool.STYLUS,
        (if(back)(0..100).map{if(it<=50)it else 100-it}else(0..50).toList()).mapIndexed{i,n->InkSample(40+n*4f,80f,i*8L,p,tilt)},appearance=StrokeAppearance(recipe,123,40f,80f))
    private fun bitmap(strokes:List<InkStroke>,dx:Float=0f,dy:Float=0f):Bitmap {
        val b=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.translate(dx,dy)
        val renderer=InkBrushes.renderer()
        for(s in strokes){val saved=c.save();s.cuts.forEach{c.clipOutPath(VisibleInkGeometry.cutPath(it))};if(s.pen==InkPen.PENCIL)PencilRenderer.draw(c,s)else renderer.draw(c,InkBrushes.stroke(s),Matrix().apply{setTranslate(dx,dy)});c.restoreToCount(saved)}
        return b
    }
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height);b.recycle()}
    @Test fun grainPressureTiltAndSeparateStrokesAreVisible(){
        val light=pixels(bitmap(listOf(line(p=.05f))));val heavy=pixels(bitmap(listOf(line(p=.95f))))
        fun ink(a:IntArray)=a.sumOf{Color.alpha(it).toLong()}
        assertTrue(ink(heavy)>ink(light)*1.4)
        val grain=(60..220).map{heavy[80*320+it]}.toSet();assertTrue("pencil must have internal texture",grain.size>8)
        assertTrue(ink(pixels(bitmap(listOf(line(tilt=1.1f)))))>ink(pixels(bitmap(listOf(line(tilt=0f)))))*2)
        val s=line();assertTrue(ink(pixels(bitmap(listOf(s,s))))>ink(pixels(bitmap(listOf(s))))*1.3)
        assertArrayEquals(pixels(bitmap(listOf(s))),pixels(bitmap(listOf(s))))
    }
    @Test fun copyingAndReopeningKeepGrainAndEraseDoesNotRephase(){
        val s=line();val original=pixels(bitmap(listOf(s)))
        assertArrayEquals(original,pixels(bitmap(listOf(InkStrokeCodec.decode(InkStrokeCodec.encode(s))))))
        val copied=InkSelectionEdit.copy(listOf(s),20f,30f).single()
        assertArrayEquals("translation changed grain",original,pixels(bitmap(listOf(copied),-20f,-30f)))
        val cut=s.withCuts(listOf(InkCut(UUID.randomUUID().toString(),8f,listOf(EraserPoint(140f,80f)))))
        val erased=pixels(bitmap(listOf(cut)));assertEquals(0,erased[80*320+140]);assertEquals(original[80*320+70],erased[80*320+70])
    }
    @Test fun stableBallpointAndFixedPressureFallback(){
        assertArrayEquals(pixels(bitmap(listOf(line(InkPen.BALLPOINT,.1f)))),pixels(bitmap(listOf(line(InkPen.BALLPOINT,.9f)))))
        val pen=line(InkPen.PEN,-1f);val slow=InkStroke(pen.id,pen.pen,pen.color,pen.width,pen.tool,pen.samples.map{it.copy(elapsedMs=it.elapsedMs*4)},appearance=pen.appearance)
        assertArrayEquals(pixels(bitmap(listOf(pen))),pixels(bitmap(listOf(slow))))
        assertTrue(pixels(bitmap(listOf(line(InkPen.PEN,.9f)))).count{it!=0}>pixels(bitmap(listOf(line(InkPen.PEN,.05f)))).count{it!=0})
    }
    @Test fun selfCrossingDoesNotDepositTwice(){
        val once=pixels(bitmap(listOf(line())));val back=pixels(bitmap(listOf(line(back=true))))
        val a=(70..210).sumOf{Color.alpha(once[80*320+it])};val b=(70..210).sumOf{Color.alpha(back[80*320+it])}
        assertEquals("same-stroke accumulation",a.toFloat(),b.toFloat(),a*.03f)
    }
    @Test fun incrementalTilesMatchReopenAndCrossPageMaterialIsContinuous(){
        val s=line();s.samples.indices.filter{it%5==0}.forEach{i->
            bitmap(listOf(InkStroke(s.id,s.pen,s.color,s.width,s.tool,s.samples.take(i+1),appearance=s.appearance))).recycle()
        }
        assertArrayEquals(pixels(bitmap(listOf(s))),pixels(bitmap(listOf(InkStrokeCodec.decode(InkStrokeCodec.encode(s))))))
        val cross=InkStroke(UUID.randomUUID().toString(),InkPen.PENCIL,Color.BLACK,12f,InkTool.STYLUS,
            listOf(InkSample(100f,1370f,0,.6f,world=true),InkSample(160f,1460f,100,.6f,world=true)),true,
            appearance=StrokeAppearance(BrushRecipe(),123,100f,1370f))
        val whole=pixels(bitmap(listOf(cross),0f,-1334f))
        val b=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        ContinuousInk.split(cross,0,2).forEach{(page,strokes)->val save=c.save();c.translate(0f,page*1414f-1334f);c.clipRect(0f,0f,1000f,1414f);strokes.forEach{PencilRenderer.draw(c,it)};c.restoreToCount(save)}
        assertArrayEquals("page seam changed graphite",whole,pixels(b))
        val graded=InkStroke(UUID.randomUUID().toString(),cross.pen,cross.color,cross.width,cross.tool,cross.samples.mapIndexed{i,p->p.copy(pressure=if(i==0).1f else .9f,tilt=if(i==0).2f else 1.2f)},true,appearance=cross.appearance)
        val expected=pixels(bitmap(listOf(graded),0f,-1334f));val target=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);val targetCanvas=Canvas(target)
        ContinuousInk.split(graded,0,2).forEach{(page,strokes)->val save=targetCanvas.save();targetCanvas.translate(0f,page*1414f-1334f);targetCanvas.clipRect(0f,0f,1000f,1414f);strokes.forEach{PencilRenderer.draw(targetCanvas,it)};targetCanvas.restoreToCount(save)}
        val actual=pixels(target)
        assertTrue("pressure/tilt seam changed density",expected.indices.all{kotlin.math.abs(Color.alpha(expected[it])-Color.alpha(actual[it]))<=1})

    }
    @Test fun measureIncrementalMaterialCost(){
        val id=UUID.randomUUID().toString();val appearance=StrokeAppearance(BrushRecipe(),54321)
        val samples=(0..239).map{InkSample(40f+it,80f+20*kotlin.math.sin(it*.08f),it*8L,.5f)}
        val timings=mutableListOf<Double>()
        (8..240 step 8).forEach{n->val s=InkStroke(id,InkPen.PENCIL,Color.BLACK,8f,InkTool.STYLUS,samples.take(n),appearance=appearance)
            val start=System.nanoTime();bitmap(listOf(s)).recycle();timings.add((System.nanoTime()-start)/1e6)}
        val sorted=timings.sorted()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"brush-b1-material-cost.txt").writeText("Synthetic CPU tile draw; includes bitmap creation, not stylus latency. n=${sorted.size}; medianMs=${sorted[sorted.size/2]}; p95Ms=${sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)]}")
    }
    @Test fun saveSameColorComparison(){
        val b=Bitmap.createBitmap(900,640,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=24f};val renderer=InkBrushes.renderer()
        (PenKinds.writing+InkPen.HIGHLIGHTER).forEachIndexed{i,kind->
            c.drawText(PenKinds.title(kind),20f,60f+i*100,paint)
            val s=InkStroke(UUID.randomUUID().toString(),kind,if(kind==InkPen.HIGHLIGHTER)0x66000000 else Color.BLACK,18f,InkTool.STYLUS,
                (0..100).map{n->InkSample(160+n*6f,50+i*100+20*kotlin.math.sin(n*.12f),n*8L,.05f+.9f*n/100f)},appearance=StrokeAppearance(BrushRecipe(),123))
            if(s.pen==InkPen.PENCIL)PencilRenderer.draw(c,s)else renderer.draw(c,InkBrushes.stroke(s),Matrix())
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"brush-b1-comparison.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
}
