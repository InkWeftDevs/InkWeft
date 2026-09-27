// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import org.inkweft.core.*
import java.text.BreakIterator
import java.util.Locale

internal object TextStyles {
    private var wenkai:Typeface?=null
    @Synchronized fun initialize(context:Context){if(wenkai==null)wenkai=Typeface.createFromAsset(context.assets,"fonts/LXGWWenKaiLite-Regular.ttf")}
    fun name(font:TextFont)=when(font){TextFont.SYSTEM->"系统黑体";TextFont.WENKAI->"霞鹜文楷";TextFont.SERIF->"系统衬线"}
    fun face(font:TextFont)=when(font){TextFont.SYSTEM->Typeface.DEFAULT;TextFont.WENKAI->checkNotNull(wenkai);TextFont.SERIF->Typeface.SERIF}
    fun paint(o:PageObject)=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=o.color;textSize=o.fontSize;typeface=face(o.font);isFakeBoldText=o.bold}
    fun graphemes(text:String):List<IntRange>{
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT);iterator.setText(text);val result=mutableListOf<IntRange>()
        var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE){if(text.substring(start,end).isNotBlank())result.add(start until end);start=end;end=iterator.next()};return result
    }
    /** Capture the actual old layout before editing/erasing an existing text object. */
    fun positioned(o:PageObject):List<TextGlyph>{
        if(o.glyphs.isNotEmpty())return o.glyphs
        val layout=layout(o);val paint=paint(o);val ink=Rect()
        return graphemes(o.text).mapNotNull{range->
            val end=range.last+1;val line=layout.getLineForOffset(range.first)
            paint.getTextBounds(o.text,range.first,end,ink)
            val x=(layout.getPrimaryHorizontal(range.first)+ink.left).coerceAtLeast(0f)
            val y=(layout.getLineBaseline(line)+ink.top).toFloat().coerceAtLeast(0f)
            val right=(layout.getPrimaryHorizontal(range.first)+ink.right).coerceAtMost(o.width)
            val bottom=(layout.getLineBaseline(line)+ink.bottom).toFloat().coerceAtMost(o.height)
            if(right<=x||bottom<=y)null else TextGlyph(range.first,end,x,y,right-x,bottom-y)
        }
    }
    fun layout(o:PageObject,width:Float=o.width):StaticLayout = StaticLayout.Builder.obtain(o.text,0,o.text.length,
        paint(o),width.toInt().coerceAtLeast(1))
        .setIncludePad(false).setLineSpacing(0f,o.lineSpacing).build()
}
