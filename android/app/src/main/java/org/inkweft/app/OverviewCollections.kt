// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal class OverviewViewModel(val book:String,val repo:KnowledgeRepository,study:StudyRepository):ViewModel(){
    val marks=repo.observe().map{rows->rows.filter{it.notebookId==book&&!it.removed&&it.data() is KnowledgeData.PageMark}}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val excerpts=study.excerpts(book).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val busy=MutableStateFlow(false);val error=MutableStateFlow<String?>(null)
    private var pending:KnowledgeCommand?=null
    fun save(row:KnowledgeRow?,mark:KnowledgeData.PageMark,remove:Boolean=false){
        if(busy.value||pending!=null)return
        pending=KnowledgeCommand(UUID.randomUUID().toString(),book,row?.id?:UUID.randomUUID().toString(),row?.revision?:0,mark,remove);retry()
    }
    fun retry(){val command=pending?:return;if(busy.value)return;busy.value=true;error.value=null
        viewModelScope.launch{try{repo.submit(command);pending=null}catch(c:CancellationException){throw c}catch(_:Exception){
            if(runCatching{repo.lookup(command)}.getOrNull()!=null)pending=null else error.value="保存未完成，请重试"
        }finally{busy.value=false}}
    }
    class Factory(val book:String,val app:InkWeftApplication):ViewModelProvider.Factory{
        override fun<T:ViewModel>create(c:Class<T>):T{@Suppress("UNCHECKED_CAST")return OverviewViewModel(book,app.knowledge,app.study) as T}
    }
}

@Composable internal fun ColumnScope.OverviewCollections(book:String,tab:Int,pages:List<NotebookPageRow>,current:NotebookPageRow?,ready:Boolean,app:InkWeftApplication,
    openPage:(String)->Unit,openExcerpt:(ExcerptRow)->Unit,editExcerpt:(String)->Unit,newExcerpt:()->Unit){
    val vm:OverviewViewModel=viewModel(key="overview-$book",factory=OverviewViewModel.Factory(book,app))
    val marks by vm.marks.collectAsStateWithLifecycle();val excerpts by vm.excerpts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle();val error by vm.error.collectAsStateWithLifecycle()
    var query by remember(tab){mutableStateOf("")}
    var editor by remember{mutableStateOf<Pair<KnowledgeRow?,KnowledgeData.PageMark>?>(null)}
    val enabled=ready&&!busy&&error==null
    OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索${listOf("页面","大纲","页签","摘录")[tab]}")},
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).testTag("overview-filter"))
    error?.let{Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(it,Modifier.weight(1f));TextButton(vm::retry){Text("重试")}}}
    if(tab==3){
        val list=excerpts.filter{it.title.contains(query,true)||it.body.contains(query,true)}
        if(list.isEmpty())Text(if(query.isBlank())"框选笔迹，保存摘录后会显示在这里"else"没有匹配的摘录",Modifier.padding(20.dp),color=Quiet)
        LazyColumn(Modifier.weight(1f).testTag("overview-excerpts"),contentPadding=PaddingValues(horizontal=12.dp)){
            items(list,key={it.id}){item->val page=pages.find{it.id==item.pageId}
                Column(Modifier.fillMaxWidth().clickable(enabled=enabled&&page!=null){openExcerpt(item)}.padding(vertical=8.dp).testTag("overview-excerpt-${item.id}")){
                    Row(verticalAlignment=Alignment.CenterVertically){Text(item.title,Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis);TextButton({editExcerpt(item.id)},enabled=enabled,modifier=Modifier.testTag("excerpt-edit-${item.id}")){Text("编辑")}}
                    if(item.body.isNotBlank())Text(item.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=Quiet)
                    Text(page?.let{"第${it.position+1}页"}?:"原页已回收",style=MaterialTheme.typography.labelSmall,color=Quiet)
                };HorizontalDivider(color=Line)
            }
        }
        TextButton(newExcerpt,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("overview-new-excerpt")){Glyph("excerpt");Text("添加摘录",Modifier.padding(start=8.dp))}
    }else{
        val bookmark=tab==2
        val list=marks.filter{val m=it.data() as KnowledgeData.PageMark;m.bookmark==bookmark&&pages.any{p->p.id==m.pageId}&&m.title.contains(query,true)}
            .sortedWith(compareBy<KnowledgeRow>{r->pages.first{it.id==(r.data() as KnowledgeData.PageMark).pageId}.position}.thenBy{it.id})
        if(list.isEmpty())Text(if(query.isNotBlank())"没有匹配项"else if(bookmark)"给常用页面添加页签"else"将当前页加入大纲目录",Modifier.padding(20.dp),color=Quiet)
        LazyColumn(Modifier.weight(1f).testTag("overview-marks"),contentPadding=PaddingValues(horizontal=12.dp)){
            items(list,key={it.id}){row->val m=row.data() as KnowledgeData.PageMark;val page=pages.first{it.id==m.pageId};var menu by remember{mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable(enabled=enabled){openPage(m.pageId)}.padding(start=(m.depth*12).dp).testTag("overview-mark-${row.id}"),verticalAlignment=Alignment.CenterVertically){
                    Glyph(if(bookmark)"bookmark"else"list",if(page.id==current?.id)Forest else TextInk)
                    Text(m.title,Modifier.weight(1f).padding(start=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${page.position+1}",color=Quiet,style=MaterialTheme.typography.labelSmall)
                    Box{IconButton({menu=true},enabled=enabled,modifier=Modifier.testTag("mark-menu-${row.id}").describedAs("${m.title}操作")){Glyph("more")}
                        DropdownMenu(menu,{menu=false}){
                            DropdownMenuItem(text={Text("重命名")},onClick={menu=false;editor=row to m},modifier=Modifier.testTag("mark-rename"))
                            if(!bookmark){
                                DropdownMenuItem(text={Text("增加缩进")},enabled=m.depth<3,onClick={menu=false;vm.save(row,m.copy(depth=m.depth+1))})
                                DropdownMenuItem(text={Text("减少缩进")},enabled=m.depth>0,onClick={menu=false;vm.save(row,m.copy(depth=m.depth-1))})
                            }
                            DropdownMenuItem(text={Text(if(bookmark)"移除页签"else"移除目录项")},onClick={menu=false;vm.save(row,m,true)},modifier=Modifier.testTag("mark-remove"))
                        }
                    }
                };HorizontalDivider(color=Line)
            }
        }
        val exists=bookmark&&marks.any{(it.data() as KnowledgeData.PageMark).let{m->m.bookmark&&m.pageId==current?.id}}
        TextButton({current?.let{p->editor=null to KnowledgeData.PageMark(p.id,"第${p.position+1}页",bookmark)}},enabled=enabled&&current!=null&&!exists,
            modifier=Modifier.fillMaxWidth().testTag("overview-add-mark")){Glyph("add");Text(if(exists)"当前页已有页签"else if(bookmark)"为当前页添加页签"else"将当前页加入大纲",Modifier.padding(start=8.dp))}
    }
    editor?.let{(row,mark)->var title by remember(mark){mutableStateOf(mark.title)}
        AlertDialog(onDismissRequest={editor=null},title={Text(if(mark.bookmark)"页签名称"else"大纲标题")},text={OutlinedTextField(title,{if(it.length<=120)title=it},singleLine=true,modifier=Modifier.testTag("overview-mark-title"))},
            confirmButton={TextButton({vm.save(row,mark.copy(title=title.trim()));editor=null},enabled=title.isNotBlank(),modifier=Modifier.testTag("overview-mark-save")){Text("保存")}},dismissButton={TextButton({editor=null}){Text("取消")}})
    }
}
