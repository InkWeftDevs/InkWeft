// Frozen v41 layout only for native before/after regression evidence.
package org.inkweft.app
import org.inkweft.core.*
import java.util.UUID
internal fun legacyBeautyObject(strokes:List<InkStroke>,text:String,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString()):PageObject {
    val lines=HandwritingLines.split(strokes)
    val texts=text.lines().filter{it.isNotBlank()}
    val regions=if(lines.size==texts.size)lines.zip(texts){line,value->RecognizedLine(value,line.bounds,line.strokes.map{it.id},emptyList())}else emptyList()
    return legacyBeautyObject(strokes,RecognizedWriting(text,1f,texts.size,regions),options,world,id)
}

internal fun legacyBeautyObject(strokes:List<InkStroke>,result:RecognizedWriting,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString()):PageObject {
    val sources=result.regions.takeIf{it.isNotEmpty()}?.flatMap{it.strokeIds}?.distinct()?:strokes.map{it.id}
    val text=result.regions.takeIf{it.isNotEmpty()}?.joinToString("\n"){it.text}?:result.text
    require(strokes.isNotEmpty()&&text.isNotBlank()&&text.length<=4000)
    val bounds=strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
    val x=if(world)bounds.left.toFloat()else bounds.left.toFloat().coerceIn(0f,976f)
    val y=if(world)bounds.top.toFloat()else bounds.top.toFloat().coerceIn(0f,1390f)
    val width=maxOf(24f,(bounds.right-x).toFloat()).coerceAtMost(if(world)4000f else 1000f-x)
    if(options.preserveLayout){
        val height=maxOf(24f,(bounds.bottom-y).toFloat()).coerceAtMost(if(world)4000f else 1414f-y)
        val regions=result.regions.ifEmpty{listOf(RecognizedLine(text,bounds,strokes.map{it.id},emptyList()))}
        val glyphs=mutableListOf<TextGlyph>();var offset=0
        for(line in regions){
            val writing=strokes.filter{it.id in line.strokeIds};if(writing.isEmpty()){offset+=line.text.length+1;continue}
            val b=writing.map{it.bounds()}.reduce{a,v->a.union(v)}
            val ranges=TextStyles.graphemes(line.text)
            val tokens=line.tokens.takeIf{it.joinToString(""){token->token.text}==line.text}
            val centers=ranges.map{range->
                var index=0
                val token=tokens?.firstOrNull{t->val contains=range.first in index until index+t.text.length;index+=t.text.length;contains}
                val fraction=token?.center?:((range.first+range.last+1f)/2/line.text.length)
                (line.bounds.left+fraction*(line.bounds.right-line.bounds.left)).coerceIn(b.left,b.right)
            }
            val stableCenters=if(centers.zipWithNext().all{(a,v)->v>a})centers else ranges.indices.map{b.left+(it+.5)*(b.right-b.left)/ranges.size}
            ranges.forEachIndexed{i,range->
                val left=if(i==0)b.left else (stableCenters[i-1]+stableCenters[i])/2
                val right=if(i==ranges.lastIndex)b.right else (stableCenters[i]+stableCenters[i+1])/2
                val pieces=writing.map{it.bounds()}.filter{it.right>left&&it.left<right}
                val ink=if(pieces.isEmpty())CanvasBounds(left,b.top,right,b.bottom)else pieces.reduce{a,v->a.union(v)}
                val gx=maxOf(left,ink.left,x.toDouble());val gy=maxOf(ink.top,y.toDouble())
                val gr=minOf(right,ink.right,(x+width).toDouble());val gb=minOf(ink.bottom,(y+height).toDouble())
                val pressure=writing.filter{it.bounds().right>left&&it.bounds().left<right}.flatMap{it.samples}.map{it.pressure}.filter{it>=0}.average().takeUnless{it.isNaN()}?.toFloat()?:.5f
                if(gr>gx&&gb>gy)glyphs.add(TextGlyph(offset+range.first,offset+range.last+1,(gx-x).toFloat(),(gy-y).toFloat(),(gr-gx).toFloat(),(gb-gy).toFloat(),weight=if(options.bold).25f+.75f*pressure else 0f).let{g->BeautyAppearance.apply(g,writing,CanvasBounds(gx,gy,gr,gb))})
            }
            offset+=line.text.length+1
        }
        require(glyphs.isNotEmpty())
        return PageObject(id,PageObjectKind.TEXT,x,y,width,height,text=text,color=strokes.first().color,fontSize=options.size,
            font=options.font,lineSpacing=options.spacing,bold=options.bold,sourceStrokeIds=sources,glyphs=glyphs)
    }
    val original=legacyBeautyObject(strokes,result,options.copy(preserveLayout=true),world,id)
    val o=PageObject(id,PageObjectKind.TEXT,x,y,width,24f,text=text,color=strokes.first().color,fontSize=options.size,
        font=options.font,lineSpacing=options.spacing,bold=options.bold,sourceStrokeIds=sources)
    val height=maxOf(24f,TextStyles.layout(o).height+4f).coerceAtMost(4000f)
    val arranged=TextStyles.positioned(o.copy(height=height)).associateBy{it.start}
    fun mix(a:Float,b:Float)=a+(b-a)*options.snap
    val glyphs=original.glyphs.map{g->arranged[g.start]?.let{target->g.copy(x=mix(g.x,target.x),y=mix(g.y,target.y),width=mix(g.width,target.width),height=mix(g.height,target.height))}?:g}
    val finalWidth=maxOf(24f,glyphs.maxOf{it.x+it.width});val finalHeight=maxOf(24f,glyphs.maxOf{it.y+it.height})
    require(world||x+finalWidth<=1000f&&y+finalHeight<=1414f)
    return o.copy(width=finalWidth,height=finalHeight,glyphs=glyphs)

}

