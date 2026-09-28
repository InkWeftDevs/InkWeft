// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.BorderStroke
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

internal val LocalStudyResizeAllowed=staticCompositionLocalOf<MutableState<Boolean>?>{null}

/** App-owned surface: only its rectangle participates in hit testing. Paper is never resized. */
@Composable internal fun FloatingStudyWindow(book:String,enabled:Boolean,minimized:Boolean,
    onMinimize:(Boolean)->Unit,docked:Boolean,onDock:(Boolean)->Unit,close:()->Unit,content:@Composable ()->Unit){
    val resizeAllowed=remember{mutableStateOf(false)}
    val holder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val prefs=LocalContext.current.getSharedPreferences("inkweft-study-window",0)
    val density=LocalDensity.current.density
    var x by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-x",1f))}
    var y by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-y",.25f))}
    var width by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-width",420f))}
    var height by rememberSaveable(book){mutableFloatStateOf(prefs.getFloat("$book-height",340f))}
    var maximized by rememberSaveable(book){mutableStateOf(false)}
    fun save(){prefs.edit().putFloat("$book-x",x).putFloat("$book-y",y).putFloat("$book-width",width).putFloat("$book-height",height).apply()}
    BoxWithConstraints(Modifier.fillMaxSize()){
        val availableW=(maxWidth.value-16).coerceAtLeast(1f)
        // Keep room for readable form controls when the IME reduces the viewport.
        // Moving the window above the toolbar is preferable to shrinking its targets.
        val topInset=if(!resizeAllowed.value&&maxHeight.value<412f)8f else 104f
        val availableH=(maxHeight.value-topInset-8).coerceAtLeast(48f)
        val collapsed=minimized||availableH<180||(availableH<300&&resizeAllowed.value)
        val w=if(collapsed)minOf(availableW,320f)else if(maximized)availableW else if(docked)minOf(480f,availableW*.46f).coerceAtLeast(minOf(320f,availableW))else width.coerceIn(minOf(320f,availableW),availableW)
        val h=if(collapsed)48f else if(maximized||docked)availableH else height.coerceIn(minOf(300f,availableH),availableH)
        val travelX=(availableW-w).coerceAtLeast(0f);val travelY=(availableH-h).coerceAtLeast(0f)
        val left=8+if(docked||maximized)travelX else x.coerceIn(0f,1f)*travelX
        val top=topInset+if(maximized||docked)0f else y.coerceIn(0f,1f)*travelY
        val currentEnabled by rememberUpdatedState(enabled)
        Surface(Modifier.offset{IntOffset((left*density).roundToInt(),(top*density).roundToInt())}
            .size(w.dp,h.dp).testTag("study-panel"),color=Color.White,shape=RoundedCornerShape(16.dp),
            shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))){
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("study-window-drag")
                        .pointerInput(travelX,travelY,docked,maximized){detectDragGestures(onDragEnd={save()},onDragCancel={save()}){change,delta->
                            change.consume();if(currentEnabled&&!docked&&!maximized){if(travelX>0)x=(x+delta.x/density/travelX).coerceIn(0f,1f);if(travelY>0)y=(y+delta.y/density/travelY).coerceIn(0f,1f)}
                        }},contentAlignment=Alignment.CenterStart){Text("⠿ 导图",Modifier.padding(start=12.dp),style=MaterialTheme.typography.titleSmall,maxLines=1)}
                    if(!collapsed){
                        IconButton({onDock(!docked)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-dock").describedAs(if(docked)"浮动窗口"else"停靠右侧")){Glyph("overview")}
                        IconButton({maximized=!maximized},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-maximize").describedAs("放大或还原导图窗口")){Glyph("fullscreen")}
                    }
                    IconButton({onMinimize(!collapsed)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-window-minimize").describedAs(if(collapsed)"恢复导图"else"最小化导图")){Text(if(collapsed)"□"else"−")}
                    IconButton(close,enabled=enabled,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("关闭导图")){Glyph("close")}
                }
                if(!collapsed)Box(Modifier.weight(1f)){
                    holder.SaveableStateProvider(book){CompositionLocalProvider(LocalStudyResizeAllowed provides resizeAllowed){content()}}
                    if(resizeAllowed.value)Box(Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("study-window-resize").describedAs("拖动调整导图窗口大小")
                        .pointerInput(availableW,availableH,docked,maximized){detectDragGestures(onDragEnd={save()},onDragCancel={save()}){change,delta->
                            change.consume();if(currentEnabled&&!docked&&!maximized){val oldW=width.coerceIn(minOf(320f,availableW),availableW);val oldH=height.coerceIn(minOf(300f,availableH),availableH);val oldX=x*(availableW-oldW);val oldY=y*(availableH-oldH);width=(oldW+delta.x/density).coerceIn(minOf(320f,availableW),availableW);height=(oldH+delta.y/density).coerceIn(minOf(300f,availableH),availableH);x=if(availableW>width)(oldX/(availableW-width)).coerceIn(0f,1f)else 0f;y=if(availableH>height)(oldY/(availableH-height)).coerceIn(0f,1f)else 0f}
                        }},contentAlignment=Alignment.BottomEnd){Text("⌟",Modifier.padding(8.dp),color=Quiet)}
                }
            }
        }
    }
}
