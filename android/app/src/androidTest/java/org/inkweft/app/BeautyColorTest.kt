package org.inkweft.app
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import kotlinx.coroutines.*
class BeautyColorTest {
 private fun stroke(x:Float,color:Int,pen:InkPen=InkPen.PEN)=InkStroke(UUID.randomUUID().toString(),pen,color,3f,InkTool.STYLUS,
    listOf(InkSample(x,100f,0,pressure=.4f),InkSample(x+40,100f,10,pressure=.4f),InkSample(x+40,150f,20,pressure=.4f),InkSample(x,150f,30,pressure=.4f)),appearance=StrokeAppearance(recipe=BrushRecipe()))
 private fun pixels(o:PageObject):IntArray {val b=Bitmap.createBitmap(400,250,Bitmap.Config.ARGB_8888);val c=Canvas(b);PageObjectPainter().draw(c,listOf(o),false,CanvasBounds(0.0,0.0,400.0,250.0));return IntArray(100000).also{b.getPixels(it,0,400,0,0,400,250);b.recycle()}}
 @Test fun automaticRecognitionCommitsPenAndPencilAppearance(){runBlocking{
    val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as InkWeftApplication
    val note=app.workspaceRepository.create("V31 自动美化验收",false,PaperStyle.BLANK)
    lateinit var vm:PageObjectViewModel
    ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects)}
    withTimeout(15000){while(vm.ui.value.loading)delay(50)}
    val options=BeautyOptions(enabled=true,font=TextFont.WENKAI,keepInk=false)
    ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
    val sources=listOf(stroke(180f,0xff1565c0.toInt()),stroke(380f,0xff00897b.toInt(),InkPen.PENCIL))
    for((index,source) in sources.withIndex()){
        app.inkRepository.save(CommitInk(UUID.randomUUID().toString(),note.id,index.toLong(),InkMutation.Add(source)))
        ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=sources.take(index+1),loading=false,revision=index+1L),false,options,false,app)}
        withTimeout(30000){while(vm.ui.value.objects.none{source.id in it.sourceStrokeIds}||vm.ui.value.busy)delay(100)}
        val saved=app.pageObjects.read(note.id).objects.first{source.id in it.sourceStrokeIds}
        assertTrue(saved.glyphs.isNotEmpty());assertTrue(saved.glyphs.all{(it.color!! and 0xffffff)==(source.color and 0xffffff)})
        if(source.pen==InkPen.PENCIL)assertTrue(saved.glyphs.all{it.grain>0&&(it.color!! ushr 24)<255})
    }
    ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options.copy(enabled=false),false,app)}
 }}
 @Test fun mixedColoursStayPerCharacterThroughFontConversionCodecAndErase(){
    TextStyles.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    val red=0xffe53935.toInt();val blue=0xff1565c0.toInt()
    val source=listOf(stroke(50f,red),stroke(180f,blue))
    val o=beautyObject(source,"田田",BeautyOptions(font=TextFont.SYSTEM),false)
    assertEquals(red,o.glyphs[0].color);assertEquals(blue,o.glyphs[1].color)
    val read=PageObjectCodec.decode(PageObjectCodec.encode(listOf(o))).single();assertEquals(o,read)
    val before=pixels(read);assertTrue(before.any{Color.alpha(it)>128&&Color.red(it)>200&&Color.blue(it)<100});assertTrue(before.any{Color.alpha(it)>128&&Color.blue(it)>160&&Color.red(it)<80})
    val erased=read.copy(glyphs=read.glyphs.mapIndexed{i,g->if(i==0)g.copy(hidden=true)else g})
    val after=pixels(erased);for(y in 0 until 250)for(x in 150 until 300)assertEquals(before[y*400+x],after[y*400+x])
    val legacy=o.copy(color=Color.BLACK,glyphs=o.glyphs.map{it.copy(color=null,grain=0f)})
    val repaired=BeautyAppearance.restore(legacy,source.associateBy{it.id});assertEquals(o.glyphs,repaired.glyphs);assertArrayEquals(before,pixels(repaired))
 }
 @Test fun colouredPencilRetainsHueDensityAndTextureAfterReopen(){
    TextStyles.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    val source=listOf(stroke(80f,0xffc62828.toInt(),InkPen.PENCIL))
    val o=beautyObject(source,"田",BeautyOptions(font=TextFont.SYSTEM),false)
    val glyph=o.glyphs.single();assertTrue(glyph.grain>0);assertTrue((glyph.color!! ushr 24) in 1..254);assertEquals(source[0].color and 0xffffff,glyph.color!! and 0xffffff)
    val read=PageObjectCodec.decode(PageObjectCodec.encode(listOf(o))).single();val p=pixels(read)
    val visible=p.filter{Color.alpha(it)>10};assertTrue(visible.size>100);assertTrue(visible.all{Color.red(it)>Color.green(it)*2});assertTrue(visible.map{Color.alpha(it)}.distinct().size>12);assertTrue(visible.maxOf{Color.alpha(it)}<220)
    assertArrayEquals(pixels(o),p)
 }
}
