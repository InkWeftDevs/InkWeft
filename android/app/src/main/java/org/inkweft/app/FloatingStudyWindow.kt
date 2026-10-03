// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

internal class StudyWindowChrome(val drag:Modifier,val sourceReading:Boolean=false,val controls:@Composable ()->Unit)
internal val LocalStudyWindowChrome=staticCompositionLocalOf<StudyWindowChrome?>{null}
internal enum class StudyWindowMode(val title:String,val width:Float,val height:Float){
    COLLECT("收集",420f,340f), ORGANIZE("整理",580f,420f), FOCUS("专注",580f,420f)
}

/** Geometry belongs to the frame, not the current card/template/content page. */
@Composable internal fun FloatingStudyWindow(book:String,enabled:Boolean,minimized:Boolean,
    onMinimize:(Boolean)->Unit,docked:Boolean,onDock:(Boolean)->Unit,close:()->Unit,frameEnabled:Boolean=enabled,sourceReading:Boolean=false,onSourceReadingEnd:()->Unit={},beforeContentExit:()->Boolean={true},content:@Composable ()->Unit){
    val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val prefs=LocalContext.current.getSharedPreferences("inkweft-study-window",0)
    val density=LocalDensity.current.density
    var modeName by rememberSaveable(book){mutableStateOf(prefs.getString("$book-mode",StudyWindowMode.ORGANIZE.name)!!)}
    val mode=StudyWindowMode.entries.find{it.name==modeName}?:StudyWindowMode.ORGANIZE
    var x by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-x",1f))}
    var y by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-y",.25f))}
    var width by rememberSaveable(book,modeName){mutableFloatStateOf(prefs.getFloat("$book-$modeName-width",if(mode==StudyWindowMode.COLLECT)prefs.getFloat("$book-width",mode.width)else mode.width))}
    var height by rememberSaveable(book,modeName){mutableFloatStateOf(prefs.getFloat("$book-$modeName-height",if(mode==StudyWindowMode.COLLECT)prefs.getFloat("$book-height",mode.height)else mode.height))}
    var modeMenu by remember{mutableStateOf(false)}
    var dragging by remember{mutableStateOf(false)}
    fun save(){prefs.edit().putString("$book-mode",mode.name).putFloat("$book-x",x).putFloat("$book-y",y)
        .putFloat("$book-${mode.name}-width",width).putFloat("$book-${mode.name}-height",height).apply()}
    fun choose(next:StudyWindowMode){if(!beforeContentExit())return;save();modeName=next.name;prefs.edit().putString("$book-mode",next.name).apply();modeMenu=false;onMinimize(false)}
    BoxWithConstraints(Modifier.fillMaxSize()){
        val availableW=(maxWidth.value-16).coerceAtLeast(1f)
        val topInset=if(sourceReading&&(maxWidth<700.dp||minimized)||maxHeight.value<412f)8f else 64f
        val availableH=(maxHeight.value-topInset-8).coerceAtLeast(48f)
        val collapsed=minimized||availableH<180||(sourceReading&&maxWidth<700.dp)
        val maximized=mode==StudyWindowMode.FOCUS&&!sourceReading
        val effectiveDocked=docked||(sourceReading&&maxWidth>=700.dp)
        val w=if(collapsed)minOf(availableW,320f)else if(maximized)availableW else if(effectiveDocked)minOf(480f,availableW*.46f).coerceAtLeast(minOf(320f,availableW))else width.coerceIn(minOf(320f,availableW),availableW)
        val h=if(collapsed)48f else if(maximized||effectiveDocked)availableH else height.coerceIn(minOf(300f,availableH),availableH)
        val travelX=(availableW-w).coerceAtLeast(0f);val travelY=(availableH-h).coerceAtLeast(0f)
        val left=8+if(effectiveDocked||maximized)travelX else x.coerceIn(0f,1f)*travelX
        val top=topInset+if(sourceReading||maximized||effectiveDocked)0f else y.coerceIn(0f,1f)*travelY
        // Animate frame travel only. Native map viewport and author geometry stay unchanged.
        val motion=if(dragging||sourceReading)snap() else tween<androidx.compose.ui.unit.Dp>(InkTheme.MotionMillis)
        val frameLeft by animateDpAsState(left.dp,motion,label="study-frame-left")
        val frameTop by animateDpAsState(top.dp,motion,label="study-frame-top")
        val currentEnabled by rememberUpdatedState(enabled)
        val currentFrameEnabled by rememberUpdatedState(frameEnabled)
        val drag=Modifier.testTag("study-window-drag").pointerInput(travelX,travelY,effectiveDocked,maximized,sourceReading){
            detectDragGestures(onDragStart={dragging=true},onDragEnd={if(!sourceReading)save();dragging=false},onDragCancel={if(!sourceReading)save();dragging=false}){change,delta->
                change.consume();if(currentFrameEnabled&&!effectiveDocked&&!maximized&&!sourceReading){if(travelX>0)x=(x+delta.x/density/travelX).coerceIn(0f,1f);if(travelY>0)y=(y+delta.y/density/travelY).coerceIn(0f,1f)}
            }
        }
        val controls:@Composable ()->Unit={
            if(sourceReading)TextButton(onSourceReadingEnd,enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("study-window-source-return")){Text("恢复窗口")}
            if(!collapsed)Box{
                IconButton({modeMenu=true},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-maximize").describedAs("导图窗口模式：${mode.title}")){Glyph("fullscreen")}
                DropdownMenu(modeMenu,{modeMenu=false}){
                    StudyWindowMode.entries.forEach{m->DropdownMenuItem(text={Text(m.title)},onClick={if(currentEnabled)choose(m)},enabled=enabled,modifier=Modifier.testTag("study-window-mode-${m.name}"))}
                    DropdownMenuItem(text={Text(if(docked)"恢复浮动"else"停靠右侧")},onClick={if(currentEnabled&&beforeContentExit()){onDock(!docked);modeMenu=false}},enabled=enabled,modifier=Modifier.testTag("study-window-dock"))
                }
            }
            IconButton({if(currentEnabled&&beforeContentExit())onMinimize(!collapsed)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-minimize").describedAs(if(collapsed)"恢复导图"else"最小化导图")){Text(if(collapsed)"□"else"−")}
            IconButton({if(currentEnabled&&beforeContentExit())close()},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("关闭导图")){Glyph("close")}
        }
        // Size changes apply immediately; an in-flight position must still fit the current frame.
        Surface(Modifier.offset{IntOffset((frameLeft.value.coerceIn(8f,8f+travelX)*density).roundToInt(),(frameTop.value.coerceIn(topInset,topInset+travelY)*density).roundToInt())}
            .size(w.dp,h.dp).testTag("study-panel"),color=Color.White,shape=InkTheme.FloatingShape,
            shadowElevation=InkTheme.FloatingElevation){
            Box(Modifier.fillMaxSize().clip(InkTheme.FloatingShape)){
                if(collapsed)Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(drag.weight(1f).fillMaxHeight(),contentAlignment=Alignment.CenterStart){Text("⠿ 导图",Modifier.padding(start=12.dp))};controls()
                }else {
                    holder.SaveableStateProvider(book){CompositionLocalProvider(LocalStudyWindowChrome provides StudyWindowChrome(drag,sourceReading,controls)){content()}}
                    Box(Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("study-window-resize").describedAs("拖动调整导图窗口大小")
                        .pointerInput(availableW,availableH,effectiveDocked,maximized,sourceReading){detectDragGestures(onDragStart={dragging=true},onDragEnd={if(!sourceReading)save();dragging=false},onDragCancel={if(!sourceReading)save();dragging=false}){change,delta->
                            change.consume();if(currentFrameEnabled&&!effectiveDocked&&!maximized&&!sourceReading){val oldW=width.coerceIn(minOf(320f,availableW),availableW);val oldH=height.coerceIn(minOf(300f,availableH),availableH);val oldX=x*(availableW-oldW);val oldY=y*(availableH-oldH)
                                width=(oldW+delta.x/density).coerceIn(minOf(320f,availableW),availableW);height=(oldH+delta.y/density).coerceIn(minOf(300f,availableH),availableH)
                                x=if(availableW>width)(oldX/(availableW-width)).coerceIn(0f,1f)else 0f;y=if(availableH>height)(oldY/(availableH-height)).coerceIn(0f,1f)else 0f}
                        }},contentAlignment=Alignment.BottomEnd){Text("⌟",Modifier.padding(8.dp),color=Quiet)}
                }
            }
        }
    }
}
