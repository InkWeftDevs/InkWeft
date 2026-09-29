// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import ai.onnxruntime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.json.JSONArray
import java.nio.FloatBuffer
import kotlin.math.*

internal data class RecognizedToken(val text:String,val center:Float,val score:Float=1f,val alternative:String?=null,val alternativeScore:Float=0f)
internal data class RecognizedLine(val text:String,val bounds:CanvasBounds,val strokeIds:List<String>,val tokens:List<RecognizedToken>,val score:Float=-1f)
internal data class RecognizedWriting(val text:String,val confidence:Float,val lines:Int,val regions:List<RecognizedLine> = emptyList(),val tokens:List<RecognizedToken> = emptyList())

/** Bundled Apache-2.0 model; no network, telemetry, screenshots, or author text in diagnostics. */
internal class HandwritingRecognizer(context:Context) {
    private val app=context.applicationContext
    private val lock=Mutex()
    private val env by lazy{OrtEnvironment.getEnvironment().also{it.setTelemetry(false)}}
    private val session by lazy {
        OrtSession.SessionOptions().use{options->options.setIntraOpNumThreads(2)
            env.createSession(app.assets.open("ocr/recognition.onnx").use{it.readBytes()},options)}
    }
    private val chars by lazy{JSONArray(app.assets.open("ocr/characters.json").bufferedReader().use{it.readText()}).let{a->listOf("")+List(a.length()){a.getString(it)}+listOf(" ")}}
    private val englishAllowed by lazy{BooleanArray(chars.size){index->chars[index].all{it.code in 32..126}}}
    suspend fun recognize(strokes:List<InkStroke>,language:BeautyLanguage=BeautyLanguage.MIXED,padded:Boolean=false,progress:(Int,Int)->Unit={_,_->}):RecognizedWriting=withContext(Dispatchers.Default){
        val lines=HandwritingLines.split(strokes);val texts=mutableListOf<String>();val regions=mutableListOf<RecognizedLine>();var total=0f
        for((i,line)in lines.withIndex()){
            ensureActive();progress(i,lines.size)
            val bitmap=raster(line,padded)
            val r=try{recognizeBitmap(bitmap,language)}finally{RenderResources.release(bitmap,"ocr");bitmap.recycle()}
            ensureActive();texts.add(r.text);total+=r.confidence
            if(r.text.isNotBlank())regions.add(RecognizedLine(r.text,line.bounds,line.strokes.map{it.id},r.tokens,r.confidence))
        }
        progress(lines.size,lines.size)
        RecognizedWriting(texts.joinToString("\n").trim(),if(lines.isEmpty())0f else total/lines.size,lines.size,regions)
    }
    internal suspend fun recognizeBitmap(bitmap:Bitmap,language:BeautyLanguage=BeautyLanguage.MIXED):RecognizedWriting=withContext(Dispatchers.Default){lock.withLock {
        ensureActive()
        val scaledWidth=ceil(bitmap.width*48.0/bitmap.height).toInt().coerceAtLeast(1)
        require(scaledWidth<=2048){"文字行过长，请缩小选区后识别"}
        val width=max(320,scaledWidth);val pixels=IntArray(scaledWidth*48)
        Bitmap.createScaledBitmap(bitmap,scaledWidth,48,true).let{b->b.getPixels(pixels,0,scaledWidth,0,0,scaledWidth,48);if(b!==bitmap)b.recycle()}
        val data=FloatArray(3*48*width)
        for(y in 0 until 48)for(x in 0 until scaledWidth){val pixel=pixels[y*scaledWidth+x]
            data[y*width+x]=(pixel and 255)/127.5f-1
            data[48*width+y*width+x]=((pixel shr 8)and 255)/127.5f-1
            data[96*width+y*width+x]=((pixel shr 16)and 255)/127.5f-1
        }
        val allowed=if(language==BeautyLanguage.MIXED)null else englishAllowed
        OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,48,width.toLong())).use{input->
            session.run(mapOf("x" to input)).use{result->
                ensureActive()
                val tensor=result[0] as OnnxTensor;val shape=tensor.info.shape
                require(shape.size==3&&shape[0]==1L&&shape[2]==chars.size.toLong())
                val scores=checkNotNull(tensor.floatBuffer);val text=StringBuilder();var previous=-1
                val tokens=mutableListOf<RecognizedToken>()
                repeat(shape[1].toInt()) {step->var best=0;var probability=-Float.MAX_VALUE;var second=0;var runnerUp=-Float.MAX_VALUE
                    repeat(chars.size){index->val score=scores.get();if(index==0||allowed==null||allowed[index]){if(score>probability){second=best;runnerUp=probability;best=index;probability=score}else if(score>runnerUp){second=index;runnerUp=score}}}
                    if(best!=0){
                        val token=RecognizedToken(chars[best],((step+.5f)*width/(shape[1]*scaledWidth)).coerceIn(0f,1f),probability,chars[second].takeIf{it.isNotBlank()},runnerUp.coerceIn(0f,1f))
                        if(best!=previous){text.append(chars[best]);tokens.add(token)}
                        else if(tokens.isNotEmpty()&&probability>tokens.last().score)tokens[tokens.lastIndex]=token
                    };previous=best
                }
                val decoded=tokens.dropWhile{it.text.isBlank()}.dropLastWhile{it.text.isBlank()}
                RecognizedWriting(text.toString().trim(),decoded.map{it.score}.average().takeUnless{it.isNaN()}?.toFloat()?:0f,1,tokens=decoded)
            }
        }
    }}
    private fun raster(line:HandwritingLine,padded:Boolean=false):Bitmap {
        val b=if(padded)line.bounds.padded(3.0)else line.bounds;val scale=min(3.0,96.0/(b.bottom-b.top).coerceAtLeast(1.0))
        val width=ceil((b.right-b.left)*scale).toInt().coerceAtLeast(1);val height=ceil((b.bottom-b.top)*scale).toInt().coerceAtLeast(1)
        require(width<=4096&&height<=512){"文字行过长，请缩小选区后识别"}
        RenderResources.admit(width.toLong()*height*4)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bitmap->
            RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"ocr","ocr",RenderResources.Role.IN_FLIGHT)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.scale(scale.toFloat(),scale.toFloat());canvas.translate(-b.left.toFloat(),-b.top.toFloat())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
            val geometry=VisibleInkGeometry()
            for(s in line.strokes){val save=canvas.save()
                if(s.pen==InkPen.PENCIL){paint.style=Paint.Style.FILL;canvas.drawPath(geometry.path(s),paint);paint.style=Paint.Style.STROKE;canvas.restoreToCount(save);continue}
                for(cut in s.cuts){val path=Path();val first=cut.points.first();path.moveTo(first.x,first.y);cut.points.drop(1).forEach{path.lineTo(it.x,it.y)}
                    if(cut.shape==InkCutShape.RECTANGLE){val last=cut.points.last();path.reset();path.addRect(first.x,first.y,last.x,last.y,Path.Direction.CW);canvas.clipOutPath(path)}
                    else if(cut.shape==InkCutShape.POLYGON){path.close();canvas.clipOutPath(path)}
                    else {val fill=Path();val eraser=Paint(paint).apply{strokeWidth=cut.radius*2};if(cut.points.size==1)fill.addCircle(first.x,first.y,cut.radius,Path.Direction.CW)else eraser.getFillPath(path,fill);canvas.clipOutPath(fill)}
                }
                paint.strokeWidth=s.width.coerceAtLeast(1f)
                val path=Path();val first=s.samples.first();path.moveTo(first.x,first.y);s.samples.drop(1).forEach{path.lineTo(it.x,it.y)}
                if(s.samples.size==1)canvas.drawPoint(first.x,first.y,paint)else canvas.drawPath(path,paint)
                canvas.restoreToCount(save)
            }
        }
    }
}
