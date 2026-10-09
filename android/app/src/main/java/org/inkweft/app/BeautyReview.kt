// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.*
import org.inkweft.data.PageObjectRepository

internal data class BeautyReview(
    val strokes:List<InkStroke>,val result:RecognizedWriting,val options:BeautyOptions,val world:Boolean,
    val inkRevision:Long,val objectRevision:Long,val previous:PageObject?,val affected:Set<String>,val automatic:Boolean,
    val candidate:PageObject?,val reason:String?,val open:Boolean=false,val preview:Boolean=false,val trace:BeautyDiagnostics.Trace?=null
)

/** A touched neighbour is valid for moving, but must not become recognition source by accident. */
internal fun manualBeautySources(selection:SelectedInk,geometry:VisibleInkGeometry=VisibleInkGeometry())=
    selection.strokes.filter{it.pen!=InkPen.HIGHLIGHTER&&geometry.selects(selection.region,it,precise=true)}

internal fun RecognizedWriting.corrected(text:String):RecognizedWriting {
    val lines=text.lines()
    return copy(text=text,regions=if(lines.size==regions.size)regions.zip(lines){r,s->r.copy(text=s,tokens=emptyList(),score=-1f)}else emptyList(),tokens=emptyList(),confidence=-1f)
}

internal fun prepareBeautyReview(strokes:List<InkStroke>,result:RecognizedWriting,options:BeautyOptions,world:Boolean,
    inkRevision:Long,objectRevision:Long,previous:PageObject?,affected:Set<String>,automatic:Boolean,ink:List<InkStroke>,objects:List<PageObject>):BeautyReview {
    if(options.formula){
        val built=runCatching{FormulaLayout.build(strokes,result.text,options,world)}
        val candidate=built.getOrNull()
        val sourceIds=strokes.map{it.id}.toSet();val suppressed=objects.flatMap{it.sourceStrokeIds}.toSet()
        val collision=candidate?.let{c->ink.any{it.id !in sourceIds&&it.id !in suppressed&&it.bounds().intersects(c.bounds())}||objects.any{!it.hidden&&it.kind!=PageObjectKind.IMAGE&&it.bounds().intersects(c.bounds())}}==true
        val reason=if(built.isFailure)"公式格式或尺寸不适用，请修改候选或缩小字号" else if(collision)"公式与邻近内容相交，请缩小字号或保留原迹"else "请核对函数名、分子分母和上下标，再应用公式"
        return BeautyReview(strokes,result,options,world,inkRevision,objectRevision,null,emptySet(),automatic,candidate,reason)
    }
    var reason:String?=null
    val candidate=runCatching{
        val built=beautyObject(strokes,result,options,world,revision=inkRevision)
        val fresh=if(previous!=null&&affected.isEmpty())NaturalText.alignToLine(previous,built,strokes)else built
        val combined=when{previous==null->fresh;affected.isNotEmpty()->replaceBeautyFragment(previous,fresh,affected)
            ?:error("FRAGMENT_EDITED");else->appendBeauty(previous,fresh)?:fresh}
        PageObjectRepository.validateBounds(listOf(combined),world)
        reason=BeautyQuality.layoutReason(fresh,strokes)
        if(BeautyQuality.collision(combined,strokes,ink,objects,combined.id))reason="结果与邻近内容相交，请缩小字号或保留原迹"
        combined
    }.getOrElse{reason=when(it.message){"SOURCE_MAPPING_INCOMPLETE"->"候选未覆盖全部原迹，请补全校对文字";"FRAGMENT_EDITED"->"这段结果已有擦除或样式修改，请恢复原迹后重新选择";"LINE_MAPPING_CHANGED"->"分行已改变，请选择段落整理预览";else->"结果超出可用范围，请调整字号或保留原迹"};null}
    return BeautyReview(strokes,result,options,world,inkRevision,objectRevision,previous,affected,automatic,candidate,reason)
}

/** Preview is displayed in the same world coordinates as the eventual persisted object. */
internal fun beautyPreviewObjects(objects:List<PageObject>,review:BeautyReview?):List<PageObject>{
    val o=review?.takeIf{it.open&&it.preview}?.candidate?:return objects
    return objects.filterNot{it.id==o.id}+o
}
