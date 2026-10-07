// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

internal class StudyWindowChrome(val drag:Modifier,val sourceReading:Boolean=false,val onAuthorDraft:(Boolean)->Unit={},val controls:@Composable ()->Unit)
internal val LocalStudyWindowChrome=staticCompositionLocalOf<StudyWindowChrome?>{null}
internal enum class StudyWindowMode(val title:String,val width:Float,val height:Float){
    COLLECT("收集",420f,340f), ORGANIZE("整理",580f,420f), FOCUS("专注",580f,420f)
}

internal data class StudyPaneLayout(val sideBySide:Boolean,val mapWidth:Dp)
internal fun studyPaneLayout(width:Dp,height:Dp,fontScale:Float):StudyPaneLayout {
    val textScale=fontScale.coerceAtLeast(1f)
    val minimumMap=360.dp*textScale
    val sideBySide=width>=560.dp+minimumMap+12.dp&&height>=360.dp*textScale
    val maximumMap=minOf(maxOf(480.dp,minimumMap),width-560.dp-12.dp)
    return StudyPaneLayout(sideBySide,if(sideBySide)(width*.4f).coerceIn(minimumMap,maximumMap)else width)
}

/** Keep the native paper and its viewport alive; an inactive pane is measured but not placed. */
@Composable internal fun RetainedStudyPane(visible:Boolean,modifier:Modifier=Modifier,onActivate:(()->Unit)?=null,content:@Composable ()->Unit){
    val activate by rememberUpdatedState(onActivate)
    Layout(modifier=modifier.then(if(visible)Modifier else Modifier.clearAndSetSemantics{}),content={
        val activeModifier=if(onActivate==null)Modifier else Modifier.pointerInput(Unit){awaitEachGesture{awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);activate?.invoke()}}
        Box(Modifier.fillMaxSize().then(activeModifier)){content()}
    }){children,constraints->
        val child=children.single().measure(constraints)
        layout(child.width,child.height){if(visible)child.placeRelative(0,0)}
    }
}

