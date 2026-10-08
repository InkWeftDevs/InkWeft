// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import ai.onnxruntime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

/** Pinned Texo / FormulaNet (AGPL-3.0), entirely offline. Scores are not correctness estimates. */
internal class FormulaRecognizer(context:Context) {
    private val app=context.applicationContext
    private val lock=Mutex()
    private val env by lazy{OrtEnvironment.getEnvironment().also{it.setTelemetry(false)}}
    private val tokens by lazy{
        val vocab=JSONObject(app.assets.open("formula/tokenizer.json").bufferedReader().use{it.readText()}).getJSONObject("model").getJSONObject("vocab")
        Array(vocab.length()){ "" }.also{out->vocab.keys().forEach{name->out[vocab.getInt(name)]=name}}
    }
    suspend fun recognize(strokes:List<InkStroke>,trace:BeautyDiagnostics.Trace?=null):RecognizedWriting=withContext(Dispatchers.Default){lock.withLock{
        require(strokes.size in 1..256)
        val lines=HandwritingLines.split(strokes);require(lines.size in 1..16){"请每次选择不超过 16 行公式"}
        val regions=mutableListOf<RecognizedLine>()
        runCatching{trace?.record("recognizer","texo@b2668efe5112082846fde4d446b9bfaab3989533")}
        // Release both sessions after the selection, including cancellation. The editor need not
        // retain 80 MB of model weights while the user continues writing or reading.
        OrtSession.SessionOptions().use{options->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            ensureActive()
            env.createSession(app.assets.open("formula/encoder_model.onnx").use{it.readBytes()},options).use{encoder->
                ensureActive()
                env.createSession(app.assets.open("formula/decoder_model.onnx").use{it.readBytes()},options).use{decoder->
                    for((i,line) in lines.withIndex()){
                        ensureActive();runCatching{trace?.group("formula",i,line)}
                        val bitmap=raster(line)
                        val result=try{withTimeout(30_000){recognizeBitmap(bitmap,encoder,decoder)}}finally{bitmap.recycle()}
                        regions.add(RecognizedLine(result.first,line.bounds,line.strokes.map{it.id},emptyList(),result.second))
                        runCatching{trace?.output("formula",i,RecognizedWriting(result.first,result.second,1),null)}
                    }
                }
            }
        }
        RecognizedWriting(regions.joinToString("\n"){it.text},regions.map{it.score}.average().toFloat(),regions.size,regions)
    }}
    private suspend fun recognizeBitmap(bitmap:Bitmap,encoder:OrtSession,decoder:OrtSession):Pair<String,Float>{
        val scaled=preprocess(bitmap)
        val pixels=IntArray(384*384);scaled.getPixels(pixels,0,384,0,0,384,384);scaled.recycle()
        val plane=pixels.size;val data=FloatArray(plane*3)
        for(i in pixels.indices){val gray=(pixels[i] and 255)/255f;val v=(gray-.7931f)/.1738f;data[i]=v;data[plane+i]=v;data[2*plane+i]=v}
        return OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,384,384)).use{input->
            encoder.run(mapOf("pixel_values" to input)).use{encoded->
                currentCoroutineContext().ensureActive()
                val hidden=encoded[0] as OnnxTensor;val encodedShape=hidden.info.shape
                require(encodedShape.size==3&&encodedShape[0]==1L&&encodedShape[1] in 1L..1024L&&encodedShape[2]==2048L)
                // These IDs come from the pinned model's generation_config.json.
                val ids=mutableListOf(0L);var sum=0.0;var ended=false
                for(step in 0 until 384){
                    currentCoroutineContext().ensureActive()
                    val next=OnnxTensor.createTensor(env,LongBuffer.wrap(ids.toLongArray()),longArrayOf(1,ids.size.toLong())).use{sequence->
                        decoder.run(mapOf("input_ids" to sequence,"encoder_hidden_states" to hidden)).use{result->
                            val tensor=result[0] as OnnxTensor;val shape=tensor.info.shape
                            require(shape.contentEquals(longArrayOf(1,ids.size.toLong(),tokens.size.toLong())))
                            val scores=checkNotNull(tensor.floatBuffer);val offset=(ids.size-1)*tokens.size
                            var best=0;var maximum=-Float.MAX_VALUE
                            for(i in tokens.indices){val v=scores.get(offset+i);require(v.isFinite());if(v>maximum){maximum=v;best=i}}
                            var denominator=0.0;for(i in tokens.indices)denominator+=exp((scores.get(offset+i)-maximum).toDouble())
                            best to (1.0/denominator)
                        }
                    }
                    if(next.first==2){ended=true;break}
                    require(next.first>=4){"公式候选不完整，请缩小选区"}
                    ids.add(next.first.toLong());sum+=next.second
                }
                require(ended&&ids.size>1){"公式过长或未识别完整，请分段框选"}
                val text=ids.drop(1).joinToString(" "){tokens[it.toInt()]}.trim()
                require(text.isNotBlank()&&text.length<=4000)
                text to (sum/(ids.size-1)).toFloat()
            }
        }
    }
    /** Match the upstream evaluation geometry: ink crop, retained aspect, centered black padding. */
    private fun preprocess(bitmap:Bitmap):Bitmap {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val gray=IntArray(pixels.size){i->val p=pixels[i];(((p shr 16)and 255)*299+((p shr 8)and 255)*587+(p and 255)*114)/1000}
        val low=gray.minOrNull()!!;val high=gray.maxOrNull()!!;require(high>low){"选区没有可见公式笔迹"}
        var left=bitmap.width;var top=bitmap.height;var right=0;var bottom=0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width)if((gray[y*bitmap.width+x]-low)*255<200*(high-low)){
            left=min(left,x);top=min(top,y);right=max(right,x+1);bottom=max(bottom,y+1)
        }
        require(right>left&&bottom>top)
        val width=right-left;val height=bottom-top;val scale=384f/max(width,height)
        val w=max(1,(width*scale).roundToInt());val h=max(1,(height*scale).roundToInt())
        val result=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888);val canvas=Canvas(result);canvas.drawColor(Color.BLACK)
        // Input raster is black/white; filtered resampling keeps all three channels identical.
        canvas.drawBitmap(bitmap,Rect(left,top,right,bottom),Rect((384-w)/2,(384-h)/2,(384-w)/2+w,(384-h)/2+h),Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }
    private fun raster(line:HandwritingLine):Bitmap {
        val b=line.bounds.padded(3.0);val scale=min(3.0,192.0/(b.bottom-b.top).coerceAtLeast(1.0))
        val width=ceil((b.right-b.left)*scale).toInt().coerceAtLeast(1);val height=ceil((b.bottom-b.top)*scale).toInt().coerceAtLeast(1)
        require(width<=4096&&height<=512){"公式行过长，请分段框选"}
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bitmap->
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.scale(scale.toFloat(),scale.toFloat());canvas.translate(-b.left.toFloat(),-b.top.toFloat())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
            val geometry=VisibleInkGeometry()
            for(s in line.strokes){val saved=canvas.save()
                if(s.pen==InkPen.PENCIL){paint.style=Paint.Style.FILL;canvas.drawPath(geometry.path(s),paint);paint.style=Paint.Style.STROKE}
                else{
                    s.cuts.forEach{canvas.clipOutPath(VisibleInkGeometry.cutPath(it))};paint.strokeWidth=s.width.coerceAtLeast(1f)
                    val path=Path();s.samples.forEachIndexed{i,p->if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)}
                    if(s.samples.size==1)canvas.drawPoint(s.samples[0].x,s.samples[0].y,paint)else canvas.drawPath(path,paint)
                }
                canvas.restoreToCount(saved)
            }
        }
    }
}
