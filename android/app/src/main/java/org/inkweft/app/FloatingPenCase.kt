// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import org.inkweft.core.InkPen
import kotlin.math.roundToInt

internal val LocalPenPointsLeft=compositionLocalOf{false}

@Composable internal fun FloatingPenCase(storageKey:String="case",wide:Boolean=false,expandRequest:Int=0,topInset:Dp=56.dp,content:@Composable ColumnScope.()->Unit){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    // App-wide persisted preferences outrank a notebook tab's saved composition.
    var x by remember(storageKey){mutableFloatStateOf(prefs.getFloat("$storageKey-x",if(wide).5f else 0f))}
    var y by remember(storageKey){mutableFloatStateOf(prefs.getFloat("$storageKey-y",if(wide).92f else .3f))}
    val initiallyCollapsed=!wide||LocalConfiguration.current.screenWidthDp<600
    var collapsed by remember(storageKey){mutableStateOf(prefs.getBoolean("$storageKey-collapsed",initiallyCollapsed))}
    var positionMenu by remember{mutableStateOf(false)}
    var host by remember{mutableStateOf(IntSize.Zero)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    val density=LocalDensity.current
    val tag=if(wide)"favorite-pen-case"else"floating-pen-case"
    fun persist(){prefs.edit().putFloat("$storageKey-x",x).putFloat("$storageKey-y",y).putBoolean("$storageKey-collapsed",collapsed).apply()}
    LaunchedEffect(expandRequest){if(expandRequest>0){collapsed=false;persist()}}
    Box(Modifier.fillMaxSize().padding(top=topInset,bottom=48.dp).onSizeChanged{host=it}){
        val maxX=(host.width-size.width).coerceAtLeast(0).toFloat()
        val maxY=(host.height-size.height).coerceAtLeast(0).toFloat()
        Surface(Modifier.offset{IntOffset((x.coerceIn(0f,1f)*maxX).roundToInt(),(y.coerceIn(0f,1f)*maxY).roundToInt())}
            .width(if(wide&&!collapsed)224.dp else if(collapsed)56.dp else 104.dp).heightIn(max=minOf(384.dp,with(density){host.height.coerceAtLeast(1).toDp()})).onSizeChanged{size=it}.testTag(tag)
            .semantics{stateDescription=if(collapsed)"已收起"else if(x>.5f)"笔尖朝左"else"笔尖朝右"},
            shape=InkTheme.FloatingShape,color=InkTheme.Surface,shadowElevation=InkTheme.FloatingElevation){
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
@Composable private fun toolAdvance(selected:Boolean):Modifier {
    val left=LocalPenPointsLeft.current
    val advance by animateDpAsState(if(selected)8.dp else 0.dp,tween(InkTheme.MotionMillis),label="工具向纸面前移")
    val pixels=with(LocalDensity.current){advance.toPx()}
    return Modifier.graphicsLayer{translationX=if(left)-pixels else pixels}
}
@Composable internal fun PenSilhouette(kind:InkPen,color:Int,modifier:Modifier=Modifier,selected:Boolean=false){
    val left=LocalPenPointsLeft.current
    Canvas(modifier.then(toolAdvance(selected)).size(64.dp,32.dp)){
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
            if(kind==InkPen.PENCIL){drawPath(nib,Color(0xffdabb8c));val tip=Path().apply{moveTo(w*.81f,h*.43f);lineTo(w*.96f,h*.5f);lineTo(w*.81f,h*.57f);close()};drawPath(tip,ink);drawRect(ink.copy(alpha=.65f),Offset(0f,h*top),Size(w*.3f,h*(bottom-top)))}
            if(thick)drawRoundRect(ink,Offset(w*.8f,h*.39f),Size(w*.17f,h*.22f),CornerRadius(h*.08f))
            if(kind==InkPen.BALLPOINT)drawCircle(ink,1.5.dp.toPx(),Offset(w*.96f,h*.5f))
        }
    }
}

/** Original scalable desk-tool drawings; their direction follows the dock. */
@Composable internal fun CaseAccessory(kind:String,selected:Boolean,modifier:Modifier=Modifier){
    val left=LocalPenPointsLeft.current
    Canvas(modifier.then(toolAdvance(selected)).size(72.dp,34.dp)){
        scale(if(left)-1f else 1f,1f){
            val w=size.width;val h=size.height
            if(kind=="eraser"){
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xffcbd0d5),Color.White,Color(0xffaab2bb))),Offset(-w*.2f,h*.2f),Size(w*.95f,h*.6f),CornerRadius(h*.14f))
                drawRect(Brush.verticalGradient(listOf(Color(0xff77828d),Color(0xfff5f6f8),Color(0xff7e8993))),Offset(w*.55f,h*.2f),Size(w*.16f,h*.6f))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xffe8a5ad),Color(0xffefbac0),Color(0xffcd7c88))),Offset(w*.68f,h*.2f),Size(w*.3f,h*.6f),CornerRadius(h*.2f))
                drawLine(Color.White.copy(alpha=.6f),Offset(w*.73f,h*.27f),Offset(w*.85f,h*.27f),1.dp.toPx())
            }else{
                val strip=Path().apply{moveTo(-w*.2f,h*.18f);lineTo(w*.96f,h*.18f);lineTo(w*.9f,h*.32f);lineTo(w*.97f,h*.43f);lineTo(w*.9f,h*.56f);lineTo(w*.97f,h*.7f);lineTo(w*.9f,h*.82f);lineTo(-w*.2f,h*.82f);close()}
                drawPath(strip,Brush.verticalGradient(listOf(Color(0xffc5dce9),Color(0xffe4f1f8),Color(0xffaecadb))))
                clipPath(strip){for(i in -3..12){val x=i*w/9;drawLine(Color.White.copy(alpha=.8f),Offset(x,h*.85f),Offset(x+w*.28f,h*.15f),2.dp.toPx())}}
                drawLine(Color(0xff9eb9cc),Offset(-w*.2f,h*.18f),Offset(w*.96f,h*.18f),.5.dp.toPx())
            }
        }
    }
}
