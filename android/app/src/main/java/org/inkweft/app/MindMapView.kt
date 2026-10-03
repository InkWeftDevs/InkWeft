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
        set(value){if(field!=value){field=value;dx=0f;dy=0f;dropPoint=null;dropParent=null;candidate=null;lastTapId=null;if(!value)onActive(false);invalidate()}}
    var captureBook=""
        set(value){if(field!=value){field=value;clearSceneTransition();knowledgeRelations=emptyList()}}
    var captureMapKey=""
        set(value){if(field!=value){field=value;clearSceneTransition();knowledgeRelations=emptyList()}}
    private var capturedMap=""
    var captureGraph=""
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
        set(value){if(field!=value){field=value;requestSourcePreviews();publishBounds();invalidate()}}
    /** Expansion belongs to this occurrence, never to the shared card or stored coordinates. */
    var expandedNodeId:String?=null
        set(value){if(field!=value){field=value;remeasure();value?.let{id->nodes.find{it.id==id}?.let{sourcePreviews.retry(it.cardId)}};requestSourcePreviews();publishBounds();invalidate()}}
    fun canExpandNode(id:String)=nodes.find{it.id==id}?.let{nodeLayout(it).canExpand}==true
    var onViewport:(MapViewport)->Unit={}
    private var positioned=false
    // The source dock has a temporary camera; returning restores the user's exact original viewport.
    private var sourceReadingViewport:MapViewport?=null
    private var sourceFocusPending=false
    fun setSourceReading(enabled:Boolean){
        if(enabled){
            if(sourceReadingViewport==null){sourceReadingViewport=snapshotViewport();sourceFocusPending=true}
            focusSourceSelection()
        }else sourceReadingViewport?.let{original->
            sourceReadingViewport=null;sourceFocusPending=false;restoreViewport(original)
        }
    }
    private fun focusSourceSelection(){
        if(sourceReadingViewport!=null&&sourceFocusPending&&width>0&&height>0&&nodes.isNotEmpty()){
            if(selectedNodeId?.let(::focusNode)!=true)fit()
            sourceFocusPending=false
        }
    }
    fun snapshotViewport()=MapViewport(scale,tx,ty)
    fun restoreViewport(v:MapViewport){
        if(sourceReadingViewport!=null){
            sourceReadingViewport=v;sourceFocusPending=true;focusSourceSelection();return
        }
        scale=v.scale;tx=v.x;ty=v.y;positioned=true;requestSourcePreviews();publishBounds();invalidate()
    }
    private fun changedViewport(){positioned=true;if(sourceReadingViewport==null)onViewport(snapshotViewport());requestSourcePreviews();publishBounds()}
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
    fun nodeBounds(id:String):RectF?=nodes.find{it.id==id}?.let{n->val box=worldBounds(n);RectF(box.left*d*scale+tx,box.top*d*scale+ty,box.right*d*scale+tx,box.bottom*d*scale+ty)}
    private fun publishBounds(){val b=selectedNodeId?.let(::nodeBounds);if(b!=lastBounds){lastBounds=b;post{onSelectionBounds(b)}}}
    var onMove:(StudyNodeRow,Double,Double)->Unit={_,_,_->}
    var onActive:(Boolean)->Unit={}
    private var nodes=emptyList<StudyNodeRow>();private var titles=emptyMap<String,String>()
    private var bodies=emptyMap<String,String>()
    private var revisions=emptyMap<String,Long>()
    private var sources=emptyMap<String,MapSourceInfo>()
    private var structuralCardIds=emptySet<String>()
    private var layouts=emptyMap<String,MapNodeLayout>()
    private var layoutInputs:List<Any?>?=null
    private val sourcePreviews=MapSourcePreview({card->(context.applicationContext as? InkWeftApplication)?.study?.source(card)},{invalidate()})
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
    private fun remeasure(){
        val input=listOf(nodes.map{it.id to it.cardId},titles,bodies,sources,structuralCardIds,resources.configuration.fontScale,expandedNodeId,relationMode)
        if(layoutInputs==input)return
        layoutInputs=input
        layouts=nodes.associate{n->n.id to MapNodeMetrics.measure(titles[if(relationMode)n.id else n.cardId].orEmpty(),bodies[n.cardId].orEmpty(),
            sources[n.cardId].takeUnless{relationMode},resources.configuration.fontScale,expandedNodeId==n.id,n.cardId in structuralCardIds&&!relationMode)}
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
    })
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
    private fun x(n:StudyNodeRow)=n.x.toFloat()+if(n.id==active?.id)dx else 0f
    private fun y(n:StudyNodeRow)=n.y.toFloat()+if(n.id==active?.id)dy else 0f
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        clearSceneTransition()
        if(sourceReadingViewport!=null){sourceFocusPending=true;focusSourceSelection()}
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
            val bow=if(horizontal)max(a.height(),b.height())+48f else max(a.width(),b.width())+48f
            val cx=(sx+ex)/2+nx*bow;val cy=(sy+ey)/2+ny*bow
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f;paint.color=InkTheme.Accent.toArgb();paint.pathEffect=relationDash
            c.drawPath(Path().apply{moveTo(sx,sy);quadTo(cx,cy,ex,ey)},paint)
            paint.pathEffect=null;paint.style=Paint.Style.FILL
            val t=.8f;val u=1-t
            val ax=u*u*sx+2*u*t*cx+t*t*ex;val ay=u*u*sy+2*u*t*cy+t*t*ey
            val angle=atan2(u*(cy-sy)+t*(ey-cy),u*(cx-sx)+t*(ex-cx))
            c.drawPath(Path().apply{
                moveTo(ax,ay);lineTo(ax-10*cos(angle-.45f),ay-10*sin(angle-.45f))
                lineTo(ax-10*cos(angle+.45f),ay-10*sin(angle+.45f));close()
            },paint)
            val label=edge.labels.joinToString(" · ")
            paint.textSize=11f*resources.configuration.fontScale;paint.typeface=Typeface.DEFAULT
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
            viewStyle,layouts,sourcePreviews.frames())
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
        c.restoreToCount(save)
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
        if(!enabledInput||editingTitle){active=null;dx=0f;dy=0f;onActive(false);invalidate();return true}
        detector.onTouchEvent(e)
        if(e.pointerCount>1){lastTapId=null;multi=true;active=null;dx=0f;dy=0f;invalidate();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{startX=e.x;startY=e.y;lastX=e.x;lastY=e.y;dx=0f;dy=0f;moving=false;multi=false
                pressedBranch=nodes.lastOrNull{n->n.id in branchIds&&n.id!=selectedNodeId&&nodeBounds(n.id)?.let{b->abs(e.x-(b.right+12*d))<=24*d&&abs(e.y-b.centerY())<=24*d}==true}?.id
                val px=(e.x-tx)/(scale*d);val py=(e.y-ty)/(scale*d)
                val ordered=nodes.sortedBy{it.id==selectedNodeId}
                active=if(pressedBranch!=null)null else ordered.lastOrNull{worldBounds(it).contains(px,py)}
                parent?.requestDisallowInterceptTouchEvent(true);onActive(true)}
            MotionEvent.ACTION_MOVE->{if(multi)return true
                if(hypot(e.x-startX,e.y-startY)>ViewConfiguration.get(context).scaledTouchSlop)moving=true
                if(moving&&pressedBranch==null){lastTapId=null;if(active!=null&&authorEditing){dx=(e.x-startX)/(scale*d);dy=(e.y-startY)/(scale*d)}else {tx+=e.x-lastX;ty+=e.y-lastY}}
                lastX=e.x;lastY=e.y;if(active==null||!authorEditing)changedViewport()else publishBounds();invalidate()}
            MotionEvent.ACTION_UP->{val n=active;val mx=dx;val my=dy;active=null;dx=0f;dy=0f;onActive(false);parent?.requestDisallowInterceptTouchEvent(false)
                val branch=pressedBranch;pressedBranch=null
                if(branch!=null){lastTapId=null;if(!multi&&!moving)onToggleBranch(branch)}else if(!multi){if(n!=null&&moving){if(authorEditing)onMove(n,(n.x+mx).coerceIn(-40000.0,40000.0),(n.y+my).coerceIn(-40000.0,40000.0))}
                else if(!moving){if(n==null){lastTapId=null;onSelect?.invoke(null)}else if(onSelect==null)onOpen(n)else{
                    val twice=lastTapId==n.id&&e.eventTime-lastTapTime<=ViewConfiguration.getDoubleTapTimeout()&&hypot(e.x-lastTapX,e.y-lastTapY)<ViewConfiguration.get(context).scaledDoubleTapSlop
                    selectedNodeId=n.id;requestFocus();onSelect?.invoke(n)
                    if(twice){lastTapId=null;if(authorEditing)onEditTitle(n)else onOpenDetails(n)}else{lastTapId=n.id;lastTapTime=e.eventTime;lastTapX=e.x;lastTapY=e.y}
                }}}else lastTapId=null;invalidate();performClick()}
            MotionEvent.ACTION_CANCEL->{pressedBranch=null;lastTapId=null;parent?.requestDisallowInterceptTouchEvent(false);active=null;dx=0f;dy=0f;onActive(false);invalidate()}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
