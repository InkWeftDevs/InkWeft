package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import org.inkweft.core.*

@Composable internal fun TapeSettingsCard(mode:Int,changeMode:(Int)->Unit,width:Float,changeWidth:(Float)->Unit,color:Int,changeColor:(Int)->Unit,
 pattern:TapePattern,changePattern:(TapePattern)->Unit,shown:Boolean,showAll:(Boolean)->Unit,clear:()->Unit,dismiss:()->Unit){
 val density=LocalDensity.current.density
 val position=remember(density){object:PopupPositionProvider{
  override fun calculatePosition(a:IntRect,w:IntSize,d:LayoutDirection,p:IntSize):IntOffset{
   val margin=(12*density).toInt();val x=if(a.right+p.width+margin<w.width)a.right+margin else a.left-p.width-margin
   return IntOffset(x.coerceIn(margin,(w.width-p.width-margin).coerceAtLeast(margin)),(a.top-p.height/2).coerceIn(margin,(w.height-p.height-margin).coerceAtLeast(margin)))
  }
 }}
 var confirmClear by remember{mutableStateOf(false)}
 Popup(position,onDismissRequest=dismiss,properties=PopupProperties(focusable=true)){
  Surface(Modifier.width(300.dp).heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.86f).testTag("tape-settings"),shape=RoundedCornerShape(20.dp),color=Color.White,shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
   Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Text("胶带",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(dismiss,modifier=Modifier.size(44.dp).testTag("tape-close").semantics{contentDescription="关闭胶带参数"}){Glyph("close")}}
    Row(verticalAlignment=Alignment.CenterVertically){Text("粗细",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(width.toInt().toString(),modifier=Modifier.testTag("tape-width-value"))}
    Canvas(Modifier.fillMaxWidth().height(40.dp).testTag("tape-width-preview")){val h=(width/192f*size.height*.85f).coerceAtLeast(2f);val n=drawContext.canvas.nativeCanvas.save();drawContext.canvas.nativeCanvas.translate(0f,(size.height-h)/2);TapeArt.draw(drawContext.canvas.nativeCanvas,PageObject("00000000-0000-0000-0000-000000000001",PageObjectKind.TAPE,0f,0f,size.width.coerceAtLeast(24f),h.coerceAtLeast(24f),color=color,lineWidth=h,tapePoints=listOf(TapePoint(h/2,h/2),TapePoint(size.width-h/2,h/2)),tapePattern=pattern));drawContext.canvas.nativeCanvas.restoreToCount(n)}
    Slider(width,{if(mode==1)changeMode(2);changeWidth(it)},valueRange=4f..192f,modifier=Modifier.testTag("tape-width").semantics{contentDescription="胶带粗细"})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(12f,24f,48f,96f).forEach{value->
     OutlinedButton({if(mode==1)changeMode(2);changeWidth(value)},Modifier.weight(1f).heightIn(min=48.dp).testTag("tape-width-${value.toInt()}"),contentPadding=PaddingValues(0.dp)){Text(value.toInt().toString())}
    }}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(2 to "自由画",0 to "画直线").forEach{(i,title)->
     OutlinedButton({changeMode(i)},Modifier.weight(1f).heightIn(min=48.dp).testTag("tape-mode-$i"),contentPadding=PaddingValues(0.dp),border=BorderStroke(if(mode==i)2.dp else 1.dp,if(mode==i)Forest else Line)){Text(title)}
    }}
    Row(verticalAlignment=Alignment.CenterVertically){Text("显示本页胶带",Modifier.weight(1f));Switch(shown,showAll,modifier=Modifier.testTag("tape-show-all"))}
    Text("样式",style=MaterialTheme.typography.labelLarge)
    TapePattern.entries.chunked(3).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{value->
     val title=when(value){TapePattern.STRIPES->"斜纹";TapePattern.SPARKLES->"星光";TapePattern.GRID->"网格";TapePattern.SOLID->"纯色";TapePattern.DOTS->"圆点";TapePattern.WAVES->"波纹"}
     Box(Modifier.weight(1f).height(48.dp).border(if(value==pattern)2.dp else 1.dp,if(value==pattern)Forest else Line,RoundedCornerShape(10.dp)).clickable{changePattern(value)}.testTag("tape-pattern-${value.name.lowercase()}").semantics{contentDescription=title;selected=value==pattern}.padding(5.dp),contentAlignment=Alignment.Center){
      Canvas(Modifier.fillMaxWidth().height(28.dp)){TapeArt.draw(drawContext.canvas.nativeCanvas,PageObject("00000000-0000-0000-0000-000000000001",PageObjectKind.TAPE,0f,0f,size.width.coerceAtLeast(24f),size.height.coerceAtLeast(24f),color=color,tapePattern=value))}
     }
    }}}
    Text("颜色",style=MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
     listOf(0xff91d8dc.toInt(),0xffb6dca7.toInt(),0xfff4aebc.toInt(),0xfff6da95.toInt(),0xffbba4de.toInt()).forEach{value->
      IconButton({changeColor(value)},modifier=Modifier.size(48.dp).testTag("tape-color-${Integer.toHexString(value)}").semantics{contentDescription="胶带颜色 ${Integer.toHexString(value)}";selected=value==color}){
       Box(Modifier.size(30.dp).background(Color(value),RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){if(value==color)Text("✓",color=TextInk)}
      }
     }
    }
    TextButton({changeMode(1)},Modifier.fillMaxWidth().testTag("tape-mode-1")){Text(if(mode==1)"区域遮盖 · 拖出矩形"else"改用矩形区域遮盖")}
    TextButton({confirmClear=true},Modifier.fillMaxWidth().testTag("tape-clear-page")){Text("清除本页胶带",color=Color(0xffb64149))}
   }
  }
 }
 if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除本页胶带？")},text={Text("可通过撤销恢复。")},confirmButton={TextButton({confirmClear=false;clear();dismiss()}){Text("清除")}},dismissButton={TextButton({confirmClear=false}){Text("取消")}})
}
