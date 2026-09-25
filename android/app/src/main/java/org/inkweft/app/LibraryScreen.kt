// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(ui:NotebookUi,workspace:WorkspaceViewModel,open:(Note)->Unit,create:()->Unit,importPage:()->Unit,diagnostics:()->Unit){
    val entries by workspace.entries.collectAsState()
    val counts by workspace.inkCounts.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var type by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(true) }
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var navOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceRow?>(null) }
    var removing by remember { mutableStateOf<WorkspaceRow?>(null) }
    val display=LinkedHashMap<String,Note>()
    ui.notes.forEach{display[it.id]=it};ui.drafts.values.forEach{d->display[d.base.id]=d.base.copy(title=d.title,text=d.text)}
    val all=display.values.toList()
    fun row(n:Note)=entries[n.id]?:WorkspaceRow(n.id)
    val active=all.filter{row(it).trashedAt==null}
    val folders=active.map{row(it).folder}.filter{it.isNotBlank()}.distinct().sorted()
    val tags=active.flatMap{row(it).tags.split('\n')}.filter{it.isNotBlank()}.distinct().sorted()
    val shown=all.filter{n->val r=row(n)
        (if(filter=="trash")r.trashedAt!=null else r.trashedAt==null) &&
        when{filter=="favorite"->r.favorite;filter=="unfiled"->r.folder.isBlank();filter.startsWith("folder:")->r.folder==filter.removePrefix("folder:");filter.startsWith("tag:")->filter.removePrefix("tag:") in r.tags.split('\n');else->true} &&
        (type=="all"||(type=="board")==r.world) && (query.isBlank()||n.title.contains(query,true)||n.text.contains(query,true)||r.tags.contains(query,true))}
        .let{list->if(byTitle)list.sortedBy{it.title.lowercase(Locale.ROOT)}else list}
    val title=when{filter=="favorite"->"已收藏";filter=="trash"->"回收站";filter=="unfiled"->"未分类";filter.startsWith("folder:")->filter.removePrefix("folder:");filter.startsWith("tag:")->"标签 · "+filter.removePrefix("tag:");else->"全部笔记"}
    val nav:@Composable ()->Unit={
        Column(Modifier.fillMaxHeight().width(224.dp).background(Side).padding(horizontal=12.dp).verticalScroll(rememberScrollState())){
            Row(Modifier.fillMaxWidth().height(82.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(color=Forest,shape=RoundedCornerShape(10.dp)){Box(Modifier.size(34.dp),contentAlignment=Alignment.Center){Text("墨",color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp)}}
                Spacer(Modifier.width(12.dp));Column{Text("墨织",fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text("你的本地学习资料库",fontSize=10.sp,color=Quiet)}
            }
            OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索标题与文字",fontSize=12.sp)},leadingIcon={Glyph("search")},modifier=Modifier.fillMaxWidth().testTag("library-search"))
            Spacer(Modifier.height(20.dp))
            NavigationLine("全部笔记","note",active.size,filter=="all"){filter="all";navOpen=false}
            NavigationLine("已收藏","star",active.count{row(it).favorite},filter=="favorite"){filter="favorite";navOpen=false}
            NavigationLine("未分类","folder",active.count{row(it).folder.isBlank()},filter=="unfiled"){filter="unfiled";navOpen=false}
            NavigationLine("回收站","trash",all.size-active.size,filter=="trash"){filter="trash";navOpen=false}
            HorizontalDivider(Modifier.padding(vertical=17.dp),color=Line)
            Text("文件夹",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,6.dp))
            if(folders.isEmpty())Text("从笔记菜单添加分类",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,8.dp))
            folders.forEach{folder->NavigationLine(folder,"folder",active.count{row(it).folder==folder},filter=="folder:$folder"){filter="folder:$folder";navOpen=false}}
            HorizontalDivider(Modifier.padding(vertical=17.dp),color=Line)
            Text("标签",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,6.dp))
            if(tags.isEmpty())Text("从笔记菜单添加标签",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,8.dp))
            tags.forEach{tag->NavigationLine(tag,"tag",null,filter=="tag:$tag"){filter="tag:$tag";navOpen=false}}
            Spacer(Modifier.height(30.dp))
            TextButton(onClick=diagnostics,modifier=Modifier.fillMaxWidth().testTag("open-diagnostics")){Glyph("diagnostics");Spacer(Modifier.width(8.dp));Text("诊断与导出",fontSize=12.sp)}
            Text("本地优先 · 无账号要求",fontSize=10.sp,color=Quiet,modifier=Modifier.padding(12.dp,10.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)){
        val wide=maxWidth>=840.dp
        Row(Modifier.fillMaxSize()){
            if(wide){nav();VerticalDivider(color=Line)}
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(wide)26.dp else 16.dp)){
                Row(Modifier.fillMaxWidth().heightIn(min=76.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                    if(!wide)IconButton(onClick={navOpen=true}){Glyph("menu")}
                    Text(title,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick={grid=!grid},modifier=Modifier.testTag("library-layout")){Glyph(if(grid)"list" else "grid")}
                    IconButton(onClick={byTitle=!byTitle},modifier=Modifier.testTag("library-sort")){Glyph("sort",if(byTitle)Forest else Quiet)}
                    if(!wide)IconButton(onClick=diagnostics,modifier=Modifier.testTag("open-diagnostics")){Glyph("diagnostics")}
                    TextButton(onClick=importPage,enabled=!ui.readFailed){Text("导入副本",fontSize=12.sp)}
                    Button(onClick=create,enabled=!ui.loading&&!ui.readFailed,modifier=Modifier.testTag("new-note"),contentPadding=PaddingValues(horizontal=18.dp,vertical=10.dp)){Glyph("add");Spacer(Modifier.width(6.dp));Text("新建")}
                }
                if(!wide)OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索标题与文字（不含未识别手写）",fontSize=12.sp)},leadingIcon={Glyph("search")},modifier=Modifier.fillMaxWidth().testTag("library-search"))
                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                    listOf("all" to "全部","page" to "纸张笔记","board" to "无界笔记").forEach{(id,label)->
                        Column(Modifier.clickable{type=id}.padding(end=20.dp).testTag("library-type-$id"),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=if(type==id)Forest else Quiet,fontSize=13.sp,modifier=Modifier.padding(vertical=13.dp));HorizontalDivider(Modifier.width(if(id=="all")28.dp else 60.dp),thickness=if(type==id)2.dp else 0.dp,color=if(type==id)Forest else Color.Transparent)}
                    }
                    Spacer(Modifier.weight(1f));Text("${shown.size} 份",fontSize=11.sp,color=Quiet)
                }
                if(ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(filter=="trash")Text("仅移出资料列表，笔迹和文字仍保留。可从菜单恢复。",fontSize=12.sp,color=Quiet,modifier=Modifier.padding(vertical=12.dp))
                val itemContent:@Composable (Note)->Unit={n->
                    val meta=row(n)
                    NoteTile(n,meta,counts[n.id]?.modifiedRevision?:0,counts[n.id]?.visibleCount?:0,grid,{if(meta.trashedAt==null)open(n)},
                        {workspace.organize(meta,favorite=!meta.favorite)},{editing=meta},{if(meta.trashedAt!=null)workspace.organize(meta,trash=false)else removing=meta})
                }
                if(grid)LazyVerticalGrid(columns=GridCells.Adaptive(165.dp),modifier=Modifier.weight(1f).testTag("library-grid"),contentPadding=PaddingValues(top=21.dp,bottom=30.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
                    if(filter!="trash"&&query.isBlank())item(key="new-tile"){NewTile(create)}
                    items(shown,key={it.id}){itemContent(it)}
                    if(shown.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Text(if(query.isNotBlank())"没有匹配的标题或文字。手写尚未识别。"else if(filter=="trash")"回收站为空。"else"新建一份纸张或无界笔记，开始记录。",color=Quiet,fontSize=13.sp,modifier=Modifier.padding(vertical=28.dp))}
                }else LazyColumn(Modifier.weight(1f).testTag("library-list"),contentPadding=PaddingValues(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(filter!="trash"&&query.isBlank())item{OutlinedButton(onClick=create,modifier=Modifier.fillMaxWidth()){Glyph("add");Text("新建笔记")}}
                    items(shown,key={it.id}){itemContent(it)}
                }
            }
        }
        if(!wide&&navOpen)Surface(Modifier.fillMaxSize().clickable{navOpen=false},color=Color.Black.copy(alpha=.15f)){
            Box(Modifier.fillMaxSize()){Surface(Modifier.width(256.dp).fillMaxHeight().clickable(enabled=false){},shadowElevation=10.dp){nav()}}
        }
    }
    editing?.let{row->
        var folder by remember(row.noteId){mutableStateOf(row.folder)};var label by remember(row.noteId){mutableStateOf(row.tags.replace('\n',','))}
        AlertDialog(onDismissRequest={editing=null},title={Text("文件夹与标签")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(folder,{if(it.length<=48)folder=it},label={Text("文件夹，留空为未分类")},singleLine=true)
            OutlinedTextField(label,{if(it.length<=240)label=it},label={Text("标签，以逗号分隔")})
            Text("现有内容不移动、不复制。一个文件夹，多枚标签。",fontSize=12.sp,color=Quiet)
        }},confirmButton={TextButton(onClick={workspace.organize(row,folder=folder,tags=label);editing=null}){Text("保存")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})
    }
    removing?.let{row->AlertDialog(onDismissRequest={removing=null},title={Text("移入回收站？")},text={Text("笔迹、文字和页面设置都会保留，可随时恢复。不会清空数据库。")},confirmButton={TextButton(onClick={workspace.organize(row,trash=true);removing=null}){Text("移入回收站")}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

@Composable
private fun NavigationLine(title:String,icon:String,count:Int?,selected:Boolean,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=46.dp).clip(RoundedCornerShape(7.dp)).background(if(selected)Leaf else Color.Transparent).clickable(onClick=onClick).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
        Glyph(icon,if(selected)Forest else Quiet);Text(title,fontSize=14.sp,color=if(selected)Forest else TextInk,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(count!=null)Text(count.toString(),fontSize=11.sp,color=Quiet)
    }
}
@Composable
private fun NewTile(create:()->Unit){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.height(185.dp).width(136.dp).border(1.dp,Color(0xff91b3a3),RoundedCornerShape(9.dp)).background(Color(0xfff8fbf9),RoundedCornerShape(9.dp)).clickable(onClick=create).testTag("new-note-tile"),contentAlignment=Alignment.Center){Glyph("add",Forest,Modifier.size(34.dp))}
        Text("新建",color=Forest,fontSize=14.sp,modifier=Modifier.padding(top=14.dp));Text("纸张 / 无界画布",fontSize=10.sp,color=Quiet,modifier=Modifier.padding(top=5.dp))
    }
}
@Composable
private fun NoteTile(note:Note,row:WorkspaceRow,inkRevision:Long,count:Int,grid:Boolean,open:()->Unit,favorite:()->Unit,classify:()->Unit,trash:()->Unit){
    var menu by remember{mutableStateOf(false)}
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val strokes by produceState<List<InkStroke>?>(null,note.id,inkRevision){value=try{withContext(Dispatchers.IO){app.inkRepository.read(note.id).strokes.filter{it.visible}.map{it.stroke}}}catch(_:Exception){null}}
    val date by produceState("",note.id,inkRevision,note.revision){val at=withContext(Dispatchers.IO){runCatching{app.workspaceRepository.modifiedAt(note.id)}.getOrDefault(0L)};value=if(at>0)SimpleDateFormat("yyyy/MM/dd",Locale.getDefault()).format(Date(at))else""}
    val cover:@Composable (Modifier)->Unit={m->
        Surface(m,shape=RoundedCornerShape(7.dp),color=Color.White,border=BorderStroke(1.dp,Line),shadowElevation=1.dp){
            Box(Modifier.fillMaxSize().clickable(onClick=open)){
                val loaded=strokes
                if(loaded!=null&&loaded.isNotEmpty())AndroidView(factory={c->InkCanvasView(c).apply{preview=true;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}},update={v->v.configure(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED},null);v.showStrokes(loaded)},modifier=Modifier.fillMaxSize())
                else PaperThumbnail(row.world,PaperStyle.entries.getOrElse(row.paper){PaperStyle.RULED})
                if(loaded?.isEmpty()!=false && note.text.isNotBlank())Text(note.text,fontSize=8.sp,lineHeight=13.sp,maxLines=10,color=Quiet,modifier=Modifier.padding(12.dp))
                if(row.favorite)Box(Modifier.align(Alignment.TopEnd).padding(7.dp).background(Color.White,RoundedCornerShape(6.dp)).padding(3.dp)){Glyph("star",Forest,Modifier.size(14.dp))}
                if(row.world)Text("无界",fontSize=9.sp,color=Forest,modifier=Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Leaf,RoundedCornerShape(4.dp)).padding(4.dp))
            }
        }
    }
    val info:@Composable ()->Unit={
        Row(verticalAlignment=Alignment.CenterVertically){Text(note.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp,modifier=Modifier.weight(1f).clickable(onClick=open));Box{IconButton(onClick={menu=true},modifier=Modifier.size(36.dp).testTag("note-menu-${note.id}")){Glyph("more",Quiet,Modifier.size(17.dp))};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
            if(row.trashedAt==null){DropdownMenuItem(text={Text(if(row.favorite)"取消收藏"else"收藏")},onClick={menu=false;favorite()});DropdownMenuItem(text={Text("文件夹与标签")},onClick={menu=false;classify()})}
            DropdownMenuItem(text={Text(if(row.trashedAt!=null)"恢复笔记"else"移入回收站")},onClick={menu=false;trash()})
        }}}
        Text(date+(if(count>0)" · $count 笔"else""),fontSize=10.sp,color=Quiet)
        if(row.folder.isNotBlank())Text(row.folder,fontSize=10.sp,color=Quiet,modifier=Modifier.padding(top=5.dp))
    }
    if(grid)Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.height(185.dp),contentAlignment=Alignment.Center){cover(Modifier.width(if(row.world)160.dp else 136.dp).height(if(row.world)135.dp else 185.dp))};Column(Modifier.fillMaxWidth().padding(top=7.dp)){info()}}
    else Row(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(10.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){cover(Modifier.size(61.dp,79.dp));Column(Modifier.weight(1f)){info()}}
}

@Composable
internal fun PaperThumbnail(board:Boolean,style:PaperStyle){
    Canvas(Modifier.fillMaxSize().background(Color.White)){
        val c=Color(0xffe7ede9);val gap=if(board)16.dp.toPx()else 11.dp.toPx();val inset=if(board)0f else 10.dp.toPx()
        if(style==PaperStyle.RULED||style==PaperStyle.GRID){var y=inset+gap;while(y<size.height-inset){drawLine(c,androidx.compose.ui.geometry.Offset(inset,y),androidx.compose.ui.geometry.Offset(size.width-inset,y),1f);y+=gap}}
        if(style==PaperStyle.GRID){var x=inset+gap;while(x<size.width-inset){drawLine(c,androidx.compose.ui.geometry.Offset(x,inset),androidx.compose.ui.geometry.Offset(x,size.height-inset),1f);x+=gap}}
        if(style==PaperStyle.DOTS){var y=inset+gap;while(y<size.height-inset){var x=inset+gap;while(x<size.width-inset){drawCircle(c,1.dp.toPx(),androidx.compose.ui.geometry.Offset(x,y));x+=gap};y+=gap}}
    }
}
