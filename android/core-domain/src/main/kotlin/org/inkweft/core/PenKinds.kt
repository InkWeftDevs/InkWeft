// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.pow

/** Display and render policy; raw pressure samples remain unchanged in author data. */
object PenKinds {
    val writing=listOf(InkPen.BALLPOINT,InkPen.PEN,InkPen.PENCIL,InkPen.BRUSH,InkPen.MARKER)
    fun title(pen:InkPen)=when(pen){InkPen.PEN->"钢笔";InkPen.BALLPOINT->"圆珠笔";InkPen.BRUSH->"毛笔";InkPen.MARKER->"马克笔";InkPen.HIGHLIGHTER->"荧光笔";InkPen.PENCIL->"铅笔"}
    fun description(pen:InkPen)=when(pen){
        InkPen.PENCIL->"石墨颗粒，轻写浅、重写深；支持倾斜时可侧锋铺色。"
        InkPen.BALLPOINT->"均匀线宽，不随压力改变，适合日常记录。"
        InkPen.PEN->"实心墨迹，轻重控制粗细；无压感时保持固定笔尖。"
        InkPen.BRUSH->"圆润笔尖，轻重变化明显；无压感时快写收细。"
        InkPen.MARKER->"不透明斜笔尖，适合粗标题和重点强调。"
        InkPen.HIGHLIGHTER->"半透明斜笔尖，适合在文字上标注。"
    }
    fun pressureSensitive(pen:InkPen)=pen==InkPen.PEN||pen==InkPen.BRUSH
    fun renderPressure(pen:InkPen,raw:Float):Float {
        require(raw.isFinite()&&(raw==-1f||raw in 0f..1f))
        return if(raw<0||!pressureSensitive(pen))-1f else if(pen==InkPen.BRUSH)raw.pow(.55f) else raw.pow(.7f)
    }
    fun defaultWidth(pen:InkPen)=when(pen){InkPen.PENCIL->5f;InkPen.BALLPOINT->3f;InkPen.PEN->3f;InkPen.BRUSH->8f;InkPen.MARKER->10f;InkPen.HIGHLIGHTER->22f}
}
