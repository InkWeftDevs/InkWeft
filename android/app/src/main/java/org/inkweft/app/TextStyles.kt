// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import org.inkweft.core.*

internal object TextStyles {
    private var wenkai:Typeface?=null
    @Synchronized fun initialize(context:Context){if(wenkai==null)wenkai=Typeface.createFromAsset(context.assets,"fonts/LXGWWenKaiLite-Regular.ttf")}
    fun name(font:TextFont)=when(font){TextFont.SYSTEM->"系统黑体";TextFont.WENKAI->"霞鹜文楷";TextFont.SERIF->"系统衬线"}
    fun face(font:TextFont)=when(font){TextFont.SYSTEM->Typeface.DEFAULT;TextFont.WENKAI->checkNotNull(wenkai);TextFont.SERIF->Typeface.SERIF}
    fun layout(o:PageObject,width:Float=o.width):StaticLayout = StaticLayout.Builder.obtain(o.text,0,o.text.length,
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=o.color;textSize=o.fontSize;typeface=face(o.font);isFakeBoldText=o.bold},width.toInt().coerceAtLeast(1))
        .setIncludePad(false).setLineSpacing(0f,o.lineSpacing).build()
}
