// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import java.util.Locale
import kotlin.math.roundToInt

/** Global pen presets, not author data. Existing A3 width keys stay valid. */
internal class PenWidthStore(context:Context,name:String="inkweft-pen-widths") {
    private val preferences=context.applicationContext.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val global=if(name.startsWith("inkweft-pen-widths-book-"))PenWidthStore(context)else null
    fun read():List<Float> = (0..2).map { tool ->
        val fallback=global?.read()?.get(tool)?:if(tool==2)22f else 3f
        val width=runCatching{preferences.getFloat("width-$tool",fallback)}.getOrDefault(fallback)
        if(width.isFinite() && width in range(tool))width else fallback
    }
    fun readColors():List<Int> = (0..2).map { tool ->
        val fallback=global?.readColors()?.get(tool)?:colors(tool)[if(tool==1)1 else if(tool==2)4 else 0]
        runCatching{preferences.getInt("color-$tool",fallback)}.getOrDefault(fallback).takeIf{validColor(tool,it)}?:fallback
    }
    fun readKinds():List<InkPen> = (0..2).map{tool->
        val fallback=global?.readKinds()?.get(tool)?:defaultKind(tool)
        runCatching{InkPen.valueOf(preferences.getString("kind-$tool",fallback.name)!!)}.getOrDefault(fallback).takeIf{allowed(tool,it)}?:fallback
    }
    suspend fun save(tool:Int,width:Float):Boolean {
        require(tool in 0..2 && width.isFinite() && width in range(tool))
        return writes.withLock { withContext(Dispatchers.IO){preferences.edit().putFloat("width-$tool",width).commit()} }
    }
    suspend fun savePreset(tool:Int,width:Float,color:Int,kind:InkPen=readKinds()[tool]):Boolean {
        require(tool in 0..2 && width.isFinite() && width in range(tool) && validColor(tool,color) && allowed(tool,kind))
        return writes.withLock { withContext(Dispatchers.IO){preferences.edit().putFloat("width-$tool",width).putInt("color-$tool",color).putString("kind-$tool",kind.name).commit()} }
    }
    companion object {
        fun validColor(tool:Int,color:Int)=(color ushr 24)==(if(tool==2)0x66 else 0xff)
        private val writes=Mutex()
        fun range(tool:Int)=if(tool==2)6f..40f else .5f..12f
        fun presets(tool:Int)=if(tool==2)listOf(12f,22f,34f)else listOf(1.5f,3f,6f)
        fun colors(tool:Int)=listOf(0x24342f,0xb83239,0x2f53aa,0x126b50,0xe1ad19).map{it or (if(tool==2)0x66000000 else 0xff000000.toInt())}
        fun label(width:Float)=String.format(Locale.ROOT,"%.1f",width)
        fun defaultKind(tool:Int)=when(tool){0->InkPen.BALLPOINT;1->InkPen.PEN;else->InkPen.HIGHLIGHTER}
        fun allowed(tool:Int,kind:InkPen)=if(tool==2)kind==InkPen.HIGHLIGHTER else kind in PenKinds.writing
        fun name(tool:Int,kind:InkPen=defaultKind(tool))=PenKinds.title(kind)
    }
}

