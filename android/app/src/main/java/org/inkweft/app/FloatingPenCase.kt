// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import org.inkweft.core.InkPen
import kotlin.math.roundToInt

internal val LocalPenPointsLeft=compositionLocalOf{false}

@Composable internal fun FloatingPenCase(storageKey:String="case",wide:Boolean=false,content:@Composable ColumnScope.()->Unit){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    var x by rememberSaveable(storageKey){mutableFloatStateOf(prefs.getFloat("$storageKey-x",if(wide).5f else 0f))}
    var y by rememberSaveable(storageKey){mutableFloatStateOf(prefs.getFloat("$storageKey-y",if(wide).92f else .3f))}
    var collapsed by rememberSaveable(storageKey){mutableStateOf(prefs.getBoolean("$storageKey-collapsed",false))}
    var positionMenu by remember{mutableStateOf(false)}
    var host by remember{mutableStateOf(IntSize.Zero)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    val density=LocalDensity.current
    val tag=if(wide)"favorite-pen-case"else"floating-pen-case"
    fun persist(){prefs.edit().putFloat("$storageKey-x",x).putFloat("$storageKey-y",y).putBoolean("$storageKey-collapsed",collapsed).apply()}
    Box(Modifier.fillMaxSize().onSizeChanged{host=it}){
        val maxX=(host.width-size.width).coerceAtLeast(0).toFloat()
        val maxY=(host.height-size.height).coerceAtLeast(0).toFloat()
        Surface(Modifier.offset{IntOffset((x.coerceIn(0f,1f)*maxX).roundToInt(),(y.coerceIn(0f,1f)*maxY).roundToInt())}
            .width(if(wide&&!collapsed)224.dp else 72.dp).heightIn(max=with(density){host.height.coerceAtLeast(1).toDp()}).onSizeChanged{size=it}.testTag(tag)
            .semantics{stateDescription=if(collapsed)"已收起"else if(x>.5f)"笔尖朝左"else"笔尖朝右"},
            shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=8.dp,border=BorderStroke(1.dp,Line.copy(alpha=.6f))){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(wide&&!collapsed)Arrangement.Start else Arrangement.Center){
                Box{
                    Box(Modifier.size(48.dp).testTag(if(wide)"favorite-case-handle"else"pen-case-handle").describedAs(if(wide)"拖动收藏笔盒，点按放置"else"拖动笔盒，点按放置")
                        .clickable{positionMenu=true}.pointerInput(maxX,maxY){detectDragGestures(onDragEnd={persist()}){change,delta->
                            change.consume();if(maxX>0)x=(x+delta.x/maxX).coerceIn(0f,1f);if(maxY>0)y=(y+delta.y/maxY).coerceIn(0f,1f)
                        }},contentAlignment=Alignment.Center){
                        Canvas(Modifier.size(20.dp,8.dp)){for(row in 0..1)for(col in 0..2)drawCircle(Quiet,1.5.dp.toPx(),Offset(col*8.dp.toPx()+2.dp.toPx(),row*5.dp.toPx()+1.dp.toPx()))}
                    }
                    DropdownMenu(positionMenu,{positionMenu=false}){
                        DropdownMenuItem(text={Text("放到左侧")},onClick={x=0f;persist();positionMenu=false})
                        DropdownMenuItem(text={Text("放到右侧")},onClick={x=1f;persist();positionMenu=false})
                    }
                }
                if(wide&&!collapsed){Text("收藏笔",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);IconButton(onClick={collapsed=true;persist()},modifier=Modifier.size(48.dp).testTag("$storageKey-collapse").describedAs("收起收藏笔盒")){Glyph("collapse")}}
                }
                if(!collapsed)Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally){CompositionLocalProvider(LocalPenPointsLeft provides(x>.5f)){content()}}
                if(!wide||collapsed)IconButton(onClick={collapsed=!collapsed;persist()},modifier=Modifier.size(48.dp).testTag("$storageKey-collapse").describedAs(if(collapsed)"展开${if(wide)"收藏"else""}笔盒"else"收起${if(wide)"收藏"else""}笔盒")){
                    Glyph(if(collapsed)if(wide)"star"else"pen"else"collapse")
                }
            }
        }
    }
}

/** Original vector artwork. The side-docked nib points toward the writing area. */
@Composable internal fun PenSilhouette(kind:InkPen,color:Int,modifier:Modifier=Modifier){
    val left=LocalPenPointsLeft.current
    Canvas(modifier.size(56.dp,32.dp)){
        scale(if(left)-1f else 1f,1f){
            val w=size.width;val h=size.height;val ink=Color(color or 0xff000000.toInt())
            val thick=kind==InkPen.HIGHLIGHTER||kind==InkPen.MARKER
            val top=if(thick).25f else .32f;val bottom=1-top
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xffc4cbd5),Color.White,Color(0xffd6dce5)),h*top,h*bottom),Offset(-w*.14f,h*top),Size(w*.69f,h*(bottom-top)),CornerRadius(h*.1f))
            drawRect(ink,Offset(w*.30f,h*top),Size(w*.08f,h*(bottom-top)))
            drawRect(Color(0xff667181),Offset(w*.49f,h*top),Size(w*.035f,h*(bottom-top)))
            val nib=Path().apply{moveTo(w*.55f,h*top);lineTo(w*.96f,h*.5f);lineTo(w*.55f,h*bottom);close()}
            drawPath(nib,if(kind==InkPen.PEN)Brush.verticalGradient(listOf(Color(0xff7a8795),Color.White,Color(0xff7a8795)))else Brush.linearGradient(listOf(ink.copy(alpha=.7f),ink)))
            if(kind==InkPen.PEN){drawLine(Color(0xff283343),Offset(w*.67f,h*.5f),Offset(w*.97f,h*.5f),.8.dp.toPx());drawCircle(Color(0xff283343),1.5.dp.toPx(),Offset(w*.7f,h*.5f))}
            if(thick)drawRoundRect(ink,Offset(w*.8f,h*.39f),Size(w*.17f,h*.22f),CornerRadius(h*.08f))
            if(kind==InkPen.BALLPOINT)drawCircle(ink,1.5.dp.toPx(),Offset(w*.96f,h*.5f))
        }
    }
}
