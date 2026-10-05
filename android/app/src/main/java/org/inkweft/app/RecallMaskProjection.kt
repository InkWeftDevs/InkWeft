// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*

/** The source child is clipped before drawing; the mask is not merely a translucent cover over answer pixels. */
internal class RecallMaskedSourceView(context:Context):FrameLayout(context){
    private val source=InkCanvasView(context).apply{allowInput=false;fingerWrites=false;preview=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
    private var file:InkPageFile?=null;private var basis:CanvasBounds?=null
    private var hidden=emptyList<Pair<Int,RecallRegion>>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xffdce7e1.toInt()}
    var onReveal:(Int)->Unit={};var interactionsEnabled=true
    private var pressed:Int?=null;private var downX=0f;private var downY=0f
    init{
        addView(source,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));isClickable=true
        source.onViewport={invalidate()};importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->fit()}
    }
    fun show(value:InkPageFile,anchor:StudySourceRevisionRow,masks:List<Pair<Int,RecallRegion>>){
        val changed=file!==value;file=value;hidden=masks
        val preview=value.objects.singleOrNull()?.takeIf{it.kind==PageObjectKind.IMAGE&&value.strokes.isEmpty()}
        basis=preview?.bounds()?:CanvasBounds(anchor.left,anchor.top,anchor.right,anchor.bottom)
        source.configure(value.world,PaperStyle.BLANK,null)
        source.showAuthoring(value.authoring)
        source.showImageSources(value.imageSources);source.showObjects(value.objects.filter{it.mapEmbed==null});source.showStrokes(value.strokes)
        contentDescription="固定来源原位投影，${masks.size} 处仍被遮挡；可用下方按钮逐片揭示"
        if(changed)post{fit()};invalidate()
    }
    // InkCanvasView renders original paper coordinates; expanded whitespace is a separate document surface.
    private fun display(point:CanvasPoint):CanvasPoint=point
    private fun fit(){val b=basis?:return;if(width<=0||height<=0)return;val a=display(CanvasPoint(b.left,b.top));val z=display(CanvasPoint(b.right,b.bottom));source.fixedRegion(CanvasBounds(a.x,a.y,z.x,z.y));invalidate()}
    private fun screen(region:RecallRegion):RectF{
        val b=requireNotNull(basis);val a=display(CanvasPoint(b.left+(b.right-b.left)*region.left,b.top+(b.bottom-b.top)*region.top))
        val z=display(CanvasPoint(b.left+(b.right-b.left)*region.right,b.top+(b.bottom-b.top)*region.bottom))
        val viewport=source.snapshotViewport();val density=resources.displayMetrics.density.toDouble()
        val p=viewport.worldToScreen(a.x,a.y,width.toDouble(),height.toDouble(),density);val q=viewport.worldToScreen(z.x,z.y,width.toDouble(),height.toDouble(),density)
        val pad=(density*2).toFloat();return RectF(p.x.toFloat()-pad,p.y.toFloat()-pad,q.x.toFloat()+pad,q.y.toFloat()+pad)
    }
    override fun dispatchDraw(canvas:Canvas){
        val regions=if(basis==null)emptyList()else hidden.map{it.first to screen(it.second)}
        val save=canvas.save();regions.forEach{canvas.clipOutRect(it.second)};super.dispatchDraw(canvas);canvas.restoreToCount(save)
        for((_,rect)in regions)canvas.drawRect(rect,paint)
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_DOWN&&interactionsEnabled&&basis!=null){pressed=hidden.firstOrNull{screen(it.second).contains(event.x,event.y)}?.first;downX=event.x;downY=event.y}
        return pressed!=null
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        val id=pressed?:return super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP){pressed=null;performClick();if(interactionsEnabled&&kotlin.math.abs(event.x-downX)<24*resources.displayMetrics.density&&kotlin.math.abs(event.y-downY)<24*resources.displayMetrics.density)onReveal(id)}
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)pressed=null
        return true
    }
    override fun performClick():Boolean=super.performClick()
    fun clear(){pressed=null;hidden=emptyList();file=null;basis=null;source.showDocument(null);source.showObjects(emptyList());source.showStrokes(emptyList());source.showAuthoring(null);invalidate()}
}

