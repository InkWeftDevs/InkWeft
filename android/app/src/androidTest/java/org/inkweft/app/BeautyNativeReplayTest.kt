package org.inkweft.app

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Original generated outlines, not human samples or a replay of the reported failure. */
class BeautyNativeReplayTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as InkWeftApplication
    private fun id()=UUID.randomUUID().toString()
    private fun outlines(text:String):List<InkStroke>{
        TextStyles.initialize(ins.targetContext)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=48f;typeface=TextStyles.face(TextFont.WENKAI)}
        val path=Path();paint.getTextPath(text,0,text.length,100f,260f,path)
        val measure=PathMeasure(path,false);val strokes=mutableListOf<InkStroke>()
        do{if(measure.length>0){val xy=FloatArray(2);val count=kotlin.math.ceil(measure.length/1.5f).toInt().coerceAtLeast(2)
            val samples=(0..count).map{i->measure.getPosTan(measure.length*i/count,xy,null);InkSample(xy[0],xy[1],i.toLong())}
            strokes.add(InkStroke(id(),InkPen.PEN,Color.BLACK,1.5f,InkTool.STYLUS,samples))
        }}while(measure.nextContour())
        return strokes
    }
    private fun entries(bytes:ByteArray)=linkedMapOf<String,ByteArray>().apply{
        ZipInputStream(ByteArrayInputStream(bytes)).use{zip->while(true){val entry=zip.nextEntry?:break;put(entry.name,zip.readBytes())}}
    }
    @Test fun actualModelReplayReportsAccuracyAndAutomaticCoverageSeparately()=runBlocking {
        val rows=mutableListOf<JSONObject>()
        for((index,text)in listOf("12345","2026","墨织手写查找","InkWeft notes").withIndex()){
            val strokes=outlines(text);val source=InkPageFile("合成字体轮廓","",strokes).encode();val options=BeautyOptions(enabled=true)
            app.beautyDiagnostics.start(true)
            val trace=checkNotNull(app.beautyDiagnostics.begin(id(),InkUi(strokes=strokes,loading=false,revision=1),emptySet(),emptyList(),strokes,options,true,null,emptySet()))
            val started=System.nanoTime();val first=app.handwriting.recognize(strokes,trace=trace);val second=app.handwriting.recognize(strokes,padded=true,trace=trace)
            val recognitionMs=(System.nanoTime()-started)/1e6
            val quality=BeautyQuality.decide(strokes,first,second,true)
            val review=prepareBeautyReview(strokes,first,options,false,1,0,null,emptySet(),true,strokes,emptyList())
            trace.decision(first,second,quality,review);trace.finish("EVALUATED_NOT_COMMITTED")
            val admissible=quality.automatic&&review.candidate!=null&&review.reason==null
            var automatic=false;var endToEnd:Double?=null
            if(admissible){
                val note=app.workspaceRepository.create("ABF 合成轮廓 $index",false,PaperStyle.BLANK)
                lateinit var vm:PageObjectViewModel
                ins.runOnMainSync{vm=PageObjectViewModel(note.id,app.pageObjects)}
                withTimeout(10000){while(vm.ui.value.loading)delay(20)}
                ins.runOnMainSync{vm.observeBeauty(InkUi(loading=false),false,options,false,app)}
                app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Replace(emptyList(),strokes)))
                val observed=System.nanoTime()
                ins.runOnMainSync{vm.observeBeauty(InkUi(strokes=strokes,loading=false,revision=1),false,options,false,app)}
                withTimeout(15000){while(vm.ui.value.busy||vm.ui.value.objects.isEmpty()&&vm.beautyReview.value==null)delay(20)}
                ins.runOnMainSync{ }
                automatic=vm.ui.value.objects.isNotEmpty()&&!vm.ui.value.pending
                endToEnd=(System.nanoTime()-observed)/1e6
                if(automatic){val saved=app.pageObjects.read(note.id).objects.single();assertEquals(text,saved.text);assertEquals(strokes.map{it.id}.toSet(),saved.sourceStrokeIds.toSet())}
                assertEquals(strokes.size,app.inkRepository.read(note.id).strokes.size)
            }
            rows+=JSONObject().put("id","outline-$index").put("writer","generated-font-outline").put("split","development").put("consent",true)
                .put("source_kind","SYNTHETIC_FONT_OUTLINE_NOT_HANDWRITING").put("source_sha256",DiagnosticLog.hash(source)).put("reference",text)
                .put("hypothesis",first.text).put("variant",second.text).put("automatic",automatic).put("admissible",admissible).put("end_to_end_ms",endToEnd?:JSONObject.NULL).put("reason",quality.reason?:review.reason?:JSONObject.NULL)
                .put("recognition_ms",recognitionMs)
            val bundle=app.beautyDiagnostics.bundle();val files=entries(bundle)
            assertTrue(files.containsKey("attempt-1/source.inkweft"));assertTrue(files.keys.any{it.endsWith("base-input-0.png")})
            val metadata=JSONObject(checkNotNull(files["attempt-1/trace.json"]).toString(Charsets.UTF_8))
            assertEquals(strokes.map{it.id}.toSet(),metadata.getJSONObject("source").getJSONArray("replacement_ids").let{a->(0 until a.length()).map{a.getString(it)}.toSet()})
            assertTrue(metadata.has("base-output-0"));assertTrue(metadata.has("padded-output-0"))
            File(app.getExternalFilesDir(null),"abf-outline-$index.zip").writeBytes(bundle)
            app.beautyDiagnostics.stop()
        }
        File(app.getExternalFilesDir(null),"abf-native-results.jsonl").writeText(rows.joinToString("\n"){it.toString()}+"\n")
        app.beautyDiagnostics.clear()
        assertTrue("Synthetic controls must include a correct automatic candidate",rows.any{it.getBoolean("automatic")&&it.getString("reference")==it.getString("hypothesis")})
    }

    @Test fun blankRasterGroupRemainsMappedAndCannotHideOtherInk()=runBlocking {
        val visible=outlines("2026")
        val erased=InkStroke(id(),InkPen.PEN,Color.BLACK,2f,InkTool.STYLUS,listOf(InkSample(100f,450f,0),InkSample(135f,490f,40)),
            cuts=listOf(InkCut(id(),2f,listOf(EraserPoint(90f,440f),EraserPoint(145f,500f)),InkCutShape.RECTANGLE)))
        val strokes=visible+erased;val result=app.handwriting.recognize(strokes)
        assertEquals(2,result.lines);assertEquals(2,result.regions.size)
        assertEquals("",result.regions.last().text);assertEquals(listOf(erased.id),result.regions.last().strokeIds)
        assertEquals(strokes.map{it.id}.toSet(),result.regions.flatMap{it.strokeIds}.toSet())
        assertFalse(BeautyQuality.decide(strokes,result,result,true).automatic)
    }

    @Test fun defaultAndStructuralCaptureKeepPrivateAttachmentsOutOfBasicZip()=runBlocking {
        val strokes=outlines("2026");val ink=InkUi(strokes=strokes,loading=false,revision=1);val page=id();val options=BeautyOptions(enabled=true)
        app.beautyDiagnostics.clear()
        assertNull(app.beautyDiagnostics.begin(page,ink,emptySet(),emptyList(),strokes,options,true,null,emptySet()))
        app.beautyDiagnostics.start(false)
        val trace=checkNotNull(app.beautyDiagnostics.begin(page,ink,emptySet(),emptyList(),strokes,options,true,null,emptySet()))
        app.handwriting.recognize(strokes,trace=trace);trace.finish("REVIEW_UNAPPLIED")
        val captured=entries(app.beautyDiagnostics.bundle())
        assertEquals(setOf("README.txt","manifest.json","attempt-1/trace.json"),captured.keys)
        val meta=JSONObject(captured.getValue("attempt-1/trace.json").toString(Charsets.UTF_8))
        assertTrue(meta.getJSONObject("base-output-0").isNull("text"))
        val basic=entries(app.diagnostics.bundle()).values.joinToString{it.toString(Charsets.UTF_8)}
        assertFalse(basic.contains(page));strokes.forEach{assertFalse(basic.contains(it.id))}
        assertFalse(basic.contains("base-output"));assertFalse(basic.contains("source.inkweft"))
        val combined=entries(app.beautyDiagnostics.bundle(app.diagnostics.bundle()))
        assertTrue(combined.containsKey("basic-diagnostics.zip"))
        val environment=entries(combined.getValue("basic-diagnostics.zip"))
        assertEquals(BuildConfig.VERSION_CODE,JSONObject(environment.getValue("report.json").toString(Charsets.UTF_8)).getJSONObject("app").getInt("version_code"))
        app.beautyDiagnostics.stop();assertFalse(app.beautyDiagnostics.state.value.active)
        assertNull(app.beautyDiagnostics.begin(page,ink,emptySet(),emptyList(),strokes,options,true,null,emptySet()))
        app.beautyDiagnostics.clear()
    }

    @Test fun captureBudgetAndNewSessionDiscardOldTraceWithoutAffectingInk()=runBlocking {
        val strokes=outlines("2026");val capture=app.beautyDiagnostics;capture.start(true)
        var old:BeautyDiagnostics.Trace?=null
        repeat(5){index->val trace=checkNotNull(capture.begin(id(),InkUi(strokes=strokes,loading=false,revision=1),emptySet(),emptyList(),strokes,BeautyOptions(),true,null,emptySet()))
            if(index==0)old=trace
            trace.record("marker",index)
            if(index==4)trace.attach("too-large.png",ByteArray(4*1024*1024+1))
        }
        assertEquals(3,capture.state.value.attempts);assertTrue(capture.state.value.truncated)
        old!!.record("marker",99)
        val files=entries(capture.bundle());val markers=files.filterKeys{it.endsWith("trace.json")}.values.map{JSONObject(it.toString(Charsets.UTF_8)).getInt("marker")}
        assertEquals(listOf(2,3,4),markers);assertFalse(files.keys.any{it.endsWith("too-large.png")})
        capture.start(false);old!!.finish("SAVED")
        assertEquals(0,capture.state.value.attempts);assertFalse(capture.state.value.privateAttachments)
        capture.clear()
    }
}
