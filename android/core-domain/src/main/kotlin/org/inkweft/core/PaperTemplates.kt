// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.*

data class PaperLine(val x1:Float,val y1:Float,val x2:Float,val y2:Float)
data class PaperGuides(val lines:List<PaperLine>,val dots:List<CanvasPoint>)

/** Declarative built-ins; preview and native canvas consume the same author-space geometry. */
object PaperTemplates {
    fun title(style:PaperStyle)=when(style){
        PaperStyle.BLANK->"空白";PaperStyle.RULED->"横线";PaperStyle.GRID->"方格";PaperStyle.DOTS->"点阵"
        PaperStyle.CORNELL->"康奈尔 · 窄栏";PaperStyle.CORNELL_WIDE->"康奈尔 · 宽栏";PaperStyle.CORNELL_BLANK->"康奈尔 · 空白"
        PaperStyle.MISTAKES->"错题整理";PaperStyle.DERIVATION->"推导";PaperStyle.DAILY->"每日学习"
    }
    fun category(style:PaperStyle)=when(style){PaperStyle.BLANK,PaperStyle.RULED,PaperStyle.GRID,PaperStyle.DOTS->"基础";PaperStyle.DAILY->"计划";else->"学习"}
    fun guides(style:PaperStyle,visible:CanvasBounds,world:Boolean,pixelsPerUnit:Double):PaperGuides {
        require(pixelsPerUnit.isFinite()&&pixelsPerUnit>0)
        val lines=mutableListOf<PaperLine>();val dots=mutableListOf<CanvasPoint>()
        if(style==PaperStyle.BLANK)return PaperGuides(lines,dots)
        val l=if(world)visible.left else max(40.0,visible.left);val r=if(world)visible.right else min(960.0,visible.right)
        val t=if(world)visible.top else max(80.0,visible.top);val b=if(world)visible.bottom else min(1334.0,visible.bottom)
        if(l>r||t>b)return PaperGuides(lines,dots)
        fun line(x:Double,y:Double,X:Double,Y:Double){if(CanvasBounds(min(x,X),min(y,Y),max(x,X),max(y,Y)).intersects(visible))lines+=PaperLine(x.toFloat(),y.toFloat(),X.toFloat(),Y.toFloat())}
        val cornell=style in setOf(PaperStyle.CORNELL,PaperStyle.CORNELL_WIDE,PaperStyle.CORNELL_BLANK)
        if(!world){
            if(cornell){line(if(style==PaperStyle.CORNELL_WIDE)340.0 else 260.0,80.0,if(style==PaperStyle.CORNELL_WIDE)340.0 else 260.0,1120.0);line(40.0,1120.0,960.0,1120.0)}
            if(style==PaperStyle.MISTAKES){line(40.0,530.0,960.0,530.0);line(40.0,1020.0,960.0,1020.0)}
            if(style==PaperStyle.DAILY){line(40.0,240.0,960.0,240.0);line(40.0,1080.0,960.0,1080.0);line(220.0,240.0,220.0,1080.0)}
        }
        if(style in setOf(PaperStyle.CORNELL_BLANK,PaperStyle.MISTAKES))return PaperGuides(lines,dots)
        var gap=if(style in setOf(PaperStyle.GRID,PaperStyle.DOTS))40.0 else 55.0
        while(gap*pixelsPerUnit<8)gap*=2
        val sx=ceil(l/gap)*gap;val sy=ceil(t/gap)*gap
        val nx=((r-sx)/gap).toInt().coerceIn(-1,240);val ny=((b-sy)/gap).toInt().coerceIn(-1,240)
        if(style==PaperStyle.DOTS){for(y in 0..ny)for(x in 0..nx)if(dots.size<4096)dots+=CanvasPoint(sx+x*gap,sy+y*gap)}
        else {
            for(y in 0..ny){val yy=sy+y*gap;val left=if(cornell&&!world&&yy<1120)max(l,if(style==PaperStyle.CORNELL_WIDE)340.0 else 260.0)else l;line(left,yy,r,yy)}
            if(style==PaperStyle.GRID)for(x in 0..nx)line(sx+x*gap,t,sx+x*gap,b)
            if(style==PaperStyle.DERIVATION&&!world)line(160.0,80.0,160.0,1334.0)
        }
        return PaperGuides(lines,dots)
    }
}
