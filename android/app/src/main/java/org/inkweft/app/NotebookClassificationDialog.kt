// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.data.WorkspaceRow

@Composable internal fun NotebookClassificationDialog(row:WorkspaceRow,dismiss:()->Unit,save:(String,String)->Unit){
    var folder by remember(row.noteId){mutableStateOf(row.folder)}
    var tags by remember(row.noteId){mutableStateOf(row.tags.replace('\n',','))}
    AlertDialog(onDismissRequest=dismiss,title={Text("文件夹与标签")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(folder,{if(it.length<=48)folder=it},label={Text("文件夹，留空为未分类")},singleLine=true,modifier=Modifier.testTag("notebook-folder"))
            OutlinedTextField(tags,{if(it.length<=240)tags=it},label={Text("标签，以逗号分隔")},modifier=Modifier.testTag("notebook-tags"))
        }
    },confirmButton={TextButton(onClick={save(folder,tags)},modifier=Modifier.testTag("save-notebook-tags")){Text("保存")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
