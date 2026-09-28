package org.inkweft.app

import android.graphics.*
import org.inkweft.core.*

internal object ObjectGeometry {
    fun path(o:PageObject,inset:Float=0f):Path=Path().apply {
        val l=o.x+inset;val t=o.y+inset;val r=o.x+o.width-inset;val b=o.y+o.height-inset
        if(o.kind==PageObjectKind.TAPE&&o.tapePoints.isNotEmpty()){
            val center=Path().apply{o.tapePoints.forEachIndexed{i,p->if(i==0)moveTo(l+p.x,t+p.y)else lineTo(l+p.x,t+p.y)}}
            Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=o.lineWidth;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}.getFillPath(center,this)
            op(Path(),Path.Op.UNION)
        }else if(o.kind==PageObjectKind.SHAPE){
            when(o.shape){
                ObjectShape.RECTANGLE->addRect(l,t,r,b,Path.Direction.CW)
                ObjectShape.ELLIPSE->addOval(l,t,r,b,Path.Direction.CW)
                ObjectShape.TRIANGLE->{moveTo((l+r)/2,t);lineTo(r,b);lineTo(l,b);close()}
                ObjectShape.LINE->{moveTo(l,(t+b)/2);lineTo(r,(t+b)/2)}
                ObjectShape.ARROW->{moveTo(l,(t+b)/2);lineTo(r,(t+b)/2);moveTo(r-o.width*.25f,t+o.height*.22f);lineTo(r,(t+b)/2);lineTo(r-o.width*.25f,b-o.height*.22f)}
                ObjectShape.TABLE->{for(i in 0..3){moveTo(l+(r-l)*i/3,t);lineTo(l+(r-l)*i/3,b);moveTo(l,t+(b-t)*i/3);lineTo(r,t+(b-t)*i/3)}}
            }
        }else if(o.kind==PageObjectKind.TAPE)addRoundRect(l,t,r,b,4f,4f,Path.Direction.CW)else addRect(l,t,r,b,Path.Direction.CW)
    }
    fun intersectsTape(o:PageObject,samples:List<InkSample>,radius:Float):Boolean{
        if(o.kind!=PageObjectKind.TAPE||samples.isEmpty())return false
        val swept=VisibleInkGeometry.sweptPath(samples.map{EraserPoint(it.x,it.y)},radius)
        val overlap=path(o);overlap.op(swept,Path.Op.INTERSECT);return !overlap.isEmpty
    }
    fun hit(o:PageObject,x:Float,y:Float):Boolean {
        if(x<o.x||x>o.x+o.width||y<o.y||y>o.y+o.height)return false
        if(o.kind!=PageObjectKind.TAPE||o.tapePoints.isEmpty())return true
        val overlap=path(o);overlap.op(Path().apply{addCircle(x,y,1f,Path.Direction.CW)},Path.Op.INTERSECT)
        return !overlap.isEmpty
    }
}
