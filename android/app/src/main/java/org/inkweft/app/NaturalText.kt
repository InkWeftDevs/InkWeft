// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.text.*
import android.text.style.CharacterStyle
import org.inkweft.core.*
import java.util.UUID
import kotlin.math.*

/** One shaping definition for automatic, selected, preview, canvas and export text. */
internal object NaturalText {
    fun layout(o:PageObject,r:TextRun,decorate:Boolean=true):StaticLayout {
        val paint=TextStyles.paint(o).apply{textSize=r.size;isFakeBoldText=false}
        val value=o.text.substring(r.start,r.end)
        val text=SpannableString(value)
        if(decorate)o.glyphs.filter{it.start>=r.start&&it.end<=r.end}.forEach{g->
            text.setSpan(object:CharacterStyle(){override fun updateDrawState(p:TextPaint){
                p.color=g.color?:o.color;p.shader=null;p.isFakeBoldText=o.bold&&g.weight==0f
                p.style=if(g.weight>0)Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                p.strokeWidth=r.size*.04f*g.weight;p.strokeJoin=Paint.Join.ROUND
                if(g.grain>0)p.shader=grain(g.color?:o.color,g.grain)
            }},g.start-r.start,g.end-r.start,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StaticLayout.Builder.obtain(text,0,text.length,paint,ceil(paint.measureText(value)+r.size*2).toInt().coerceAtLeast(1))
            .setIncludePad(false).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build()
    }
    private val grains=object:LinkedHashMap<Pair<Int,Float>,BitmapShader>(8,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<Pair<Int,Float>,BitmapShader>?)=size>16}
    @Synchronized private fun grain(color:Int,amount:Float):BitmapShader=grains.getOrPut(color to amount){
        val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val p=GraphiteMaterial.alpha().map{a->((((a.toInt()and 255)*(color ushr 24)/255)shl 24)or(color and 0xffffff))}.toIntArray()
        b.setPixels(p,0,64,0,0,64,64)
        BitmapShader(b,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply{setLocalMatrix(Matrix().apply{setScale(.5f*amount,.5f*amount)})}
    }
    fun glyphs(o:PageObject,r:TextRun,strokes:List<InkStroke>):List<TextGlyph> {
        val l=layout(o,r,false);val p=TextStyles.paint(o).apply{textSize=r.size;isFakeBoldText=false};val box=Rect()
        return TextStyles.graphemes(o.text.substring(r.start,r.end)).mapNotNull{range->
            val start=range.first+r.start;val end=range.last+1+r.start
            p.getTextBounds(o.text,start,end,box)
            val left=r.x+l.getPrimaryHorizontal(range.first)+box.left
            val top=r.baseline+box.top
            val bounds=strokes.map{it.bounds()}.reduceOrNull{a,b->a.union(b)}
            val fraction=(range.first+.5f)/valueLength(o,r)
            val center=bounds?.let{it.left+fraction*(it.right-it.left)}?:0.0
            val nearest=strokes.minByOrNull{val b=it.bounds();abs((b.left+b.right)/2-center)}
            val pressure=nearest?.samples?.map{if(it.pressure<0).5f else it.pressure}?.average()?.toFloat()?:.5f
            val weight=if(o.bold).25f+.75f*pressure else 0f;val pad=r.size*.02f*weight
            if(box.width()==0||box.height()==0)null else TextGlyph(start,end,(left-pad).coerceAtLeast(0f),(top-pad).coerceAtLeast(0f),box.width()+pad*2,box.height()+pad*2,weight=weight)
        }
    }
    private fun valueLength(o:PageObject,r:TextRun)=(r.end-r.start).coerceAtLeast(1)
    fun restyle(o:PageObject,font:TextFont,size:Float,spacing:Float,bold:Boolean,color:Int,world:Boolean):PageObject {
        require(o.textRuns.isNotEmpty()&&o.erasures.isEmpty()&&o.glyphs.none{it.hidden}){"先恢复原迹，再重新美化已擦除的文字"}
        val scale=size/o.fontSize
        val runs=o.textRuns.map{r->r.copy(size=r.size*scale,baseline=r.baseline*scale*(spacing/o.lineSpacing))}
        val base=o.copy(width=4000f,height=4000f,font=font,fontSize=size,lineSpacing=spacing,bold=bold,color=color,glyphs=emptyList(),textRuns=emptyList())
        val glyphs=runs.flatMap{r->glyphs(base,r,emptyList()).map{g->
            val old=o.glyphs.firstOrNull{it.start==g.start&&it.end==g.end}
            g.copy(weight=if(bold)old?.weight?.takeIf{it>0}?:.6f else 0f,color=if(color==o.color)old?.color else if(old?.grain?:0f>0)(old!!.color!! and 0xff000000.toInt())or(color and 0xffffff)else color,grain=old?.grain?:0f)
        }}
        val w=maxOf(24f,glyphs.maxOf{it.x+it.width}+1);val h=maxOf(24f,glyphs.maxOf{it.y+it.height}+1)
        require(world||o.x+w<=1000&&o.y+h<=1414){"新样式超出页面，请减小字号"}
        return base.copy(width=w,height=h,glyphs=glyphs,textRuns=runs)
    }
    fun draw(canvas:Canvas,o:PageObject,layouts:List<StaticLayout>,live:Path?,whole:Boolean){
        for((index,r) in o.textRuns.withIndex()){
            val save=canvas.save()
            o.glyphs.filter{(it.hidden||live!=null&&whole)&&it.start>=r.start&&it.end<=r.end}.forEach{g->
                val rect=Path().apply{addRect(o.x+g.x,o.y+g.y,o.x+g.x+g.width,o.y+g.y+g.height,Path.Direction.CW)}
                if(g.hidden)canvas.clipOutPath(rect)
                else if(live!=null&&whole){val hit=Path(rect);hit.op(live,Path.Op.INTERSECT);if(!hit.isEmpty)canvas.clipOutPath(rect)}
            }
            if(live!=null&&!whole)canvas.clipOutPath(Path(live).apply{op(scope(o,r,r.start,r.end),Path.Op.INTERSECT)})
            masks(o,r).forEach{canvas.clipOutPath(it)}
            val l=layouts[index];canvas.translate(o.x+r.x,o.y+r.baseline-l.getLineBaseline(0));l.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    fun masks(o:PageObject,r:TextRun):List<Path> = o.erasures.filter{it.start<r.end&&it.end>r.start}.map{cut->
        val path=VisibleInkGeometry.sweptPath(cut.points.map{EraserPoint(o.x+it.x,o.y+it.y)},cut.radius)
        path.apply{op(scope(o,r,cut.start,cut.end),Path.Op.INTERSECT)}
    }
    private fun scope(o:PageObject,r:TextRun,start:Int,end:Int)=Path().apply{
        o.glyphs.filter{it.start<end&&it.end>start&&it.start>=r.start&&it.end<=r.end}.forEach{g->addRect(o.x+g.x,o.y+g.y,o.x+g.x+g.width,o.y+g.y+g.height,Path.Direction.CW)}
    }
    /** Natural outline for hit testing. Glyph boxes only scope erasure; never rescale outlines. */
    fun path(o:PageObject):Path {
        val result=Path()
        for(r in o.textRuns){
            val p=TextStyles.paint(o).apply{textSize=r.size;isFakeBoldText=false};val value=o.text.substring(r.start,r.end)
            val outline=Path();p.getTextPath(value,0,value.length,o.x+r.x,o.y+r.baseline,outline)
            val painted=Path()
            o.glyphs.filter{!it.hidden&&it.start>=r.start&&it.end<=r.end}.forEach{g->
                val part=Path();p.style=if(g.weight>0)Paint.Style.FILL_AND_STROKE else Paint.Style.FILL;p.strokeWidth=r.size*.04f*g.weight;p.strokeJoin=Paint.Join.ROUND
                p.getFillPath(outline,part)
                part.op(Path().apply{addRect(o.x+g.x,o.y+g.y,o.x+g.x+g.width,o.y+g.y+g.height,Path.Direction.CW)},Path.Op.INTERSECT)
                painted.op(part,Path.Op.UNION)
            }
            masks(o,r).forEach{painted.op(it,Path.Op.DIFFERENCE)};result.op(painted,Path.Op.UNION)
        }
        return result
    }
    fun build(strokes:List<InkStroke>,result:RecognizedWriting,options:BeautyOptions,world:Boolean,id:String,revision:Long=-1):PageObject {
        require(strokes.isNotEmpty());val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
        require(!options.preserveLayout||result.regions.isNotEmpty()||!result.text.contains('\n')){"LINE_MAPPING_CHANGED"}
        val regions=result.regions.ifEmpty{listOf(RecognizedLine(result.text,bounds,strokes.map{it.id},emptyList()))}
        val text=regions.joinToString("\n"){it.text};require(text.isNotBlank()&&text.length<=4000)
        val x=bounds.left.toFloat();val y=bounds.top.toFloat();require(world||x>=0&&y>=0)
        val base=PageObject(id,PageObjectKind.TEXT,x,y,4000f,4000f,text=text,font=options.font,fontSize=options.size,lineSpacing=options.spacing,bold=options.bold,color=strokes.first().color,sourceStrokeIds=strokes.map{it.id})
        val runs=mutableListOf<TextRun>();val glyphs=mutableListOf<TextGlyph>();var offset=0;var paragraphY=0f
        for(line in regions){
            val writing=strokes.filter{it.id in line.strokeIds};if(writing.isEmpty()||line.text.isBlank()){offset+=line.text.length+1;continue}
            val b=writing.map{it.bounds()}.reduce{a,v->a.union(v)};val p=TextStyles.paint(base).apply{isFakeBoldText=false};val box=Rect()
            p.getTextBounds(line.text,0,line.text.length,box)
            val size=if(options.preserveLayout)(options.size*(b.bottom-b.top)/box.height().coerceAtLeast(1)).toFloat().coerceIn(12f,96f)else options.size
            p.textSize=size;p.getTextBounds(line.text,0,line.text.length,box)
            val rx=(b.left-x).toFloat()+max(0,-box.left).toFloat()+size*.02f
            val firstBaseline=if(options.preserveLayout)(b.top-y).toFloat()-box.top+size*.02f else paragraphY-box.top+size*.02f
            val width=if(world)(bounds.right-bounds.left).toFloat().coerceAtLeast(24f)else minOf((bounds.right-bounds.left).toFloat().coerceAtLeast(24f),1000f-x)
            val paragraph=if(options.preserveLayout)null else StaticLayout.Builder.obtain(line.text,0,line.text.length,p,width.toInt().coerceAtLeast(1)).setIncludePad(false).setLineSpacing(0f,options.spacing).build()
            val segments=paragraph?.let{l->(0 until l.lineCount).map{l.getLineStart(it) to l.getLineEnd(it)}}?:listOf(0 to line.text.length)
            val lineId=UUID.randomUUID().toString()
            segments.forEachIndexed{i,(start,end)->if(end>start){
                val baseline=firstBaseline+(paragraph?.let{it.getLineBaseline(i)-it.getLineBaseline(0)}?:0)
                val run=TextRun(UUID.randomUUID().toString(),lineId,offset+start,offset+end,rx,baseline,size,writing.map{it.id},revision,if(options.preserveLayout)TextLayoutPolicy.IN_PLACE else TextLayoutPolicy.PARAGRAPH,score=line.score.coerceIn(-1f,1f))
                runs.add(run)
                glyphs+=glyphs(base,run,writing).map{g->
                    // Appearance is inferred from sequence location, not claimed to be a detected character box.
                    val fraction=(g.start-offset+.5)/(line.text.length.coerceAtLeast(1))
                    val center=b.left+fraction*(b.right-b.left)
                    BeautyAppearance.apply(g,writing,CanvasBounds(center-1,b.top,center+1,b.bottom))
                }
            }}
            paragraphY=firstBaseline+(paragraph?.let{it.getLineBaseline(it.lineCount-1)-it.getLineBaseline(0)}?:0)+p.descent()+size*(options.spacing-1)
            offset+=line.text.length+1
        }
        require(glyphs.isNotEmpty())
        val w=maxOf(24f,glyphs.maxOf{it.x+it.width}+1f);val h=maxOf(24f,glyphs.maxOf{it.y+it.height}+1f)
        require(w<=4000f&&h<=4000f&&(world||x+w<=1000f&&y+h<=1414f)){"BEAUTY_LAYOUT_OUTSIDE_PAGE"}
        return base.copy(width=w,height=h,glyphs=glyphs,textRuns=runs)
    }
}
