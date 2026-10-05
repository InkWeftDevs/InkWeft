// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*

internal enum class RecallContextTab(val label:String,val tag:String) {
    SOURCE("原页","source"), MAP("导图","map"), EXCERPT("摘录","excerpt")
}

private const val RecallPlaceholderColor:Int = 0xfff4f7f5.toInt()

/** Temporary read-only projections. Clue permission belongs to the fixed-question parent. */
@Composable internal fun RecallContextPanel(
    plan:BranchReviewPlan,
    current:FrozenBranchReviewQuestion,
    cluesVisible:Boolean,
    source:StudySourceRow?,
    tab:RecallContextTab,
    onTabChange:(RecallContextTab)->Unit,
    modifier:Modifier=Modifier,
) {
    key(plan.ref,plan.branchId,current.reference.questionId,current.reference.questionRevision) {
        Surface(modifier.testTag("recall-context")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    RecallContextTab.entries.forEach { item ->
                        FilterChip(tab==item,{onTabChange(item)},label={Text(item.label)},
                            modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-tab-${item.tag}"))
                    }
                }
                // A permission change replaces the complete subtree, including native caches.
                key(tab,cluesVisible) {
                    when(tab) {
                        RecallContextTab.SOURCE -> RecallSourceContext(plan,current,source,cluesVisible)
                        RecallContextTab.MAP -> RecallMapContext(plan,current,cluesVisible)
                        RecallContextTab.EXCERPT -> RecallExcerptContext(plan,current,source,cluesVisible)
                    }
                }
            }
        }
    }
}

/** No source canvas, document renderer, object layer or snapshot decoder runs in this branch. */
@Composable private fun RecallPlaceholder(tag:String,message:String,hidden:Boolean=false) {
    AndroidView(factory={context->TextView(context).apply {
        val density=resources.displayMetrics.density
        this.tag=tag
        background=GradientDrawable().apply {
            setColor(RecallPlaceholderColor);cornerRadius=12*density
            setStroke(density.toInt().coerceAtLeast(1),0xffdce4df.toInt())
        }
        gravity=Gravity.CENTER;textSize=14f;setTextColor(0xff63736b.toInt())
        val padding=(16*density).toInt();setPadding(padding,padding,padding,padding)
        if(hidden){
            compoundDrawablePadding=(8*density).toInt()
            setCompoundDrawables(null,context.getDrawable(android.R.drawable.ic_lock_lock)?.mutate()?.apply {
                setTint(0xff63736b.toInt());val side=(20*density).toInt();setBounds(0,0,side,side)
            },null,null)
        }
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }},update={native->
        native.text=message
        native.contentDescription=if(hidden)"$message；本题线索已遮住的中性占位" else message
    },modifier=Modifier.fillMaxWidth().height(160.dp).testTag(tag))
}

private data class RecallSourcePage(val title:String,val page:NotebookPageRow,val strokes:List<InkStroke>,val objects:List<PageObject>,val authoring:PageAuthoring)
private class RecallSourceChanged:Exception()
private class RecallSourceUnavailable:Exception()
private class RecallMapUnrelated:Exception()

private fun sameSource(a:StudySourceRow,b:StudySourceRow):Boolean =
    a.cardId==b.cardId&&a.pageId==b.pageId&&a.inkRevision==b.inkRevision&&
        a.left==b.left&&a.top==b.top&&a.right==b.right&&a.bottom==b.bottom&&
        a.strokeIds==b.strokeIds&&a.snapshot.contentEquals(b.snapshot)

/** The selected source must belong to this exact fixed card revision, not today's crop. */
private fun checkedSource(plan:BranchReviewPlan,current:FrozenBranchReviewQuestion,source:StudySourceRow):StudySourceRow {
    if(source.cardId!=current.reference.cardId||current.question.notebookId!=plan.ref.notebookId||!current.sources.complete)throw RecallSourceUnavailable()
    return current.sources.sources.firstOrNull{frozen->frozen.notebookId==plan.ref.notebookId&&sameSource(frozen.legacy(current.reference.cardId),source)}
        ?.legacy(current.reference.cardId)?:throw RecallSourceUnavailable()
}

