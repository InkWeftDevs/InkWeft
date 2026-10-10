// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BeautyCaptureState(val active:Boolean=false,val privateAttachments:Boolean=false,val attempts:Int=0,val truncated:Boolean=false)

/** Explicit local opt-in, separate from the content-free basic diagnostic archive.
 * The bounded in-memory capture ends on process exit and never reads other pages. */
internal class BeautyDiagnostics {
    private val mutable=MutableStateFlow(BeautyCaptureState())
    val state=mutable.asStateFlow()
    private val records=ArrayDeque<Trace>()
    private var generation=0
    @Synchronized fun start(privateAttachments:Boolean){generation++;records.clear();mutable.value=BeautyCaptureState(true,privateAttachments)}
    @Synchronized fun stop(){mutable.value=mutable.value.copy(active=false)}
    @Synchronized fun clear(){generation++;records.clear();mutable.value=BeautyCaptureState()}

    suspend fun begin(page:String,ink:InkUi,known:Set<String>,objects:List<PageObject>,sources:List<InkStroke>,options:BeautyOptions,automatic:Boolean,previous:PageObject?,affected:Set<String>):Trace?=withContext(Dispatchers.Default){
        val trace=synchronized(this@BeautyDiagnostics){
            if(!mutable.value.active)return@withContext null
            Trace(generation,mutable.value.privateAttachments).also{records.addLast(it);while(records.size>3)records.removeFirst();mutable.value=mutable.value.copy(attempts=records.size)}
        }
        trace.record("source",JSONObject().apply{
            put("page_id",page);put("ink_revision",ink.revision);put("automatic",automatic)
            put("version_code",BuildConfig.VERSION_CODE);put("version_name",BuildConfig.VERSION_NAME);put("application_id",BuildConfig.APPLICATION_ID);put("source_commit",BuildConfig.SOURCE_COMMIT);put("utc_ms",System.currentTimeMillis())
            put("content_mode",if(options.formula)"FORMULA" else "TEXT")
            put("variant_scope",if(options.formula)"FORMULA_SINGLE_PASS_REVIEW_REQUIRED" else "SAME_ONNX_EXTRA_MARGIN_NOT_INDEPENDENT_MODEL")
            put("language",if(options.formula)"LATEX" else options.language.name);put("font",if(options.formula)"MATH" else options.font.name);put("preserve_layout",options.preserveLayout)
            put("known_ids",JSONArray(known.toList()));put("input_ids",JSONArray(ink.strokes.map{it.id}))
            put("converted_ids",JSONArray(objects.flatMap{it.sourceStrokeIds}));put("replacement_ids",JSONArray(sources.map{it.id}))
            put("fresh_ids",JSONArray(sources.filter{it.id !in known&&objects.none{o->it.id in o.sourceStrokeIds}}.map{it.id}))
            put("read_only_context_ids",JSONArray());put("previous_object",previous?.id?:JSONObject.NULL);put("affected_runs",JSONArray(affected.toList()))
            put("time_scope","PER_STROKE_ELAPSED_MS_NOT_GLOBAL_PEN_ORDER")
            put("strokes",JSONArray(sources.map{s->JSONObject().apply{
                put("id",s.id);put("points",s.samples.size);put("tool",s.tool.name);put("pen",s.pen.name)
                put("elapsed_start",s.samples.first().elapsedMs);put("elapsed_end",s.samples.last().elapsedMs);put("bounds",bounds(s.bounds()))
            }}))
        })
        if(trace.privateAttachments)runCatching{trace.attach("source.inkweft",InkPageFile("美化诊断区域","",sources,world=sources.first().world).encode())}.onFailure{trace.record("source_attachment","UNAVAILABLE")}
        trace.record("phase","RECOGNIZING")
        trace
    }
    private fun bounds(b:CanvasBounds)=JSONArray(listOf(b.left,b.top,b.right,b.bottom))
    inner class Trace internal constructor(private val epoch:Int,val privateAttachments:Boolean){
        internal val meta=JSONObject().put("format","InkWeft.BeautyDiagnostics/1").put("private_attachments",privateAttachments)
        internal val attachments=linkedMapOf<String,ByteArray>()
        private val started=SystemClock.elapsedRealtime()
        private var bytes=0
        fun record(key:String,value:Any){synchronized(this@BeautyDiagnostics){
            if(epoch!=generation||!mutable.value.active||this !in records)return
            val prior=meta.opt(key);meta.put(key,value)
            if(meta.toString().toByteArray().size>512*1024){if(prior==null)meta.remove(key)else meta.put(key,prior);truncate()}
        }}
        private fun truncate(){meta.put("capture_truncated",true);mutable.value=mutable.value.copy(truncated=true)}
        fun attach(name:String,data:ByteArray){synchronized(this@BeautyDiagnostics){
            if(epoch!=generation||!mutable.value.active||!privateAttachments||this !in records)return
            val replaced=attachments[name]?.size?:0
            if(data.size>4*1024*1024||records.sumOf{it.bytes}+data.size-replaced>8*1024*1024){truncate();return}
            attachments[name]=data;bytes+=data.size-replaced
        }}
        fun group(pass:String,index:Int,line:HandwritingLine)=record("$pass-group-$index",JSONObject().put("bounds",bounds(line.bounds)).put("extra_world_margin",if(pass in listOf("padded","formula"))3 else 0)
            .put("raster_bounds",bounds(if(pass in listOf("padded","formula"))line.bounds.padded(3.0)else line.bounds)).put("stroke_ids",JSONArray(line.strokes.map{it.id})))
        fun formulaInput(index:Int,raster:Bitmap,input:Bitmap){
            record("formula-input-$index",JSONObject().put("raster_width",raster.width).put("raster_height",raster.height)
                .put("shape",JSONArray(listOf(1,3,384,384))).put("channel_order","RGB_GRAYSCALE")
                .put("normalization","(pixel/255-0.7931)/0.1738").put("padding","CENTERED_BLACK_BEFORE_NORMALIZATION"))
            if(privateAttachments){
                ByteArrayOutputStream().also{raster.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray().let{attach("formula-raster-$index.png",it)}
                ByteArrayOutputStream().also{input.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray().let{attach("formula-input-$index.png",it)}
            }
        }
        fun formulaTensor(index:Int,data:FloatArray){
            if(!privateAttachments)return
            val bytes=java.nio.ByteBuffer.allocate(data.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            data.forEach(bytes::putFloat)
            record("formula-tensor-$index",JSONObject().put("contract","RGB_GRAY_FLOAT32_CHW_384_V1")
                .put("byte_order","LITTLE_ENDIAN").put("bytes",bytes.capacity()).put("sha256",DiagnosticLog.hash(bytes.array())))
        }
        fun input(pass:String,index:Int,bitmap:Bitmap,scaledWidth:Int,width:Int,data:FloatArray){
            // This is the actual normalized ONNX input, including zero-valued right padding.
            var left=scaledWidth;var top=48;var right=-1;var bottom=-1
            for(y in 0 until 48)for(x in 0 until scaledWidth)if(data[y*width+x]<.9f){left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)}
            record("$pass-input-$index",JSONObject().put("raster_width",bitmap.width).put("raster_height",bitmap.height)
                .put("scaled_width",scaledWidth).put("shape",JSONArray(listOf(1,3,48,width)))
                .put("channel_order","BGR").put("normalization","pixel/127.5-1").put("right_padding",0)
                .put("nonempty_bounds",if(right<0)JSONObject.NULL else JSONArray(listOf(left,top,right+1,bottom+1))))
            if(!privateAttachments)return
            runCatching{
                val pixels=IntArray(width*48){i->fun component(channel:Int)=((data[channel*width*48+i]+1)*127.5f).toInt().coerceIn(0,255)
                    (0xff shl 24)or(component(2)shl 16)or(component(1)shl 8)or component(0)}
                val input=Bitmap.createBitmap(pixels,width,48,Bitmap.Config.ARGB_8888)
                try{ByteArrayOutputStream().also{input.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray().let{attach("$pass-input-$index.png",it)}}finally{input.recycle()}
            }
        }
        fun output(pass:String,index:Int,result:RecognizedWriting,steps:JSONArray?=null){
            record("$pass-output-$index",JSONObject().put("characters",result.text.length).put("score_uncalibrated",result.confidence.toDouble())
                .put("text",if(privateAttachments)result.text else JSONObject.NULL).put("tokens",JSONArray(result.tokens.map{token->JSONObject().apply{
                    put("center",token.center.toDouble());put("score",token.score.toDouble());put("alternative_score",token.alternativeScore.toDouble())
                    if(privateAttachments){put("text",token.text);put("alternative",token.alternative?:JSONObject.NULL)}
                }})).apply{if(steps!=null)put(if(pass=="formula")"formula_decoder_steps" else "ctc_steps_top2",steps)})
        }
        fun decision(first:RecognizedWriting,second:RecognizedWriting,quality:BeautyDecision,review:BeautyReview){
            record("decision",JSONObject().put("same_text",first.text==second.text).put("quality_automatic",quality.automatic)
                .put("quality_reason",quality.reason?:JSONObject.NULL).put("layout_reason",review.reason?:JSONObject.NULL)
                .put("candidate_source_ids",JSONArray(review.candidate?.sourceStrokeIds.orEmpty())).put("preview",false)
                .put("ink_revision",review.inkRevision).put("object_revision",review.objectRevision))
        }
        fun finish(phase:String){record("phase",phase);record("elapsed_ms",SystemClock.elapsedRealtime()-started)}
    }
    suspend fun bundle(basicDiagnostics:ByteArray?=null):ByteArray=withContext(Dispatchers.Default){
        val files=synchronized(this@BeautyDiagnostics){
            require(records.isNotEmpty()){"NO_BEAUTY_CAPTURE"}
            val out=linkedMapOf("README.txt" to "美化诊断：仅用户主动录制的最多3次尝试；模型分数未经校准。此包含页面/笔划ID和区域信息。private_attachments=true时另含原迹、实际模型输入、候选及逐步解码，请只交给获准的接收者。未捕获的尝试、系统日志和进程终止后的回执不在此包内。数据只在内存，重启丢失；不是笔记备份，不自动上传。\n".toByteArray())
            records.forEachIndexed{i,trace->out["attempt-${i+1}/trace.json"]=trace.meta.toString(2).toByteArray();trace.attachments.forEach{(name,data)->out["attempt-${i+1}/$name"]=data}}
            out
        }
        if(basicDiagnostics!=null){require(basicDiagnostics.size<=DiagnosticArchive.MAX_ZIP_BYTES);files["basic-diagnostics.zip"]=basicDiagnostics}
        files["manifest.json"]=JSONObject().put("files",JSONArray(files.map{(name,data)->JSONObject().put("name",name).put("bytes",data.size).put("sha256",DiagnosticLog.hash(data))})).toString(2).toByteArray()
        ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->files.forEach{(name,data)->zip.putNextEntry(ZipEntry(name).apply{time=0});zip.write(data);zip.closeEntry()}}}.toByteArray().also{require(it.size<=12*1024*1024)}
    }
}
