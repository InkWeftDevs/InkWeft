// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.inkweft.core.*
import org.inkweft.data.StudyNodeRow

@Composable internal fun ColumnScope.KnowledgeGraph(book:String,graph:KnowledgeQueries.LocalGraph,label:(TargetRef)->String,focus:(TargetRef)->Unit){
    var list by remember{mutableStateOf(false)};var view by remember{mutableStateOf<MindMapView?>(null)}
    var positions by remember(graph.nodes){mutableStateOf<Map<String,CanvasPoint>>(emptyMap())}
    fun key(r:TargetRef)=r.kind.name+":"+r.id
    val refs=graph.nodes.associateBy{key(it)}
    Row(Modifier.padding(horizontal=12.dp)){TextButton(onClick={list=!list}){Text(if(list)"图形视图"else"关系列表")};TextButton(onClick={view?.fit()}){Text("适配全部")}}
    if(list)LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp)){
        items(graph.nodes.toList(),key={key(it)}){ref->OutlinedCard(Modifier.fillMaxWidth()){
            Column(Modifier.padding(12.dp)){Text(label(ref));TextButton(onClick={focus(ref)}){Text("以此为中心")};graph.edges.filter{it.source==ref}.forEach{l->Text("${l.relation.label} → ${label(l.target)}")}}
        }}
    }else AndroidView(factory={MindMapView(it).also{v->view=v;v.contentDescription="局部关联图，箭头表示方向；可切换关系列表浏览。"}},update={v->
        val nodes=refs.entries.mapIndexed{i,(id,ref)->val p=positions[id]?:CanvasPoint((i%4)*280.0,(i/4)*150.0);StudyNodeRow(id,book,ref.id,null,p.x,p.y)}
        v.showRelations(nodes,refs.mapValues{label(it.value)},graph.edges.map{key(it.source) to key(it.target)})
        v.onMove={n,x,y->positions=positions+(n.id to CanvasPoint(x,y))};v.onOpen={n->refs[n.id]?.let(focus)}
    },modifier=Modifier.fillMaxWidth().weight(1f).testTag("knowledge-graph"))
}