private fun sourceFailure(error:Exception):String = when(error) {
    is RecallSourceChanged -> "来源已变化，本题仍保留固定答案；此处暂不显示来源。"
    is RecallSourceUnavailable -> "来源已回收或不可用，本题固定问答仍保留。"
    else -> "来源读取失败，请重试；本题固定问答仍保留。"
}

@Composable private fun RecallSourceContext(plan:BranchReviewPlan,current:FrozenBranchReviewQuestion,
    source:StudySourceRow?,cluesVisible:Boolean) {
    if(!cluesVisible) {
        RecallPlaceholder("recall-context-source-placeholder","原页已隐藏",hidden=true)
        return
    }
    if(source==null) {
        RecallPlaceholder("recall-context-source-placeholder","此卡片没有可用的来源页。")
        return
    }
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var page by remember { mutableStateOf<RecallSourcePage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<InkCanvasView?>(null) }
    LaunchedEffect(source,attempt) {
        page=null;error=null
        try {
            val value=withContext(Dispatchers.IO) {
                val fresh=checkedSource(plan,current,source)
                val owned=app.pages.activePages(plan.ref.notebookId).find{it.id==fresh.pageId}
                    ?:throw RecallSourceUnavailable()
                val (note,_)=app.knowledge.resolve(TargetRef(TargetKind.PAGE,owned.id))
                if(note.id!=plan.ref.notebookId)throw RecallSourceUnavailable()
                val ink=app.inkRepository.read(owned.id)
                if(ink.revision!=fresh.inkRevision)throw RecallSourceChanged()
                // Do not load another notebook through a live page-map object.
                val objects=app.pageObjects.read(owned.id).objects.filter{
                    it.mapEmbed?.target?.notebookId?.let{book->book==plan.ref.notebookId}!=false
                }
                RecallSourcePage(note.title,owned,InkSession(ink).visibleDraft(),objects,app.authoring.readPage(owned.id).state)
            }
            page=value
        } catch(c:CancellationException) { throw c }
        catch(e:Exception) { error=sourceFailure(e) }
    }
    LaunchedEffect(page,view) {
        if(page!=null)view?.post { view?.focusRegion(CanvasBounds(source.left,source.top,source.right,source.bottom)) }
    }
    val loaded=page
    if(loaded==null||error!=null) {
        RecallPlaceholder("recall-context-source-placeholder",error?:"正在读取本题来源…")
        error?.let {
            Text(it,modifier=Modifier.testTag("recall-context-source-error"))
            TextButton({attempt++},modifier=Modifier.testTag("recall-context-source-retry")){Text("重试来源")}
        }
        return
    }
    Text("${loaded.title} · 第 ${loaded.page.position+1} 页 · 原页视图（不含展开留白），只读",
        maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("recall-context-source-title"))
    AndroidView(factory={context->InkCanvasView(context).also { native->
        native.allowInput=false;native.fingerWrites=false
        native.contentDescription="本题当前来源页，只读；已许可查看提示，可能包含答案，可平移与缩放。"
        view=native
    }},onRelease={native->
        native.showDocument(null);native.showObjects(emptyList());native.showStrokes(emptyList())
        native.onNotice={};view=null
    },update={native->
        native.allowInput=false;native.fingerWrites=false
        native.onNotice={error="来源呈现失败，本题固定问答仍保留。"}
        native.configure(loaded.page.world,PaperStyle.entries[loaded.page.paper],null)
        native.showAuthoring(loaded.authoring);native.showDocument(loaded.page.id);native.showObjects(loaded.objects);native.showStrokes(loaded.strokes)
    },modifier=Modifier.fillMaxWidth().height(280.dp).testTag("recall-context-source-canvas"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton({view?.zoomBy(1/1.2)},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-source-zoom-out")){Text("缩小")}
        TextButton({view?.zoomBy(1.2)},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-source-zoom-in")){Text("放大")}
        TextButton({view?.fitContent()},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-source-fit")){Text("全部内容")}
    }
}

@Composable private fun RecallMapContext(plan:BranchReviewPlan,current:FrozenBranchReviewQuestion,cluesVisible:Boolean) {
    if(!cluesVisible) {
        RecallPlaceholder("recall-context-map-placeholder","导图已隐藏",hidden=true)
        return
    }
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var scene by remember { mutableStateOf<MapScene?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var collapsed by rememberSaveable(current.reference.questionId) { mutableStateOf(emptyList<String>()) }
    var view by remember { mutableStateOf<MindMapView?>(null) }
    LaunchedEffect(plan.ref,plan.branchId,attempt) {
        scene=null;error=null
        try {
            val value=withContext(Dispatchers.IO) {
                val selected=app.mapGraphs.read(plan.ref.notebookId).find{it.ref==plan.ref}
                    ?.branch(plan.branchId)?:throw RecallSourceUnavailable()
                if(!selected.available)throw RecallSourceUnavailable()
                if(selected.nodes.none{it.cardId==current.reference.cardId})throw RecallMapUnrelated()
                selected
            }
            scene=value
        } catch(c:CancellationException) { throw c }
        catch(e:Exception) { error=if(e is RecallMapUnrelated)"本题不在此图，未切换到其它图。" else "本轮导图或分支不可用，未切换到另一张图。" }
    }
    val loaded=scene
    if(loaded==null) {
        Text(error?:"正在读取本轮导图结构…",modifier=Modifier.testTag("recall-context-map-error"))
        if(error!=null)TextButton({attempt++},modifier=Modifier.testTag("recall-context-map-retry")){Text("重试本轮导图")}
        return
    }
    // One display card per node avoids sharing title caches between distinct occurrences.
    val ids=loaded.nodes.map{it.id}.toSet()
    val nodes=loaded.nodes.map{node->StudyNodeRow(node.id,plan.ref.notebookId,node.id,
        node.parentId?.takeIf{it in ids},node.x,node.y,node.revision)}
    val cards=loaded.nodes.map{node->StudyCardRow(node.id,plan.ref.notebookId,node.contentRevision,node.title,node.body)}
    val projection=StudyOutline.project(nodes.map{it.model()},collapsed.toSet())
    val shown=projection.rows.map{it.node.id}.toSet()
    val hiddenCounts=projection.rows.filter{it.node.id in collapsed}.associate{it.node.id to it.descendants}
    fun toggle(id:String) { collapsed=if(id in collapsed)collapsed-id else collapsed+id }
    Text("当前导图 · 只读 · ${loaded.nodes.size} 个主题")
    if(nodes.isEmpty())Text("本轮导图没有可显示的主题。")
    AndroidView(factory={context->MindMapView(context).also { native->
        native.authorEditing=false
        native.contentDescription="本轮导图，只读；已许可查看提示，主题与结构可能包含答案。"
        view=native
    }},onRelease={native->
        native.show(emptyList(),emptyList());native.onToggleBranch={};native.onSelect=null
        native.onOpen={};native.onOpenDetails={};native.onEditTitle={};view=null
    },update={native->
        native.enabledInput=true;native.authorEditing=false;native.selectedNodeId=null
        native.onSelect={};native.onOpen={};native.onOpenDetails={};native.onEditTitle={}
        native.branchIds=nodes.mapNotNull{it.parentId}.toSet();native.onToggleBranch=::toggle
        native.show(nodes.filter{it.id in shown},cards,hiddenCounts)
    },modifier=Modifier.fillMaxWidth().height(280.dp).testTag("recall-context-map"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton({view?.zoom(1/1.2f)},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-map-zoom-out")){Text("缩小")}
        TextButton({view?.zoom(1.2f)},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-map-zoom-in")){Text("放大")}
        TextButton({view?.fit()},modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-map-fit")){Text("适配")}
    }
    val byId=loaded.nodes.associateBy{it.id}
    Column(Modifier.fillMaxWidth().testTag("recall-context-map-outline")) {
        Text("当前导图大纲",style=MaterialTheme.typography.titleSmall)
        projection.rows.forEach { row ->
            Column(Modifier.fillMaxWidth().padding(start=(row.depth.coerceAtMost(10)*12).dp)) {
                Text(byId.getValue(row.node.id).title,modifier=Modifier.testTag("recall-context-map-node-${row.node.id}"))
                if(row.descendants>0)TextButton({toggle(row.node.id)},
                    modifier=Modifier.heightIn(min=48.dp).testTag("recall-context-map-fold-${row.node.id}")) {
                    Text(if(row.node.id in collapsed)"展开 ${row.descendants}"else"收起下级")
                }
            }
        }
    }
}

@Composable private fun RecallExcerptContext(plan:BranchReviewPlan,current:FrozenBranchReviewQuestion,
    source:StudySourceRow?,cluesVisible:Boolean) {
    Text(if(cluesVisible)current.card.title else "本题摘要卡",modifier=Modifier.testTag("recall-context-card-title"))
    // A hint permits clue views, never an implicit reveal of the fixed answer body.
    Text("答案在本题面板显示",modifier=Modifier.testTag("recall-context-card-body"))
    if(!cluesVisible) {
        RecallPlaceholder("recall-context-excerpt-placeholder","摘录已隐藏",hidden=true)
        return
    }
    if(source==null) {
        RecallPlaceholder("recall-context-excerpt-placeholder","此卡片没有可用的原迹摘录。")
        return
    }
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var snapshot by remember { mutableStateOf<InkPageFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(source,attempt) {
        snapshot=null;error=null
        try {
            snapshot=withContext(Dispatchers.IO) { InkPageFile.decode(checkedSource(plan,current,source).snapshot) }
        } catch(c:CancellationException) { throw c }
        catch(e:Exception) { error=sourceFailure(e) }
    }
    val loaded=snapshot
    if(loaded==null) {
        RecallPlaceholder("recall-context-excerpt-placeholder",error?:"正在读取本题原迹摘录…")
        error?.let {
            Text(it,modifier=Modifier.testTag("recall-context-excerpt-error"))
            TextButton({attempt++},modifier=Modifier.testTag("recall-context-excerpt-retry")){Text("重试摘录")}
        }
        return
    }
    Text("摘录时保存的原迹快照 · 只读 · 可能包含本题答案",style=MaterialTheme.typography.bodySmall)
    AndroidView(factory={context->InkCanvasView(context).apply {
        preview=true;allowInput=false;fingerWrites=false
        contentDescription="本题已保存原迹摘录；已许可查看提示，可能包含答案。"
    }},onRelease={native->native.showDocument(null);native.showObjects(emptyList());native.showStrokes(emptyList())},update={native->
        native.preview=true;native.allowInput=false;native.fingerWrites=false
        native.configure(loaded.world,PaperStyle.BLANK,null)
        native.showAuthoring(loaded.authoring);native.showImageSources(loaded.imageSources)
        native.showStrokes(loaded.strokes);native.showObjects(loaded.objects.filter{
            it.mapEmbed?.target?.notebookId?.let{book->book==plan.ref.notebookId}!=false
        })
    },modifier=Modifier.fillMaxWidth().height(240.dp).testTag("recall-context-excerpt-canvas"))
}

/** Durable typed recall uses the same original-context surface without ever exposing neighbour content. */
@Composable internal fun RecallContextPanel(plan:BranchReviewPlan,current:RecallLoadedAttempt,enabled:Boolean,onReveal:(Int)->Unit){
    var showMap by rememberSaveable(current.row.id){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().testTag("recall-durable-context"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        RecallQuestionProjection(current,enabled,onReveal)
        TextButton({showMap=!showMap},enabled=enabled){Text(if(showMap)"收起原导图位置"else"在原导图位置回忆（其他主题保持遮挡）")}
        if(showMap)RecallMaskedMapProjection(plan,current)
    }
}
