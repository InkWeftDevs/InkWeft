// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(ui:NotebookUi,workspace:WorkspaceViewModel,open:(Note)->Unit,create:()->Unit,importPage:()->Unit,diagnostics:()->Unit,rename:(Note)->Unit,duplicate:(Note)->Unit,export:(Note)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val summaryFlow=remember(app){app.knowledge.cards()};val summaries by summaryFlow.collectAsState(initial=emptyList())
    var destination by rememberSaveable{mutableStateOf("")};var learningNote by remember{mutableStateOf<Note?>(null)}
    var learningQuery by remember{mutableStateOf("")}
    val entries by workspace.entries.collectAsState();val counts by workspace.inkCounts.collectAsState()
    val searchRows by workspace.searchable.collectAsState()
    var filter by rememberSaveable{mutableStateOf("all")};var type by rememberSaveable{mutableStateOf("all")}
    var query by rememberSaveable{mutableStateOf("")};var grid by rememberSaveable{mutableStateOf(true)};var byTitle by rememberSaveable{mutableStateOf(false)}
    val drawer=rememberDrawerState(DrawerValue.Closed);val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var drawerJob by remember{mutableStateOf<Job?>(null)}
    var editing by remember{mutableStateOf<WorkspaceRow?>(null)};var removing by remember{mutableStateOf<WorkspaceRow?>(null)}
    var coverTargetId by rememberSaveable{mutableStateOf<String?>(null)}
    var coverExpected by rememberSaveable{mutableLongStateOf(0)}
    val coverTarget=coverTargetId?.let{id->ui.notes.find{it.id==id}?.let{it to (entries[id]?:WorkspaceRow(id)).copy(revision=coverExpected)}}
    var coverBusy by remember{mutableStateOf(false)};var coverError by remember{mutableStateOf<String?>(null)}
    fun closeDrawer(){drawerJob?.cancel();drawerJob=scope.launch{drawer.close()}}
    fun choose(value:String){filter=value;closeDrawer()}
    BackHandler(enabled=drawer.isOpen||drawer.targetValue==DrawerValue.Open){closeDrawer()}
    val display=LinkedHashMap<String,Note>();ui.notes.forEach{display[it.id]=it};ui.drafts.values.forEach{display[it.base.id]=it.base.copy(title=it.title,text=it.text)}
    val all=display.values.toList()
    fun row(n:Note)=entries[n.id]?:WorkspaceRow(n.id)
    val active=all.filter{row(it).trashedAt==null}
    val folders=active.map{row(it).folder}.filter{it.isNotBlank()}.distinct().sorted()
    val tags=active.flatMap{row(it).tags.split('\n')}.filter{it.isNotBlank()}.distinct().sorted()
    val shown=all.filter{n->val r=row(n)
        (if(filter=="trash")r.trashedAt!=null else r.trashedAt==null)&&
        when{filter=="favorite"->r.favorite;filter=="unfiled"->r.folder.isBlank();filter.startsWith("folder:")->r.folder==filter.removePrefix("folder:");filter.startsWith("tag:")->filter.removePrefix("tag:") in r.tags.split('\n');else->true}&&
        (type=="all"||(type=="board")==r.world)&&(query.isBlank()||n.title.contains(query,true)||n.text.contains(query,true)||r.tags.contains(query,true)||summaries.any{it.notebookId==n.id&&it.trashedAt==null&&(it.title.contains(query,true)||it.body.contains(query,true))}||searchRows.any{it.notebookId==n.id&&it.text.contains(query,true)})}.let{ShelfOrder.arrange(it,{n->row(n).pinned},{n->n.title},byTitle)}
    val title=when{filter=="favorite"->"已收藏";filter=="trash"->"回收站";filter=="unfiled"->"未分类";filter.startsWith("folder:")->filter.removePrefix("folder:");filter.startsWith("tag:")->"标签 · "+filter.removePrefix("tag:");else->"全部笔记"}
    val nav:@Composable (Boolean)->Unit={search->
        Column(Modifier.fillMaxHeight().width(224.dp).background(Side).padding(horizontal=12.dp).verticalScroll(rememberScrollState())){
            Row(Modifier.fillMaxWidth().height(82.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(color=Forest,shape=RoundedCornerShape(10.dp)){Box(Modifier.size(34.dp),contentAlignment=Alignment.Center){Text("墨",color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp)}}
                Spacer(Modifier.width(12.dp));Column{Text("墨织",fontSize=19.sp,fontWeight=FontWeight.SemiBold);Text("你的本地学习资料库",fontSize=10.sp,color=Quiet)}
            }
            Spacer(Modifier.height(20.dp))
            NavigationLine("资料库","note",null,destination.isEmpty()){destination="";choose("all")}
            NavigationLine("学习","learn",null,false){closeDrawer();destination="learn"}
            NavigationLine("复习","review",null,false){closeDrawer();destination="review"}
            HorizontalDivider(Modifier.padding(vertical=12.dp),color=Line)
            NavigationLine("全部笔记","note",active.size,filter=="all"){choose("all")}
            NavigationLine("已收藏","star",active.count{row(it).favorite},filter=="favorite"){choose("favorite")}
            NavigationLine("未分类","folder",active.count{row(it).folder.isBlank()},filter=="unfiled"){choose("unfiled")}
            NavigationLine("回收站","trash",all.size-active.size,filter=="trash"){choose("trash")}
            HorizontalDivider(Modifier.padding(vertical=17.dp),color=Line)
            Text("文件夹",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,6.dp))
            if(folders.isEmpty())Text("从笔记菜单添加分类",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,8.dp))
            folders.forEach{folder->NavigationLine(folder,"folder",active.count{row(it).folder==folder},filter=="folder:$folder"){choose("folder:$folder")}}
            HorizontalDivider(Modifier.padding(vertical=17.dp),color=Line)
            Text("标签",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,6.dp))
            if(tags.isEmpty())Text("从笔记菜单添加标签",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(12.dp,8.dp))
            tags.forEach{tag->NavigationLine(tag,"tag",null,filter=="tag:$tag"){choose("tag:$tag")}}
            Spacer(Modifier.height(30.dp))
            NavigationLine("设置与数据","settings",null,false){closeDrawer();destination="settings"}
            TextButton(onClick={closeDrawer();diagnostics()},modifier=Modifier.fillMaxWidth().testTag(if(search)"open-diagnostics"else"drawer-diagnostics")){Glyph("diagnostics");Spacer(Modifier.width(8.dp));Text("诊断与导出",fontSize=12.sp)}
            Text("本地优先 · 无账号要求",fontSize=10.sp,color=Quiet,modifier=Modifier.padding(12.dp,10.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)){
        val wide=maxWidth>=840.dp
        val compact=maxWidth<600.dp
        var headerMore by remember{mutableStateOf(false)}
        LaunchedEffect(wide){if(wide)drawer.close()}
        // Platform motion settings remain authoritative. No custom loop, no forced animation scale.
        ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=!wide&&drawer.isOpen,drawerContent={
            if(!wide)ModalDrawerSheet(Modifier.width(272.dp).testTag("library-drawer"),drawerContainerColor=Side,drawerTonalElevation=0.dp){
                Box(Modifier.fillMaxSize()){nav(false);IconButton(onClick=::closeDrawer,modifier=Modifier.align(Alignment.TopEnd).padding(top=18.dp).testTag("close-library-drawer").describedAs("收起分类")){Glyph("close")}}
            }
        }){
            Row(Modifier.fillMaxSize()){
                if(wide){nav(true);VerticalDivider(color=Line)}
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(wide)26.dp else 16.dp)){
                    Row(Modifier.fillMaxWidth().heightIn(min=76.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        if(!wide)IconButton(onClick={focus.clearFocus();keyboard?.hide();if(drawer.targetValue==DrawerValue.Closed){drawerJob?.cancel();drawerJob=scope.launch{drawer.open()}}},modifier=Modifier.testTag("open-library-drawer").describedAs("展开分类")){Glyph("menu")}
                        Text(title,fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(compact){
                            Box{
                                IconButton(onClick={headerMore=true},modifier=Modifier.testTag("library-more").describedAs("资料库更多操作")){Glyph("more")}
                                DropdownMenu(expanded=headerMore,onDismissRequest={headerMore=false}){
                                    DropdownMenuItem(text={Text(if(grid)"切换列表视图"else"切换网格视图")},onClick={grid=!grid;headerMore=false},modifier=Modifier.testTag("library-layout"))
                                    DropdownMenuItem(text={Text(if(byTitle)"按最近修改排序"else"按标题排序")},onClick={byTitle=!byTitle;headerMore=false},modifier=Modifier.testTag("library-sort"))
                                    DropdownMenuItem(text={Text("导入副本")},enabled=!ui.readFailed,onClick={headerMore=false;importPage()})
                                    DropdownMenuItem(text={Text("诊断与导出")},onClick={headerMore=false;diagnostics()},modifier=Modifier.testTag("open-diagnostics"))
                                }
                            }
                        }else{
                            IconButton(onClick={grid=!grid},modifier=Modifier.testTag("library-layout").describedAs(if(grid)"切换列表视图"else"切换网格视图")){Glyph(if(grid)"list"else"grid")}
                            IconButton(onClick={byTitle=!byTitle},modifier=Modifier.testTag("library-sort").describedAs(if(byTitle)"改按最近修改排序"else"改按标题排序")){Glyph("sort",if(byTitle)Forest else Quiet)}
                            if(!wide)IconButton(onClick=diagnostics,modifier=Modifier.testTag("open-diagnostics").describedAs("诊断与导出")){Glyph("diagnostics")}
                            TextButton(onClick=importPage,enabled=!ui.readFailed){Text("导入副本",fontSize=12.sp)}
                        }
                        Button(onClick=create,enabled=!ui.loading&&!ui.readFailed,modifier=Modifier.testTag("new-note"),contentPadding=PaddingValues(horizontal=if(compact)12.dp else 18.dp,vertical=10.dp)){Glyph("add");Spacer(Modifier.width(6.dp));Text("新建",maxLines=1)}
                    }
                    OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("搜索标题、文字、人工索引和摘要",fontSize=12.sp)},leadingIcon={Glyph("search")},modifier=Modifier.fillMaxWidth().testTag("library-search"))
                    Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                        listOf("all" to "全部","page" to "纸张笔记","board" to "无界笔记").forEach{(id,label)->Column(Modifier.heightIn(min=48.dp).clickable{type=id}.padding(end=20.dp).testTag("library-type-$id"),horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=if(type==id)Forest else Quiet,fontSize=13.sp,modifier=Modifier.padding(vertical=13.dp));HorizontalDivider(Modifier.width(if(id=="all")28.dp else 60.dp),thickness=if(type==id)2.dp else 0.dp,color=if(type==id)Forest else Color.Transparent)}}
                        Spacer(Modifier.weight(1f));Text("${shown.size} 份",fontSize=11.sp,color=Quiet)
                    }
                    if(ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(filter=="trash")Text("笔记内容与封面仍保留，可从菜单恢复。",fontSize=12.sp,color=Quiet,modifier=Modifier.padding(vertical=12.dp))
                    val itemContent:@Composable (Note)->Unit={n->val meta=row(n)
                        NoteTile(n,meta,counts[n.id]?.modifiedRevision?:0,counts[n.id]?.visibleCount?:0,grid,
                            {if(meta.trashedAt==null){val hit=searchRows.firstOrNull{it.notebookId==n.id&&query.isNotBlank()&&it.text.contains(query,true)};if(hit!=null)workspace.openSearchPage(n.id,hit.pageId){open(n)}else if(query.isNotBlank()&&summaries.any{it.notebookId==n.id&&it.trashedAt==null&&(it.title.contains(query,true)||it.body.contains(query,true))}){learningQuery=query;learningNote=n}else open(n)}},{rename(n)},{coverError=null;coverExpected=meta.revision;coverTargetId=n.id},
                            {workspace.organize(meta,favorite=!meta.favorite)},{editing=meta},{if(meta.trashedAt!=null)workspace.organize(meta,trash=false)else removing=meta},
                            {duplicate(n)},{export(n)},{workspace.pin(meta,!meta.pinned)})
                    }
                    if(grid)LazyVerticalGrid(columns=GridCells.Adaptive(165.dp),modifier=Modifier.weight(1f).testTag("library-grid"),contentPadding=PaddingValues(top=21.dp,bottom=30.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
                        if(filter!="trash"&&query.isBlank())item(key="new-tile"){NewTile(create)}
                        items(shown,key={it.id}){itemContent(it)}
                        if(shown.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Text(if(query.isNotBlank())"没有匹配的标题或文字。覆盖标题、键入文字、人工索引和独立摘要；自动手写识别尚未接入，旧索引不参与搜索。"else if(filter=="trash")"回收站为空。"else"新建一份纸张或无界笔记，开始记录。",color=Quiet,fontSize=13.sp,modifier=Modifier.padding(vertical=28.dp))}
                    }else LazyColumn(Modifier.weight(1f).testTag("library-list"),contentPadding=PaddingValues(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        if(filter!="trash"&&query.isBlank())item{OutlinedButton(onClick=create,modifier=Modifier.fillMaxWidth()){Glyph("add");Text("新建笔记")}}
                        items(shown,key={it.id}){itemContent(it)}
                    }
                }
            }
        }
    }
    if(destination in listOf("learn","review"))LearningLibrary(active,destination=="review",{destination=""}){learningQuery="";learningNote=it}
    if(destination=="settings")WorkspaceSettings({destination=""}){destination="";diagnostics()}
    learningNote?.let{n->
        if(destination=="review")KnowledgeWorkspace(n.id,TargetRef(TargetKind.NOTE,n.id),initialTab=4,dismiss={learningNote=null}){target->app.openKnowledgeTarget.value=target;learningNote=null;destination=""}
        else StudyWorkspace(NoteDraft(n),null,{learningNote=null},initialQuery=learningQuery){source->workspace.openSearchPage(n.id,source.pageId){open(n)};learningNote=null;destination="";true}
    }
    coverTarget?.let{(n,r)->key(n.id){
        var coverLoading by remember{mutableStateOf(true)};var storedCover by remember{mutableStateOf<ByteArray?>(null)}
        var coverReadFailed by remember{mutableStateOf(false)}
        LaunchedEffect(n.id){try{storedCover=withContext(Dispatchers.IO){app.workspaceRepository.customCover(n.id)}}catch(c:CancellationException){throw c}catch(_:Exception){coverReadFailed=true;coverError="封面读取失败，请关闭后重试。"}finally{coverLoading=false}}
        if(coverLoading||coverReadFailed)AlertDialog(onDismissRequest={coverTargetId=null},title={Text("读取封面")},text={Text(if(coverReadFailed)"封面读取失败，请关闭后重试。"else"正在读取已保存的设计…")},confirmButton={TextButton(onClick={coverTargetId=null}){Text("关闭")}})
        else CoverPickerDialog(n.title,r.world,NotebookCover.fromKey(r.coverKey),coverBusy,coverError,{coverTargetId=null},storedCover){choice,custom->
        coverBusy=true
        scope.launch{try{if(workspace.cover(r,choice,custom))coverTargetId=null else coverError="设置已被其他操作更新，未覆盖。请关闭并重新打开，查看最新封面。"}
        catch(c:CancellationException){throw c}catch(e:IllegalArgumentException){coverError=if(e.message=="COVER_LIBRARY_BUDGET")"封面图片空间已达 32 MB；请先移除不需要的图片封面。"else"封面数据无效，未保存。请重新选择图片或样式。"}catch(_:Exception){coverError="封面结果待核对；可以重试相同选择。笔记正文与笔迹不受影响。"}finally{coverBusy=false}}
    }}}
    editing?.let{r->
        var folder by remember(r.noteId){mutableStateOf(r.folder)};var label by remember(r.noteId){mutableStateOf(r.tags.replace('\n',','))}
        AlertDialog(onDismissRequest={editing=null},title={Text("文件夹与标签")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(folder,{if(it.length<=48)folder=it},label={Text("文件夹，留空为未分类")},singleLine=true)
            OutlinedTextField(label,{if(it.length<=240)label=it},label={Text("标签，以逗号分隔")})
            Text("现有内容不移动、不复制。一个文件夹，多枚标签。",fontSize=12.sp,color=Quiet)
        }},confirmButton={TextButton(onClick={workspace.organize(r,folder=folder,tags=label);editing=null}){Text("保存")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})
    }
    removing?.let{r->AlertDialog(onDismissRequest={removing=null},modifier=Modifier.testTag("trash-note-dialog"),title={Text("移入回收站？")},text={Text("笔迹、文字和封面都会保留，可随时恢复。不会清空数据库。")},confirmButton={TextButton(onClick={workspace.organize(r,trash=true);removing=null},modifier=Modifier.testTag("confirm-trash-${r.noteId}")){Text("移入回收站")}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

@Composable
private fun NavigationLine(title:String,icon:String,count:Int?,selected:Boolean,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(7.dp)).background(if(selected)Leaf else Color.Transparent).clickable(onClick=onClick).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
        Glyph(icon,if(selected)Forest else Quiet);Text(title,fontSize=14.sp,color=if(selected)Forest else TextInk,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(count!=null)Text(count.toString(),fontSize=11.sp,color=Quiet)
    }
}
@Composable
private fun NewTile(create:()->Unit){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.height(185.dp).width(136.dp).border(1.dp,Color(0xff91b3a3),RoundedCornerShape(9.dp)).background(Color(0xfff8fbf9),RoundedCornerShape(9.dp)).clickable(onClick=create).testTag("new-note-tile").describedAs("新建纸张或无界笔记"),contentAlignment=Alignment.Center){Glyph("add",Forest,Modifier.size(34.dp))}
        Text("新建",color=Forest,fontSize=14.sp,modifier=Modifier.padding(top=14.dp));Text("纸张 / 无界画布",fontSize=10.sp,color=Quiet,modifier=Modifier.padding(top=5.dp))
    }
}
@Composable
private fun NoteTile(note:Note,row:WorkspaceRow,inkRevision:Long,count:Int,grid:Boolean,open:()->Unit,rename:()->Unit,cover:()->Unit,favorite:()->Unit,classify:()->Unit,trash:()->Unit,duplicate:()->Unit,export:()->Unit,pin:()->Unit){
    var menu by remember{mutableStateOf(false)}
    val menuFocus=LocalFocusManager.current
    val menuKeyboard=LocalSoftwareKeyboardController.current
    val menuView=LocalView.current;val menuScope=rememberCoroutineScope()
    var menuOpening by remember{mutableStateOf(false)}
    fun openMenu(){if(menuOpening)return;menuOpening=true;menuScope.launch{
        try{
            menuFocus.clearFocus(force=true);menuKeyboard?.hide()
            menuView.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
            // Let the editor window finish hiding its IME before a focusable popup takes control.
            withTimeoutOrNull(1500){while(menuView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true)withFrameNanos{}}
            menu=true
        }finally{menuOpening=false}
    }}
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val style=NotebookCover.fromKey(row.coverKey)
    // Decorated covers do not load or decode the author's strokes just to draw the shelf.
    val pagePreview by produceState<Pair<NotebookPageRow,List<InkStroke>>?>(null,note.id,inkRevision,style,row.revision){
        value=if(style!=NotebookCover.CONTENT)null else try{withContext(Dispatchers.IO){
            val first=app.pages.activePages(note.id).firstOrNull()
            first?.let{it to InkSession(app.inkRepository.read(it.id)).visibleDraft()}
        }}catch(c:CancellationException){throw c}catch(_:Exception){null}
    }
    val customCover by produceState<CustomCover?>(null,note.id,row.revision,style){
        value=if(style!=NotebookCover.CUSTOM)null else try{withContext(Dispatchers.IO){app.workspaceRepository.customCover(note.id)?.let(CustomCoverCodec::decode)}}catch(c:CancellationException){throw c}catch(_:Exception){null}
    }
    val strokes=pagePreview?.second
    val date by produceState("",note.id,inkRevision,note.revision){val at=withContext(Dispatchers.IO){runCatching{app.workspaceRepository.modifiedAt(note.id)}.getOrDefault(0L)};value=if(at>0)SimpleDateFormat("yyyy/MM/dd",Locale.getDefault()).format(Date(at))else""}
    val art:@Composable (Modifier)->Unit={m->
        Surface(m,shape=RoundedCornerShape(7.dp),color=Color.White,border=BorderStroke(1.dp,Line),shadowElevation=1.dp){
            Box(Modifier.fillMaxSize().clickable(onClick=open).testTag("note-cover-${note.id}").describedAs("打开笔记：${note.title}；封面：${style.label()}")){
                if(style==NotebookCover.CUSTOM){customCover?.let{CustomCoverArt(it,note.title,Modifier.fillMaxSize())}?:Text("封面暂不可用",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(8.dp))}
                else if(style!=NotebookCover.CONTENT)NotebookCoverArt(style,note.id,note.title,row.world,Modifier.fillMaxSize())
                else{
                    val loaded=strokes
                    if(loaded!=null&&loaded.isNotEmpty())AndroidView(factory={c->InkCanvasView(c).apply{preview=true;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}},update={v->v.configure(row.world,PaperStyle.entries.getOrElse(pagePreview?.first?.paper?:row.paper){PaperStyle.RULED},null);v.showStrokes(loaded)},modifier=Modifier.fillMaxSize())
                    else PaperThumbnail(row.world,PaperStyle.entries.getOrElse(pagePreview?.first?.paper?:row.paper){PaperStyle.RULED})
                    if(loaded?.isEmpty()!=false&&note.text.isNotBlank())Text(note.text,fontSize=8.sp,lineHeight=13.sp,maxLines=10,color=Quiet,modifier=Modifier.padding(12.dp))
                }
                if(row.favorite)Box(Modifier.align(Alignment.TopEnd).padding(7.dp).background(Color.White,RoundedCornerShape(6.dp)).padding(3.dp)){Glyph("star",Forest,Modifier.size(14.dp))}
                if(row.world)Text("无界",fontSize=9.sp,color=Forest,modifier=Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Leaf,RoundedCornerShape(4.dp)).padding(4.dp))
            }
        }
    }
    val info:@Composable ()->Unit={
        Row(verticalAlignment=Alignment.CenterVertically){Text(note.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp,modifier=Modifier.weight(1f).clickable(onClick=open));Box{
            IconButton(onClick=::openMenu,enabled=!menuOpening,modifier=Modifier.size(48.dp).testTag("note-menu-${note.id}").describedAs("笔记菜单：${note.title}")){Glyph("more",Quiet,Modifier.size(17.dp))}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false},modifier=Modifier.width(240.dp).testTag("notebook-actions-menu")){
                Text(note.title,fontSize=12.sp,color=Quiet,maxLines=1,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(horizontal=16.dp,vertical=9.dp))
                HorizontalDivider(color=Line)
                if(row.trashedAt==null){
                    DropdownMenuItem(text={Text("打开笔记")},leadingIcon={Glyph("note")},onClick={menu=false;open()})
                    DropdownMenuItem(text={Text("重命名")},leadingIcon={Glyph("pen")},onClick={menu=false;rename()},modifier=Modifier.testTag("rename-note-${note.id}"))
                    DropdownMenuItem(text={Text("更换封面")},leadingIcon={Glyph("note")},onClick={menu=false;cover()},modifier=Modifier.testTag("change-cover-${note.id}"))
                    DropdownMenuItem(text={Text("复制笔记")},leadingIcon={Glyph("note")},onClick={menu=false;duplicate()},modifier=Modifier.testTag("duplicate-note-${note.id}"))
                    DropdownMenuItem(text={Text("导出内容副本")},leadingIcon={Glyph("export")},onClick={menu=false;export()},modifier=Modifier.testTag("export-note-${note.id}"))
                    HorizontalDivider(color=Line)
                    DropdownMenuItem(text={Text(if(row.pinned)"取消置顶"else"置顶笔记")},leadingIcon={Glyph("sort")},onClick={menu=false;pin()},modifier=Modifier.testTag("pin-note-${note.id}"))
                    DropdownMenuItem(text={Text(if(row.favorite)"取消收藏"else"收藏")},leadingIcon={Glyph("star")},onClick={menu=false;favorite()},modifier=Modifier.testTag("favorite-note-${note.id}"))
                    DropdownMenuItem(text={Text("文件夹与标签")},leadingIcon={Glyph("folder")},onClick={menu=false;classify()})
                    HorizontalDivider(color=Line)
                }
                DropdownMenuItem(text={Text(if(row.trashedAt!=null)"恢复笔记"else"移入回收站",color=if(row.trashedAt!=null)Forest else Color(0xffab3939))},
                    leadingIcon={Glyph(if(row.trashedAt!=null)"undo"else"trash",if(row.trashedAt!=null)Forest else Color(0xffab3939))},onClick={menu=false;trash()},modifier=Modifier.testTag("trash-note-${note.id}"))
            }
        }}
        if(row.pinned)Text("置顶",fontSize=10.sp,color=Forest,modifier=Modifier.testTag("pinned-${note.id}"))
        Text(date+(if(count>0)" · $count 笔"else""),fontSize=10.sp,color=Quiet)
        if(row.folder.isNotBlank())Text(row.folder,fontSize=10.sp,color=Quiet,modifier=Modifier.padding(top=5.dp))
    }
    if(grid)Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){art(Modifier.width(136.dp).height(185.dp));Column(Modifier.fillMaxWidth().padding(top=7.dp)){info()}}
    else Row(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(10.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)){art(Modifier.size(61.dp,79.dp));Column(Modifier.weight(1f)){info()}}
}

@Composable
internal fun PaperThumbnail(board:Boolean,style:PaperStyle){
    Canvas(Modifier.fillMaxSize().background(Color.White)){
        val sx=size.width/1000f;val sy=size.height/1414f
        val guides=PaperTemplates.guides(style,CanvasBounds(0.0,0.0,1000.0,1414.0),board,.7)
        val c=Color(0xffd8e0dc)
        guides.lines.forEach{drawLine(c,Offset(it.x1*sx,it.y1*sy),Offset(it.x2*sx,it.y2*sy),1f)}
        guides.dots.forEach{drawCircle(c,1f,Offset(it.x.toFloat()*sx,it.y.toFloat()*sy))}
    }
}
