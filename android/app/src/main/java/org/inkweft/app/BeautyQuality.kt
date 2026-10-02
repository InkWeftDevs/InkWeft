// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.*
import kotlin.math.*

internal data class BeautyDecision(val automatic:Boolean,val reason:String?)

/** Model scores are uncalibrated signals, combined with independent layout and temporal evidence. */
internal object BeautyQuality {
    fun decide(strokes:List<InkStroke>,a:RecognizedWriting,b:RecognizedWriting,stable:Boolean):BeautyDecision {
        fun review(reason:String)=BeautyDecision(false,reason)
        if(!stable)return review("片段仍在变化，等待补写")
        if(a.text.isBlank())return review("没有可靠文字候选")
        if(!completeSources(strokes,a)||!completeSources(strokes,b))return review("候选未覆盖全部原迹，请校对")
        if(a.text!=b.text)return review("两次候选不同，请校对")
        if(Regex("[=+*/^_√∫∑<>≤≥²³]").containsMatchIn(a.text))return review("疑似公式，原迹保留")
        if(Regex("[A-Za-z][\\p{IsHan}]|[\\p{IsHan}][A-Za-z]").containsMatchIn(a.text)&&a.text.replace(" ","").length<=3)return review("短词中英混淆，请校对")
        val tokens=a.regions.flatMap{it.tokens}.ifEmpty{a.tokens}
        val variantTokens=b.regions.flatMap{it.tokens}.ifEmpty{b.tokens}
        if((tokens+variantTokens).any{!it.center.isFinite()||it.center !in 0f..1f})return review("候选超出实际输入范围，请校对")
        // Deliberately not exposed as accuracy; low-score and competing-token cases are reviewable.
        if(a.confidence<.60f||b.confidence<.60f||(tokens+variantTokens).any{it.score<.45f||it.score-it.alternativeScore<.12f})return review("候选含糊，请校对")
        if(strokes.isEmpty())return review("原迹不可用")
        val heights=strokes.map{(it.bounds().bottom-it.bounds().top).coerceAtLeast(1.0)}.sorted()
        val typical=heights[ceil(heights.lastIndex*.75).toInt()].coerceAtLeast(8.0)
        if(strokes.any{val v=it.bounds();v.right-v.left>typical*3&&v.bottom-v.top<typical*.22})return review("含长横线或分式结构，请选择文字范围")
        if(a.regions.any{r->r.bounds.bottom-r.bounds.top>typical*2.8})return review("分行位置不确定，请缩小范围")
        if(tokens.zipWithNext().any{(x,y)->x.center>=y.center}&&a.regions.size<=1)return review("文字位置不确定，请校对")
        return BeautyDecision(true,null)
    }
    fun completeSources(strokes:List<InkStroke>,result:RecognizedWriting):Boolean {
        val ids=result.regions.flatMap{it.strokeIds}
        return result.regions.isNotEmpty()&&result.lines==result.regions.size&&result.regions.all{it.text.isNotBlank()&&it.strokeIds.isNotEmpty()}&&
            ids.size==ids.distinct().size&&ids.toSet()==strokes.map{it.id}.toSet()
    }
    /** Compare source extent to natural type advance, never count strokes as characters. */
    fun layoutReason(candidate:PageObject,strokes:List<InkStroke>):String? {
        for(runs in candidate.textRuns.groupBy{it.sourceIds.toSet()}.values){
            val source=strokes.filter{it.id in runs.first().sourceIds}.map{it.bounds()}.reduceOrNull{a,b->a.union(b)}?:return "原迹映射不完整，请校对"
            val text=runs.joinToString(""){candidate.text.substring(it.start,it.end)}
            val paint=TextStyles.paint(candidate).apply{textSize=runs.first().size;isFakeBoldText=false}
            val box=android.graphics.Rect();paint.getTextBounds(text,0,text.length,box)
            // Normalize to the input's height even when paragraph size/wrapping was chosen
            // explicitly. Layout policy cannot turn a short candidate into full source coverage.
            val advance=paint.measureText(text)*(source.bottom-source.top)/box.height().coerceAtLeast(1)
            if(source.right-source.left>maxOf(advance,source.bottom-source.top)*1.8)
                return "候选跨度明显小于原迹，请校对"
        }
        return null
    }
    fun collision(candidate:PageObject,sources:List<InkStroke>,ink:List<InkStroke>,objects:List<PageObject>,replaced:String?):Boolean {
        val ids=sources.map{it.id}.toSet();val suppressed=objects.flatMap{it.sourceStrokeIds}.toSet()
        val boxes=candidate.glyphs.filterNot{it.hidden}.map{g->CanvasBounds((candidate.x+g.x).toDouble(),(candidate.y+g.y).toDouble(),(candidate.x+g.x+g.width).toDouble(),(candidate.y+g.y+g.height).toDouble())}
        return ink.any{it.id !in ids&&it.id !in suppressed&&boxes.any{b->b.intersects(it.bounds())}}||objects.any{!it.hidden&&it.id!=replaced&&it.kind!=PageObjectKind.IMAGE&&boxes.any{b->b.intersects(it.bounds())}}
    }
}

/** Replace one source fragment while preserving unrelated glyphs, masks, identities and positions. */
internal fun replaceBeautyFragment(previous:PageObject,next:PageObject,affected:Set<String>):PageObject?=runCatching{
    val runs=previous.textRuns.filter{it.id in affected};require(runs.isNotEmpty()&&next.textRuns.isNotEmpty())
    val start=runs.minOf{it.start};val end=runs.maxOf{it.end}
    require(previous.textRuns.filter{it.start<end&&it.end>start}.all{it.id in affected})
    require(previous.glyphs.none{it.hidden&&it.start<end&&it.end>start}&&previous.erasures.none{it.start<end&&it.end>start})
    require(previous.font==next.font&&previous.bold==next.bold)
    val delta=next.text.length-(end-start);val x=minOf(previous.x,next.x);val y=minOf(previous.y,next.y)
    val retained=previous.textRuns.filterNot{it.id in affected}.map{it.transformed(previous.x-x,previous.y-y,offset=if(it.start>=end)delta else 0)}
    val changed=next.textRuns.map{it.transformed(next.x-x,next.y-y,offset=start).copy(lineId=runs.first().lineId)}
    val glyphs=previous.glyphs.filter{it.end<=start||it.start>=end}.map{g->val shift=if(g.start>=end)delta else 0;g.copy(start=g.start+shift,end=g.end+shift,x=g.x+previous.x-x,y=g.y+previous.y-y)}+
        next.glyphs.map{it.copy(start=it.start+start,end=it.end+start,x=it.x+next.x-x,y=it.y+next.y-y)}
    previous.copy(x=x,y=y,width=maxOf(previous.x+previous.width,next.x+next.width)-x,height=maxOf(previous.y+previous.height,next.y+next.height)-y,
        text=previous.text.substring(0,start)+next.text+previous.text.substring(end),glyphs=glyphs.sortedBy{it.start},textRuns=(retained+changed).sortedBy{it.start},
        sourceStrokeIds=(retained+changed).flatMap{it.sourceIds}.distinct(),
        erasures=previous.erasures.map{it.transformed(previous.x-x,previous.y-y,offset=if(it.start>=end)delta else 0)})
}.getOrNull()
