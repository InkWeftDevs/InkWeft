// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.StudySourceRow
import java.util.Base64
import kotlin.math.*

/** View-only provenance. Missing bounds permit a stable placeholder while metadata arrives. */
internal data class MapSourceInfo(val label:String,val bounds:CanvasBounds?=null)
internal data class MapSourceFrame(val bitmap:Bitmap?=null,val unavailable:Boolean=false)

/** At most eight visible cards; the existing raster worker owns all stroke/erase rendering. */
internal class MapSourcePreview(private val read:suspend (String)->StudySourceRow?,private val changed:()->Unit){
    private data class Key(val card:String,val revision:Long)
    private class Entry(val owner:String){var job:Job?=null;var bitmap:Bitmap?=null;var unavailable=false}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val loading=Mutex()
    private val entries=linkedMapOf<Key,Entry>()
    private var wanted=emptyList<Key>()

    fun request(cards:List<Pair<String,Long>>){
        val keys=cards.distinctBy{it.first}.take(8).map{Key(it.first,it.second)}
        if(keys==wanted)return
        wanted=keys
        entries.keys.filter{it !in keys}.forEach{key->entries.remove(key)?.let(::release)}
        keys.forEach{key->if(key !in entries){
            val entry=Entry("map-source-${key.card}-${key.revision}-${System.identityHashCode(this)}")
            entries[key]=entry
            entry.job=scope.launch{
                var result:Bitmap?=null
                try{
                    loading.withLock{
                        val snapshot=withContext(Dispatchers.IO){read(key.card)?.let{InkPageFile.decode(it.snapshot)}}
                            ?:error("SOURCE_UNAVAILABLE")
                        ensureActive()
                        val image=snapshot.objects.singleOrNull()?.takeIf{it.kind==PageObjectKind.IMAGE&&snapshot.strokes.isEmpty()}
                        if(image!=null)withContext(Dispatchers.Default){
                            val bytes=Base64.getDecoder().decode(image.image)
                            val size=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                            BitmapFactory.decodeByteArray(bytes,0,bytes.size,size)
                            require(size.outWidth in 1..1024&&size.outHeight in 1..1024)
                            val options=BitmapFactory.Options().apply{inSampleSize=1}
                            while(max(size.outWidth,size.outHeight)/options.inSampleSize>512)options.inSampleSize*=2
                            RenderResources.admit((size.outWidth/options.inSampleSize).toLong()*(size.outHeight/options.inSampleSize)*4)
                            result=checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)).also{
                                RenderResources.track(it,it.allocationByteCount.toLong(),"map-source",entry.owner,RenderResources.Role.IN_FLIGHT)
                            }
                        }else result=render(snapshot,entry.owner)
                    }
                    ensureActive()
                    if(entries[key]===entry){
                        entry.bitmap=result
                        result?.let{RenderResources.track(it,it.allocationByteCount.toLong(),"map-source",entry.owner,RenderResources.Role.CACHE)}
                        result=null;changed()
                    }
                }catch(_:TimeoutCancellationException){if(entries[key]===entry){entry.unavailable=true;changed()}}
                catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){if(entries[key]===entry){entry.unavailable=true;changed()}}
                finally{result?.let{RenderResources.release(it,entry.owner);it.recycle()}}
            }
        }}
    }

    fun frames():Map<String,MapSourceFrame> = entries.mapKeys{it.key.card}.mapValues{(_,entry)->MapSourceFrame(entry.bitmap,entry.unavailable)}
    fun retry(card:String){entries.keys.filter{it.card==card&&entries[it]?.unavailable==true}.forEach{entries.remove(it)?.let(::release)};wanted=emptyList()}
    fun clear(){wanted=emptyList();entries.values.forEach(::release);entries.clear();scope.coroutineContext.cancelChildren()}
    // A published bitmap may still be referenced by a hardware display list.
    private fun release(entry:Entry){entry.job?.cancel();entry.bitmap?.let{RenderResources.release(it,entry.owner)};entry.bitmap=null}

    private suspend fun render(file:InkPageFile,owner:String):Bitmap {
        val objects=file.objects.filterNot{it.hidden}
        val suppressed=objects.flatMap{it.sourceStrokeIds}.toSet()
        val strokes=file.strokes.filter{it.id !in suppressed}
        val bounds=(objects.map{it.bounds()}+strokes.map{it.bounds()}).reduceOrNull{a,b->a.union(b)}?.padded(6.0)
            ?:error("EMPTY_SOURCE")
        val ratio=((bounds.right-bounds.left)/(bounds.bottom-bounds.top)).coerceIn(1.0/16,16.0)
        val width=if(ratio>=1)384 else (384*ratio).roundToInt().coerceAtLeast(24)
        val height=if(ratio>=1)(384/ratio).roundToInt().coerceAtLeast(24)else 384
        RenderResources.admit(width.toLong()*height*4)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"map-source",owner,RenderResources.Role.IN_FLIGHT)
        val viewport=CanvasViewport.fit(bounds,width.toDouble(),height.toDouble())
        val ready=CompletableDeferred<Unit>()
        lateinit var raster:AsyncInkRaster
        raster=AsyncInkRaster({if(!raster.pending)ready.complete(Unit)},{ready.completeExceptionally(IllegalStateException("SOURCE_RENDER_FAILED"))})
        val painter=PageObjectPainter()
        var completed=false
        try{
            val canvas=Canvas(bitmap)
            // The first draw schedules the existing worker. Only its complete frame is published.
            raster.draw(canvas,width,height,viewport,1.0,true,false,strokes)
            if(!raster.pending)ready.complete(Unit)
            withTimeout(8_000){ready.await()}
            currentCoroutineContext().ensureActive()
            check(!raster.pending&&strokes.all{raster.contains(it.id)}){"SOURCE_RENDER_INCOMPLETE"}
            bitmap.eraseColor(Color.WHITE)
            val matrix=Matrix().apply{setScale(viewport.zoom.toFloat(),viewport.zoom.toFloat());postTranslate((width/2.0-viewport.centerX*viewport.zoom).toFloat(),(height/2.0-viewport.centerY*viewport.zoom).toFloat())}
            val under=canvas.save();canvas.concat(matrix);painter.draw(canvas,objects,false,bounds);canvas.restoreToCount(under)
            raster.draw(canvas,width,height,viewport,1.0,true,false,strokes)
            val over=canvas.save();canvas.concat(matrix);painter.draw(canvas,objects,true,bounds);canvas.restoreToCount(over)
            bitmap.prepareToDraw();completed=true;return bitmap
        }finally{
            raster.clear();painter.clear()
            if(!completed){RenderResources.release(bitmap,owner);bitmap.recycle()}
        }
    }
}
