// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.inkweft.core.*
import org.inkweft.data.StudyCardRow
import org.inkweft.data.StudyNodeRow

@Composable internal fun MapTemplateChoices(templates:List<KnowledgeData.MapTemplate>,enabled:Boolean,choose:(KnowledgeData.MapTemplate)->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    var category by rememberSaveable{mutableStateOf("全部")}
    OutlinedTextField(query,{query=it.take(120)},label={Text("查找模板")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("map-template-search"))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf("全部","基础结构","学习复习","阅读与规划","我的模板").forEach{label->FilterChip(category==label,{category=label},label={Text(label)},modifier=Modifier.testTag("map-template-category-$label"))}
    }
    val choices=templates.withIndex().filter{(i,t)->(category=="全部"||(if(i<MapTemplates.builtins.size)MapTemplates.category(t)else"我的模板")==category)&&(query.isBlank()||t.title.contains(query,true)||t.nodes.any{it.title.contains(query,true)})}
    if(choices.isEmpty())Text("没有匹配的模板，可换个关键词或分类。",style=MaterialTheme.typography.bodySmall)
    choices.forEach{(index,t)->OutlinedCard(onClick={choose(t)},enabled=enabled,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("map-template-$index")){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(t.title,style=MaterialTheme.typography.titleSmall)
            Text(MapTemplates.description(t),style=MaterialTheme.typography.bodySmall)
            Text("${MapLayouts.label(t.layout)} · ${t.nodes.size} 个结构主题 · 创建前可预览",style=MaterialTheme.typography.labelSmall,color=Quiet)
        }
    }}
}

@Composable internal fun MapTemplatePreview(template:KnowledgeData.MapTemplate){
    val definition=remember(template){MapTemplates.instantiate(template,"预览")}
    val cards=remember(definition){definition.structures.map{StudyCardRow(it.id,"",1,it.title,"")}}
    val nodes=remember(definition){definition.structures.map{StudyNodeRow(it.id,"",it.id,it.parentId,it.x,it.y,1)}}
    if(nodes.isNotEmpty())AndroidView(factory={context->MindMapView(context).apply{authorEditing=false;contentDescription="模板结构预览"
        addOnLayoutChangeListener{_,l,t,r,b,ol,ot,orr,ob->if(r>l&&b>t&&(orr==ol||ob==ot))fitOverview()}
    }},update={it.mapLayout=template.layout;it.show(nodes,cards,structuralCardIds=cards.map{c->c.id}.toSet())},modifier=Modifier.fillMaxWidth().height(220.dp).testTag("map-template-preview"))
    Text(MapTemplates.description(template),style=MaterialTheme.typography.bodySmall,color=Quiet)
}
