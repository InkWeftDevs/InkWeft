// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.inkweft.core.*
import java.util.Base64

/** Bounded caches; decoded media is shared by transformed copies until the view is detached. */
internal class PageObjectPainter {
    var mapScenes:Map<MapRef,MapScene> = emptyMap()
    var originalImage:(PageObject)->ImageFrame? = {null}
    private val mapScenesResolved=object:LinkedHashMap<String,MapScene?>(16,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,MapScene?>?)=size>32}
    private val resourceOwner="objects-"+java.util.UUID.randomUUID()
    private val images=object:LinkedHashMap<String,Bitmap?>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Bitmap?>?):Boolean{if(size<=8)return false;eldest?.value?.let{RenderResources.release(it,resourceOwner)};return true}}
    private val layouts=object:LinkedHashMap<PageObject,StaticLayout>(32,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<PageObject,StaticLayout>?)=size>32}
    private val naturalLayouts=object:LinkedHashMap<PageObject,List<StaticLayout>>(16,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<PageObject,List<StaticLayout>>?)=size>32}
    private val graphite=object:LinkedHashMap<Int,BitmapShader>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,BitmapShader>?)=size>8}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun clear(){RenderResources.releaseOwner(resourceOwner);images.clear();layouts.clear();naturalLayouts.clear();graphite.clear();mapScenesResolved.clear();mapScenes=emptyMap()}
    fun draw(canvas:Canvas,objects:List<PageObject>,tapes:Boolean,visible:CanvasBounds,liveErase:Path?=null,wholeErase:Boolean=false) {
        objects.filter{!it.hidden&&(it.kind==PageObjectKind.TAPE)==tapes&&it.bounds().intersects(visible)}.forEach { source ->
            val o=if(liveErase!=null&&source.sourceStrokeIds.isNotEmpty()&&source.glyphs.isEmpty())source.copy(glyphs=TextStyles.positioned(source))else source
            paint.alpha=255;paint.style=Paint.Style.FILL;paint.pathEffect=null
            val save=canvas.save();canvas.clipRect(o.x,o.y,o.x+o.width,o.y+o.height)
            when(o.kind) {
                PageObjectKind.MAP->{
                    val embed=checkNotNull(o.mapEmbed)
                    val live=embed.snapshot?:mapScenes[embed.target]?:if(mapScenes.keys.any{it.notebookId==embed.target.notebookId})MapScene(embed.target,"",emptyList(),"",false)else null
                    val key=embed.cacheKey(live,o.width.toInt(),o.height.toInt())
                    val scene=if(mapScenesResolved.containsKey(key))mapScenesResolved[key]else live?.branch(embed.branchId,embed.depth).also{mapScenesResolved[key]=it}
                    MapScenePainter.embed(canvas,scene,o)
                }
                PageObjectKind.IMAGE->{
                    val original=originalImage(o)
                    val bitmap=if(images.containsKey(o.image))images[o.image]else try{
                        val bytes=Base64.getDecoder().decode(o.image)
                        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                        require(opts.outWidth in 1..1024&&opts.outHeight in 1..1024)
                        RenderResources.admit(opts.outWidth.toLong()*opts.outHeight*4)
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.also{RenderResources.track(it,it.allocationByteCount.toLong(),"image",resourceOwner,RenderResources.Role.ACTIVE)}.also{images[o.image]=it}
                    }catch(_:RenderBudgetBusy){null}catch(_:Exception){images[o.image]=null;null}
                    val fallback=canvas.save()
                    // Never paint an opaque JPEG underneath transparent original pixels.
                    original?.let{canvas.clipOutRect(it.bounds(o))}
                    if(bitmap!=null)canvas.drawBitmap(bitmap,null,RectF(o.x,o.y,o.x+o.width,o.y+o.height),paint)
                    else {paint.color=Color.LTGRAY;canvas.drawRect(o.x,o.y,o.x+o.width,o.y+o.height,paint)}
                    canvas.restoreToCount(fallback)
                    original?.draw(canvas,o,paint)
                }
                PageObjectKind.TEXT->{
                    if(o.textRuns.isNotEmpty()){
                        val shaped=naturalLayouts[o]?:o.textRuns.map{NaturalText.layout(o,it)}.also{naturalLayouts[o]=it}
                        NaturalText.draw(canvas,o,shaped,liveErase,wholeErase)
                    }else if(o.glyphs.isEmpty()){
                        val layout=layouts[o]?:TextStyles.layout(o).also{layouts[o]=it}
                        canvas.translate(o.x,o.y);layout.draw(canvas)
                    }else{
                        val textPaint=TextStyles.paint(o);val ink=Rect()
                        val masks=o.erasures.map{cut->cut to erasePath(cut,o.x,o.y)}
                        for(g in o.glyphs.filterNot{it.hidden}){
                            textPaint.shader=null;textPaint.color=g.color?:o.color
                            textPaint.style=if(g.weight>0)Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                            textPaint.isFakeBoldText=o.bold&&g.weight==0f;textPaint.strokeWidth=o.fontSize*.04f*g.weight;textPaint.strokeJoin=Paint.Join.ROUND
                            val value=o.text.substring(g.start,g.end);textPaint.getTextBounds(value,0,value.length,ink)
                            if(ink.width()==0||ink.height()==0)continue
                            if(liveErase!=null&&wholeErase){val overlap=Path(liveErase);overlap.op(Path().apply{addRect(o.x+g.x,o.y+g.y,o.x+g.x+g.width,o.y+g.y+g.height,Path.Direction.CW)},Path.Op.INTERSECT);if(!overlap.isEmpty)continue}
                            val glyphSave=canvas.save()
                            if(liveErase!=null&&!wholeErase)canvas.clipOutPath(liveErase)
                            masks.filter{(cut,_)->cut.start<g.end&&cut.end>g.start}.forEach{(_,path)->canvas.clipOutPath(path)}
                            canvas.translate(o.x+g.x,o.y+g.y)
                            val pad=if(g.weight>0)textPaint.strokeWidth/2 else 0f
                            canvas.scale(g.width/(ink.width()+pad*2),g.height/(ink.height()+pad*2));canvas.translate(-ink.left.toFloat()+pad,-ink.top.toFloat()+pad)
                            if(g.grain>0){
                                val tint=g.color?:o.color
                                val shader=graphite.getOrPut(tint){
                                    val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                                    val pixels=GraphiteMaterial.alpha().map{a->((((a.toInt()and 255)*(tint ushr 24)/255) shl 24)or(tint and 0xffffff))}.toIntArray()
                                    bitmap.setPixels(pixels,0,64,0,0,64,64);BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
                                }
                                shader.setLocalMatrix(Matrix().apply{setScale(.5f*g.grain*(ink.width()+pad*2)/g.width,.5f*g.grain*(ink.height()+pad*2)/g.height)})
                                textPaint.alpha=255;textPaint.shader=shader
                            }
                            canvas.drawText(value,0f,0f,textPaint);canvas.restoreToCount(glyphSave)
                        }
                    }
                }
                PageObjectKind.TAPE->TapeArt.draw(canvas,o)
                PageObjectKind.SHAPE->{paint.color=o.color;paint.style=Paint.Style.STROKE;paint.strokeWidth=o.lineWidth;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND
                    // Inset a half stroke so borders survive object clipping.
                    canvas.drawPath(ObjectGeometry.path(o,o.lineWidth/2),paint);paint.style=Paint.Style.FILL}
            };canvas.restoreToCount(save)
        }
    }
    private fun erasePath(cut:TextErasePath,x:Float,y:Float):Path {
        val result=Path();val first=cut.points.first()
        if(cut.points.size==1){result.addCircle(x+first.x,y+first.y,cut.radius,Path.Direction.CW);return result}
        val center=Path().apply{moveTo(x+first.x,y+first.y);cut.points.drop(1).forEach{lineTo(x+it.x,y+it.y)}}
        Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=cut.radius*2;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}.getFillPath(center,result)
        return result
    }

}
