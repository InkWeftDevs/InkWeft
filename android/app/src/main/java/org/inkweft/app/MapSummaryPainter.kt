// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import androidx.core.graphics.withTranslation
import org.inkweft.core.*

internal data class MapSummaryVisual(val bracket:RectF,val label:RectF,val text:android.text.StaticLayout,val left:Boolean,val horizontal:Boolean){
    val bounds get()=RectF(bracket).apply{union(label)}
}

/** Geometry follows exact visible occurrences and their subtrees, including drag previews. */
internal object MapSummaryPainter {
    fun measure(nodes:List<MapSceneNode>,layouts:Map<String,MapNodeLayout>,groups:List<KnowledgeData.MapSummaryGroup>,fontScale:Float=1f,layout:String="right"):List<MapSummaryVisual>{
        val byId=nodes.associateBy{it.id};val children=nodes.groupBy{it.parentId}
        return groups.mapNotNull{group->
            if(!group.memberIds.all{it in byId})return@mapNotNull null
            val ids=linkedSetOf<String>();val pending=java.util.ArrayDeque<String>();group.memberIds.forEach{pending.add(it)}
            while(pending.isNotEmpty()){val id=pending.removeFirst();if(ids.add(id))children[id].orEmpty().forEach{pending.add(it.id)}}
            val boxes=ids.map{id->val n=byId.getValue(id);val size=layouts.getValue(id);RectF(n.x.toFloat(),n.y.toFloat(),n.x.toFloat()+size.width,n.y.toFloat()+size.height)}
            val box=RectF(boxes.first());boxes.drop(1).forEach(box::union)
            val first=byId.getValue(group.memberIds.first());val parent=byId[first.parentId]?:return@mapNotNull null
            val parentSize=layouts.getValue(parent.id);val firstSize=layouts.getValue(first.id)
            val horizontal=layout=="organization"
            val left=!horizontal&&first.x+firstSize.width/2<parent.x+parentSize.width/2
            val text=MapNodeMetrics.textLayout(group.label.replace('\n',' '),14f,fontScale,3,true,width=204)
            val bracket=if(horizontal)RectF(box.left,box.bottom+16,box.right,box.bottom+28)
                else if(left)RectF(box.left-28,box.top,box.left-16,box.bottom)else RectF(box.right+16,box.top,box.right+28,box.bottom)
            val label=if(horizontal)RectF(box.centerX()-110,bracket.bottom+12,box.centerX()+110,bracket.bottom+28+text.height)
                else if(left)RectF(bracket.left-232,box.centerY()-text.height/2-8,bracket.left-12,box.centerY()+text.height/2+8)
                else RectF(bracket.right+12,box.centerY()-text.height/2-8,bracket.right+232,box.centerY()+text.height/2+8)
            MapSummaryVisual(bracket,label,text,left,horizontal)
        }
    }
    fun draw(canvas:Canvas,visuals:List<MapSummaryVisual>){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xff176bb5.toInt();style=Paint.Style.STROKE;strokeWidth=1.8f}
        for(v in visuals){
            val b=v.bracket
            val path=Path().apply{if(v.horizontal){moveTo(b.left,b.top);lineTo(b.left,b.bottom);lineTo(b.right,b.bottom);lineTo(b.right,b.top)}
                else if(v.left){moveTo(b.right,b.top);lineTo(b.left,b.top);lineTo(b.left,b.bottom);lineTo(b.right,b.bottom)}
                else{moveTo(b.left,b.top);lineTo(b.right,b.top);lineTo(b.right,b.bottom);lineTo(b.left,b.bottom)}}
            canvas.drawPath(path,paint)
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawRoundRect(v.label,8f,8f,paint)
            canvas.withTranslation(v.label.left+8,v.label.top+8){v.text.paint.color=0xff176bb5.toInt();v.text.draw(this)}
            paint.style=Paint.Style.STROKE;paint.color=0xff176bb5.toInt()
        }
    }
}
