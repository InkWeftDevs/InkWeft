package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import kotlin.math.roundToInt

@Composable internal fun BrushParameterControls(kind:InkPen,r:BrushRecipe,change:(BrushRecipe)->Unit) {
    @Composable fun slider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,tag:String,update:(Float)->Unit){
        Row{Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Text("${(value*100).roundToInt()}%",style=MaterialTheme.typography.bodyMedium)}
        Slider(value,onValueChange=update,valueRange=range,modifier=Modifier.height(36.dp).testTag(tag))
    }
    when(kind){
        InkPen.PEN->{
            slider("压感灵敏度",r.sensitivity,0f..1f,"pen-sensitivity"){change(r.copy(sensitivity=it))}
            Row(verticalAlignment=Alignment.CenterVertically){Text("圆润笔尖",Modifier.weight(1f));Switch(r.roundNib,{change(r.copy(roundNib=it))},modifier=Modifier.testTag("pen-round-nib"))}
            if(!r.roundNib)slider("笔尖扁度",r.sharpness,0f..1f,"pen-sharpness"){change(r.copy(sharpness=it))}
        }
        InkPen.PENCIL->{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("偏硬","HB","偏软").forEachIndexed{i,label->FilterChip(r.hardness==i,{change(r.copy(hardness=i))},label={Text(label)},modifier=Modifier.weight(1f).testTag("pencil-hardness-$i"))}}
            slider("浓度",r.density,.5f..1.3f,"pencil-density"){change(r.copy(density=it))}
            var advanced by remember{mutableStateOf(false)}
            TextButton(onClick={advanced=!advanced},modifier=Modifier.testTag("pencil-advanced")){Text(if(advanced)"收起颗粒与倾斜"else"颗粒与倾斜")}
            if(advanced){
                slider("颗粒大小",r.grain,.5f..2f,"pencil-grain"){change(r.copy(grain=it))}
                Row(verticalAlignment=Alignment.CenterVertically){Text("倾斜铺色",Modifier.weight(1f));Switch(r.tiltShading,{change(r.copy(tiltShading=it))},modifier=Modifier.testTag("pencil-tilt"))}
            }
        }
        else->Unit
    }
}
