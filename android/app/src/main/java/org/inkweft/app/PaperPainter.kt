// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.inkweft.core.PaperGuides

/** Shared renderer for the real page, directory thumbnail and template picker. */
internal object PaperPainter {
    fun draw(canvas:Canvas,guides:PaperGuides,pixelsPerUnit:Double){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=0xffedf4ef.toInt()
        guides.fills.forEach{canvas.drawRect(it.x,it.y,it.x+it.width,it.y+it.height,paint)}
        guides.lines.forEach{
            paint.color=if(it.strong)0xff9caf9f.toInt() else 0xffd8e0dc.toInt()
            paint.strokeWidth=((if(it.strong)1.3 else .8)/pixelsPerUnit).toFloat()
            canvas.drawLine(it.x1,it.y1,it.x2,it.y2,paint)
        }
        paint.color=0xffd8e0dc.toInt()
        guides.dots.forEach{canvas.drawCircle(it.x.toFloat(),it.y.toFloat(),(1/pixelsPerUnit).toFloat(),paint)}
        paint.color=0xff517266.toInt();paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        guides.labels.forEach{paint.textSize=it.size;canvas.drawText(it.text,it.x,it.y,paint)}
    }
}
