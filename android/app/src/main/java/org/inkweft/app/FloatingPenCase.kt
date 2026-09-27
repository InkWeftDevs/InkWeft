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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import org.inkweft.core.InkPen
import kotlin.math.roundToInt

@Composable internal fun FloatingPenCase(content:@Composable ColumnScope.()->Unit){
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    var x by rememberSaveable{mutableFloatStateOf(prefs.getFloat("case-x",0f))}
    var y by rememberSaveable{mutableFloatStateOf(prefs.getFloat("case-y",.35f))}
    var host by remember{mutableStateOf(IntSize.Zero)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    val density=LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged{host=it}){
        val maxX=(host.width-size.width).coerceAtLeast(0).toFloat()
        val maxY=(host.height-size.height).coerceAtLeast(0).toFloat()
        Surface(Modifier.offset{IntOffset((x.coerceIn(0f,1f)*maxX).roundToInt(),(y.coerceIn(0f,1f)*maxY).roundToInt())}
            .width(64.dp).heightIn(max=with(density){host.height.coerceAtLeast(1).toDp()}).onSizeChanged{size=it}.testTag("floating-pen-case"),
            shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=4.dp){
            Column(Modifier.verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally){
                Box(Modifier.size(48.dp).testTag("pen-case-handle").describedAs("拖动笔盒")
                    .pointerInput(maxX,maxY){detectDragGestures(onDragEnd={prefs.edit().putFloat("case-x",x).putFloat("case-y",y).apply()}){change,delta->
                        change.consume();if(maxX>0)x=(x+delta.x/maxX).coerceIn(0f,1f);if(maxY>0)y=(y+delta.y/maxY).coerceIn(0f,1f)
                    }},contentAlignment=Alignment.Center){
                    Canvas(Modifier.size(20.dp,12.dp)){for(row in 0..1)for(col in 0..2)drawCircle(Color(0xff596963),1.7.dp.toPx(),Offset(col*8.dp.toPx(),row*8.dp.toPx()))}
                }
                content()
            }
        }
    }
}

/** Geometric tool silhouettes remain identifiable without reading their labels. */
@Composable internal fun PenSilhouette(kind:InkPen,color:Int,modifier:Modifier=Modifier){
    Canvas(modifier.size(32.dp,42.dp)){
        val w=size.width;val h=size.height;val ink=Color(color or 0xff000000.toInt())
        val body=if(kind==InkPen.HIGHLIGHTER||kind==InkPen.MARKER).52f else .3f
        drawRoundRect(ink,Offset(w*(.5f-body/2),h*.42f),Size(w*body,h*.58f),CornerRadius(3.dp.toPx()))
        val tip=Path().apply{
            if(kind==InkPen.HIGHLIGHTER||kind==InkPen.MARKER){moveTo(w*.27f,h*.18f);lineTo(w*.73f,h*.1f);lineTo(w*.73f,h*.4f);lineTo(w*.27f,h*.4f)}
            else{moveTo(w*.5f,h*.04f);lineTo(w*(if(kind==InkPen.BRUSH).67f else .7f),h*.36f);lineTo(w*.5f,h*.46f);lineTo(w*.3f,h*.36f)}
            close()
        }
        drawPath(tip,if(kind==InkPen.PEN)Color(0xffd4dbd8)else ink)
        if(kind==InkPen.PEN){drawLine(Color(0xff263e34),Offset(w*.5f,h*.05f),Offset(w*.5f,h*.34f),1.dp.toPx());drawCircle(Color(0xff263e34),2.dp.toPx(),Offset(w*.5f,h*.33f))}
        if(kind==InkPen.BALLPOINT)drawCircle(ink,1.8.dp.toPx(),Offset(w*.5f,h*.06f))
        drawLine(Color.White.copy(alpha=.5f),Offset(w*.43f,h*.51f),Offset(w*.43f,h*.95f),2.dp.toPx())
    }
}
