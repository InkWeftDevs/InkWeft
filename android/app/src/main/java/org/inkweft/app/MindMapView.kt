// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.view.*
import org.inkweft.data.*
import org.inkweft.core.*
import kotlin.math.*
import androidx.compose.ui.graphics.toArgb
import org.inkweft.app.ui.designsystem.InkTheme

/** Bounded native map canvas. Cards own text; this view owns only temporary drag/pan. */
internal data class MapViewport(val scale:Float,val x:Float,val y:Float)
internal class MindMapView(context:Context):View(context){
    private val viewStyle=MapViewStyle(InkTheme.Accent.toArgb(),InkTheme.Selected.toArgb(),0xffafc2d8.toInt())
    var enabledInput=true
    /** Browsing remains enabled while author movement, capture and title editing are locked. */
    var authorEditing=true
        set(value){if(field!=value){field=value;dx=0f;dy=0f;dropPoint=null;dropParent=null;candidate=null;lastTapId=null;if(!value)cancelAuthorGesture();invalidate()}}
    var captureBook=""
        set(value){if(field!=value){field=value;clearSceneTransition();knowledgeRelations=emptyList()}}
    var captureMapKey=""
        set(value){if(field!=value){field=value;clearSceneTransition();knowledgeRelations=emptyList()}}
    private var capturedMap=""
    var captureGraph=""
        set(value){if(field!=value){if(movingIds.isNotEmpty()||selectionBox!=null)cancelAuthorGesture("图已变化，拖动取消");field=value}}
    var onCapture:(CaptureTransfer,StudyNodeRow?,Double,Double,String)->Unit={_,_,_,_,_->}
    private var capturedGraph=""
    private var dropPoint:PointF?=null
    private var dropParent:StudyNodeRow?=null
    private var candidate:String?=null
    private var candidateSince=0L
    override fun onDragEvent(e:DragEvent):Boolean{
        val transfer=e.localState as? CaptureTransfer?:return false
        if(transfer.book!=captureBook)return false
        if(e.action==DragEvent.ACTION_DRAG_ENDED){dropPoint=null;dropParent=null;candidate=null;onActive(false);invalidate();return true}
        if(editingTitle||!enabledInput||!authorEditing)return false
        when(e.action){
            DragEvent.ACTION_DRAG_STARTED->{clearSceneTransition();capturedGraph=captureGraph;capturedMap=captureMapKey;dropParent=null;candidate=null;onActive(true);return true}
            DragEvent.ACTION_DRAG_LOCATION,DragEvent.ACTION_DROP->{
                if(!enabledInput||!authorEditing||capturedMap!=captureMapKey){dropPoint=null;dropParent=null;invalidate();return true}
                val p=PointF((e.x-tx)/(scale*d),(e.y-ty)/(scale*d));dropPoint=p
                fun distance(n:StudyNodeRow):Float{val box=checkNotNull(nodeBounds(n.id));return hypot((box.left-e.x).coerceAtLeast(0f)+(e.x-box.right).coerceAtLeast(0f),(box.top-e.y).coerceAtLeast(0f)+(e.y-box.bottom).coerceAtLeast(0f))}
                val nearest=dropParent?.takeIf{distance(it)<=36*d}?:nodes.minByOrNull{distance(it)}?.takeIf{distance(it)<=24*d}
                val now=android.os.SystemClock.uptimeMillis()
                if(candidate!=nearest?.id){candidate=nearest?.id;candidateSince=now}
                if(nearest==null)dropParent=null else if(now-candidateSince>=120)dropParent=nearest
                if(e.action==DragEvent.ACTION_DROP){
                    // An unstable near-node target is rejected, never silently changed to a root.
                    if(nearest==null||dropParent?.id==nearest.id){
                        val parent=dropParent
                        val childX=parent?.let{val next=it.x+nodeLayout(it).width+40; if(next<=40000.0)next else it.x-MapNodeMetrics.WIDTH-40}?:p.x.toDouble()
                        val childY=parent?.let{n->nodes.filter{it.parentId==n.id}.maxOfOrNull{it.y+nodeLayout(it).height+32}?:n.y}?:p.y.toDouble()
                        onCapture(transfer,parent,childX.coerceIn(-40000.0,40000.0),childY.coerceIn(-40000.0,40000.0),capturedGraph)
                    }
                    dropPoint=null;dropParent=null
                }
                invalidate()
            }
            DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->{dropPoint=null;dropParent=null;candidate=null;invalidate()}
        };return true
    }
    var selectedNodeId:String?=null
        set(value){if(field!=value){field=value;if(readingViewport!=null)readingFocusPending=true;requestSourcePreviews();publishBounds();invalidate()}}
    /** Expansion belongs to this occurrence, never to the shared card or stored coordinates. */
    var expandedNodeId:String?=null
        set(value){if(field!=value){field=value;remeasure();value?.let{id->nodes.find{it.id==id}?.let{sourcePreviews.retry(it.cardId)}};requestSourcePreviews();publishBounds();invalidate()}}
    fun canExpandNode(id:String)=nodes.find{it.id==id}?.let{nodeLayout(it).canExpand}==true
    var onViewport:(MapViewport)->Unit={}
    private var positioned=false
    // Reading panes temporarily resize the map; closing them restores the user's camera.
    private var readingViewport:MapViewport?=null
    private var readingFocusPending=false
    private var centerReadingSelection=true
    fun setReadingFocus(sourceReading:Boolean,inspectingCard:Boolean){
        val enabled=sourceReading||inspectingCard
        if(enabled){
            if(readingViewport==null){readingViewport=snapshotViewport();readingFocusPending=true}
            if(centerReadingSelection!=sourceReading){centerReadingSelection=sourceReading;readingFocusPending=true}
            focusReadingSelection()
        }else readingViewport?.let{original->
            readingViewport=null;readingFocusPending=false;restoreViewport(original)
        }
    }
    private fun focusReadingSelection(){
        if(readingViewport!=null&&readingFocusPending&&width>0&&height>0&&nodes.isNotEmpty()){
            val selected=selectedNodeId
            val bounds=selected?.let(::nodeBounds)
            if(selected==null||bounds==null)fit()
            else if(centerReadingSelection||bounds.width()>width-24*d||bounds.height()>height-24*d)focusNode(selected)
            else revealNode(selected)
            readingFocusPending=false
        }
    }
    fun snapshotViewport()=MapViewport(scale,tx,ty)
    fun readingReturnViewport()=readingViewport?:snapshotViewport()
    fun restoreViewport(v:MapViewport){
        if(readingViewport!=null){
            readingViewport=v;readingFocusPending=true;focusReadingSelection();return
        }
        scale=v.scale;tx=v.x;ty=v.y;positioned=true;requestSourcePreviews();publishBounds();invalidate()
    }
    private fun changedViewport(){positioned=true;if(readingViewport==null)onViewport(snapshotViewport());requestSourcePreviews();publishBounds()}
    var onOpen:(StudyNodeRow)->Unit={} // Other knowledge canvases retain their own activation contract.
    var onSelect:((StudyNodeRow?)->Unit)?=null
    var onEditTitle:(StudyNodeRow)->Unit={}
    var onOpenDetails:(StudyNodeRow)->Unit={}
    var editingTitle=false
    var onIndent:(Boolean)->Unit={} // Shift+Tab outdents the selected subtree.
    var onAddSibling:()->Unit={}
    private val pressedOrganizationKeys=mutableMapOf<Int,Pair<String,Boolean>>()
    var branchIds:Set<String> = emptySet()
    var onToggleBranch:(String)->Unit={}
    private var pressedBranch:String?=null
    var onSelectionBounds:(RectF?)->Unit={}
    private var lastBounds:RectF?=null
    private var lastTapId:String?=null
    private var lastTapTime=0L
    private var lastTapX=0f;private var lastTapY=0f
    private var annotations:PageAuthoring?=null
    private val annotationPainter=AnnotationPainter()
    fun showAuthoring(state:PageAuthoring?){if(annotations===state)return;annotations=state;clearSceneTransition();invalidate()}
    fun annotationBounds(id:String):CanvasBounds?=nodes.firstOrNull{it.id==id}?.let{worldBounds(it)}?.let{CanvasBounds(it.left.toDouble(),it.top.toDouble(),it.right.toDouble(),it.bottom.toDouble())}
    fun nodeBounds(id:String):RectF?=nodes.find{it.id==id}?.let{n->val box=worldBounds(n);RectF(box.left*d*scale+tx,box.top*d*scale+ty,box.right*d*scale+tx,box.bottom*d*scale+ty)}
    private fun publishBounds(){val b=selectedNodeId?.let(::nodeBounds);if(b!=lastBounds){lastBounds=b;post{onSelectionBounds(b)}}}
    var onMove:(StudyNodeRow,Double,Double)->Unit={_,_,_->}
    /** Full graph for subtree membership, including currently folded descendants. */
    var movementNodes:List<StudyNodeRow> = emptyList()
    var selectionMode=false
    var selectedNodeIds:Set<String> = emptySet()
        set(value){if(field!=value){field=value;invalidate()}}
    var onSelectMany:(Set<String>)->Unit={}
    var onMoveSelection:((Set<String>,Double,Double,String)->Unit)?=null
    var onGestureMessage:(String)->Unit={}
    private var movingIds:Set<String> = emptySet()
    private var movingRoots:Set<String> = emptySet()
    private var movingRows:List<StudyNodeRow> = emptyList()
    private var gestureGraph=""
    private var selectionBox:RectF?=null
    private fun branchIdsFor(roots:Set<String>):Set<String>{
        val source=movementNodes.ifEmpty{nodes};val result=roots.toMutableSet()
        // At most 128 topics. Iterate to a fixed point for callers without canonical order.
        repeat(source.size){val size=result.size;source.forEach{if(it.parentId in result)result+=it.id};if(size==result.size)return result}
        return result
    }
    private fun cancelAuthorGesture(message:String?=null){
        active=null;movingIds=emptySet();movingRoots=emptySet();movingRows=emptyList();selectionBox=null;dx=0f;dy=0f
        onActive(false);message?.let(onGestureMessage);publishBounds();invalidate()
    }
    var onActive:(Boolean)->Unit={}
    private var nodes=emptyList<StudyNodeRow>();private var titles=emptyMap<String,String>()
    private var presentations=emptyMap<String,org.inkweft.core.KnowledgeData.CardPresentation>()
    fun setCardPresentations(value:Map<String,org.inkweft.core.KnowledgeData.CardPresentation>){if(presentations!=value){presentations=value;invalidate()}}
    private var bodies=emptyMap<String,String>()
    private var revisions=emptyMap<String,Long>()
    private var sources=emptyMap<String,MapSourceInfo>()
    private var structuralCardIds=emptySet<String>()
    private var layouts=emptyMap<String,MapNodeLayout>()
    private var layoutInputs:List<Any?>?=null
    private val sourcePreviews=MapSourcePreview({card->(context.applicationContext as? InkWeftApplication)?.study?.source(card)},{remeasure();publishBounds();invalidate()})
    private var relationEdges=emptyList<Pair<String,String>>()
    private var knowledgeRelations=emptyList<StudyRelationEdge>()
    private var relationMode=false
    private var hiddenCounts=emptyMap<String,Int>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val relationDash=DashPathEffect(floatArrayOf(6f,4f),0f)
    private var transitionProgress=1f
    private var previousOutlines=emptyList<RectF>()
    private var transitionAnchor:String?=null
    private val sceneAnimator=ValueAnimator.ofFloat(0f,1f).apply{
        duration=InkTheme.MotionMillis.toLong()
        addUpdateListener{if(!ValueAnimator.areAnimatorsEnabled())clearSceneTransition()else{transitionProgress=it.animatedValue as Float;invalidate()}}
        addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){
            transitionProgress=1f;previousOutlines=emptyList();transitionAnchor=null;invalidate()
        }})
    }
    internal val isSceneTransitionRunning get()=sceneAnimator.isStarted
    private fun clearSceneTransition(){
        sceneAnimator.cancel();transitionProgress=1f;previousOutlines=emptyList();transitionAnchor=null;invalidate()
    }
    /** Commit all geometry synchronously; the animator owns only short-lived border decoration. */
    fun transitionScene(affectedNodeIds:Set<String>,anchorNodeId:String?=selectedNodeId,showFinal:()->Unit){
        clearSceneTransition()
        fun geometry()=nodes.map{n->listOf(n.id,n.parentId,n.x,n.y,nodeLayout(n).width,nodeLayout(n).height)} to hiddenCounts
        val before=geometry()
        val outlines=nodes.asSequence().filter{it.id in affectedNodeIds}.take(128).mapNotNull{nodeBounds(it.id)}.toList()
        showFinal()
        if(outlines.isEmpty()||before==geometry()||!isAttachedToWindow||width<=0||height<=0||!ValueAnimator.areAnimatorsEnabled())return
        previousOutlines=outlines;transitionAnchor=anchorNodeId;transitionProgress=0f;sceneAnimator.start()
    }
    fun setKnowledgeRelations(edges:List<StudyRelationEdge>){if(knowledgeRelations!=edges){knowledgeRelations=edges;invalidate()}}
    private var scale=.8f;private var tx=20f;private var ty=20f
    private var active:StudyNodeRow?=null;private var moving=false;private var multi=false
    private var startX=0f;private var startY=0f;private var lastX=0f;private var lastY=0f
    private var dx=0f;private var dy=0f
    private val d get()=resources.displayMetrics.density
    private fun nodeLayout(n:StudyNodeRow)=layouts.getValue(n.id)
    private fun worldBounds(n:StudyNodeRow):RectF{val size=nodeLayout(n);return RectF(x(n),y(n),x(n)+size.width,y(n)+size.height)}
    fun previewSourceInfo(card:String,source:MapSourceInfo):MapSourceInfo = source.copy(
        contentRatio=source.contentRatio?:sourcePreviews.aspectRatio(card,revisions[card]?:nodes.find{it.cardId==card}?.revision?:0))
    private fun remeasure(){
        val input=listOf(nodes.map{it.id to it.cardId},titles,bodies,sources,structuralCardIds,resources.configuration.fontScale,expandedNodeId,relationMode,sourcePreviews.shapeSignature())
        if(layoutInputs==input)return
        layoutInputs=input
        layouts=nodes.associate{n->n.id to MapNodeMetrics.measure(titles[if(relationMode)n.id else n.cardId].orEmpty(),bodies[n.cardId].orEmpty(),
            sources[n.cardId]?.let{previewSourceInfo(n.cardId,it)}.takeUnless{relationMode},resources.configuration.fontScale,expandedNodeId==n.id,n.cardId in structuralCardIds&&!relationMode)}
    }
    private fun requestSourcePreviews(){
        if(!isAttachedToWindow||relationMode||width<=0||height<=0){sourcePreviews.request(emptyList());return}
        val viewport=RectF(0f,0f,width.toFloat(),height.toFloat())
        val visible=nodes.filter{it.cardId in sources&&nodeLayout(it).preview!=null&&nodeBounds(it.id)?.let{b->RectF.intersects(b,viewport)}==true}
            .sortedWith(compareBy<StudyNodeRow>{it.id!=selectedNodeId}.thenBy{n->val b=checkNotNull(nodeBounds(n.id));hypot(b.centerX()-width/2,b.centerY()-height/2)})
        sourcePreviews.request(visible.map{it.cardId to (revisions[it.cardId]?:it.revision)})
    }
    private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(s:ScaleGestureDetector):Boolean{val old=scale;scale=(scale*s.scaleFactor).coerceIn(.001f,2.5f);tx=s.focusX-(s.focusX-tx)*scale/old;ty=s.focusY-(s.focusY-ty)*scale/old;changedViewport();invalidate();return true}
    }).apply{
        // Double-tap belongs to inline title editing; two-finger pinch owns zoom.
        isQuickScaleEnabled=false
    }
    init{isFocusable=true;isFocusableInTouchMode=true;contentDescription="可缩放思维导图，拖动节点移动；需要无障碍浏览时切换大纲视图。"}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        val key=event.keyCode
        if(key!=KeyEvent.KEYCODE_TAB&&key!=KeyEvent.KEYCODE_ENTER)return super.dispatchKeyEvent(event)
        val allowed=enabledInput&&authorEditing&&!editingTitle&&selectedNodeId!=null&&!event.isCtrlPressed&&!event.isAltPressed&&!event.isMetaPressed
        if(event.action==KeyEvent.ACTION_UP){
            val pressed=pressedOrganizationKeys.remove(key)
            if(pressed!=null){
                if(allowed&&!event.isCanceled&&pressed.first==selectedNodeId){if(key==KeyEvent.KEYCODE_TAB)onIndent(pressed.second)else onAddSibling()}
                return true
            }
        }
        if(!allowed)return super.dispatchKeyEvent(event)
        return when(event.action){
            KeyEvent.ACTION_DOWN->{if(event.repeatCount==0&&!pressedOrganizationKeys.containsKey(key))pressedOrganizationKeys[key]=checkNotNull(selectedNodeId) to event.isShiftPressed;true}
            KeyEvent.ACTION_UP->true
            else->super.dispatchKeyEvent(event)
        }
    }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?){
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        if(!gainFocus)pressedOrganizationKeys.clear()
    }
    fun show(items:List<StudyNodeRow>,cards:List<StudyCardRow>,collapsed:Map<String,Int> = emptyMap(),sources:Map<String,MapSourceInfo> = emptyMap(),structuralCardIds:Set<String> = emptySet()){
        relationMode=false;relationEdges=emptyList()
        hiddenCounts=collapsed
        val oldEmpty=nodes.isEmpty();nodes=items.filter{!it.removed};titles=cards.associate{it.id to it.title};bodies=cards.associate{it.id to it.body};revisions=cards.associate{it.id to it.revision}
        this.sources=sources;this.structuralCardIds=structuralCardIds;remeasure()
        if(!positioned&&oldEmpty&&nodes.isNotEmpty()&&width>0)fit();requestSourcePreviews();publishBounds();invalidate()
    }
    fun showRelations(items:List<StudyNodeRow>,labels:Map<String,String>,edges:List<Pair<String,String>>){
        clearSceneTransition();knowledgeRelations=emptyList()
        val empty=nodes.isEmpty();nodes=items;relationMode=true;relationEdges=edges;titles=labels;bodies=emptyMap();hiddenCounts=emptyMap();sources=emptyMap();structuralCardIds=emptySet();remeasure();sourcePreviews.clear()
        if(empty&&nodes.isNotEmpty()&&width>0)fit();publishBounds();invalidate()
    }
    fun decorations(edges:List<Pair<String,String>>){relationEdges=edges;invalidate()}
    fun fitOverview(){fit(.001f)}
    fun fit(minScale:Float=.8f){if(nodes.isEmpty()||width==0||height==0)return
        val bounds=nodes.map(::worldBounds);val left=bounds.minOf{it.left};val right=bounds.maxOf{it.right}+24;val top=bounds.minOf{it.top};val bottom=bounds.maxOf{it.bottom}
        scale=min((width-48*d)/((right-left)*d),(height-48*d)/((bottom-top)*d)).coerceIn(minScale,1.3f)
        // Keep the leading nodes whole when readable scaling requires panning.
        tx=max(width/2-((left+right)/2*d*scale).toFloat(),24*d-(left*d*scale).toFloat())
        ty=max(height/2-((top+bottom)/2*d*scale).toFloat(),24*d-(top*d*scale).toFloat());changedViewport();invalidate()
    }
    fun focusNode(id:String):Boolean{
        val node=nodes.find{it.id==id}?:return false;if(width<=0||height<=0)return false
        val box=worldBounds(node);val fit=min((width-48*d)/(box.width()*d),(height-48*d)/(box.height()*d))
        scale=scale.coerceIn(.8f,1.3f).coerceAtMost(fit).coerceAtLeast(.15f);tx=width/2-box.centerX()*d*scale;ty=height/2-box.centerY()*d*scale;changedViewport();invalidate();return true
    }
    fun revealNode(id:String):Boolean{
        val node=nodes.find{it.id==id}?:return false
        if(width<=0||height<=0)return false
        val box=checkNotNull(nodeBounds(node.id));val pad=12*d
        tx+=if(box.width()>width-2*pad)pad-box.left else if(box.right>width-pad)width-pad-box.right else if(box.left<pad)pad-box.left else 0f
        ty+=if(box.height()>height-2*pad)pad-box.top else if(box.bottom>height-pad)height-pad-box.bottom else if(box.top<pad)pad-box.top else 0f
        changedViewport();invalidate();return true
    }
    fun zoom(f:Float){val old=scale;scale=(scale*f).coerceIn(.001f,2.5f);tx=width/2-(width/2-tx)*scale/old;ty=height/2-(height/2-ty)*scale/old;changedViewport();invalidate()}
    private fun x(n:StudyNodeRow)=n.x.toFloat()+if(n.id in movingIds)dx else 0f
    private fun y(n:StudyNodeRow)=n.y.toFloat()+if(n.id in movingIds)dy else 0f
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        clearSceneTransition()
        if(readingViewport!=null){readingFocusPending=true;focusReadingSelection()}
        else if(oldw==0&&!positioned)fit()
        requestSourcePreviews();publishBounds()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();requestSourcePreviews()}
    override fun onDetachedFromWindow(){clearSceneTransition();sourcePreviews.clear();super.onDetachedFromWindow()}
    override fun onConfigurationChanged(newConfig:Configuration){clearSceneTransition();super.onConfigurationChanged(newConfig);remeasure();requestSourcePreviews();publishBounds();invalidate()}
    // AndroidView can share a Compose canvas with surrounding controls. A background
    // drawColor and transformed nodes must never paint outside this viewport.
    override fun draw(canvas:Canvas){val save=canvas.save();try{canvas.clipRect(0,0,width,height);super.draw(canvas)}finally{canvas.restoreToCount(save)}}
    private fun drawKnowledgeRelations(c:Canvas,lookup:Map<String,StudyNodeRow>){
        val lanes=mutableMapOf<Pair<String,String>,Int>()
        for(edge in knowledgeRelations){
            val a=lookup[edge.fromNodeId]?.let(::worldBounds)?:continue
            val b=lookup[edge.toNodeId]?.let(::worldBounds)?:continue
            val horizontal=abs(b.centerX()-a.centerX())>=abs(b.centerY()-a.centerY())
            val forward=if(horizontal)b.centerX()>=a.centerX()else b.centerY()>=a.centerY()
            val sx=if(horizontal){if(forward)a.right else a.left}else a.centerX()
            val ex=if(horizontal){if(forward)b.left else b.right}else b.centerX()
            val sy=if(horizontal)a.centerY()else if(forward)a.bottom else a.top
            val ey=if(horizontal)b.centerY()else if(forward)b.top else b.bottom
            val length=hypot(ex-sx,ey-sy).coerceAtLeast(1f)
            val nx=-(ey-sy)/length;val ny=(ex-sx)/length
            // A bow keeps a knowledge link visible even when it shares a tree parent/child pair.
            val pair=edge.fromNodeId to edge.toNodeId;val lane=lanes[pair]?:0;lanes[pair]=lane+1
            val bow=(if(horizontal)max(a.height(),b.height())+48f else max(a.width(),b.width())+48f)+lane*28f
            val cx=(sx+ex)/2+nx*bow;val cy=(sy+ey)/2+ny*bow
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f;paint.color=InkTheme.Accent.toArgb();paint.pathEffect=if(edge.lineStyle==org.inkweft.core.RelationLineStyle.DASHED)relationDash else null
            c.drawPath(Path().apply{moveTo(sx,sy);quadTo(cx,cy,ex,ey)},paint)
            paint.pathEffect=null;paint.style=Paint.Style.FILL
            fun arrow(t:Float,reverse:Boolean=false){
                val u=1-t
                val ax=u*u*sx+2*u*t*cx+t*t*ex;val ay=u*u*sy+2*u*t*cy+t*t*ey
                val angle=atan2(u*(cy-sy)+t*(ey-cy),u*(cx-sx)+t*(ex-cx))+(if(reverse)PI.toFloat()else 0f)
                c.drawPath(Path().apply{
                    moveTo(ax,ay);lineTo(ax-10*cos(angle-.45f),ay-10*sin(angle-.45f))
                    lineTo(ax-10*cos(angle+.45f),ay-10*sin(angle+.45f));close()
                },paint)
            }
            arrow(.8f)
            if(edge.direction==org.inkweft.core.RelationDirection.BOTH)arrow(.2f,true)
            val text=(edge.labels+edge.annotations.map{it.replace('\n',' ')}).joinToString(" · ")
            paint.textSize=11f*resources.configuration.fontScale;paint.typeface=Typeface.DEFAULT
            val limit=220f*resources.configuration.fontScale.coerceIn(1f,1.5f)
            val count=paint.breakText(text,true,limit-paint.measureText("…"),null)
            val label=if(count<text.length)text.take(count)+"…"else text
            val mx=(sx+2*cx+ex)/4;val my=(sy+2*cy+ey)/4
            val half=paint.measureText(label)/2;val metrics=paint.fontMetrics
            val baseline=my-(metrics.ascent+metrics.descent)/2
            paint.color=Color.WHITE;c.drawRoundRect(mx-half-5,my-(metrics.descent-metrics.ascent)/2-3,mx+half+5,my+(metrics.descent-metrics.ascent)/2+3,5f,5f,paint)
            paint.color=InkTheme.Accent.toArgb();c.drawText(label,mx-half,baseline,paint)
        }
        paint.pathEffect=null;paint.alpha=255
    }
    private fun drawSceneTransition(c:Canvas){
        val remaining=1-transitionProgress
        paint.style=Paint.Style.STROKE;paint.pathEffect=null;paint.strokeWidth=1.5f*d
        paint.color=InkTheme.Accent.toArgb();paint.alpha=(100*remaining).toInt()
        previousOutlines.forEach{c.drawRoundRect(it,10*d,10*d,paint)}
        transitionAnchor?.let(::nodeBounds)?.let{bounds->
            val pad=(3+3*transitionProgress)*d;bounds.inset(-pad,-pad)
            paint.strokeWidth=2f*d;paint.alpha=(150*sin(PI*transitionProgress)).toInt().coerceIn(0,255)
            c.drawRoundRect(bounds,12*d,12*d,paint)
        }
        paint.alpha=255
    }
    override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(InkTheme.Workspace.toArgb())
        if(isSceneTransitionRunning&&!ValueAnimator.areAnimatorsEnabled())clearSceneTransition()
        val layer=if(isSceneTransitionRunning)c.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(200+55*transitionProgress).toInt())else null
        val save=c.save();c.translate(tx,ty);c.scale(scale*d,scale*d)
        val lookup=nodes.associateBy{it.id}
        drawKnowledgeRelations(c,lookup)
        MapScenePainter.draw(c,nodes.map{n->org.inkweft.core.MapSceneNode(n.id,n.parentId,n.cardId.takeUnless{it in structuralCardIds},titles[if(relationMode)n.id else n.cardId].orEmpty(),bodies[n.cardId].orEmpty(),x(n).toDouble(),y(n).toDouble(),n.revision,revisions[n.cardId]?:n.revision)},active?.id?:selectedNodeId,hiddenCounts,resources.configuration.fontScale,scale>=.35f,!relationMode,
            viewStyle,layouts,sourcePreviews.frames(),presentations)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.pathEffect=relationDash
        paint.color=InkTheme.Accent.toArgb()
        for((a,b) in relationEdges){val start=lookup[a]?:continue;val end=lookup[b]?:continue
            val aBox=worldBounds(start);val bBox=worldBounds(end)
            val horizontal=abs(bBox.centerX()-aBox.centerX())>=abs(bBox.centerY()-aBox.centerY());val forward=if(horizontal)bBox.centerX()>=aBox.centerX()else bBox.centerY()>=aBox.centerY()
            val sx=if(horizontal){if(forward)aBox.right else aBox.left}else aBox.centerX();val ex=if(horizontal){if(forward)bBox.left else bBox.right}else bBox.centerX()
            val sy=if(horizontal)aBox.centerY()else if(forward)aBox.bottom else aBox.top;val ey=if(horizontal)bBox.centerY()else if(forward)bBox.top else bBox.bottom
            paint.alpha=if(selectedNodeId==null||selectedNodeId==a||selectedNodeId==b)255 else 90
            c.drawLine(sx,sy,ex,ey,paint)
            val angle=atan2(ey-sy,ex-sx);val ax=sx+(ex-sx)*.7f;val ay=sy+(ey-sy)*.7f
            if(relationMode){c.drawLine(ax,ay,ax-12*cos(angle-.45f),ay-12*sin(angle-.45f),paint);c.drawLine(ax,ay,ax-12*cos(angle+.45f),ay-12*sin(angle+.45f),paint)}
        }
        paint.alpha=255;paint.pathEffect=null
        dropPoint?.let{point->
            paint.style=Paint.Style.STROKE;paint.color=InkTheme.Accent.toArgb();paint.strokeWidth=2f/scale
            paint.pathEffect=DashPathEffect(floatArrayOf(6f/scale,4f/scale),0f)
            dropParent?.let{parent->val box=worldBounds(parent);c.drawRoundRect(box.left-3f,box.top-3f,box.right+3f,box.bottom+3f,12f,12f,paint);c.drawLine(box.right,box.centerY(),point.x,point.y,paint)}
            c.drawRoundRect(point.x,point.y,point.x+168,point.y+52,8f,8f,paint);paint.pathEffect=null
        }
        annotations?.let{state->
            val occurrences=nodes.mapNotNull{n->annotationBounds(n.id)?.let{n.id to it}}.toMap()
            val viewport=Matrix().apply{setScale(scale*d,scale*d);postTranslate(tx,ty)}
            annotationPainter.regions(c,state.regions,emptyList(),occurrences)
            for(layer in state.layers.layers.filter{it.visible})annotationPainter.draw(c,state.visibleAnnotations().filter{state.layers.layer(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))?.id==layer.id},emptyList(),viewport,occurrences,state.regions)
        }
        // Selected branches have both an outline and a checkmark, independent of card colors.
        for(n in nodes)if(n.id in selectedNodeIds){
            val box=worldBounds(n);paint.style=Paint.Style.STROKE;paint.strokeWidth=3f/scale;paint.pathEffect=null;paint.color=InkTheme.Accent.toArgb()
            c.drawRoundRect(box.left-4/scale,box.top-4/scale,box.right+4/scale,box.bottom+4/scale,10f,10f,paint)
            paint.style=Paint.Style.FILL;paint.textSize=18f/scale;c.drawText("✓",box.left+5/scale,box.top+20/scale,paint)
        }
        c.restoreToCount(save)
        selectionBox?.let{box->
            paint.style=Paint.Style.FILL;paint.color=InkTheme.Accent.toArgb();paint.alpha=28;c.drawRect(box,paint)
            paint.style=Paint.Style.STROKE;paint.alpha=255;paint.strokeWidth=2*d;paint.pathEffect=DashPathEffect(floatArrayOf(7*d,4*d),0f);c.drawRect(box,paint);paint.pathEffect=null
        }
        // Screen-size affordances remain hittable independently of zoom and node dragging.
        nodes.filter{it.id in branchIds&&it.id!=selectedNodeId}.forEach{n->nodeBounds(n.id)?.let{b->
            val cx=b.right+12*d;val cy=b.centerY();paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawCircle(cx,cy,12*d,paint)
            paint.style=Paint.Style.STROKE;paint.color=InkTheme.Accent.toArgb();paint.strokeWidth=1.5f*d;c.drawCircle(cx,cy,12*d,paint);c.drawLine(cx-5*d,cy,cx+5*d,cy,paint)
            if(n.id in hiddenCounts)c.drawLine(cx,cy-5*d,cx,cy+5*d,paint)
        }}
        if(dropPoint!=null){
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawRoundRect(8*d,8*d,width-8*d,44*d,8*d,8*d,paint)
            paint.color=InkTheme.Accent.toArgb();paint.textSize=13*d*resources.configuration.fontScale
            val label=dropParent?.let{"松手：添加到 "+titles[it.cardId].orEmpty()}?:if(candidate!=null)"停留以选择分支"else"松手：放到根层"
            val end=paint.breakText(label,true,width-32*d,null);c.drawText(label.take(end),16*d,32*d,paint)
        }
        layer?.let{c.restoreToCount(it);drawSceneTransition(c)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(e.actionMasked==MotionEvent.ACTION_DOWN)clearSceneTransition()
        if(!enabledInput||editingTitle){cancelAuthorGesture();return true}
        detector.onTouchEvent(e)
        if(e.pointerCount>1){lastTapId=null;multi=true;cancelAuthorGesture();onActive(true);return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{startX=e.x;startY=e.y;lastX=e.x;lastY=e.y;dx=0f;dy=0f;moving=false;multi=false
                pressedBranch=nodes.lastOrNull{n->n.id in branchIds&&n.id!=selectedNodeId&&nodeBounds(n.id)?.let{b->abs(e.x-(b.right+12*d))<=24*d&&abs(e.y-b.centerY())<=24*d}==true}?.id
                val px=(e.x-tx)/(scale*d);val py=(e.y-ty)/(scale*d)
                val ordered=nodes.sortedBy{it.id==selectedNodeId}
                active=if(pressedBranch!=null)null else ordered.lastOrNull{worldBounds(it).contains(px,py)}
                gestureGraph=captureGraph
                movingRoots=active?.let{if(it.id in selectedNodeIds)selectedNodeIds else setOf(it.id)}.orEmpty()
                movingIds=if(onMoveSelection==null)movingRoots else branchIdsFor(movingRoots)
                movingRows=movementNodes.ifEmpty{nodes}.filter{it.id in movingIds}
                selectionBox=if(selectionMode&&active==null&&pressedBranch==null)RectF(e.x,e.y,e.x,e.y)else null
                parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}
            MotionEvent.ACTION_MOVE->{if(multi)return true
                if(hypot(e.x-startX,e.y-startY)>ViewConfiguration.get(context).scaledTouchSlop)moving=true
                if(moving&&pressedBranch==null){
                    lastTapId=null
                    if(selectionBox!=null)selectionBox=RectF(min(startX,e.x),min(startY,e.y),max(startX,e.x),max(startY,e.y))
                    else if(active!=null&&authorEditing){
                        val desiredX=(e.x-startX)/(scale*d);val desiredY=(e.y-startY)/(scale*d)
                        dx=if(movingRows.isEmpty())desiredX else desiredX.coerceIn((-40000-movingRows.minOf{it.x}).toFloat(),(40000-movingRows.maxOf{it.x}).toFloat())
                        dy=if(movingRows.isEmpty())desiredY else desiredY.coerceIn((-40000-movingRows.minOf{it.y}).toFloat(),(40000-movingRows.maxOf{it.y}).toFloat())
                    }else {tx+=e.x-lastX;ty+=e.y-lastY}
                }
                lastX=e.x;lastY=e.y;if(selectionBox==null&&(active==null||!authorEditing))changedViewport()else publishBounds();invalidate()}
            MotionEvent.ACTION_UP->{
                val n=active;val mx=dx;val my=dy;val roots=movingRoots;val stamp=gestureGraph;val box=selectionBox
                val branch=pressedBranch;pressedBranch=null
                cancelAuthorGesture();parent?.requestDisallowInterceptTouchEvent(false)
                if(branch!=null){lastTapId=null;if(!multi&&!moving)onToggleBranch(branch)}
                else if(!multi&&box!=null){
                    val selected=if(moving)nodes.filter{nodeBounds(it.id)?.let{bounds->RectF.intersects(bounds,box)}==true}.map{it.id}.toSet()else emptySet()
                    selectedNodeIds=selected;onSelectMany(selected);lastTapId=null
                    onGestureMessage(if(selected.isEmpty())"框选为空，未改变内容"else"已框选 ${selected.size} 个主题；移动、分组和布局包含各自下级")
                }else if(!multi){
                    if(n!=null&&moving&&authorEditing){
                        if(stamp!=captureGraph)onGestureMessage("图已变化，移动取消")
                        else if(mx!=0f||my!=0f){
                            val moveMany=onMoveSelection
                            if(moveMany==null)onMove(n,n.x+mx,n.y+my)else moveMany(roots,mx.toDouble(),my.toDouble(),stamp)
                        }
                    }else if(!moving){
                        if(n==null){lastTapId=null;onSelect?.invoke(null)}
                        else if(selectionMode){
                            val selected=if(n.id in selectedNodeIds)selectedNodeIds-n.id else selectedNodeIds+n.id
                            selectedNodeIds=selected;onSelectMany(selected);lastTapId=null
                        }else if(onSelect==null)onOpen(n)else{
                            val twice=lastTapId==n.id&&e.eventTime-lastTapTime<=ViewConfiguration.getDoubleTapTimeout()&&hypot(e.x-lastTapX,e.y-lastTapY)<ViewConfiguration.get(context).scaledDoubleTapSlop
                            selectedNodeId=n.id;requestFocus();onSelect?.invoke(n)
                            if(twice){lastTapId=null;if(authorEditing)onEditTitle(n)else onOpenDetails(n)}else{lastTapId=n.id;lastTapTime=e.eventTime;lastTapX=e.x;lastTapY=e.y}
                        }
                    }
                }else lastTapId=null
                invalidate();performClick()
            }
            MotionEvent.ACTION_CANCEL->{pressedBranch=null;lastTapId=null;parent?.requestDisallowInterceptTouchEvent(false);cancelAuthorGesture("拖动已取消，原位置保留")}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
