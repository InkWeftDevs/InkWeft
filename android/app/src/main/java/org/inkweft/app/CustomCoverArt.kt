// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import org.inkweft.core.*
import kotlinx.coroutines.*

@Composable internal fun CustomCoverArt(c:CustomCover,title:String,modifier:Modifier=Modifier){
    val bitmap by produceState<ImageBitmap?>(null,c.image){value=withContext(Dispatchers.Default){if(c.image.isEmpty())null else BitmapFactory.decodeByteArray(c.image,0,c.image.size)?.asImageBitmap()}}
    val density=LocalDensity.current
    // A cover is document artwork; accessibility names and scalable text live on the shelf tile.
    CompositionLocalProvider(LocalDensity provides Density(density.density,1f)){
        BoxWithConstraints(modifier.clip(RoundedCornerShape(7.dp)).background(Color(c.color)).clearAndSetSemantics{}){
            val w=maxWidth.value
            Canvas(Modifier.fillMaxSize()){
                bitmap?.let{b->
                    val scale=maxOf(size.width/b.width,size.height/b.height)*c.zoom
                    val sw=(size.width/scale).coerceIn(1f,b.width.toFloat());val sh=(size.height/scale).coerceIn(1f,b.height.toFloat())
                    drawImage(b,srcOffset=IntOffset(((b.width-sw)*c.focusX).toInt(),((b.height-sh)*c.focusY).toInt()),srcSize=IntSize(sw.toInt(),sh.toInt()),dstSize=IntSize(size.width.toInt(),size.height.toInt()),filterQuality=FilterQuality.Medium)
                }
                drawRect(Color.Black.copy(alpha=.07f),size=Size(size.width*.055f,size.height))
                drawLine(Color.White.copy(alpha=.35f),Offset(size.width*.06f,0f),Offset(size.width*.06f,size.height),1.dp.toPx())
            }
            if(c.showTitle){
                val alignment=when(c.layout){CoverLayout.LABEL->Alignment.Center;CoverLayout.BAND->Alignment.BottomCenter;CoverLayout.MINIMAL->Alignment.TopCenter}
                val ink=if(Color(c.color).luminance()>.35f)Color(0xff22272e)else Color.White
                val panel=if(c.layout==CoverLayout.LABEL)Color(0xfffbfaf6)else Color(c.color)
                Column(Modifier.align(alignment).padding(start=(w*.13f).dp,end=(w*.09f).dp,top=(w*.16f).dp,bottom=(w*.15f).dp)
                    .fillMaxWidth().background(panel.copy(alpha=if(bitmap==null)1f else .94f),RoundedCornerShape(2.dp)).padding((w*.075f).dp),verticalArrangement=Arrangement.spacedBy((w*.06f).dp)){
                    Text(c.title.ifBlank{title.ifBlank{"我的笔记"}},fontSize=(w*.12f).sp,lineHeight=(w*.17f).sp,fontFamily=if(c.layout==CoverLayout.LABEL)FontFamily.Serif else FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,color=if(c.layout==CoverLayout.LABEL)Color(0xff22272e)else ink,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(c.subtitle.isNotBlank())Text(c.subtitle,fontSize=(w*.065f).sp,lineHeight=(w*.095f).sp,color=if(c.layout==CoverLayout.LABEL)Color(0xff5e6670)else ink.copy(alpha=.8f),maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}
