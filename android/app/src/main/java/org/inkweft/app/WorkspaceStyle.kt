// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

internal val Forest=Color(0xff236653)
internal val Leaf=Color(0xffe9f2ec)
internal val TextInk=Color(0xff24352f)
internal val Quiet=Color(0xff6c7771)
internal val Line=Color(0xffe6eae7)
internal val Side=Color(0xfff7f8f7)

@Composable
fun InkWeftTheme(content:@Composable ()->Unit){
    MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,primaryContainer=Leaf,
        onPrimaryContainer=Forest,secondary=Forest,onSecondary=Color.White,secondaryContainer=Leaf,
        onSecondaryContainer=Forest,tertiary=Forest,tertiaryContainer=Leaf,
        background=Color.White,onBackground=TextInk,surface=Color.White,onSurface=TextInk,
        surfaceVariant=Side,onSurfaceVariant=Quiet,outline=Color(0xffb9c6be)),content=content)
}

/** Small original geometric glyphs; no proprietary icons or font dependency. */
@Composable
internal fun Glyph(kind:String,tint:Color=LocalContentColor.current,modifier:Modifier=Modifier){
    Canvas(modifier.size(21.dp)){
        val k=size.width/24;val stroke=1.5f*k
        fun line(x:Float,y:Float,X:Float,Y:Float)=drawLine(tint,Offset(x*k,y*k),Offset(X*k,Y*k),stroke,StrokeCap.Round)
        fun box(x:Float,y:Float,w:Float,h:Float)=drawRoundRect(tint,Offset(x*k,y*k),Size(w*k,h*k),CornerRadius(2*k),style=Stroke(stroke))
        when(kind){
            "add"->{line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            "back"->{line(14f,5f,7f,12f);line(7f,12f,14f,19f)}
            "close"->{line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            "search"->{drawCircle(tint,7*k,Offset(10*k,10*k),style=Stroke(stroke));line(15f,15f,21f,21f)}
            "grid"->{for(y in listOf(4f,14f))for(x in listOf(4f,14f))box(x,y,6f,6f)}
            "list"->{for(y in listOf(5f,12f,19f)){line(7f,y,21f,y);line(3f,y,3.2f,y)}}
            "menu"->{for(y in listOf(5f,12f,19f))line(3f,y,21f,y)}
            "folder"->{val p=Path().apply{moveTo(3*k,7*k);lineTo(3*k,4*k);lineTo(10*k,4*k);lineTo(13*k,7*k);lineTo(21*k,7*k);lineTo(21*k,20*k);lineTo(3*k,20*k);close()};drawPath(p,tint,style=Stroke(stroke))}
            "trash"->{line(4f,6f,20f,6f);box(6f,6f,12f,15f);line(9f,3f,15f,3f);line(10f,10f,10f,17f);line(14f,10f,14f,17f)}
            "star"->{val p=Path();for(i in 0..9){val a=(-Math.PI/2+i*Math.PI/5);val r=if(i%2==0)9.5 else 4.6;val x=(12+r*kotlin.math.cos(a)).toFloat()*k;val y=(12+r*kotlin.math.sin(a)).toFloat()*k;if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();drawPath(p,tint,style=Stroke(stroke))}
            "board"->{val p=Path().apply{moveTo(12*k,12*k);cubicTo(3*k,-1*k,-3*k,24*k,12*k,12*k);cubicTo(27*k,-1*k,27*k,24*k,12*k,12*k)};drawPath(p,tint,style=Stroke(stroke))}
            "pen"->{line(5f,19f,7f,13f);line(7f,13f,17f,3f);line(17f,3f,21f,7f);line(21f,7f,11f,17f);line(11f,17f,5f,19f);line(14f,6f,18f,10f)}
            "eraser"->{val p=Path().apply{moveTo(3*k,15*k);lineTo(14*k,4*k);lineTo(21*k,11*k);lineTo(12*k,20*k);lineTo(8*k,20*k);close()};drawPath(p,tint,style=Stroke(stroke));line(8f,10f,16f,18f)}
            "undo","redo"->{val flip=kind=="redo";withTransform({if(flip)scale(-1f,1f,center)}){line(8f,5f,3f,10f);line(3f,10f,8f,15f);val p=Path().apply{moveTo(3*k,10*k);lineTo(14*k,10*k);cubicTo(23*k,10*k,23*k,21*k,14*k,21*k)};drawPath(p,tint,style=Stroke(stroke))}}
            "more"->{for(x in listOf(5f,12f,19f))drawCircle(tint,1.4f*k,Offset(x*k,12*k))}
            "tag"->{box(3f,3f,18f,18f);line(9f,6f,8f,18f);line(16f,6f,15f,18f);line(6f,10f,19f,10f);line(5f,15f,18f,15f)}
            "sort"->{line(5f,3f,5f,20f);line(2f,17f,5f,20f);line(5f,20f,8f,17f);line(11f,5f,22f,5f);line(11f,11f,19f,11f);line(11f,17f,16f,17f)}
            "diagnostics"->{drawCircle(tint,8.5f*k,center,style=Stroke(stroke));line(12f,7f,12f,12f);line(12f,16f,12f,16.2f)}
            "check"->{line(4f,12f,9f,17f);line(9f,17f,20f,6f)}
            "export"->{line(12f,3f,12f,15f);line(8f,7f,12f,3f);line(16f,7f,12f,3f);line(4f,13f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,13f)}
            else->{box(4f,3f,16f,18f);line(8f,7f,16f,7f);line(8f,11f,16f,11f);line(8f,15f,13f,15f)}
        }
    }
}

internal fun Modifier.describedAs(label:String)=semantics { contentDescription=label }
