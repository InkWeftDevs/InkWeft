// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import org.inkweft.data.*

@Composable internal fun CardPropertiesDialog(card:StudyCardRow,current:KnowledgeData.Properties?,questions:List<KnowledgeRow>,enabled:Boolean,canClose:Boolean,available:Boolean,dismiss:()->Unit,openQuestion:(KnowledgeRow,Boolean)->Unit,save:(KnowledgeData)->Unit){
    var state by rememberSaveable(card.id){mutableStateOf(current?.state?:ManualState.INBOX)};var tags by rememberSaveable(card.id){mutableStateOf(current?.tags?.joinToString(",").orEmpty())};var question by rememberSaveable(card.id){mutableStateOf("")};var alias by rememberSaveable(card.id){mutableStateOf("")}
    var questionQuery by rememberSaveable(card.id){mutableStateOf("")}
    val matches=remember(questions,questionQuery){questions.filter{(it.data() as KnowledgeData.Question).prompt.contains(questionQuery.trim(),ignoreCase=true)}}
    AlertDialog(onDismissRequest={if(canClose)dismiss()},modifier=Modifier.testTag("card-properties-dialog"),title={Text(card.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(!available)Text("摘要卡已回收或不可用；未保存的草稿仍保留。",color=Quiet,modifier=Modifier.testTag("card-properties-unavailable"))
        Text("手工状态");ManualState.entries.forEach{s->FilterChip(state==s,{state=s},enabled=enabled,label={Text(s.label)})}
        OutlinedTextField(tags,{if(it.length<=240)tags=it},enabled=enabled,label={Text("标签，逗号分隔")},modifier=Modifier.testTag("card-properties-tags"))
        OutlinedTextField(alias,{if(it.length<=120)alias=it},enabled=enabled,label={Text("别名 · 用于候选提及")},modifier=Modifier.testTag("card-properties-alias"))
        TextButton(onClick={save(KnowledgeData.Alias(card.id,alias.trim()))},enabled=enabled&&alias.isNotBlank()){Text("添加别名")}
        OutlinedTextField(question,{if(it.length<=2000)question=it},enabled=enabled,label={Text("独立复习问题")},modifier=Modifier.testTag("card-properties-question"))
        TextButton(onClick={save(KnowledgeData.Question(card.id,question.trim()))},enabled=enabled&&question.isNotBlank(),modifier=Modifier.heightIn(min=48.dp).testTag("card-question-add")){Text("添加回忆题")}
        Text("已保存的回忆题",style=MaterialTheme.typography.titleSmall)
        OutlinedTextField(questionQuery,{if(it.length<=2000)questionQuery=it},enabled=canClose,
            label={Text("查找已保存的问题")},singleLine=true,
            trailingIcon={if(questionQuery.isNotEmpty())IconButton(onClick={questionQuery=""},enabled=canClose,
                modifier=Modifier.size(48.dp).describedAs("清空问题搜索").testTag("card-question-search-clear")){Glyph("close")}},
            modifier=Modifier.fillMaxWidth().testTag("card-question-search"))
        Text("显示 ${matches.size} / ${questions.size} 道已保存问题",color=Quiet,
            modifier=Modifier.testTag("card-question-search-count"))
        if(questions.isEmpty())Text("还没有独立回忆题。",color=Quiet)
        else if(matches.isEmpty())Text("没有匹配的问题。可清空搜索查看全部。",color=Quiet,
            modifier=Modifier.testTag("card-question-search-empty"))
        matches.forEach{row->key(row.id){val savedQuestion=row.data() as KnowledgeData.Question
            OutlinedCard(Modifier.fillMaxWidth().testTag("question-row-${row.id}")){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(savedQuestion.prompt,modifier=Modifier.testTag("question-prompt-${row.id}"))
                    Text("手工状态：${savedQuestion.state.label} · 修订 ${row.revision}",color=Quiet,modifier=Modifier.testTag("question-state-${row.id}"))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        TextButton(onClick={openQuestion(row,false)},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("question-edit-${row.id}")){Text("编辑")}
                        TextButton(onClick={openQuestion(row,true)},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("question-remove-${row.id}")){Text("移除")}
                    }
                }
            }
        }}
    }},confirmButton={TextButton(onClick={val values=tags.split(',', '，').map{it.trim()}.filter{it.isNotEmpty()}.distinct();save(KnowledgeData.Properties(card.id,state,values))},enabled=enabled&&tags.split(',', '，').filter{it.isNotBlank()}.let{it.size<=12&&it.all{tag->tag.trim().length<=24}},modifier=Modifier.testTag("card-properties-save")){Text("保存属性")}},dismissButton={TextButton(onClick=dismiss,enabled=canClose,modifier=Modifier.testTag("card-properties-cancel")){Text(if(enabled)"取消"else"关闭")}})
}
