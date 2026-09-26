// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Display and render policy; raw pressure samples remain unchanged in author data. */
object PenKinds {
    val writing=listOf(InkPen.BALLPOINT,InkPen.PEN,InkPen.BRUSH,InkPen.MARKER)
    fun title(pen:InkPen)=when(pen){InkPen.PEN->"钢笔";InkPen.BALLPOINT->"圆珠笔";InkPen.BRUSH->"毛笔";InkPen.MARKER->"马克笔";InkPen.HIGHLIGHTER->"荧光笔"}
    fun description(pen:InkPen)=when(pen){
        InkPen.BALLPOINT->"均匀线宽，不随压力改变，适合日常记录。"
        InkPen.PEN->"随压力和速度变化，适合文字与公式。"
        InkPen.BRUSH->"轻笔更细、重笔更饱满，适合标题与练字。"
        InkPen.MARKER->"不透明斜笔尖，适合粗标题和重点强调。"
        InkPen.HIGHLIGHTER->"半透明斜笔尖，适合在文字上标注。"
    }
    fun pressureSensitive(pen:InkPen)=pen==InkPen.PEN||pen==InkPen.BRUSH
    fun renderPressure(pen:InkPen,raw:Float):Float {
        require(raw.isFinite()&&(raw==-1f||raw in 0f..1f))
        return if(raw<0||!pressureSensitive(pen))-1f else if(pen==InkPen.BRUSH)raw*raw else raw
    }
    fun defaultWidth(pen:InkPen)=when(pen){InkPen.BALLPOINT->3f;InkPen.PEN->3f;InkPen.BRUSH->8f;InkPen.MARKER->10f;InkPen.HIGHLIGHTER->22f}
}