/** Geometry belongs to the frame, not the current card/template/content page. */
@Composable internal fun FloatingStudyWindow(book:String,enabled:Boolean,minimized:Boolean,
    onMinimize:(Boolean)->Unit,docked:Boolean,onDock:(Boolean)->Unit,close:()->Unit,paneLayout:StudyPaneLayout,topInset:Dp,paneActive:Boolean,onActivate:()->Unit,
    onAuthorDraft:(Boolean)->Unit={},frameEnabled:Boolean=enabled,sourceReading:Boolean=false,beforeContentExit:()->Boolean={true},onFocusChanged:(Boolean)->Unit={},content:@Composable ()->Unit){
    val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val prefs=LocalContext.current.getSharedPreferences("inkweft-study-window",0)
    val density=LocalDensity.current.density
    // Old persisted FOCUS must not make a newly opened map replace the page by default.
    // Saveable state still restores an explicitly focused window across recreation/source visits.
    var modeName by rememberSaveable(book){mutableStateOf(prefs.getString("$book-mode",StudyWindowMode.ORGANIZE.name)
        ?.takeUnless{it==StudyWindowMode.FOCUS.name}?:StudyWindowMode.ORGANIZE.name)}
    val mode=StudyWindowMode.entries.find{it.name==modeName}?:StudyWindowMode.ORGANIZE
    val reportFocus by rememberUpdatedState(onFocusChanged)
    SideEffect{reportFocus(mode==StudyWindowMode.FOCUS&&!minimized&&!sourceReading)}
    DisposableEffect(book){onDispose{reportFocus(false)}}
    var x by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-x",1f))}
    var y by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-y",if(paneLayout.sideBySide).25f else 1f))}
    var width by rememberSaveable(book,modeName){mutableFloatStateOf(prefs.getFloat("$book-$modeName-width",if(mode==StudyWindowMode.COLLECT)prefs.getFloat("$book-width",mode.width)else mode.width))}
    var height by rememberSaveable(book,modeName){mutableFloatStateOf(prefs.getFloat("$book-$modeName-height",if(mode==StudyWindowMode.COLLECT)prefs.getFloat("$book-height",mode.height)else mode.height))}
    var modeMenu by remember{mutableStateOf(false)}
    var dragging by remember{mutableStateOf(false)}
    fun save(){prefs.edit().putString("$book-mode",mode.name).putFloat("$book-x",x).putFloat("$book-y",y)
        .putFloat("$book-${mode.name}-width",width).putFloat("$book-${mode.name}-height",height).apply()}
    fun choose(next:StudyWindowMode){if(!beforeContentExit())return;save();modeName=next.name;prefs.edit().putString("$book-mode",next.name).apply();modeMenu=false;onMinimize(false)}
    androidx.activity.compose.BackHandler(mode==StudyWindowMode.FOCUS&&!minimized&&!sourceReading&&paneActive){
        if(enabled)choose(StudyWindowMode.ORGANIZE)
    }
    BoxWithConstraints(Modifier.fillMaxSize()){
        val maximized=mode==StudyWindowMode.FOCUS&&!sourceReading&&!minimized
        val effectiveDocked=paneLayout.sideBySide&&(docked||sourceReading)&&!maximized&&!minimized
        val floating=!effectiveDocked
        val margin=if(floating)8f else 0f
        val availableW=(maxWidth.value-margin*2).coerceAtLeast(1f)
        val frameTopInset=topInset.value+margin
        val availableH=(maxHeight.value-frameTopInset-margin).coerceAtLeast(1f)
        // Keyboard/short-window changes must not dispose a guarded author draft.
        val collapsed=minimized||(floating&&!maximized&&availableH<180&&enabled)
        val visible=if(sourceReading)paneLayout.sideBySide&&!minimized else minimized||paneLayout.sideBySide||paneActive
        // Reserve reading space above a compact map; saved drag coordinates remain authoritative.
        val compactH=if(paneLayout.sideBySide)availableH else (availableH*.58f).coerceAtLeast(48f)
        val w=if(floating&&collapsed)minOf(availableW,320f)else if(effectiveDocked)paneLayout.mapWidth.value else if(!floating||maximized)availableW else width.coerceIn(minOf(320f,availableW),availableW)
        val h=if(floating&&collapsed)minOf(48f,availableH)else if(!floating||maximized)availableH else height.coerceIn(minOf(300f,compactH),compactH)
        val travelX=(availableW-w).coerceAtLeast(0f);val travelY=(availableH-h).coerceAtLeast(0f)
        val left=margin+if(effectiveDocked||maximized)travelX else if(!floating)0f else x.coerceIn(0f,1f)*travelX
        val top=frameTopInset+if(!floating||maximized)0f else y.coerceIn(0f,1f)*travelY
        // Animate frame travel only. Native map viewport and author geometry stay unchanged.
        val motion=if(dragging||!floating)snap() else tween<androidx.compose.ui.unit.Dp>(InkTheme.MotionMillis)
        val frameLeft by animateDpAsState(left.dp,motion,label="study-frame-left")
        val frameTop by animateDpAsState(top.dp,motion,label="study-frame-top")
        val currentEnabled by rememberUpdatedState(enabled)
        val currentFrameEnabled by rememberUpdatedState(frameEnabled)
        val activate by rememberUpdatedState(onActivate)
        val drag=Modifier.testTag("study-window-drag").pointerInput(travelX,travelY,floating,maximized){
            detectDragGestures(onDragStart={dragging=floating&&currentFrameEnabled},onDragEnd={if(floating&&dragging)save();dragging=false},onDragCancel={if(floating&&dragging)save();dragging=false}){change,delta->
                change.consume();if(currentFrameEnabled&&floating&&!maximized){if(travelX>0)x=(x+delta.x/density/travelX).coerceIn(0f,1f);if(travelY>0)y=(y+delta.y/density/travelY).coerceIn(0f,1f)}
            }
        }
        val controls:@Composable ()->Unit={
            if(!collapsed)Box{
                IconButton({modeMenu=true},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-maximize").describedAs("导图窗口模式：${mode.title}")){Glyph("fullscreen")}
                DropdownMenu(modeMenu,{modeMenu=false}){
                    StudyWindowMode.entries.forEach{m->DropdownMenuItem(text={Text(if(m==StudyWindowMode.FOCUS)"全屏专注"else m.title)},onClick={if(currentEnabled)choose(m)},enabled=enabled,modifier=Modifier.testTag("study-window-mode-${m.name}"))}
                    if(paneLayout.sideBySide)DropdownMenuItem(text={Text(if(effectiveDocked)"浮动窗口"else"停靠双栏")},onClick={if(currentEnabled&&beforeContentExit()){onDock(!effectiveDocked);modeName=StudyWindowMode.ORGANIZE.name;modeMenu=false}},enabled=enabled,modifier=Modifier.testTag("study-window-dock"))
                }
            }
            IconButton({if(currentEnabled&&beforeContentExit())onMinimize(!collapsed)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-minimize").describedAs(if(collapsed)"恢复导图"else"最小化导图")){Glyph(if(collapsed)"window-restore"else"window-minimize")}
            IconButton({if(currentEnabled&&beforeContentExit()){modeName=StudyWindowMode.ORGANIZE.name;close()}},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("关闭导图")){Glyph("close")}
        }
        // Size changes apply immediately; an in-flight position must still fit the current frame.
        RetainedStudyPane(visible,Modifier.fillMaxSize()){
        Surface(Modifier.offset{IntOffset((frameLeft.value.coerceIn(margin,margin+travelX)*density).roundToInt(),(frameTop.value.coerceIn(frameTopInset,frameTopInset+travelY)*density).roundToInt())}
            .size(w.dp,h.dp).pointerInput(Unit){awaitEachGesture{awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);activate()}}.testTag("study-panel"),color=Color.White,shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            shadowElevation=InkTheme.FloatingElevation){
            Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))){
                if(collapsed)Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                    Row(drag.weight(1f).fillMaxHeight().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Glyph("drag-handle",Quiet);Text("导图",Modifier.padding(start=8.dp))};controls()
                }else {
                    holder.SaveableStateProvider(book){CompositionLocalProvider(LocalStudyWindowChrome provides StudyWindowChrome(drag,sourceReading,onAuthorDraft,controls)){content()}}
                    if(floating&&!maximized)Box(Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("study-window-resize").describedAs("拖动调整导图窗口大小")
                        .pointerInput(availableW,availableH,compactH,effectiveDocked,maximized,sourceReading){detectDragGestures(onDragStart={dragging=true},onDragEnd={if(!sourceReading)save();dragging=false},onDragCancel={if(!sourceReading)save();dragging=false}){change,delta->
                            change.consume();if(currentFrameEnabled&&floating&&!maximized){val oldW=width.coerceIn(minOf(320f,availableW),availableW);val oldH=height.coerceIn(minOf(300f,compactH),compactH);val oldX=x*(availableW-oldW);val oldY=y*(availableH-oldH)
                                width=(oldW+delta.x/density).coerceIn(minOf(320f,availableW),availableW);height=(oldH+delta.y/density).coerceIn(minOf(300f,compactH),compactH)
                                x=if(availableW>width)(oldX/(availableW-width)).coerceIn(0f,1f)else 0f;y=if(availableH>height)(oldY/(availableH-height)).coerceIn(0f,1f)else 0f}
                        }},contentAlignment=Alignment.BottomEnd){Text("⌟",Modifier.padding(8.dp),color=Quiet)}
                }
            }
        }
        }
    }
}
