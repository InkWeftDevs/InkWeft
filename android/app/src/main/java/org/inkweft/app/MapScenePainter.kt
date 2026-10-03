// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import org.inkweft.core.*
import kotlin.math.*

/** One measured card drives painting, hit testing, connectors and camera operations. */
internal data class MapNodeLayout(
    val width:Float,val height:Float,
    val title:StaticLayout,val titleTop:Float,
    val summary:StaticLayout?,val summaryTop:Float,
    val source:StaticLayout?,val sourceTop:Float,
    val preview:RectF?,val canExpand:Boolean,
)

internal object MapNodeMetrics {
    const val WIDTH=232
    private const val PAD=14f
    internal fun textLayout(value:String,size:Float,fontScale:Float,lines:Int,bold:Boolean=false,width:Int=WIDTH-28):StaticLayout {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create(Typeface.DEFAULT,if(bold)Typeface.BOLD else Typeface.NORMAL);textSize=size*fontScale}
        return StaticLayout.Builder.obtain(value,0,value.length,paint,width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(lines)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(width).build()
    }
    fun measure(title:String,body:String,source:MapSourceInfo?=null,fontScale:Float=1f,expanded:Boolean=false,structural:Boolean=false):MapNodeLayout {
        val factor=fontScale.coerceAtLeast(.5f)
        val heading=textLayout(title.replace('\n',' '),if(structural)18f else 16f,factor,if(expanded)4 else 2,true)
        val summary=body.takeIf{it.isNotBlank()&&!structural}?.let{textLayout(it,13f,factor,if(expanded)8 else 3)}
        val caption=source?.let{
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11f*factor}
            val label=TextUtils.ellipsize(it.label.replace('\n',' '),paint,(WIDTH-28)*2f,TextUtils.TruncateAt.MIDDLE).toString()
            textLayout(label,11f,factor,2)
        }
        var y=PAD+heading.height
        val summaryTop=if(summary!=null)y+8f else y
        if(summary!=null)y=summaryTop+summary.height
        val preview=if(source!=null&&!structural&&(summary==null||expanded)){
            // Metadata and bitmap arrival never resize the reserved original-content slot.
            RectF(PAD,y+8f,WIDTH-PAD,y+8f+(if(expanded)144f else 88f)).also{y=it.bottom}
        }else null
        val sourceTop=if(caption!=null)y+8f else y
        if(caption!=null)y=sourceTop+max(caption.height.toFloat(),ceil(caption.paint.fontSpacing*2))
        val truncated=(0 until heading.lineCount).any{heading.getEllipsisCount(it)>0}||summary?.let{s->(0 until s.lineCount).any{s.getEllipsisCount(it)>0}}==true
        return MapNodeLayout(WIDTH.toFloat(),max(if(structural)60f else 64f,y+PAD),heading,PAD,summary,summaryTop,caption,sourceTop,preview,truncated||source!=null||expanded)
    }
}

/** Transient interaction only. Never serialized or supplied to document/embed/export painting. */
internal data class MapViewStyle(val selection:Int,val selectionFill:Int,val connector:Int)

