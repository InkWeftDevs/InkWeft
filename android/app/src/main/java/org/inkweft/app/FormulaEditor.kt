// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.inkweft.core.PageObject

@Composable internal fun FormulaPreview(text:String,textSize:Float=28f,color:Int=0xff24342f.toInt()){
    val layout=remember(text,textSize,color){runCatching{FormulaLayout.drawable(text,textSize,color)}.getOrNull()}
    if(layout==null)Text("公式格式未完成，请核对括号和命令",color=Color(0xffab3939),modifier=Modifier.testTag("formula-preview-error"))
    else Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color.White).testTag("formula-preview")){
        val canvas=drawContext.canvas.nativeCanvas;val saved=canvas.save()
        val scale=minOf(size.width/layout.intrinsicWidth,size.height/layout.intrinsicHeight,2f)
        canvas.translate(8f,(size.height-layout.intrinsicHeight*scale)/2f);canvas.scale(scale*.96f,scale*.96f);layout.draw(canvas);canvas.restoreToCount(saved)
    }
}

@Composable internal fun FormulaSourceField(value:String,change:(String)->Unit){
    var field by remember{mutableStateOf(TextFieldValue(value))}
    LaunchedEffect(value){if(field.text!=value)field=TextFieldValue(value,TextRange(value.length))}
    OutlinedTextField(field,{if(it.text.length<=4000){field=it;change(it.text)}},label={Text("公式（LaTeX）")},keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().heightIn(min=96.dp,max=180.dp).testTag("formula-source"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
        listOf(Triple("分式","\\frac{}{}",6),Triple("上标","^{}",2),Triple("下标","_{}",2),Triple("根号","\\sqrt{}",6),Triple("α","\\alpha ",7)).forEach{(label,insert,offset)->
            TextButton({val start=field.selection.min;val end=field.selection.max;val next=field.text.replaceRange(start,end,insert)
                if(next.length<=4000){field=TextFieldValue(next,TextRange(start+offset));change(next)}},modifier=Modifier.testTag("formula-insert-$label")){Text(label)}
        }
    }
}

@Composable internal fun FormulaEditor(original:PageObject,world:Boolean,dismiss:()->Unit,save:(PageObject)->Unit){
    var text by remember(original.id){mutableStateOf(original.text)}
    var font by remember(original.id){mutableFloatStateOf(original.fontSize)}
    var error by remember{mutableStateOf<String?>(null)}
    val candidate=remember(original,text,font,world){runCatching{FormulaLayout.edited(original,text,font,original.color,world)}}
    EditorPanel("编辑公式","",dismiss,"formula-editor",footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            TextButton(dismiss){Text("取消")}
            TextButton({candidate.onSuccess(save).onFailure{error=it.message}},enabled=candidate.isSuccess,modifier=Modifier.testTag("formula-save")){Text("保存")}
        }
    }){
        Column(Modifier.verticalScroll(rememberScrollState())){
            FormulaPreview(text,font,original.color);FormulaSourceField(text){text=it;error=null}
            if(original.erasures.isNotEmpty())Text("修改内容或字号会重新排版，并清除这条公式的局部擦除。",style=MaterialTheme.typography.bodySmall)
            Text("字号 ${font.toInt()}");Slider(font,{font=it},valueRange=12f..96f,modifier=Modifier.testTag("formula-size"))
            (error?:candidate.exceptionOrNull()?.message)?.let{Text(if(it.startsWith("FORMULA_"))"请核对公式格式或减小字号"else it,color=Color(0xffab3939))}
        }
    }
}
