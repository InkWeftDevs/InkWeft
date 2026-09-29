package org.inkweft.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class FontQualityLayoutTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun id()=UUID.randomUUID().toString()
    private fun sources(text:String,y:Float=20f):List<InkStroke>{
        var x=20f
        return TextStyles.graphemes(text).mapIndexed{i,_->
            val w=if(i%3==0)12f else if(i%3==1)30f else 19f;val h=if(i%2==0)26f else 42f
            InkStroke(id(),InkPen.PEN,Color.BLACK,1f,InkTool.STYLUS,listOf(InkSample(x,y,0),InkSample(x+w,y,10),InkSample(x+w,y+h,20),InkSample(x,y+h,30))).also{x+=32f}
        }
    }
    @Test fun fixedCorrectTextHasNaturalProportionsAndSharedBaselines(){
        TextStyles.initialize(context)
        val source=sources("国国国，aA.")
        val o=beautyObject(source,"国国国，aA.",BeautyOptions(font=TextFont.SYSTEM),true)
        assertTrue(o.textRuns.isNotEmpty());assertEquals(1,o.textRuns.size)
        val gs=o.glyphs.take(3)
        assertEquals(gs[0].width,gs[1].width,.01f);assertEquals(gs[1].width,gs[2].width,.01f)
        assertEquals(gs[0].height,gs[2].height,.01f);assertEquals(gs[0].y,gs[2].y,.01f)
        assertTrue(o.glyphs[3].height<gs[0].height/2)
        assertTrue(o.glyphs[4].height<o.glyphs[5].height)
        assertEquals(o,PageObjectCodec.decode(PageObjectCodec.encode(listOf(o))).single())
    }
    @Test fun renderNativeBeforeAfterWithFixedReviewedText(){
        TextStyles.initialize(context)
        val cases=listOf("条件概率与独立性","中华人民共和国","明林休湖想意语","Hello, world!","minimum information","0 O o 1 I l,.:() -0.5 = 1","中文 English 2026，复习。","ABC abc ffi e\u0301")
        val bitmap=Bitmap.createBitmap(1600,1400,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);c.drawColor(Color.WHITE)
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=28f}
        c.drawText("v41 stretched glyphs",25f,40f,label);c.drawText("Natural platform shaping",820f,40f,label)
        val painter=PageObjectPainter()
        cases.forEachIndexed{i,text->
            val strokes=sources(text);val options=BeautyOptions(font=TextFont.SYSTEM)
            val old=legacyBeautyObject(strokes,text,options,true).copy(x=25f,y=100f+i*150)
            val fresh=beautyObject(strokes,text,options,true).copy(x=820f,y=100f+i*150)
            c.drawText(text,25f,85f+i*150,label)
            painter.draw(c,listOf(old,fresh),false,CanvasBounds(0.0,0.0,1600.0,1400.0))
        }
        File(context.getExternalFilesDir(null),"fq-native-before-after.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        painter.clear();bitmap.recycle()
    }
    @Test fun newAndLegacyObjectsKeepIndependentLayoutVersions(){
        TextStyles.initialize(context);val strokes=sources("甲乙")
        val old=legacyBeautyObject(strokes,"甲乙",BeautyOptions(font=TextFont.SYSTEM),true)
        val fresh=beautyObject(strokes,"甲乙",BeautyOptions(font=TextFont.SYSTEM),true)
        assertTrue(old.textRuns.isEmpty());assertFalse(fresh.textRuns.isEmpty())
        assertEquals(listOf(old,fresh),PageObjectCodec.decode(PageObjectCodec.encode(listOf(old,fresh))))
    }
    @Test fun paragraphStyleChangesAffectNaturalSizeAndLineSpacing(){
        TextStyles.initialize(context);val source=sources("国国国国")
        val small=beautyObject(source,"国国国国",BeautyOptions(size=24f,preserveLayout=false),false)
        val large=beautyObject(source,"国国国国",BeautyOptions(size=48f,preserveLayout=false),false)
        assertEquals(24f,small.textRuns.first().size,.001f);assertEquals(48f,large.textRuns.first().size,.001f)
        assertTrue(large.textRuns.size>1);assertTrue(large.glyphs.first().width>small.glyphs.first().width*1.8)
        val spaced=beautyObject(source,"国国国国",BeautyOptions(size=48f,spacing=1.8f,preserveLayout=false),false)
        assertTrue(spaced.textRuns.last().baseline>large.textRuns.last().baseline)
        val changed=NaturalText.restyle(small,TextFont.SERIF,36f,1.2f,false,Color.BLUE,false)
        assertEquals(36f,changed.textRuns.first().size,.001f);assertTrue(changed.glyphs.all{it.color==Color.BLUE})
        assertEquals(small.sourceStrokeIds,changed.sourceStrokeIds)
    }
    @Test fun localEraseMatchesLivePixelsAndRetainsSourceThroughPageExport(){
        TextStyles.initialize(context);val source=sources("国国")
        val o=beautyObject(source,"国国",BeautyOptions(font=TextFont.SYSTEM),false)
        val g=o.glyphs.first();val x=o.x+g.x+g.width/2
        val live=VisibleInkGeometry.sweptPath(listOf(EraserPoint(x,o.y),EraserPoint(x,o.y+o.height)),3f)
        val cut=o.copy(erasures=listOf(TextErasePath(0,o.text.length,3f,listOf(TextErasePoint(x-o.x,0f),TextErasePoint(x-o.x,o.height)))))
        fun pixels(value:PageObject,path:Path?=null):IntArray {
            val b=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888);val painter=PageObjectPainter()
            painter.draw(Canvas(b),listOf(value),false,CanvasBounds(0.0,0.0,300.0,200.0),path)
            return IntArray(60000).also{b.getPixels(it,0,300,0,0,300,200);b.recycle();painter.clear()}
        }
        val before=pixels(o);val erased=pixels(cut);assertArrayEquals(erased,pixels(o,live))
        assertTrue(before.indices.any{before[it]!=0&&erased[it]==0});assertTrue(erased.any{it!=0})
        val right=o.glyphs.last();for(y in (o.y+right.y).toInt() until (o.y+right.y+right.height).toInt())for(xx in (o.x+right.x).toInt() until (o.x+right.x+right.width).toInt())assertEquals(before[y*300+xx],erased[y*300+xx])
        val page=InkPageFile.decode(InkPageFile("FQ","",source,objects=listOf(cut)).encode())
        assertEquals(cut,page.objects.single());assertArrayEquals(erased,pixels(page.objects.single()))
        assertEquals(source.map{it.id},page.strokes.map{it.id})
        val hole=InkRegion(listOf(EraserPoint(x-1,o.y),EraserPoint(x+1,o.y+o.height)))
        assertFalse(VisibleInkGeometry().selects(hole,cut))
    }
    @Test fun boundaryAndNeighboursRequireReviewWithoutDeformingText(){
        TextStyles.initialize(context);val s=sources("甲乙")
        val r=RecognizedWriting("甲乙",1f,1)
        val first=beautyObject(s,r,BeautyOptions(),false)
        val neighbour=PageObject(id(),PageObjectKind.TEXT,x=first.x,y=first.y,width=100f,height=80f,text="邻近内容")
        val reviewed=prepareBeautyReview(s,r,BeautyOptions(),false,0,0,null,emptySet(),true,s,listOf(neighbour))
        assertNotNull(reviewed.reason);assertNotNull(reviewed.candidate)
        val edge=s.map{v->InkStroke(v.id,v.pen,v.color,v.width,v.tool,v.samples.map{it.copy(x=it.x+880f)},v.world)}
        val rejected=prepareBeautyReview(edge,RecognizedWriting("很长的自然文字无法放下",1f,1),BeautyOptions(),false,0,0,null,emptySet(),true,edge,emptyList())
        assertNull(rejected.candidate);assertNotNull(rejected.reason)
    }
}
