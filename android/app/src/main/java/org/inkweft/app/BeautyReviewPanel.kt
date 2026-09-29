// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.TextFont

@Composable internal fun BeautyReviewPanel(value:BeautyReview,vm:PageObjectViewModel){
    var text by remember(value.inkRevision,value.objectRevision){mutableStateOf(value.result.text)}
    EditorPanel("校对美化",if(value.options.preserveLayout)"原位换字体"else"段落整理",vm::dismissBeauty,"beauty-review",footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            TextButton(vm::dismissBeauty,modifier=Modifier.testTag("beauty-review-cancel")){Text("保留原迹")}
            TextButton(vm::acceptBeauty,enabled=value.candidate!=null,modifier=Modifier.testTag("beauty-review-apply")){Text("应用")}
        }
    }){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            value.reason?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Quiet)}
            OutlinedTextField(text,{if(it.length<=4000){text=it;vm.reviseBeauty(it,value.options)}},label={Text("核对文字")},modifier=Modifier.fillMaxWidth().heightIn(min=88.dp,max=144.dp).testTag("beauty-review-text"))
            Row{
                FilterChip(value.options.preserveLayout,{vm.reviseBeauty(text,value.options.copy(preserveLayout=true))},label={Text("原位")},modifier=Modifier.testTag("beauty-review-in-place"))
                Spacer(Modifier.width(8.dp))
                FilterChip(!value.options.preserveLayout,{vm.reviseBeauty(text,value.options.copy(preserveLayout=false))},label={Text("段落")},modifier=Modifier.testTag("beauty-review-paragraph"))
            }
            Row(Modifier.horizontalScroll(rememberScrollState())){TextFont.entries.forEach{font->FilterChip(value.options.font==font,{vm.reviseBeauty(text,value.options.copy(font=font))},label={Text(TextStyles.name(font))},modifier=Modifier.testTag("beauty-review-font-${font.name}"))}}
            if(!value.options.preserveLayout){
                Text("字号 ${value.options.size.toInt()}",style=MaterialTheme.typography.bodySmall)
                Slider(value.options.size,{vm.reviseBeauty(text,value.options.copy(size=it))},valueRange=12f..96f,modifier=Modifier.testTag("beauty-review-size"))
                Text("行距 %.1f×".format(value.options.spacing),style=MaterialTheme.typography.bodySmall)
                Slider(value.options.spacing,{vm.reviseBeauty(text,value.options.copy(spacing=it))},valueRange=1f..2f,modifier=Modifier.testTag("beauty-review-spacing"))
            }
            TextButton({vm.previewBeauty(!value.preview)},modifier=Modifier.testTag("beauty-review-compare")){Text(if(value.preview)"查看原迹"else"查看纸面结果")}
        }
    }
}