/** Original card text stays in place; only declared cloze ranges can reach the pre-answer text tree. */
@Composable internal fun RecallQuestionProjection(current:RecallLoadedAttempt,enabled:Boolean,onReveal:(Int)->Unit){
    val row=current.row;val spec=current.spec;val revealed=RecallCodec.revealed(row.revealedMasks)
    when(spec.kind){
        RecallQuestionKind.QUESTION->if(row.answerRevealed)Text(current.card.body.ifBlank{"此题以保存的原迹来源为答案"},modifier=Modifier.testTag("recall-fixed-answer"))else Text("请先作答，再锁定作答并对照固定答案",modifier=Modifier.testTag("recall-question-neutral"))
        RecallQuestionKind.TEXT_CLOZE->{
            Text(if(row.answerRevealed)current.card.body else spec.maskedText(current.card.body,revealed),modifier=Modifier.testTag("recall-inline-cloze"))
            if(!row.answerRevealed)spec.clozes.indices.filter{it !in revealed}.forEach{i->TextButton({onReveal(i)},enabled=enabled,modifier=Modifier.testTag("recall-reveal-cloze-$i")){Text("揭开空位 ${i+1}（计为提示）")}}
        }
        RecallQuestionKind.SOURCE_MASK->RecallSourceProjection(current,enabled,onReveal)
    }
}
@Composable private fun RecallSourceProjection(current:RecallLoadedAttempt,enabled:Boolean,onReveal:(Int)->Unit){
    val refs=current.spec.regions.map{it.source}.distinct();val revealed=RecallCodec.revealed(current.row.revealedMasks)
    var selected by rememberSaveable(current.row.id){mutableIntStateOf(if(refs.size==1)0 else -1)}
    if(!current.sources.complete){Text("固定来源版本不可恢复，保持遮挡；请返回题型设置核对",color=MaterialTheme.colorScheme.error);return}
    if(refs.size>1)Column{Text("选择要查看的固定来源")
        refs.indices.forEach{i->TextButton({selected=i},enabled=enabled){Text("来源 ${i+1}${if(selected==i)" · 已选"else""}")}}
    }
    val ref=refs.getOrNull(selected)?:return
    val source=current.sources.sources.singleOrNull{it.ref()==ref}
    if(source==null){Text("此固定来源不可用，未替换为其他来源");return}
    var file by remember(current.row.id,ref){mutableStateOf<InkPageFile?>(null)}
    var failed by remember(current.row.id,ref){mutableStateOf(false)}
    LaunchedEffect(current.row.id,ref){try{file=withContext(Dispatchers.IO){InkPageFile.decode(source.snapshot)}}catch(c:CancellationException){throw c}catch(_:Exception){failed=true}}
    val loaded=file
    if(loaded==null){Text(if(failed)"固定原迹读取失败，保持遮挡"else"读取固定原迹…");return}
    val masks=current.spec.regions.withIndex().filter{it.value.source==ref&&!current.row.answerRevealed&&it.index !in revealed}.map{it.index to it.value}
    key(current.row.id,ref){AndroidView(factory={RecallMaskedSourceView(it)},onRelease={it.clear()},update={view->view.interactionsEnabled=enabled;view.onReveal=onReveal;view.show(loaded,source,masks)},
        modifier=Modifier.fillMaxWidth().height(300.dp).testTag("recall-source-mask-projection"))}
    masks.forEach{(index,_)->TextButton({onReveal(index)},enabled=enabled,modifier=Modifier.testTag("recall-reveal-region-$index")){Text("揭开原迹区域 ${index+1}（计为提示）")}}
    if(current.row.answerRevealed)Text("已封存作答，当前为完整固定原迹对照；不增加提示惩罚")
    Text("固定原页坐标视图（不含展开留白）",style=MaterialTheme.typography.bodySmall)
}

/** A geometry-preserving map projection: neighbours never receive their real titles, bodies, thumbnails or ink. */
@Composable internal fun RecallMaskedMapProjection(plan:BranchReviewPlan,current:RecallLoadedAttempt){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var graph by remember(plan.ref){mutableStateOf<StudyGraphSnapshot?>(null)}
    var unavailable by remember(plan.ref){mutableStateOf(false)}
    LaunchedEffect(plan.ref){try{graph=withContext(Dispatchers.IO){app.study.readGraph(plan.ref.notebookId,plan.ref.mapId)}}catch(c:CancellationException){throw c}catch(_:Exception){unavailable=true}}
    val source=graph
    if(source==null){Text(if(unavailable)"原导图位置不可用，固定题目仍保留"else"读取原导图位置…");return}
    val occurrences=source.nodes.filter{!it.removed&&it.cardId==current.spec.cardId}
    if(occurrences.isEmpty()){Text("此题没有当前图中的出现位置，未替换为其他图；固定题面仍保留");return}
    var selected by rememberSaveable(current.row.id,plan.ref){mutableStateOf(occurrences.singleOrNull()?.id)}
    if(occurrences.size>1){Text("本题有多处展示，请选择位置")
        occurrences.forEachIndexed{i,node->TextButton({selected=node.id}){Text("出现位置 ${i+1}${if(selected==node.id)" · 已选"else""}")}}
    }
    val body=when{
        current.row.answerRevealed->current.card.body
        current.spec.kind==RecallQuestionKind.TEXT_CLOZE->current.spec.maskedText(current.card.body,RecallCodec.revealed(current.row.revealedMasks))
        else->"本题答案已遮住"
    }
    val cards=source.nodes.filterNot{it.removed}.map{it.cardId}.distinct().map{id->
        StudyCardRow(id,plan.ref.notebookId,1,if(id==current.spec.cardId)"本题 · 固定内容"else"相邻主题已遮住",if(id==current.spec.cardId)body else"")
    }
    Text("原导图位置 · 其他主题标题、正文、缩略图与批注保持遮挡",style=MaterialTheme.typography.bodySmall)
    key(current.row.id,plan.ref){AndroidView(factory={context->MindMapView(context).apply{authorEditing=false;contentDescription="复习原导图位置，其他主题内容已遮挡";addOnLayoutChangeListener{_,l,t,r,b,ol,ot,or,ob->if(r>l&&b>t&&(or==ol||ob==ot))fitOverview()}}},
        update={native->native.authorEditing=false;native.selectedNodeId=selected;native.expandedNodeId=selected;native.show(source.nodes,cards,structuralCardIds=source.state.structuralNodeIds)},
        modifier=Modifier.fillMaxWidth().height(260.dp).testTag("recall-masked-map"))}
}