/** Anchored to the toolbar. Outside taps dismiss without passing into ink. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PenPresetMenu(expanded:Boolean,tool:Int,current:Float,currentColor:Int,currentKind:InkPen,onDismiss:()->Unit,favorites:List<FavoritePen> = emptyList(),favoriteBusy:Boolean=false,onFavorite:(InkPen,Float,Int)->Unit={_,_,_->},onApply:(Float,Int,InkPen)->Unit) {
    DropdownMenu(expanded=expanded,onDismissRequest=onDismiss,offset=DpOffset(if(LocalPenPointsLeft.current)(-320).dp else 72.dp,0.dp),shape=RoundedCornerShape(20.dp),containerColor=Color.White,tonalElevation=0.dp,shadowElevation=6.dp,border=BorderStroke(1.dp,Line),modifier=Modifier.width(320.dp).testTag("pen-width-dialog")) {
        var kind by remember(expanded,tool,currentKind){mutableStateOf(currentKind)}
        var draft by remember(expanded,tool,current){mutableFloatStateOf(current)}
        var color by remember(expanded,tool,currentColor){mutableIntStateOf(currentColor)}
        Column(Modifier.padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text(PenKinds.title(kind),Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=TextInk)
                val saved=favorites.any{it.matches(kind,draft,color)}
                IconToggleButton(saved,{onFavorite(kind,draft,color)},enabled=!favoriteBusy,modifier=Modifier.size(48.dp).testTag("pen-favorite").describedAs(if(saved)"取消收藏这支笔"else"收藏这支笔")){Glyph(if(saved)"star-filled"else"star",if(saved)Color(0xffbd8100)else Quiet)}
            }
            PenStrokePreview(kind,color,draft)
            if(tool!=2)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){PenKinds.writing.forEach{p->
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    IconToggleButton(kind==p,{kind=p;draft=PenKinds.defaultWidth(p)},modifier=Modifier.size(60.dp,48.dp).background(if(kind==p)Leaf else Color.Transparent,RoundedCornerShape(12.dp)).testTag("pen-kind-${p.name.lowercase()}").describedAs(PenKinds.title(p))){PenSilhouette(p,color)}
                    Text(PenKinds.title(p),fontSize=12.sp,color=if(kind==p)Forest else Quiet)
                }
            }}
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                PenWidthStore.presets(tool).forEachIndexed { index,width ->
                    FilterChip(selected=draft==width,onClick={draft=width},label={Text(listOf("细","中","粗")[index],fontSize=12.sp)},
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("width-preset-$index"))
                }
            }
            Row{Text("笔刷粗细",Modifier.weight(1f));Text(PenWidthStore.label(draft),modifier=Modifier.testTag("pen-width-value"),style=MaterialTheme.typography.bodyMedium)}
            Slider(value=draft,onValueChange={draft=(it*10).roundToInt()/10f},valueRange=PenWidthStore.range(tool),modifier=Modifier.fillMaxWidth().testTag("pen-width-slider"),
                thumb={Surface(Modifier.size(24.dp),shape=CircleShape,color=Color.White,shadowElevation=3.dp,border=BorderStroke(1.dp,Line)){}},
                track={Canvas(Modifier.fillMaxWidth().height(20.dp)){val path=Path().apply{moveTo(0f,size.height*.5f);lineTo(size.width,size.height*.1f);quadraticBezierTo(size.width+size.height*.3f,size.height*.5f,size.width,size.height*.9f);close()};drawPath(path,Color(color or 0xff000000.toInt()))}})
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                PenWidthStore.colors(tool).forEachIndexed { index,value ->
                    Box(Modifier.size(48.dp).border(if(color==value)2.dp else 1.dp,if(color==value)Forest else Color.Transparent,CircleShape)
                        .clickable{color=value}.testTag("pen-color-$index").describedAs("颜色："+listOf("墨黑","红色","蓝色","绿色","黄色")[index]),contentAlignment=Alignment.Center) {
                        Box(Modifier.size(25.dp).background(Color(value or 0xff000000.toInt()),CircleShape))
                    }
                }
            }
            var hex by remember(color){mutableStateOf(String.format(Locale.ROOT,"%06X",color and 0xffffff))}
            var custom by remember{mutableStateOf(false)}
            TextButton(onClick={custom=!custom},modifier=Modifier.testTag("pen-custom-open")){Text("自定义颜色")}
            if(custom){OutlinedTextField(hex,{value->if(value.length<=6)hex=value.uppercase(Locale.ROOT)},label={Text("自定义颜色 · 六位十六进制")},isError=!hex.matches(Regex("[0-9A-F]{6}")),singleLine=true,modifier=Modifier.fillMaxWidth().testTag("pen-custom-color"))
            TextButton(onClick={color=hex.toInt(16) or (if(tool==2)0x66000000 else 0xff000000.toInt())},enabled=hex.matches(Regex("[0-9A-F]{6}"))){Text("使用自定义颜色")}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick=onDismiss,modifier=Modifier.testTag("cancel-pen-preset")){Text("取消")}
                Button(onClick={onApply(draft,color,kind)},modifier=Modifier.testTag("apply-pen-width")){Text("使用这支笔")}
            }
        }
    }
}
