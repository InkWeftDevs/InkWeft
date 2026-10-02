// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.Layout
import org.inkweft.core.*
import kotlin.math.*

internal object MapNodeMetrics { const val WIDTH=232;const val HEIGHT=84;const val HALF_WIDTH=116;const val HALF_HEIGHT=42 }

/** Transient interaction only. Never serialized or supplied to document/embed/export painting. */
internal data class MapViewStyle(val selection:Int,val selectionFill:Int,val connector:Int)

/** Pure drawing shared by interactive maps and page occurrences. Never follows source pages. */
internal object MapScenePainter {
    internal fun titleLayout(title:String,fontScale:Float=1f):StaticLayout {
        val text=title.replace('\n',' ')
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);textSize=16f*fontScale}
        return StaticLayout.Builder.obtain(text,0,text.length,paint,MapNodeMetrics.WIDTH-28)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(2)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(MapNodeMetrics.WIDTH-28).build()
    }
    fun draw(c:Canvas,nodes:List<MapSceneNode>,selected:String?=null,collapsed:Map<String,Int> = emptyMap(),fontScale:Float=1f,detail:Boolean=true,hierarchy:Boolean=true,viewStyle:MapViewStyle?=null){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val lookup=nodes.associateBy{it.id}
        if(hierarchy){paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=viewStyle?.connector?:0xffd9dee7.toInt()
            nodes.forEach{n->lookup[n.parentId]?.let{p->val right=n.x>=p.x;val sx=p.x.toFloat()+if(right)MapNodeMetrics.WIDTH else 0;val ex=n.x.toFloat()+if(right)0 else MapNodeMetrics.WIDTH;val sign=if(right)1 else -1
                c.drawPath(Path().apply{moveTo(sx,p.y.toFloat()+MapNodeMetrics.HALF_HEIGHT);cubicTo(sx+28*sign,p.y.toFloat()+MapNodeMetrics.HALF_HEIGHT,ex-28*sign,n.y.toFloat()+MapNodeMetrics.HALF_HEIGHT,ex,n.y.toFloat()+MapNodeMetrics.HALF_HEIGHT)},paint)}}}
        nodes.forEach{n->
            val left=n.x.toFloat();val top=n.y.toFloat();val root=hierarchy&&n.parentId !in lookup
            val highlight=viewStyle!=null&&n.id==selected
            paint.style=Paint.Style.FILL;paint.color=if(root)0xff176eb1.toInt()else if(highlight)viewStyle.selectionFill else Color.WHITE
            c.drawRoundRect(left,top,left+MapNodeMetrics.WIDTH,top+MapNodeMetrics.HEIGHT,12f,12f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=if(root)0xff176eb1.toInt()else 0xff929cac.toInt();c.drawRoundRect(left,top,left+MapNodeMetrics.WIDTH,top+MapNodeMetrics.HEIGHT,12f,12f,paint)
            if(highlight){paint.color=viewStyle.selection;paint.strokeWidth=2f;c.drawRoundRect(left-4,top-4,left+MapNodeMetrics.WIDTH+4,top+MapNodeMetrics.HEIGHT+4,16f,16f,paint)}
            paint.style=Paint.Style.FILL;paint.color=if(root)Color.WHITE else 0xff242b36.toInt();paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);paint.textSize=16f*fontScale
            if(detail){
                val layout=titleLayout(n.title,fontScale).also{it.paint.color=paint.color}
                val saved=c.save();c.translate(left+14,top+(MapNodeMetrics.HEIGHT-layout.height)/2f);layout.draw(c);c.restoreToCount(saved)
            }else{paint.alpha=160;c.drawRoundRect(left+14,top+26,left+170,top+34,4f,4f,paint);paint.alpha=255}
        }
    }
    fun embed(c:Canvas,scene:MapScene?,o:PageObject){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val save=c.save();c.clipRect(o.x,o.y,o.x+o.width,o.y+o.height);paint.color=Color.WHITE;c.drawRect(o.x,o.y,o.x+o.width,o.y+o.height,paint)
        if(scene==null||!scene.available||scene.nodes.isEmpty()){
            paint.color=0xff626d7e.toInt();paint.textSize=18f
            c.drawText(if(scene==null)"正在读取导图…"else if(!scene.available)"源图或分支不可用，可恢复后继续"else"空导图 · 选择后编辑",o.x+12,o.y+32,paint)
        }else{
            val nodes=scene.nodes;val left=nodes.minOf{it.x};val top=nodes.minOf{it.y};val right=nodes.maxOf{it.x}+MapNodeMetrics.WIDTH;val bottom=nodes.maxOf{it.y}+MapNodeMetrics.HEIGHT
            val scale=min((o.width-24)/(right-left).toFloat(),(o.height-40)/(bottom-top).toFloat()).coerceAtLeast(.01f)
            c.translate(o.x+o.width/2-((left+right)/2*scale).toFloat(),o.y+12+(o.height-40)/2-((top+bottom)/2*scale).toFloat());c.scale(scale,scale);draw(c,nodes,detail=scale>=.3f)
        }
        c.restoreToCount(save);paint.color=0xff626d7e.toInt();paint.textSize=12f
        c.drawText(if(o.mapEmbed?.policy==MapEmbedPolicy.PINNED)"固定快照"else"实时导图 · 选择后编辑",o.x+10,o.y+o.height-10,paint)
    }
}
