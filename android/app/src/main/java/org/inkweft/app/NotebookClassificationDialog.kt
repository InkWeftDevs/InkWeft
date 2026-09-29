// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.data.WorkspaceRow

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NotebookClassificationDialog(row:WorkspaceRow,dismiss:()->Unit,suggestions:List<String> = emptyList(),save:(String,String)->Unit){
    var folder by remember(row.noteId){mutableStateOf(row.folder)}
    var tags by remember(row.noteId){mutableStateOf(row.tags.lines().filter{it.isNotBlank()})}
    var input by remember{mutableStateOf("")}
    val candidate=input.trim();val valid=candidate.isNotEmpty()&&candidate.length<=24&&candidate.none{it in ",，\n"}
    fun add(tag:String){if(tag !in tags&&tags.size<12)tags=tags+tag;input=""}
    EditorPanel("文件夹与标签","",dismiss,"notebook-classification",footer={TextButton({save(folder,(tags+listOfNotNull(candidate.takeIf{valid&&it !in tags&&tags.size<12})).joinToString("\n"))},modifier=Modifier.testTag("save-notebook-tags")){Text("完成")}}){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(folder,{if(it.length<=48)folder=it},label={Text("文件夹")},placeholder={Text("未分类")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("notebook-folder"))
            Text("标签 · ${tags.size}/12",color=Quiet)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){tags.forEach{tag->InputChip(true,{tags=tags-tag},label={Text(tag)},trailingIcon={Glyph("close",modifier=Modifier.size(14.dp))},modifier=Modifier.testTag("tag-chip-$tag").describedAs("移除标签 $tag"))}}
            OutlinedTextField(input,{if(it.length<=24)input=it},singleLine=true,placeholder={Text("输入标签")},modifier=Modifier.fillMaxWidth().testTag("notebook-tags"),trailingIcon={IconButton({add(candidate)},enabled=valid&&candidate !in tags&&tags.size<12,modifier=Modifier.testTag("tag-add").describedAs("添加标签")){Glyph("add")}})
            val available=suggestions.distinct().filter{it !in tags&&it.contains(candidate,true)}
            if(available.isNotEmpty()){Text("已有标签",color=Quiet);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){available.forEach{tag->SuggestionChip({add(tag)},label={Text(tag)},enabled=tags.size<12)}}}
        }
    }
}