/** Pure drawing shared by interactive maps and page occurrences. Never follows source pages. */
internal object MapScenePainter {
    internal fun titleLayout(title:String,fontScale:Float=1f):StaticLayout = MapNodeMetrics.textLayout(title.replace('\n',' '),16f,fontScale,2,true)
    fun draw(c:Canvas,nodes:List<MapSceneNode>,selected:String?=null,collapsed:Map<String,Int> = emptyMap(),fontScale:Float=1f,detail:Boolean=true,hierarchy:Boolean=true,viewStyle:MapViewStyle?=null,
             nodeLayouts:Map<String,MapNodeLayout> = emptyMap(),previews:Map<String,MapSourceFrame> = emptyMap()){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val lookup=nodes.associateBy{it.id}
        // Document and embedded painting deliberately ignore live source and expansion state.
        val layouts=nodes.associate{n->n.id to (nodeLayouts[n.id]?.takeIf{viewStyle!=null}
            ?:MapNodeMetrics.measure(n.title,n.body,fontScale=fontScale,structural=hierarchy&&n.cardId==null))}
        if(hierarchy){
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=viewStyle?.connector?:0xffd9dee7.toInt()
            nodes.forEach{n->lookup[n.parentId]?.let{p->
                val parent=layouts.getValue(p.id);val child=layouts.getValue(n.id)
                val right=n.x>=p.x;val sx=p.x.toFloat()+if(right)parent.width else 0f;val ex=n.x.toFloat()+if(right)0f else child.width;val sign=if(right)1 else -1
                val sy=p.y.toFloat()+parent.height/2;val ey=n.y.toFloat()+child.height/2
                c.drawPath(Path().apply{moveTo(sx,sy);cubicTo(sx+28*sign,sy,ex-28*sign,ey,ex,ey)},paint)
            }}
        }
        val ordered=if(viewStyle!=null)nodes.sortedBy{it.id==selected}else nodes
        ordered.forEach{n->
            val layout=layouts.getValue(n.id);val left=n.x.toFloat();val top=n.y.toFloat()
            val structural=hierarchy&&n.cardId==null
            val root=hierarchy&&n.parentId !in lookup
            val highlight=viewStyle!=null&&n.id==selected
            paint.style=Paint.Style.FILL;paint.color=when{highlight->checkNotNull(viewStyle).selectionFill;structural&&root->0xffe9f2fb.toInt();else->Color.WHITE}
            c.drawRoundRect(left,top,left+layout.width,top+layout.height,12f,12f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=if(structural&&root)1.5f else 1f;paint.color=if(structural)0xff7c9dbd.toInt()else 0xffc6cdd6.toInt()
            c.drawRoundRect(left,top,left+layout.width,top+layout.height,12f,12f,paint)
            if(highlight){paint.color=checkNotNull(viewStyle).selection;paint.strokeWidth=2f;c.drawRoundRect(left-4,top-4,left+layout.width+4,top+layout.height+4,16f,16f,paint)}
            paint.style=Paint.Style.FILL
            if(detail){
                fun text(value:StaticLayout,y:Float,color:Int){val saved=c.save();value.paint.color=color;c.translate(left+14,top+y);value.draw(c);c.restoreToCount(saved)}
                text(layout.title,layout.titleTop,if(structural)0xff176bb5.toInt()else 0xff20242d.toInt())
                layout.summary?.let{text(it,layout.summaryTop,0xff3d4858.toInt())}
                layout.preview?.let{slot->
                    val frame=previews[n.cardId].takeIf{viewStyle!=null}
                    val target=RectF(slot).apply{offset(left,top)}
                    paint.color=0xfff5f6f8.toInt();c.drawRoundRect(target,6f,6f,paint)
                    val bitmap=frame?.bitmap?.takeUnless{it.isRecycled}
                    if(bitmap!=null){
                        val scale=min(target.width()/bitmap.width,target.height()/bitmap.height)
                        val width=bitmap.width*scale;val height=bitmap.height*scale
                        c.drawBitmap(bitmap,null,RectF(target.centerX()-width/2,target.centerY()-height/2,target.centerX()+width/2,target.centerY()+height/2),paint)
                    }else{
                        val label=when{frame==null->"放大或选中查看原貌";frame.unavailable->"原貌暂不可用 · 展开重试";else->"正在读取原貌…"}
                        val placeholder=MapNodeMetrics.textLayout(label,11f,fontScale,2,width=(target.width()-16).toInt())
                        val saved=c.save();c.clipRect(target);c.translate(target.left+8,target.centerY()-placeholder.height/2)
                        placeholder.paint.color=0xff626b79.toInt();placeholder.draw(c);c.restoreToCount(saved)
                    }
                }
                layout.source?.let{text(it,layout.sourceTop,0xff626b79.toInt())}
            }else{
                paint.color=0xff8aa4bd.toInt();c.drawRoundRect(left+14,top+22,left+min(170f,layout.width-14),top+29,3f,3f,paint)
                if(layout.summary!=null||layout.preview!=null){paint.color=0xffd9e2eb.toInt();c.drawRoundRect(left+14,top+42,left+layout.width-28,top+48,3f,3f,paint)}
            }
        }
    }
    fun embed(c:Canvas,scene:MapScene?,o:PageObject){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val save=c.save();c.clipRect(o.x,o.y,o.x+o.width,o.y+o.height);paint.color=Color.WHITE;c.drawRect(o.x,o.y,o.x+o.width,o.y+o.height,paint)
        if(scene==null||!scene.available||scene.nodes.isEmpty()){
            paint.color=0xff626d7e.toInt();paint.textSize=18f
            c.drawText(if(scene==null)"正在读取导图…"else if(!scene.available)"源图或分支不可用，可恢复后继续"else"空导图 · 选择后编辑",o.x+12,o.y+32,paint)
        }else{
            val nodes=scene.nodes;val layouts=nodes.associate{it.id to MapNodeMetrics.measure(it.title,it.body,structural=it.cardId==null)}
            val left=nodes.minOf{it.x};val top=nodes.minOf{it.y};val right=nodes.maxOf{it.x+layouts.getValue(it.id).width};val bottom=nodes.maxOf{it.y+layouts.getValue(it.id).height}
            val scale=min((o.width-24)/(right-left).toFloat(),(o.height-40)/(bottom-top).toFloat()).coerceAtLeast(.01f)
            c.translate(o.x+o.width/2-((left+right)/2*scale).toFloat(),o.y+12+(o.height-40)/2-((top+bottom)/2*scale).toFloat());c.scale(scale,scale);draw(c,nodes,detail=scale>=.3f)
        }
        c.restoreToCount(save);paint.color=0xff626d7e.toInt();paint.textSize=12f
        c.drawText(if(o.mapEmbed?.policy==MapEmbedPolicy.PINNED)"固定快照"else"实时导图 · 选择后编辑",o.x+10,o.y+o.height-10,paint)
    }
}
