// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import androidx.ink.geometry.MutableVec
import org.inkweft.core.*

/** Selection uses the painted outline after erasing, never the hidden author centerline. */
internal class VisibleInkGeometry {
    private val cache=object:LinkedHashMap<InkStroke,Path>(64,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<InkStroke,Path>?)=size>128
    }
    fun path(stroke:InkStroke):Path=cache[stroke]?:Path().also{result->
        val mesh=InkBrushes.stroke(stroke).shape;val point=MutableVec()
        for(group in 0 until mesh.getRenderGroupCount())for(outline in 0 until mesh.getOutlineCount(group)){
            val part=Path()
            for(vertex in 0 until mesh.getOutlineVertexCount(group,outline)){
                mesh.populateOutlinePosition(group,outline,vertex,point)
                if(vertex==0)part.moveTo(point.x,point.y)else part.lineTo(point.x,point.y)
            }
            part.close();result.op(part,Path.Op.UNION)
        }
        stroke.cuts.forEach{result.op(cutPath(it),Path.Op.DIFFERENCE)}
        if(!stroke.world)result.op(Path().apply{addRect(0f,0f,1000f,1414f,Path.Direction.CW)},Path.Op.INTERSECT)
        cache[stroke]=result
    }
    fun bounds(stroke:InkStroke)=bounds(path(stroke))
    fun hits(stroke:InkStroke,eraser:List<InkSample>,radius:Float):Boolean {
        if(!InkHitTest.hits(stroke,eraser,radius))return false
        val overlap=Path(path(stroke));overlap.op(sweptPath(eraser.map{EraserPoint(it.x,it.y)},radius),Path.Op.INTERSECT)
        return !overlap.isEmpty
    }
    fun selects(region:InkRegion,stroke:InkStroke,precise:Boolean=false)=stroke.bounds().intersects(region.bounds)&&overlaps(region,path(stroke),precise)
    fun selects(region:InkRegion,obj:PageObject,precise:Boolean=false):Boolean {
        if(obj.hidden||!obj.bounds().intersects(region.bounds))return false
        if(obj.textRuns.isNotEmpty())return overlaps(region,NaturalText.path(obj),precise)
        if(obj.glyphs.isEmpty()){
            val outline=ObjectGeometry.path(obj,if(obj.kind==PageObjectKind.SHAPE)obj.lineWidth/2 else 0f)
            val painted=if(obj.kind==PageObjectKind.SHAPE)Path().also{Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=obj.lineWidth;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}.getFillPath(outline,it)}else outline
            painted.op(Path().apply{addRect(obj.x,obj.y,obj.x+obj.width,obj.y+obj.height,Path.Direction.CW)},Path.Op.INTERSECT)
            return overlaps(region,painted,precise)
        }
        val result=Path();val paint=TextStyles.paint(obj);val box=Rect()
        obj.glyphs.filterNot{it.hidden}.forEach{g->
            paint.style=if(g.weight>0)Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.isFakeBoldText=obj.bold&&g.weight==0f;paint.strokeWidth=obj.fontSize*.04f*g.weight;paint.strokeJoin=Paint.Join.ROUND
            val text=obj.text.substring(g.start,g.end);paint.getTextBounds(text,0,text.length,box)
            if(box.width()>0&&box.height()>0){
                val outline=Path();paint.getTextPath(text,0,text.length,0f,0f,outline)
                val glyph=Path();paint.getFillPath(outline,glyph)
                val pad=if(g.weight>0)paint.strokeWidth/2 else 0f
                val sx=g.width/(box.width()+2*pad);val sy=g.height/(box.height()+2*pad)
                glyph.transform(Matrix().apply{setScale(sx,sy);postTranslate(obj.x+g.x+(-box.left+pad)*sx,obj.y+g.y+(-box.top+pad)*sy)})
                obj.erasures.filter{it.start<g.end&&it.end>g.start}.forEach{cut->
                    glyph.op(sweptPath(cut.points.map{EraserPoint(obj.x+it.x,obj.y+it.y)},cut.radius),Path.Op.DIFFERENCE)
                }
                result.op(glyph,Path.Op.UNION)
            }
        }
        result.op(Path().apply{addRect(obj.x,obj.y,obj.x+obj.width,obj.y+obj.height,Path.Direction.CW)},Path.Op.INTERSECT)
        return overlaps(region,result,precise)
    }
    private fun overlaps(region:InkRegion,path:Path,precise:Boolean=false):Boolean {
        if(bounds(path)==null)return false
        val inside=Path(path);inside.op(cutPath(region.mask()),if(precise)Path.Op.DIFFERENCE else Path.Op.INTERSECT)
        return if(precise)inside.isEmpty else !inside.isEmpty
    }
    companion object {
        private fun bounds(path:Path):CanvasBounds? {
            if(path.isEmpty)return null
            val box=RectF();path.computeBounds(box,true)
            return if(box.isEmpty)null else CanvasBounds(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble())
        }
        fun cutPath(cut:InkCut):Path=when(cut.shape){
            InkCutShape.ROUND->sweptPath(cut.points,cut.radius)
            InkCutShape.RECTANGLE->Path().apply{addRect(cut.points[0].x,cut.points[0].y,cut.points[1].x,cut.points[1].y,Path.Direction.CW)}
            InkCutShape.POLYGON->Path().apply{cut.points.forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)};close()}
        }
        fun sweptPath(points:List<EraserPoint>,radius:Float):Path {
            val result=Path();if(points.isEmpty())return result
            if(points.size==1){result.addCircle(points[0].x,points[0].y,radius,Path.Direction.CW);return result}
            val center=Path().apply{points.forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)}}
            Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=radius*2;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}.getFillPath(center,result)
            return result
        }
    }
}
