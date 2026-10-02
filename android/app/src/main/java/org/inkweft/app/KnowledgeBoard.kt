// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.inkweft.core.*
import org.inkweft.data.*

/** Independent card placements use the existing bounded native view, never author ink coordinates. */
@Composable internal fun ColumnScope.KnowledgeBoard(book:String,cards:List<StudyCardRow>,rows:List<KnowledgeRow>,enabled:Boolean,
    save:(KnowledgeData,KnowledgeRow?)->Unit,remove:(KnowledgeRow)->Unit,browseReady:Boolean=true){
    val readLock=rememberBookReadLock(book);val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    val editable=enabled&&!readOnly
    val guardKey=remember(book){"knowledge-board-${java.util.UUID.randomUUID()}"}
    DisposableEffect(readLock,guardKey){onDispose{readLock.guard("$guardKey-gesture",false)}}
    var mapId by rememberSaveable(book){mutableStateOf<String?>(null)}
    val maps=rows.filter{it.notebookId==book&&!it.removed&&it.data() is KnowledgeData.MapDefinition}
    val placements=rows.filter{r->r.notebookId==book&&!r.removed&&when(val d=r.data()){is KnowledgeData.Placement->mapId==null;is KnowledgeData.MapOccurrence->d.mapId==mapId;else->false}}
        .filter{r->cards.any{it.id==when(val d=r.data()){is KnowledgeData.Placement->d.cardId;is KnowledgeData.MapOccurrence->d.cardId;else->""}}}
    var view by remember{mutableStateOf<MindMapView?>(null)};var chosenId by rememberSaveable{mutableStateOf<String?>(null)};val chosen=placements.find{it.id==chosenId}
    var adding by rememberSaveable{mutableStateOf(false)}
    var parent by rememberSaveable{mutableStateOf<String?>(null)};var newMap by rememberSaveable{mutableStateOf(false)};var mapTitle by rememberSaveable{mutableStateOf("")}
    var reparentId by rememberSaveable{mutableStateOf<String?>(null)};var reparentRevision by rememberSaveable{mutableLongStateOf(0L)};val reparent=placements.find{it.id==reparentId}?.copy(revision=reparentRevision)
    var decorate by rememberSaveable{mutableStateOf(false)}
    var from by rememberSaveable{mutableStateOf<String?>(null)};var to by rememberSaveable{mutableStateOf<String?>(null)}
    val draft=adding||newMap||reparentId!=null||decorate
    ReadLockGuard(readLock,guardKey,blocked=draft,draft=draft)
    fun cardId(row:KnowledgeRow)=when(val d=row.data()){is KnowledgeData.Placement->d.cardId;is KnowledgeData.MapOccurrence->d.cardId;else->""}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(mapId==null,{mapId=null},enabled=browseReady&&!draft,label={Text("自由白板")})
        maps.forEach{m->FilterChip(mapId==m.id,{mapId=m.id},enabled=browseReady&&!draft,label={Text((m.data() as KnowledgeData.MapDefinition).title)})}
        TextButton(onClick={mapTitle="";newMap=true},enabled=editable){Text("新建独立脑图")}
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp)){
        TextButton(onClick={parent=null;adding=true},enabled=editable&&cards.isNotEmpty()){Text("放入共享卡片")}
        if(mapId==null)TextButton(onClick={from=null;to=null;decorate=true},enabled=editable&&placements.size>=2){Text("装饰连线")}
        TextButton(onClick={view?.fit()}){Text("适配全部")};TextButton(onClick={view?.zoom(1.2f)}){Text("放大")};TextButton(onClick={view?.zoom(1/1.2f)}){Text("缩小")}
    }
    Text(if(mapId==null)"白板位置与装饰线独立保存，不成为正式关系。手写请使用已有无界笔记。"else"此脑图独立保存层级与位置；卡片正文与其他视图共享。",Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=Quiet)
    AndroidView(factory={MindMapView(it).also{v->view=v;v.contentDescription="自由白板：拖动卡片摆放，点击查看；不创建语义关系。"}},update={v->
        v.enabledInput=browseReady;v.authorEditing=editable;v.onActive={readLock.guard("$guardKey-gesture",it)};v.show(placements.map{r->when(val d=r.data()){
            is KnowledgeData.Placement->StudyNodeRow(r.id,book,d.cardId,null,d.x,d.y,r.revision)
            is KnowledgeData.MapOccurrence->StudyNodeRow(r.id,book,d.cardId,d.parentId,d.x,d.y,r.revision)
            else->error("Unexpected placement")}},cards)
        if(mapId==null)v.decorations(rows.filter{!it.removed&&it.notebookId==book}.mapNotNull{it.data() as? KnowledgeData.Decoration}.map{it.from to it.to})
        v.onMove={n,x,y->if(readLock.canWrite&&editable)placements.find{it.id==n.id}?.let{r->val d=r.data();save(when(d){is KnowledgeData.Placement->d.copy(x=x,y=y);is KnowledgeData.MapOccurrence->d.copy(x=x,y=y);else->error("Unexpected placement")},r)}}
        v.onOpen={n->chosenId=placements.find{it.id==n.id}?.id}
    },modifier=Modifier.fillMaxWidth().weight(1f).testTag("knowledge-board"))
    if(adding)AlertDialog(onDismissRequest={if(browseReady)adding=false},title={Text("放入哪张卡片")},text={Column(Modifier.verticalScroll(rememberScrollState())){cards.forEach{c->TextButton(onClick={
        val x=40.0+(placements.size%3)*260;val y=80.0+(placements.size/3)*128
        save(mapId?.let{KnowledgeData.MapOccurrence(it,c.id,parent,x,y)}?:KnowledgeData.Placement(c.id,x,y),null);adding=false
    },enabled=editable){Text(c.title)}}}},confirmButton={TextButton(onClick={adding=false},enabled=browseReady){Text("取消")}})
    if(newMap)AlertDialog(onDismissRequest={if(browseReady)newMap=false},title={Text("新建独立脑图")},text={OutlinedTextField(mapTitle,{if(it.length<=120)mapTitle=it},enabled=editable,label={Text("脑图名称")})},confirmButton={TextButton(onClick={save(KnowledgeData.MapDefinition(mapTitle.trim()),null);newMap=false},enabled=editable&&mapTitle.isNotBlank()){Text("创建脑图")}},dismissButton={TextButton(onClick={newMap=false},enabled=browseReady){Text("取消")}})
    chosen?.let{r->val card=cards.find{it.id==cardId(r)}
        AlertDialog(onDismissRequest={if(browseReady)chosenId=null},title={Text(card?.title.orEmpty())},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(card?.body.orEmpty())
            if(r.data() is KnowledgeData.MapOccurrence){TextButton(onClick={parent=r.id;adding=true;chosenId=null},enabled=editable){Text("添加共享子主题")};TextButton(onClick={reparentId=r.id;reparentRevision=r.revision;chosenId=null},enabled=editable){Text("修改上级")}}
        }},confirmButton={TextButton(onClick={remove(r);chosenId=null},enabled=editable){Text("从此视图移除")}},dismissButton={TextButton(onClick={chosenId=null},enabled=browseReady){Text("关闭")}})}
    reparent?.let{r->val d=r.data() as KnowledgeData.MapOccurrence
        AlertDialog(onDismissRequest={if(browseReady)reparentId=null},title={Text("选择此脑图中的上级")},text={Column(Modifier.verticalScroll(rememberScrollState())){
            TextButton(onClick={save(d.copy(parentId=null),r);reparentId=null},enabled=editable){Text("无上级（根主题）")}
            placements.filter{it.id!=r.id}.forEach{candidate->TextButton(onClick={save(d.copy(parentId=candidate.id),r);reparentId=null},enabled=editable){Text(cards.find{it.id==cardId(candidate)}?.title.orEmpty())}}
        }},confirmButton={TextButton(onClick={reparentId=null},enabled=browseReady){Text("取消")}})
    }
    if(reparentId!=null&&reparent==null)AlertDialog(onDismissRequest={if(browseReady)reparentId=null},title={Text("主题已变化")},text={Text("原主题尚未读取或已移除，请取消后重新核对。")},confirmButton={TextButton({reparentId=null},enabled=browseReady){Text("取消")}})
    if(decorate)AlertDialog(onDismissRequest={if(browseReady)decorate=false},title={Text("白板装饰线")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text("只连接摆放位置，不建立知识关系。")
        placements.forEachIndexed{i,p->val label="${cards.find{it.id==cardId(p)}?.title} · 位置 ${i+1}";Text(label)
            Row{FilterChip(from==p.id,{from=p.id},enabled=editable,label={Text("起点")});FilterChip(to==p.id,{to=p.id},enabled=editable,label={Text("终点")})}}
        rows.filter{it.notebookId==book&&!it.removed&&it.data() is KnowledgeData.Decoration}.forEach{r->TextButton(onClick={remove(r);decorate=false},enabled=editable){Text("移除装饰线 · ${(r.data() as KnowledgeData.Decoration).label}")}}
    }},confirmButton={TextButton(onClick={save(KnowledgeData.Decoration(from!!,to!!),null);decorate=false},enabled=editable&&from!=null&&to!=null&&from!=to){Text("添加装饰线")}},dismissButton={TextButton(onClick={decorate=false},enabled=browseReady){Text("取消")}})
}
