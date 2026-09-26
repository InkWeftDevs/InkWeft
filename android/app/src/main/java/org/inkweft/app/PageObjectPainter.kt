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
        objects.filter{(it.kind==PageObjectKind.TAPE)==tapes&&it.bounds().intersects(visible)}.forEach { o ->
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
                    val layout=layouts[o]?:StaticLayout.Builder.obtain(o.text,0,o.text.length,TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=o.color;textSize=o.fontSize},o.width.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build().also{layouts[o]=it}
                    canvas.translate(o.x,o.y);layout.draw(canvas)
                }
                PageObjectKind.TAPE->{
                    paint.color=o.color or 0xff000000.toInt();paint.style=if(o.revealed)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=2f
                    canvas.drawRect(o.x+1,o.y+1,o.x+o.width-1,o.y+o.height-1,paint);paint.style=Paint.Style.FILL
                }
            };canvas.restoreToCount(save)
        }
    }
}
