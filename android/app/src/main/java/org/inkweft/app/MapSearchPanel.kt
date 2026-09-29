// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.inkweft.core.*

@Composable internal fun MapSearchPanel(current:MapRef,dismiss:()->Unit,view:(String,Boolean,MapSearchHit)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val flow=remember(current.notebookId){app.mapGraphs.observe(current.notebookId)}
    val scenes by flow.collectAsStateWithLifecycle(initialValue=emptyList())
    var query by rememberSaveable{mutableStateOf("")};var allMaps by rememberSaveable{mutableStateOf(false)}
    val results=remember(scenes,query,current,allMaps){MapSearch.find(scenes,query,current,allMaps)}
    androidx.compose.ui.window.Popup(alignment=Alignment.CenterEnd,onDismissRequest=dismiss,properties=androidx.compose.ui.window.PopupProperties(focusable=true)){
        Surface(Modifier.padding(12.dp).widthIn(max=420.dp).fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.82f).testTag("map-search-panel"),color=Color.White,shape=RoundedCornerShape(16.dp),shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
            Column(Modifier.padding(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("查找导图内容",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(dismiss,modifier=Modifier.describedAs("关闭导图查找")){Glyph("close")}}
                OutlinedTextField(query,{query=it.take(200)},label={Text("主题、摘要或已确认文字")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("map-content-query"))
                Row(verticalAlignment=Alignment.CenterVertically){FilterChip(!allMaps,{allMaps=false},label={Text("当前图")},modifier=Modifier.testTag("map-search-current"));Spacer(Modifier.width(8.dp));FilterChip(allMaps,{allMaps=true},label={Text("本笔记所有图")},modifier=Modifier.testTag("map-search-all"))}
                Text("只检索已保存文字，未识别的手写和图片不在结果中。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                LazyColumn(Modifier.weight(1f,false).testTag("map-search-results")){
                    items(results,key={it.ref.key+":"+it.nodeId}){hit->
                        TextButton({view(query,allMaps,hit)},modifier=Modifier.fillMaxWidth().testTag("map-hit-${hit.ref.key}-${hit.nodeId}")){
                            Column(Modifier.fillMaxWidth()){Text(hit.title,style=MaterialTheme.typography.titleSmall);Text((listOf(hit.mapTitle)+hit.branchPath).joinToString(" › "),style=MaterialTheme.typography.bodySmall);Text("${hit.matchedField}：${hit.snippet}",maxLines=3);Text("查看结果 · ${hit.sourceState}",style=MaterialTheme.typography.labelSmall)}
                        }
                    }
                    if(query.isNotBlank()&&results.isEmpty())item{Text("没有匹配的主题",Modifier.padding(vertical=16.dp))}
                }
            }
        }
    }
}
