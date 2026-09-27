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
    private val images=object:LinkedHashMap<String,Bitmap?>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Bitmap?>?)=size>8}
    private val layouts=object:LinkedHashMap<PageObject,StaticLayout>(32,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<PageObject,StaticLayout>?)=size>32}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun clear(){images.clear();layouts.clear()}
    fun draw(canvas:Canvas,objects:List<PageObject>,tapes:Boolean,visible:CanvasBounds) {
        objects.filter{!it.hidden&&(it.kind==PageObjectKind.TAPE)==tapes&&it.bounds().intersects(visible)}.forEach { o ->
            val save=canvas.save();canvas.clipRect(o.x,o.y,o.x+o.width,o.y+o.height)
            when(o.kind) {
                PageObjectKind.IMAGE->{
                    val bitmap=if(images.containsKey(o.image))images[o.image]else runCatching{
                        val bytes=Base64.getDecoder().decode(o.image)
                        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                        require(opts.outWidth in 1..1024&&opts.outHeight in 1..1024)
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    }.getOrNull().also{images[o.image]=it}
                    if(bitmap!=null)canvas.drawBitmap(bitmap,null,RectF(o.x,o.y,o.x+o.width,o.y+o.height),paint)
                    else {paint.color=Color.LTGRAY;canvas.drawRect(o.x,o.y,o.x+o.width,o.y+o.height,paint)}
                }
                PageObjectKind.TEXT->{
                    if(o.glyphs.isEmpty()){
                        val layout=layouts[o]?:TextStyles.layout(o).also{layouts[o]=it}
                        canvas.translate(o.x,o.y);layout.draw(canvas)
                    }else{
                        val textPaint=TextStyles.paint(o);val ink=Rect()
                        val masks=o.erasures.map{cut->cut to erasePath(cut,o.x,o.y)}
                        for(g in o.glyphs.filterNot{it.hidden}){
                            textPaint.style=if(g.weight>0)Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                            textPaint.isFakeBoldText=o.bold&&g.weight==0f;textPaint.strokeWidth=o.fontSize*.04f*g.weight;textPaint.strokeJoin=Paint.Join.ROUND
                            val value=o.text.substring(g.start,g.end);textPaint.getTextBounds(value,0,value.length,ink)
                            if(ink.width()==0||ink.height()==0)continue
                            val glyphSave=canvas.save()
                            masks.filter{(cut,_)->cut.start<g.end&&cut.end>g.start}.forEach{(_,path)->canvas.clipOutPath(path)}
                            canvas.translate(o.x+g.x,o.y+g.y)
                            val pad=if(g.weight>0)textPaint.strokeWidth/2 else 0f
                            canvas.scale(g.width/(ink.width()+pad*2),g.height/(ink.height()+pad*2));canvas.translate(-ink.left.toFloat()+pad,-ink.top.toFloat()+pad)
                            canvas.drawText(value,0f,0f,textPaint);canvas.restoreToCount(glyphSave)
                        }
                    }
                }
                PageObjectKind.TAPE->{
                    paint.color=o.color or 0xff000000.toInt();paint.style=if(o.revealed)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=2f
                    canvas.drawRect(o.x+1,o.y+1,o.x+o.width-1,o.y+o.height-1,paint);paint.style=Paint.Style.FILL
                }
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
