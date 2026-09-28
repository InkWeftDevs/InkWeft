// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import org.inkweft.core.*
import kotlin.math.*

/** Pure drawing shared by interactive maps and page occurrences. Never follows source pages. */
internal object MapScenePainter {
    fun draw(c:Canvas,nodes:List<MapSceneNode>,selected:String?=null,collapsed:Map<String,Int> = emptyMap(),fontScale:Float=1f,detail:Boolean=true,hierarchy:Boolean=true){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val lookup=nodes.associateBy{it.id}
        if(hierarchy){paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=0xffd9dee7.toInt()
            nodes.forEach{n->lookup[n.parentId]?.let{p->val right=n.x>=p.x;val sx=p.x.toFloat()+if(right)216 else 0;val ex=n.x.toFloat()+if(right)0 else 216;val sign=if(right)1 else -1
                c.drawPath(Path().apply{moveTo(sx,p.y.toFloat()+42);cubicTo(sx+28*sign,p.y.toFloat()+42,ex-28*sign,n.y.toFloat()+42,ex,n.y.toFloat()+42)},paint)}}}
        nodes.forEach{n->
            val left=n.x.toFloat();val top=n.y.toFloat();val root=hierarchy&&n.parentId !in lookup
            paint.style=Paint.Style.FILL;paint.color=if(root)0xff176eb1.toInt()else if(n.id==selected)0xffe8f2fb.toInt()else Color.WHITE
            c.drawRoundRect(left,top,left+216,top+84,12f,12f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=if(n.id==selected)2.5f else 1f;paint.color=if(root||n.id==selected)0xff176eb1.toInt()else 0xff929cac.toInt();c.drawRoundRect(left,top,left+216,top+84,12f,12f,paint)
            paint.style=Paint.Style.FILL;paint.color=if(root)Color.WHITE else 0xff242b36.toInt();paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);paint.textSize=16f*fontScale
            if(detail){val text=n.title.replace('\n',' ');val a=paint.breakText(text,true,188f,null);c.drawText(text.take(a),left+14,top+30,paint);val rest=text.drop(a).ifEmpty{n.body.lineSequence().firstOrNull().orEmpty()};paint.typeface=Typeface.DEFAULT;paint.textSize=14f*fontScale;val b=paint.breakText(rest,true,176f,null);c.drawText(rest.take(b)+(if(rest.length>b)"…"else""),left+14,top+53,paint)
                if((collapsed[n.id]?:0)>0){paint.typeface=Typeface.DEFAULT;paint.textSize=12f;c.drawText("已收起 ${collapsed[n.id]} 个下级",left+14,top+73,paint)}
            }else{paint.alpha=160;c.drawRoundRect(left+14,top+26,left+170,top+34,4f,4f,paint);paint.alpha=255}
        }
    }
    fun embed(c:Canvas,scene:MapScene?,o:PageObject){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val save=c.save();c.clipRect(o.x,o.y,o.x+o.width,o.y+o.height);paint.color=Color.WHITE;c.drawRect(o.x,o.y,o.x+o.width,o.y+o.height,paint)
        if(scene==null||!scene.available||scene.nodes.isEmpty()){
            paint.color=0xff626d7e.toInt();paint.textSize=18f
            c.drawText(if(scene==null)"正在读取导图…"else if(!scene.available)"源图或分支不可用，可恢复后继续"else"空导图 · 选择后编辑",o.x+12,o.y+32,paint)
        }else{
            val nodes=scene.nodes;val left=nodes.minOf{it.x};val top=nodes.minOf{it.y};val right=nodes.maxOf{it.x}+216;val bottom=nodes.maxOf{it.y}+84
            val scale=min((o.width-24)/(right-left).toFloat(),(o.height-40)/(bottom-top).toFloat()).coerceAtLeast(.01f)
            c.translate(o.x+o.width/2-((left+right)/2*scale).toFloat(),o.y+12+(o.height-40)/2-((top+bottom)/2*scale).toFloat());c.scale(scale,scale);draw(c,nodes,detail=scale>=.3f)
        }
        c.restoreToCount(save);paint.color=0xff626d7e.toInt();paint.textSize=12f
        c.drawText(if(o.mapEmbed?.policy==MapEmbedPolicy.PINNED)"固定快照"else"实时导图 · 选择后编辑",o.x+10,o.y+o.height-10,paint)
    }
}
