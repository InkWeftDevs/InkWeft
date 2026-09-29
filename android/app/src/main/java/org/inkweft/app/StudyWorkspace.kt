// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

@Composable
internal fun StudyWorkspace(note:NoteDraft,initialSource:StudySourceDraft?,dismiss:()->Unit,initialQuery:String="",initialCardId:String?=null,initialMap:MapRef?=null,initialBranch:String?=null,openSource:(StudySourceRow)->Boolean){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        val chrome=StudyWindowChrome(Modifier){IconButton(onClick=dismiss,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("返回学习")){Glyph("close")}}
        Surface(Modifier.fillMaxSize().safeDrawingPadding(),color=Color.White){CompositionLocalProvider(LocalStudyWindowChrome provides chrome){Column(Modifier.fillMaxSize()){
            StudyContent(note,initialSource,dismiss,initialQuery,initialCardId,compactWindow=true,initialMap=initialMap,initialBranch=initialBranch,openSource=openSource)
        }}}
    }
}
@Composable
internal fun StudyContent(note:NoteDraft,initialSource:StudySourceDraft?,dismiss:()->Unit,initialQuery:String="",initialCardId:String?=null,documentReady:Boolean=true,compactWindow:Boolean=false,sourceRequest:Long=0L,initialCaptureText:String="",onInsertEmbed:((MapEmbed)->Unit)?=null,initialMap:MapRef?=null,initialBranch:String?=null,openSource:(StudySourceRow)->Boolean){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val focus=LocalFocusManager.current
    val vm:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm,lifecycleOwner){lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){vm.attach();try{awaitCancellation()}finally{vm.detach()}}}
    val ui by vm.ui.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var titleDraft by rememberSaveable(stateSaver=NodeTitleDraft.Saver){mutableStateOf<NodeTitleDraft?>(null)}
    var titleInput by rememberSaveable(stateSaver=androidx.compose.ui.text.input.TextFieldValue.Saver){mutableStateOf(androidx.compose.ui.text.input.TextFieldValue())}
    var titleSubmitted by rememberSaveable{mutableStateOf(false)}
    var nodeMenu by remember{mutableStateOf(false)}
    var inspectSource by remember{mutableStateOf(false)}
    var selectedBounds by remember{mutableStateOf<android.graphics.RectF?>(null)}
    var controlsAwake by remember{mutableStateOf(true)}
    var controlPulse by remember{mutableIntStateOf(0)}
    LaunchedEffect(controlPulse){controlsAwake=true;delay(2500);controlsAwake=false}
    var insertMap by remember{mutableStateOf(false)}
    var contentSearch by remember{mutableStateOf(false)}
    val graphFlow=remember(note.base.id){app.mapGraphs.observe(note.base.id)}
    val scenes by graphFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val extraFlow=remember(app,note.base.id){app.knowledge.observeBook(note.base.id)};val extraRows by extraFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    fun extraOccurrences(cardId:String)=extraRows.count{r->!r.removed&&r.notebookId==note.base.id&&when(val d=r.data()){is KnowledgeData.Placement->d.cardId==cardId;is KnowledgeData.MapOccurrence->d.cardId==cardId;else->false}}

    val currentMap by vm.mapId.collectAsStateWithLifecycle()
    LaunchedEffect(initialMap,initialBranch){initialMap?.let{ref->require(ref.notebookId==note.base.id);vm.selectMap(ref.mapId);vm.selectTab(2);initialBranch?.let{vm.revealByMap[ref.mapId?:"main"]=it;vm.selectedByMap[ref.mapId?:"main"]=it}}}
    LaunchedEffect(currentMap,ui.loading,vm.lastTab){if(!ui.loading&&vm.lastTab==2)app.learningStore.visit(StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap))}
    val mapKey=currentMap?:"main"
    val maps=extraRows.filter{!it.removed&&it.notebookId==note.base.id&&it.data() is KnowledgeData.MapDefinition}
    var mapMenu by remember{mutableStateOf(false)};var newMapTitle by rememberSaveable{mutableStateOf<String?>(null)}
    var saveTemplate by rememberSaveable{mutableStateOf(false)}
    var keepTemplateTitles by rememberSaveable{mutableStateOf(false)}
    var templateTitle by rememberSaveable{mutableStateOf("我的结构模板")}
    val mapWriter:KnowledgeViewModel=viewModel(key="study-map-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    val mapWrite by mapWriter.ui.collectAsStateWithLifecycle()
    val mapSaving=mapWrite.busy||mapWrite.unknown
    LaunchedEffect(mapWrite.completed){mapWrite.completed?.let{if(newMapTitle!=null)vm.selectMap(it);newMapTitle=null;saveTemplate=false;if(titleSubmitted&&titleDraft?.structural==true){titleDraft=null;titleSubmitted=false;focus.clearFocus()};mapWriter.consumed()}}
    val mainNodes=ui.mainNodes
    val definition=(maps.find{it.id==currentMap}?.data() as? KnowledgeData.MapDefinition)
    val structureCards=definition?.structures.orEmpty().map{StudyCardRow(it.id,note.base.id,maps.find{m->m.id==currentMap}!!.revision,it.title,"")}
    val displayCards=ui.cards+structureCards
    var template by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.Saver<KnowledgeData.MapTemplate,ByteArray>({KnowledgeCodec.encode(it)},{KnowledgeCodec.decode(it) as KnowledgeData.MapTemplate})){mutableStateOf(MapTemplates.builtins.first())}
    var installedMapHash by rememberSaveable{mutableStateOf<String?>(null)}
    var installedMapId by rememberSaveable{mutableStateOf<String?>(null)}
    var templatePicker by rememberSaveable{mutableStateOf(false)}
    fun occurrenceCount(cardId:String)=mainNodes.count{!it.removed&&it.cardId==cardId}+extraOccurrences(cardId)
    var query by remember{mutableStateOf(initialQuery)}
    var management by rememberSaveable{mutableStateOf(false)}
    var managementGroup by remember{mutableIntStateOf(0)}
    LaunchedEffect(compactWindow){if(compactWindow&&!vm.compactInitialized){vm.selectTab(2);vm.compactInitialized=true}}
    val tab=vm.lastTab;var showTrash by remember{mutableStateOf(false)}
    var returnTab by rememberSaveable{mutableStateOf<Int?>(null)}
    var editor by vm.editorState
    var chosenNode by remember{mutableStateOf<StudyNodeRow?>(null)};var chosenCard by remember{mutableStateOf<StudyCardRow?>(null)}
    var source by remember{mutableStateOf<StudySourceRow?>(null)};var stale by remember{mutableStateOf(false)}
    var map by remember{mutableStateOf<MindMapView?>(null)};var dragging by remember{mutableStateOf(false)}
    LaunchedEffect(mapKey,map){if(vm.viewports[mapKey]==null)app.learningStore.viewport(MapRef(note.base.id,currentMap))?.let{vm.viewports[mapKey]=it;map?.restoreViewport(it)}}
    DisposableEffect(vm,mapKey){val ref=MapRef(note.base.id,currentMap);onDispose{vm.viewports[mapKey]?.let{app.learningStore.viewport(ref,it)}}}
    var pendingExport by remember{mutableStateOf<String?>(null)};var localMessage by remember{mutableStateOf<String?>(null)}
    var knowledgeCard by remember{mutableStateOf<StudyCardRow?>(null)}
    var reparent by remember{mutableStateOf<StudyNodeRow?>(null)}
    val restoreEpoch=vm.viewportRestore
    var collapsed by remember(mapKey,restoreEpoch){mutableStateOf(vm.collapsedByMap[mapKey].orEmpty())}
    var focusId by remember(mapKey,restoreEpoch){mutableStateOf(vm.focusedByMap[mapKey])}
    val chrome=LocalStudyWindowChrome.current
    val active=ui.nodes.filter{!it.removed}
    val nodeById=active.associateBy{it.id};val cardById=displayCards.associateBy{it.id}
    val projection=StudyOutline.project(active.map{it.model()},collapsed.toSet(),focusId)
    val shown=projection.rows.mapNotNull{nodeById[it.node.id]}
    val hiddenCounts=projection.rows.filter{it.node.id in collapsed}.associate{it.node.id to it.descendants}
    val editable=documentReady&&!ui.loading&&!ui.readFailed&&!ui.busy&&!ui.unknown&&!mapSaving
    androidx.activity.compose.BackHandler(ui.busy||ui.unknown||mapSaving){android.widget.Toast.makeText(context,"请先核对当前导图操作",android.widget.Toast.LENGTH_SHORT).show()}
    fun toggleBranch(nodeId:String){vm.searchSession?.changedByUser=true;collapsed=if(nodeId in collapsed)collapsed-nodeId else collapsed+nodeId}
    fun focusBranch(nodeId:String?){vm.searchSession?.changedByUser=true;focus.clearFocus();focusId=nodeId}
    LaunchedEffect(ui.loading,active.map{it.id}){if(!ui.loading&&focusId!=null&&focusId !in nodeById)focusId=null}
    SideEffect{vm.collapsedByMap[mapKey]=collapsed;vm.focusedByMap[mapKey]=focusId}
    DisposableEffect(vm,mapKey,restoreEpoch){onDispose{if(vm.viewportRestore==restoreEpoch){vm.collapsedByMap[mapKey]=collapsed;vm.focusedByMap[mapKey]=focusId}}}
    var sourcePending by rememberSaveable(initialSource){mutableStateOf(initialSource!=null)}
    var sourceSeen by rememberSaveable{mutableLongStateOf(sourceRequest)}
    // Restoring a closed window must not restore an old dismissal over a fresh capture.
    LaunchedEffect(sourceRequest){if(sourceSeen!=sourceRequest){sourceSeen=sourceRequest;sourcePending=initialSource!=null}}
    var sourceParent by rememberSaveable(mapKey){mutableStateOf<String?>(null)}
    LaunchedEffect(mapKey){chosenNode=null;chosenCard=null}
    LaunchedEffect(vm.searchHit,ui.loading,map){
        val hit=vm.searchHit
        if(hit!=null&&!ui.loading&&hit.ref.mapId==currentMap&&nodeById.containsKey(hit.nodeId)){
            var parent=nodeById[hit.nodeId]?.parentId;val ancestors=mutableSetOf<String>()
            while(parent!=null&&ancestors.add(parent)){parent=nodeById[parent]?.parentId}
            collapsed=collapsed-ancestors;focusId=null;vm.selectedByMap[mapKey]=hit.nodeId
            vm.revealByMap[mapKey]=hit.nodeId
        }
    }
    LaunchedEffect(restoreEpoch,mapKey,map){if(vm.searchSession==null){vm.viewports[mapKey]?.let{map?.restoreViewport(it)}}}


    LaunchedEffect(initialCardId,ui.loading){if(initialCardId!=null&&!ui.loading)chosenCard=ui.cards.find{it.id==initialCardId&&it.trashedAt==null}}
    val id={UUID.randomUUID().toString()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->
        val text=pendingExport;pendingExport=null;if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}};localMessage="大纲与摘要已导出，共享卡片正文只保留一份。图形布局与来源原迹请用资料库备份保存。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认；卡片仍保留在本机。"}}
    }
    LaunchedEffect(chosenCard?.id){source=null;stale=false;val card=chosenCard?:return@LaunchedEffect
        try{source=withContext(Dispatchers.IO){app.study.source(card.id)};source?.let{s->stale=withContext(Dispatchers.IO){app.pages.inkRevision(s.pageId)!=s.inkRevision}}}
        catch(c:CancellationException){throw c}catch(_:Exception){localMessage="来源快照读取失败，卡片文字仍保留。"}}
    LaunchedEffect(ui.completed){if(ui.completed!=null){
        editor?.takeIf{it.card==null}?.let{e->
            e.parent?.id?.let{collapsed=collapsed-it}
            if(focusId!=null)focusId=e.parent?.id
        }
        if(titleSubmitted){titleDraft?.let{d->vm.selectedByMap[d.mapId?:"main"]=d.nodeId;vm.revealByMap[d.mapId?:"main"]=d.nodeId;d.parentId?.let{collapsed=collapsed-it}};titleDraft=null;titleSubmitted=false;focus.clearFocus()}
        sourcePending=false;returnTab=null;editor=null;chosenNode=null;chosenCard=null;reparent=null;vm.clear()
    }}
    fun openCard(card:StudyCardRow,node:StudyNodeRow?=null){if(!editable)return;focus.clearFocus();chosenNode=node;chosenCard=card;vm.selectedByMap[mapKey]=node?.id}
    fun editTitle(node:StudyNodeRow,create:Boolean=false,sibling:Boolean=false){
        if(!editable||titleDraft!=null)return
        val card=cardById[node.cardId]?:return
        val parent=if(sibling)nodeById[node.parentId]else node
        val fresh=id()
        titleDraft=NodeTitleDraft(id(),currentMap,if(create)fresh else node.id,if(create)id()else card.id,
            if(create)0 else card.revision,if(create)""else card.title,if(create)""else card.body,
            if(create)parent?.id else node.parentId,
            if(create)(parent?.x?.plus(260)?:node.x).coerceIn(-40000.0,40000.0)else node.x,
            if(create)((active.filter{it.parentId==parent?.id}.maxOfOrNull{it.y}?:node.y)+128).coerceIn(-40000.0,40000.0)else node.y,
            create,!create&&card.id in structureCards.map{it.id},if(create)StudyGraph.orderHash(ui.nodes.map{it.model()})else"",node.id)
        titleInput=androidx.compose.ui.text.input.TextFieldValue(if(create)""else card.title,androidx.compose.ui.text.TextRange(0,if(create)0 else card.title.length));titleSubmitted=false;localMessage=null;nodeMenu=false;map?.revealNode(node.id)
    }
    fun saveTitle(title:String){
        val d=titleDraft?:return
        if(d.structural){
            val row=maps.find{it.id==d.mapId};val value=row?.data() as? KnowledgeData.MapDefinition
            if(row==null||row.revision!=d.revision||value==null||value.structures.none{it.id==d.cardId}){localMessage="主题已变化，草稿保留。取消后重新核对标题。";return}
            titleSubmitted=true;mapWriter.submit(note.base.id,value.copy(structures=value.structures.map{if(it.id==d.cardId)it.copy(title=title)else it}),row)
        }else{titleSubmitted=true;vm.submit(StudyCommand(id(),note.base.id,if(d.creating)StudyAction.CREATE else StudyAction.EDIT,mapId=d.mapId,cardId=d.cardId,nodeId=if(d.creating)d.nodeId else null,expectedRevision=d.revision,parentId=d.parentId,title=title,body=d.body,x=d.x,y=d.y,expectedGraph=d.graph))}
    }
    Column(Modifier.fillMaxSize()){
    if(compactWindow&&chrome!=null)Row(Modifier.fillMaxWidth().height(48.dp).testTag("study-shared-header"),verticalAlignment=Alignment.CenterVertically){
        Box(chrome.drag.size(32.dp,48.dp),contentAlignment=Alignment.Center){Text("⠿",color=Quiet)}
        Box(Modifier.weight(1f)){
            TextButton({mapMenu=true},enabled=editable&&titleDraft==null,modifier=Modifier.fillMaxWidth().testTag("study-map-picker")){
                Text(currentMap?.let{m->(maps.find{it.id==m}?.data() as? KnowledgeData.MapDefinition)?.title}?:"主图",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(mapMenu,{mapMenu=false}){
                var mapQuery by remember{mutableStateOf("")}
                OutlinedTextField(mapQuery,{mapQuery=it},singleLine=true,label={Text("按图名查找")},modifier=Modifier.width(260.dp).padding(8.dp))
                DropdownMenuItem(text={Text("主图")},onClick={mapMenu=false;vm.selectMap(null)},modifier=Modifier.testTag("study-map-main"))
                maps.filter{(it.data() as KnowledgeData.MapDefinition).title.contains(mapQuery,true)}.forEach{m->DropdownMenuItem(text={Text((m.data() as KnowledgeData.MapDefinition).title)},onClick={mapMenu=false;vm.selectMap(m.id)},modifier=Modifier.testTag("study-map-${m.id}"))}
                DropdownMenuItem(text={Text("新建导图")},onClick={mapMenu=false;templatePicker=true},modifier=Modifier.testTag("study-new-map"))
            }
        }
        IconButton({if(vm.searchSession!=null)vm.endSearch()else contentSearch=true},enabled=titleDraft==null,modifier=Modifier.size(48.dp).testTag("study-content-search").describedAs(if(vm.searchSession!=null)"结束搜索并返回视野"else"查找导图内容")){Glyph(if(vm.searchSession!=null)"close"else"search")}
        Box{
        MapActionIcon("导图管理","more","study-management",titleDraft==null){management=!management}
        MapMenu(management,{management=false},managementGroup,{managementGroup=it}){
            if(managementGroup==0)MapMenuSection("视图"){
                listOf("摘要卡","大纲","思维导图").forEachIndexed{i,label->DropdownMenuItem(text={Text(label)},onClick={vm.selectTab(i);management=false},modifier=Modifier.testTag("study-tab-$i"))}
                DropdownMenuItem(text={Text("查看全图")},onClick={map?.fitOverview();management=false},enabled=tab==2,modifier=Modifier.testTag("study-fit-overview"))
                DropdownMenuItem(text={Text("可读大小")},onClick={map?.fit();management=false},enabled=tab==2,modifier=Modifier.testTag("study-fit-readable"))
                DropdownMenuItem(text={Text("查找导图内容")},onClick={contentSearch=true;management=false},modifier=Modifier.testTag("study-search-content"))
            }
            if(managementGroup==1)MapMenuSection("整理"){
                DropdownMenuItem(text={Text("固定当前图到学习")},onClick={app.learningStore.shortcut(StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap),true);management=false},modifier=Modifier.testTag("study-pin-map"))
                if(vm.selectedByMap[mapKey]!=null)DropdownMenuItem(text={Text("固定所选分支到学习")},onClick={app.learningStore.shortcut(StableTargetRef(LearningTargetKind.BRANCH,note.base.id,vm.selectedByMap[mapKey],currentMap),true);management=false})
                DropdownMenuItem(text={Text("新建导图")},onClick={templatePicker=true;management=false},enabled=editable,modifier=Modifier.testTag("study-new-map"))
                DropdownMenuItem(text={Text("新建摘要卡")},onClick={returnTab=tab;editor=CardEditor();vm.selectTab(0);management=false},enabled=editable,modifier=Modifier.testTag("study-add-card"))
                DropdownMenuItem(text={Text("展开全部")},onClick={collapsed=emptyList();management=false},enabled=editable&&collapsed.isNotEmpty(),modifier=Modifier.testTag("study-expand-all"))
                DropdownMenuItem(text={Text("收起分支")},onClick={collapsed=active.mapNotNull{it.parentId}.distinct();management=false},enabled=editable,modifier=Modifier.testTag("study-collapse-all"))
                DropdownMenuItem(text={Text("全部主题")},onClick={focusBranch(null);management=false},modifier=Modifier.testTag("study-focus-all"))
                DropdownMenuItem(text={Text("重新排布")},onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.ARRANGE,mapId=currentMap,expectedGraph=StudyGraph.orderHash(ui.nodes.map{it.model()})));management=false},enabled=editable,modifier=Modifier.testTag("study-arrange"))
            }
            if(managementGroup==2)MapMenuSection("输出"){
                if(onInsertEmbed!=null)DropdownMenuItem(text={Text("把导图放入笔记")},onClick={insertMap=true;management=false},enabled=editable,modifier=Modifier.testTag("study-insert-map"))
                DropdownMenuItem(text={Text("另存为结构模板")},onClick={saveTemplate=true;management=false},enabled=editable,modifier=Modifier.testTag("study-save-template"))
                DropdownMenuItem(text={Text("导出大纲")},onClick={pendingExport=StudyText.markdown(note.title,displayCards.map{StudyTextCard(it.id,it.title,it.body)},ui.nodes.map{it.model()});export.launch("墨织大纲.md");management=false},enabled=editable)
            }
        }}
        chrome.controls()
    }
    Box(Modifier.weight(1f)){
    if(templatePicker){
        val installedMaps=rememberTemplateCatalog().filter{it.resource.map!=null}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text("新建导图",Modifier.weight(1f));TextButton({templatePicker=false}){Text("返回")}}
            (MapTemplates.builtins+extraRows.filter{!it.removed&&it.notebookId==note.base.id}.mapNotNull{it.data() as? KnowledgeData.MapTemplate}).forEachIndexed{i,t->
                TextButton({installedMapHash=null;installedMapId=null;template=t;templatePicker=false;newMapTitle=t.title},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-template-$i")){Text(t.title,Modifier.weight(1f));Text("${t.nodes.size} 个结构主题",style=MaterialTheme.typography.labelSmall)}
            }
            if(installedMaps.isNotEmpty())Text("我的模板",style=MaterialTheme.typography.labelLarge,color=Quiet)
            installedMaps.forEach{entry->TextButton({template=checkNotNull(entry.resource.map);installedMapHash=entry.ref.hash;installedMapId=entry.ref.id;templatePicker=false;newMapTitle=entry.title},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("installed-map-${entry.ref.id}")){Text(entry.title,Modifier.weight(1f));Text("v${entry.version}",style=MaterialTheme.typography.labelSmall)}}

        }
    }else Box(Modifier.fillMaxSize()){
        Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(if(compactWindow)0.dp else 20.dp),color=Color.White,border=if(compactWindow)null else BorderStroke(1.dp,Line)){Column(Modifier.fillMaxSize().padding(if(compactWindow)4.dp else 16.dp)){
            val studyActions:@Composable RowScope.()->Unit={
                TextButton(onClick={app.learningStore.shortcut(vm.selectedByMap[mapKey]?.let{StableTargetRef(LearningTargetKind.BRANCH,note.base.id,it,currentMap)}?:StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap),true);localMessage="已固定到学习快捷入口"},enabled=editable,modifier=Modifier.testTag("study-pin-map")){Text(if(vm.selectedByMap[mapKey]!=null)"固定分支"else"固定到学习")}
                TextButton(onClick={editor=CardEditor()},enabled=editable,modifier=Modifier.testTag("study-add-card")){Text("＋ 新摘要卡")}
                TextButton(onClick={saveTemplate=true},enabled=editable,modifier=Modifier.testTag("study-save-template")){Text("另存为模板")}
                TextButton(onClick={pendingExport=StudyText.markdown(note.title,ui.cards.filter{it.trashedAt==null}.map{StudyTextCard(it.id,it.title,it.body)},ui.nodes.map{it.model()});export.launch("墨织摘要.md")},enabled=editable&&ui.cards.isNotEmpty()){Text("导出完整大纲")}
            }

            if(!compactWindow)BoxWithConstraints(Modifier.fillMaxWidth()){
                val compact=maxWidth<600.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
                Column{
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text("笔记导图",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        if(!compact)studyActions()
                        IconButton(onClick=dismiss,enabled=documentReady&&!ui.busy&&!ui.unknown&&!mapSaving,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("返回笔记")){Glyph("close")}
                    }
                    if(compact)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),content=studyActions)
                }
            }
            if(!compactWindow)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                if(compactWindow)Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())){
                    (listOf(null to "主图")+maps.map{it.id to (it.data() as KnowledgeData.MapDefinition).title}).forEach{(mapId,label)->
                        TextButton({vm.selectMap(mapId)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("map-tab-${mapId?:"main"}"),colors=ButtonDefaults.textButtonColors(contentColor=if(currentMap==mapId)Forest else Quiet)){Text(label,maxLines=1)}
                    }
                }
                Box(if(compactWindow)Modifier.width(48.dp)else Modifier.weight(1f)){
                    TextButton({mapMenu=true},enabled=editable&&!mapSaving,modifier=Modifier.testTag("study-map-picker")){if(!compactWindow)Text(currentMap?.let{id->(maps.find{it.id==id}?.data() as? KnowledgeData.MapDefinition)?.title}?:"主图",maxLines=1);Text("▾")}
                    DropdownMenu(mapMenu,{mapMenu=false}){
                        var mapQuery by remember{mutableStateOf("")}
                        OutlinedTextField(mapQuery,{mapQuery=it},singleLine=true,label={Text("查找导图")},modifier=Modifier.width(260.dp).padding(8.dp))
                        DropdownMenuItem(text={Text("主图")},onClick={mapMenu=false;vm.selectMap(null)},modifier=Modifier.testTag("study-map-main"))
                        maps.filter{(it.data() as KnowledgeData.MapDefinition).title.contains(mapQuery,true)}.forEach{m->DropdownMenuItem(text={Text((m.data() as KnowledgeData.MapDefinition).title)},onClick={mapMenu=false;vm.selectMap(m.id)},modifier=Modifier.testTag("study-map-${m.id}"))}
                    }
                }
                if(compactWindow)IconButton({management=!management},modifier=Modifier.testTag("study-management").describedAs("导图管理")){Glyph(if(management)"back"else"more")}
                TextButton({templatePicker=true},enabled=editable&&!mapSaving,modifier=Modifier.testTag("study-new-map")){Text(if(compactWindow)"＋"else"新建图")}
            }
            if(sourcePending&&initialSource!=null){
                Row(Modifier.fillMaxWidth().testTag("study-source-target"),verticalAlignment=Alignment.CenterVertically){
                    var branches by remember{mutableStateOf(false)}
                    Box(Modifier.weight(1f)){
                        TextButton({branches=true},enabled=editable,modifier=Modifier.testTag("study-source-branch")){Text(nodeById[sourceParent]?.let{cardById[it.cardId]?.title}?:"根主题 ▾",maxLines=1)}
                        DropdownMenu(branches,{branches=false}){
                            DropdownMenuItem(text={Text("根主题")},onClick={sourceParent=null;branches=false})
                            shown.forEach{n->DropdownMenuItem(text={Text(cardById[n.cardId]?.title.orEmpty())},onClick={sourceParent=n.id;branches=false},modifier=Modifier.testTag("study-source-parent-${n.id}"))}
                        }
                    }
                    TextButton({val parent=nodeById[sourceParent];vm.submit(CaptureDraft(note.base.id,initialSource,initialCaptureText).command(MapRef(note.base.id,currentMap),sourceParent,StudyGraph.orderHash(ui.nodes.map{it.model()}),parent?.x?.plus(260)?.coerceAtMost(40000.0)?:40.0,(parent?.y?.plus(128)?:(active.size*128.0+80)).coerceAtMost(40000.0)))},enabled=editable,modifier=Modifier.testTag("study-add-source")){Text("添加")}
                    IconButton({sourcePending=false},enabled=editable,modifier=Modifier.testTag("study-cancel-source").describedAs("取消摘录草稿")){Glyph("close")}
                }
            }
            if(tab!=0&&(!compactWindow||focusId!=null)){
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={focusBranch(null)},enabled=focusId!=null,modifier=Modifier.testTag("study-focus-all")){Text("全部主题")}
                    projection.path.forEach{n->Text("›",color=Quiet);TextButton(onClick={focusBranch(n.id)},modifier=Modifier.testTag("study-breadcrumb-${n.id}")){Text(cardById[n.cardId]?.title.orEmpty(),maxLines=1)}}
                    Text("${shown.size} / ${active.size} 个主题",fontSize=12.sp,color=Quiet)
                    if(!compactWindow)TextButton(onClick={collapsed=emptyList()},enabled=collapsed.isNotEmpty(),modifier=Modifier.testTag("study-expand-all")){Text("展开全部")}
                    if(!compactWindow)TextButton(onClick={collapsed=active.mapNotNull{it.parentId}.distinct()},enabled=active.any{it.parentId!=null},modifier=Modifier.testTag("study-collapse-all")){Text("收起分支")}
                }
            }
            if(!compactWindow)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("摘要卡","大纲","思维导图").forEachIndexed{i,label->FilterChip(selected=tab==i,onClick={focus.clearFocus();vm.selectTab(i)},label={Text(label)},shape=RoundedCornerShape(10.dp),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Leaf,selectedLabelColor=Forest),modifier=Modifier.testTag("study-tab-$i"))}
                if(tab==0)FilterChip(selected=showTrash,onClick={showTrash=!showTrash},label={Text("卡片回收区")})
                if(tab==2){TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.ARRANGE,mapId=currentMap,expectedGraph=StudyGraph.orderHash(ui.nodes.map{it.model()})))},enabled=!ui.busy&&!ui.unknown&&!dragging,modifier=Modifier.testTag("study-arrange")){Text("排布全部主题")}}
            }
            val msg=ui.message?:localMessage
            if(msg!=null)Text(msg,fontSize=12.sp,color=Forest,modifier=Modifier.testTag("study-message"))
            if(ui.unknown)TextButton(onClick=vm::retry,enabled=!ui.busy,modifier=Modifier.testTag("study-retry")){Text("核对原操作")}
            if(ui.readFailed)Row(verticalAlignment=Alignment.CenterVertically){Text("读取失败，保留上次内容",Modifier.weight(1f));TextButton(vm::refresh,modifier=Modifier.testTag("study-reload")){Text("重新加载")}}
            if(ui.loading||ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(tab==0)OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索摘要标题或正文")},
                trailingIcon={if(query.isNotEmpty())TextButton(onClick={query=""}){Text("清除")}},modifier=Modifier.fillMaxWidth().testTag("study-search"))
            val cards=ui.cards.filter{(it.trashedAt!=null)==showTrash&&StudyText.matches(StudyTextCard(it.id,it.title,it.body),query)}
            if(tab==2)BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)){key(mapKey){AndroidView(factory={MindMapView(it).also{v->map=v;vm.viewports[mapKey]?.let(v::restoreViewport);v.onViewport={vp->vm.viewports[mapKey]=vp}}},update={v->v.captureBook=note.base.id;v.captureMapKey=mapKey;v.captureGraph=StudyGraph.orderHash(ui.nodes.map{it.model()});v.onCapture={transfer,parent,x,y,graph->
                if(editable)vm.submit(transfer.command(MapRef(note.base.id,currentMap),parent?.id,graph,x,y))
            };v.selectedNodeId=vm.selectedByMap[mapKey];v.branchIds=active.mapNotNull{it.parentId}.toSet();v.onToggleBranch=::toggleBranch;v.enabledInput=editable;v.editingTitle=titleDraft!=null;v.onSelectionBounds={selectedBounds=it};v.show(shown,displayCards,hiddenCounts);vm.revealByMap[mapKey]?.let{if(if(vm.searchHit?.nodeId==it)v.focusNode(it)else v.revealNode(it))vm.revealByMap.remove(mapKey)};v.onActive={dragging=it;controlPulse++;if(it)vm.searchSession?.changedByUser=true};v.onSelect={n->vm.selectedByMap[mapKey]=n?.id;nodeMenu=false};v.onEditTitle={n->editTitle(n)};v.onOpenDetails={n->cardById[n.cardId]?.let{openCard(it,n)}};v.onMove={n,x,y->if(editable)vm.submit(StudyCommand(id(),note.base.id,StudyAction.MOVE,mapId=currentMap,nodeId=n.id,expectedRevision=n.revision,x=x,y=y))}},modifier=Modifier.fillMaxSize().then(if(compactWindow)Modifier else Modifier.clip(InkTheme.ToolShape)).testTag("study-map"))}
                val selected=nodeById[vm.selectedByMap[mapKey]]
                val density=LocalDensity.current
                var overlaySize by remember{mutableStateOf(IntSize.Zero)}
                var actionBounds by remember{mutableStateOf<android.graphics.RectF?>(null)}
                val bounds=selectedBounds
                if(selected!=null&&bounds!=null&&!dragging){
                    val w=with(density){maxWidth.toPx()};val h=with(density){maxHeight.toPx()}
                    val panelW=with(density){(if(titleDraft!=null)minOf(320.dp,maxWidth-16.dp)else 192.dp).toPx()}
                    val panelH=overlaySize.height.toFloat()
                    val bottomSpace=if(vm.searchSession!=null||vm.captureUndo[mapKey]!=null)56.dp else 8.dp
                    val reserved=with(density){bottomSpace.toPx()}
                    val editorMaxHeight=(maxHeight-bottomSpace).coerceAtLeast(48.dp)
                    val nearby=if(titleDraft==null)mapActionPosition(bounds,shown.mapNotNull{map?.nodeBounds(it.id)},w,h-reserved,panelW,panelH,8*density.density)else null
                    val px=nearby?.x?:(bounds.left).coerceIn(8*density.density,(w-panelW-8*density.density).coerceAtLeast(8*density.density))
                    val below=bounds.bottom+8*density.density
                    val py=nearby?.y?:(if(below+panelH<=h-reserved)below else bounds.top-panelH-8*density.density).coerceIn(0f,(h-panelH-reserved).coerceAtLeast(0f))
                    SideEffect{actionBounds=android.graphics.RectF(px,py,px+panelW,py+panelH)}
                    Box(Modifier.offset{IntOffset(px.roundToInt(),py.roundToInt())}.onSizeChanged{overlaySize=it}){
                        val draft=titleDraft
                        if(draft!=null)NodeTitleEditor(draft,occurrenceCount(draft.cardId),ui.busy||mapWrite.busy,ui.unknown||mapWrite.unknown,
                            localMessage?:if(draft.structural)mapWrite.message else ui.message,
                            Modifier.width(with(density){panelW.toDp()}).heightIn(max=editorMaxHeight),
                            text=titleInput,onText={titleInput=it},cancel={titleDraft=null;titleSubmitted=false;localMessage=null;focus.clearFocus()},submit=::saveTitle,retry={if(draft.structural)mapWriter.retry()else vm.retry()})
                        else Column{
                            NodeActions(editable,{editTitle(selected)},{editTitle(selected,true)},{editTitle(selected,true,true)},{nodeMenu=true})
                            DropdownMenu(nodeMenu,{nodeMenu=false},modifier=Modifier.widthIn(max=280.dp).testTag("node-menu")){
                                MapMenuSection("内容"){
                                    DropdownMenuItem(text={Text("查看内容")},onClick={nodeMenu=false;openCard(cardById.getValue(selected.cardId),selected)},modifier=Modifier.testTag("node-view-content"))
                                    if(scenes.find{it.ref.mapId==currentMap}?.nodes?.any{it.id==selected.id&&it.sourceState!="无来源"}==true)DropdownMenuItem(text={Text("查看来源")},onClick={nodeMenu=false;inspectSource=true;openCard(cardById.getValue(selected.cardId),selected)},modifier=Modifier.testTag("node-view-source"))
                                }
                                HorizontalDivider()
                                MapMenuSection("组织"){
                                    if(active.any{it.parentId==selected.id})DropdownMenuItem(text={Text(if(selected.id in collapsed)"展开下级主题"else"收起下级主题")},onClick={nodeMenu=false;toggleBranch(selected.id)},enabled=editable,modifier=Modifier.testTag("node-menu-fold"))
                                    DropdownMenuItem(text={Text("变更上级")},onClick={nodeMenu=false;reparent=selected},enabled=editable)
                                    DropdownMenuItem(text={Text("聚焦此分支")},onClick={nodeMenu=false;focusBranch(selected.id)},enabled=editable)
                                    if(selected.cardId !in structureCards.map{it.id})DropdownMenuItem(text={Text("复用到此图")},onClick={nodeMenu=false;vm.submit(StudyCommand(id(),note.base.id,StudyAction.REUSE,mapId=currentMap,cardId=selected.cardId,nodeId=id(),y=active.size*128.0+80))},enabled=editable)
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text={Text(if(active.any{it.parentId==selected.id})"请先移除下级主题"else"从此图移除")},onClick={nodeMenu=false;vm.submit(StudyCommand(id(),note.base.id,StudyAction.REMOVE_NODE,mapId=currentMap,nodeId=selected.id,expectedRevision=selected.revision))},enabled=editable&&active.none{it.parentId==selected.id},modifier=Modifier.testTag("node-remove"))
                            }
                        }
                    }
                }
                if(compactWindow&&(vm.searchSession!=null||vm.captureUndo[mapKey]!=null))Surface(Modifier.align(Alignment.BottomStart).padding(8.dp).alpha(if(controlsAwake||vm.searchSession!=null)1f else .65f),shape=InkTheme.ToolShape,color=InkTheme.Surface,shadowElevation=InkTheme.ToolElevation){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        val search=vm.searchSession
                        if(search!=null){
                            val hits=MapSearch.find(scenes,search.query,MapRef(note.base.id,search.initialMap),search.allMaps)
                            val index=hits.indexOfFirst{it.ref==vm.searchHit?.ref&&it.nodeId==vm.searchHit?.nodeId}
                            IconButton({if(hits.isNotEmpty())vm.locate(hits[if(index<0)hits.lastIndex else (index-1+hits.size)%hits.size])},enabled=hits.isNotEmpty(),modifier=Modifier.size(48.dp).testTag("map-search-previous").describedAs("上一个结果")){Glyph("back")}
                            Text(if(index<0)"结果已更新"else"${index+1}/${hits.size}",style=MaterialTheme.typography.labelSmall)
                            IconButton({if(hits.isNotEmpty())vm.locate(hits[(index+1)%hits.size])},enabled=hits.isNotEmpty(),modifier=Modifier.size(48.dp).testTag("map-search-next").describedAs("下一个结果")){Text("›")}
                        }else{
                        if(vm.captureUndo[mapKey]!=null)IconButton(vm::undoCapture,enabled=editable,modifier=Modifier.size(48.dp).testTag("study-undo-capture").describedAs("撤销此次摘录添加")){Glyph("undo")}
                        }
                    }
                }
                if(selected!=null&&bounds!=null&&!dragging&&titleDraft==null){
                    val edge=48*density.density;val w=with(density){maxWidth.toPx()};val h=with(density){maxHeight.toPx()}
                    val blocked=shown.mapNotNull{map?.nodeBounds(it.id)}+listOfNotNull(actionBounds)
                    val fold=if(active.any{it.parentId==selected.id})mapAccessoryBounds(bounds,blocked,w,h,edge,4*density.density,false)else null
                    val sourceBox=mapAccessoryBounds(bounds,blocked+listOfNotNull(fold),w,h,edge,4*density.density,true)
                    fold?.let{b->Surface(Modifier.offset{IntOffset(b.left.roundToInt(),b.top.roundToInt())},color=Color.White,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Line)){
                        MapActionIcon(if(selected.id in collapsed)"展开下级主题"else"收起下级主题","collapse","node-fold",editable){toggleBranch(selected.id)}
                    }}
                    if(scenes.find{it.ref.mapId==currentMap}?.nodes?.any{it.id==selected.id&&it.sourceState!="无来源"}==true)sourceBox?.let{b->Surface(Modifier.offset{IntOffset(b.left.roundToInt(),b.top.roundToInt())},color=Color.White,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Line)){
                        MapActionIcon("查看来源","link","node-source",editable){inspectSource=true;openCard(cardById.getValue(selected.cardId),selected)}
                    }}
                }
                if(active.isEmpty()&&!ui.loading&&!sourcePending)Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){TextButton({templatePicker=true},enabled=editable,modifier=Modifier.testTag("study-empty-create")){Text("新建图 · 选择模板")};Text("也可拖入摘录",style=MaterialTheme.typography.bodySmall,color=Quiet)}
            }
            else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("study-list"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){
                if(tab==0){items(cards,key={it.id}){card->OutlinedCard(onClick={openCard(card)},enabled=editable,colors=CardDefaults.outlinedCardColors(containerColor=Color.White),border=BorderStroke(1.dp,Line),modifier=Modifier.fillMaxWidth().testTag("study-card-${card.id}")){
                    Column(Modifier.padding(16.dp)){Text(card.title,fontWeight=FontWeight.SemiBold);if(card.body.isNotBlank())Text(card.body,maxLines=4,fontSize=14.sp,modifier=Modifier.padding(top=8.dp));Text("摘要卡 · ${occurrenceCount(card.id)} 个展示位置",fontSize=11.sp,color=Quiet)}}}
                    if(cards.isEmpty())item{Text(if(query.isNotBlank())"没有匹配的摘要卡"else if(showTrash)"卡片回收区为空"else"框选手写摘录，或新建摘要卡。摘要由你填写，不会自动发送到云端。",color=Quiet)}}
                else{items(projection.rows,key={it.node.id}){row->val node=nodeById.getValue(row.node.id);val depth=row.depth;val card=cardById[node.cardId]
                    if(card!=null)OutlinedCard(onClick={openCard(card,node)},enabled=editable,colors=CardDefaults.outlinedCardColors(containerColor=Color.White),border=BorderStroke(1.dp,Line),modifier=Modifier.fillMaxWidth().padding(start=(depth.coerceAtMost(10)*20).dp)){
                        Column(Modifier.padding(12.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                if(row.descendants>0)TextButton(onClick={toggleBranch(node.id)},modifier=Modifier.testTag("outline-fold-${node.id}")){Text(if(node.id in collapsed)"展开 ${row.descendants}"else"收起")}
                                Text(card.title,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=editable){openCard(card,node)}.padding(vertical=12.dp).testTag("outline-node-${node.id}"))
                            }
                            if(card.body.isNotBlank())Text(card.body,maxLines=2,fontSize=12.sp,color=Quiet)
                            Row(Modifier.horizontalScroll(rememberScrollState())){
                                TextButton(onClick={editor=CardEditor(parent=node)},enabled=editable,modifier=Modifier.testTag("outline-child-${node.id}")){Text("＋ 子主题")}
                                TextButton(onClick={editor=CardEditor(parent=nodeById[node.parentId])},enabled=editable,modifier=Modifier.testTag("outline-sibling-${node.id}")){Text("＋ 同级")}
                                TextButton(onClick={focusBranch(node.id)},modifier=Modifier.testTag("outline-focus-${node.id}")){Text("聚焦")}
                            }
                        }}
                };if(active.isEmpty())item{Text("大纲与脑图使用同一组节点和摘要卡，不另存一份正文。",color=Quiet)}}
            }
            if(tab!=2&&vm.captureUndo[mapKey]!=null)TextButton(vm::undoCapture,enabled=editable,modifier=Modifier.testTag("study-undo-capture")){Text("撤销此次摘录添加")}
            if(tab==2&&!compactWindow)Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(end=if(compactWindow)48.dp else 0.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton({map?.zoom(1/1.2f)},modifier=Modifier.describedAs("缩小思维导图")){Text("−")}
                TextButton({map?.fit()}){Text("适配")}
                IconButton({map?.zoom(1.2f)},modifier=Modifier.describedAs("放大思维导图")){Text("＋")}
                if(vm.captureUndo[mapKey]!=null)IconButton(vm::undoCapture,enabled=editable,modifier=Modifier.size(48.dp).testTag("study-undo-capture").describedAs("撤销此次摘录添加")){Glyph("undo")}
                else if(focusId==null)Text("${shown.size} / ${active.size} 个主题",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Quiet)
            }
        }}
    }
    if(saveTemplate)StudyDialog(compactWindow,onDismissRequest={if(!mapSaving)saveTemplate=false},title={Text("另存为结构模板")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(templateTitle,{if(it.length<=120)templateTitle=it},enabled=!mapSaving,label={Text("模板名称")},modifier=Modifier.testTag("map-template-title"))
        Text("仅保留结构和位置，主题默认换成占位名称。",style=MaterialTheme.typography.bodySmall)
        Row(verticalAlignment=Alignment.CenterVertically){Text("保留普通主题标题",Modifier.weight(1f));Switch(keepTemplateTitles,{keepTemplateTitles=it},enabled=!mapSaving)}
        Text(if(keepTemplateTitles)"${active.size} 个主题，保留当前标题"else"${active.size} 个占位主题，不含来源和正文",style=MaterialTheme.typography.bodySmall)
        active.take(6).forEachIndexed{i,n->Text(if(keepTemplateTitles)cardById[n.cardId]?.title.orEmpty()else"主题 ${i+1}",style=MaterialTheme.typography.bodySmall)}
        localMessage?.let{Text(it)}
        mapWrite.message?.let{Text(it)}
    }},confirmButton={TextButton({if(mapWrite.unknown)mapWriter.retry()else {val draft=runCatching{MapTemplates.anonymize(templateTitle.trim(),definition?.layout?:"right",ui.nodes.map{it.model()},displayCards.associate{it.id to it.title},keepTemplateTitles)}.getOrElse{localMessage="模板最多支持 128 个主题、32 层；原图保留。";return@TextButton};mapWriter.submit(note.base.id,draft)}},enabled=!mapWrite.busy&&templateTitle.isNotBlank(),modifier=Modifier.testTag("save-map-template-confirm")){Text(if(mapWrite.unknown)"核对原操作"else"保存模板")}},dismissButton={TextButton({saveTemplate=false},enabled=!mapSaving){Text("取消")}})
    if(mapWrite.unknown&&newMapTitle==null&&!saveTemplate&&titleDraft?.structural!=true)StudyDialog(compactWindow,onDismissRequest={},title={Text("核对新建图")},text={Text("上次操作结果尚未确认，继续核对不会重复创建。")},confirmButton={TextButton(mapWriter::retry,enabled=!mapWrite.busy){Text("核对原操作")}})
    newMapTitle?.let{title->StudyDialog(compactWindow,onDismissRequest={if(!mapSaving)newMapTitle=null},title={Text("新建独立图")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(title,{if(it.length<=120)newMapTitle=it},enabled=!mapSaving,modifier=Modifier.testTag("study-new-map-title"))
        Text("${template.nodes.size} 个结构主题",style=MaterialTheme.typography.labelMedium)
        template.nodes.take(6).forEach{Text(it.title,style=MaterialTheme.typography.bodySmall)}
        mapWrite.message?.let{Text(it)}
    }},confirmButton={TextButton({if(mapWrite.unknown)mapWriter.retry()else mapWriter.submit(note.base.id,MapTemplates.instantiate(template,title.trim()),template=installedMapHash?.let{h->installedMapId?.let{TemplateRef(h,it)}})},enabled=!mapWrite.busy&&title.isNotBlank(),modifier=Modifier.testTag("study-new-map-save")){Text(if(mapWrite.unknown)"核对原操作"else"创建")}},dismissButton={TextButton({newMapTitle=null},enabled=!mapSaving){Text("取消")}})}
    editor?.let{e->key(e.card?.id,e.parent?.id,e.source?.pageId){
        var title by rememberSaveable{mutableStateOf(e.card?.title.orEmpty())};var text by rememberSaveable{mutableStateOf(e.card?.body.orEmpty())}
        StudyDialog(compactWindow,onDismissRequest={if(!ui.busy&&!ui.unknown){editor=null;returnTab?.let(vm::selectTab);returnTab=null}},modifier=Modifier.testTag("study-card-editor"),title={Text(if(e.card!=null)"编辑共享摘要卡"else"新建摘要卡")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(e.source!=null)Text("保留框选原迹快照及回源位置。下方是你的摘要，不是自动识别或 AI 生成。",fontSize=12.sp,color=Quiet)
            OutlinedTextField(title,{if(it.length<=120)title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth().testTag("study-card-title"))
            if(e.card?.id !in structureCards.map{it.id})OutlinedTextField(text,{if(it.length<=20000)text=it},label={Text("我的理解 / 摘要")},minLines=2,maxLines=if(compactWindow)4 else Int.MAX_VALUE,modifier=Modifier.fillMaxWidth().heightIn(min=if(compactWindow)96.dp else 160.dp).testTag("study-card-body"))
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
            localMessage?.let{Text(it,fontSize=12.sp)}
        }},confirmButton={TextButton(onClick={val old=e.card;val parent=e.parent
            if(old!=null&&definition?.structures?.any{it.id==old.id}==true){val mapRow=maps.first{it.id==currentMap};if(mapRow.revision!=old.revision){localMessage="结构主题已经变化，草稿已保留，请核对后再编辑。";return@TextButton};mapWriter.submit(note.base.id,definition.copy(structures=definition.structures.map{if(it.id==old.id)it.copy(title=title.trim())else it}),mapRow);editor=null}
            else vm.submit(StudyCommand(id(),note.base.id,if(old==null)StudyAction.CREATE else StudyAction.EDIT,mapId=currentMap,cardId=old?.id?:id(),nodeId=if(old==null)id()else null,
                expectedRevision=old?.revision?:0L,parentId=parent?.id,title=title.trim(),body=text,x=(if(parent==null)40.0 else parent.x+260).coerceIn(-40000.0,40000.0),y=(if(parent==null)ui.nodes.count{!it.removed}*128.0+80 else parent.y+128).coerceIn(-40000.0,40000.0),source=e.source))
        },enabled=title.isNotBlank()&&!ui.busy&&!ui.unknown,modifier=Modifier.testTag("study-save-card")){Text("保存")}},dismissButton={TextButton(onClick={editor=null;returnTab?.let(vm::selectTab);returnTab=null},enabled=!ui.busy&&!ui.unknown){Text("取消")}})
    }}
    chosenCard?.let{chosen->val card=displayCards.find{it.id==chosen.id}?:chosen;val node=chosenNode?.let{n->ui.nodes.find{it.id==n.id}?:n}
        var showPositions by remember(card.id){mutableStateOf(false)}
        var showSource by remember(card.id){mutableStateOf(inspectSource)}
        var moreActions by remember(card.id){mutableStateOf(false)}
        CardInspector(compactWindow,onDismissRequest={chosenCard=null;chosenNode=null;inspectSource=false},modifier=Modifier.testTag("study-card-details"),title={Text(card.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row{
                TextButton(onClick={editor=CardEditor(card);chosenCard=null},enabled=editable,modifier=Modifier.testTag("study-edit-card")){Text(if(card.id in structureCards.map{it.id})"修改标题"else"编辑笔记")}
                if(node==null)TextButton(onClick={moreActions=!moreActions},modifier=Modifier.testTag("card-management-actions")){Text("卡片管理")}
            }
            if(!inspectSource)androidx.compose.foundation.text.selection.SelectionContainer{Text(card.body.ifBlank{"尚未填写摘要"},modifier=Modifier.testTag("card-full-body"))}
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
            if(localMessage!=null)Text(localMessage!!,fontSize=12.sp)
            if(card.trashedAt==null){
                val positions=mainNodes.filter{!it.removed&&it.cardId==card.id}.map{Triple<String?,String,String>(null,it.id,"主图")}+
                    extraRows.mapNotNull{r->(r.data() as? KnowledgeData.MapOccurrence)?.takeIf{!r.removed&&r.notebookId==note.base.id&&it.cardId==card.id}?.let{d->
                        Triple(d.mapId,r.id,(maps.find{it.id==d.mapId}?.data() as? KnowledgeData.MapDefinition)?.title?:"图不可用")}}
                if(positions.isNotEmpty()){
                    TextButton({showPositions=!showPositions},modifier=Modifier.testTag("card-positions")){Text("引用位置 · ${positions.size} 处") }
                    if(showPositions)positions.forEachIndexed{i,(targetMap,targetNode,title)->TextButton(onClick={
                        vm.selectMap(targetMap);vm.selectedByMap[targetMap?:"main"]=targetNode
                        vm.selectTab(2);chosenCard=null;chosenNode=null
                    },enabled=editable,modifier=Modifier.testTag("study-position-$targetNode")){Text("$title · 位置 ${i+1}")}}
                }
            }
            source?.let{s->
                TextButton({showSource=!showSource},modifier=Modifier.testTag("card-source-section")){Text(if(showSource)"收起来源"else"查看来源")}
                if(showSource){val strokes by produceState<List<InkStroke>>(emptyList(),s.cardId){value=withContext(Dispatchers.Default){InkPageFile.decode(s.snapshot).strokes}}
                Text(if(stale)"来源页面已变化，下方保留摘录时快照。"else"摘录时的原迹快照",fontSize=12.sp,color=Quiet)
                SourceThumbnail(s.cardId,Modifier.fillMaxWidth().height(150.dp))
                TextButton(onClick={if(openSource(s)){chosenCard=null;vm.selectedByMap[mapKey]=node?.id}else localMessage="来源页已回收或不可用；原迹快照仍保留。"},enabled=editable,modifier=Modifier.testTag("study-open-source")){Text("返回来源区域")}}}
            TextButton(onClick={knowledgeCard=card;chosenCard=null},enabled=card.id !in structureCards.map{it.id},modifier=Modifier.testTag("card-properties")){Text("属性与回忆")}
            if(node!=null)TextButton({moreActions=!moreActions},modifier=Modifier.testTag("card-node-actions")){Text("组织此主题")}
            if(card.trashedAt==null&&moreActions){
                if(card.id !in structureCards.map{it.id})TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REUSE,mapId=currentMap,cardId=card.id,nodeId=id(),y=ui.nodes.count{!it.removed}*128.0+80))},modifier=Modifier.testTag("study-reuse-card")){Text("复用到脑图新位置")}
                if(node!=null){
                    TextButton(onClick={focusBranch(node.id);chosenCard=null;chosenNode=null},modifier=Modifier.testTag("study-focus-branch")){Text("聚焦此分支")}
                    if(active.any{it.parentId==node.id})TextButton(onClick={toggleBranch(node.id);chosenCard=null;chosenNode=null},modifier=Modifier.testTag("study-toggle-branch")){Text(if(node.id in collapsed)"展开下级主题"else"收起下级主题")}
                    TextButton(onClick={editor=CardEditor(parent=node);chosenCard=null},modifier=Modifier.testTag("study-add-child")){Text("添加子主题")}
                    TextButton(onClick={editor=CardEditor(parent=nodeById[node.parentId]);chosenCard=null},modifier=Modifier.testTag("study-add-sibling")){Text("添加同级主题")}
                    TextButton(onClick={reparent=node;chosenCard=null}){Text("修改上级主题")}
                    TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REMOVE_NODE,mapId=currentMap,nodeId=node.id,expectedRevision=node.revision))},modifier=Modifier.testTag("study-remove-node")){Text("移除此节点（保留摘要卡）")}
                }
                if(node==null&&card.id !in structureCards.map{it.id})TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.TRASH_CARD,mapId=currentMap,cardId=card.id,expectedRevision=card.revision))},enabled=occurrenceCount(card.id)==0){Text("移入卡片回收区")}
            }else if(card.trashedAt!=null)TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.RESTORE_CARD,mapId=currentMap,cardId=card.id,expectedRevision=card.revision))}){Text("恢复卡片")}
        }},confirmButton={TextButton(onClick={chosenCard=null;chosenNode=null;inspectSource=false},modifier=Modifier.testTag("card-back")){Text(if(node!=null)"返回原节点"else"关闭")}})
    }
    if(insertMap&&onInsertEmbed!=null)scenes.find{it.ref==MapRef(note.base.id,currentMap)}?.let{scene->MapInsertPanel(scene,vm.selectedByMap[mapKey],compactWindow,{insertMap=false},onInsertEmbed)}
    if(contentSearch)MapSearchPanel(MapRef(note.base.id,currentMap),{contentSearch=false}){query,all,hit->contentSearch=false;vm.selectTab(2);vm.beginSearch(query,all,hit)}
    knowledgeCard?.let{card->KnowledgeWorkspace(note.base.id,TargetRef(TargetKind.CARD,card.id),dismiss={knowledgeCard=null}){target->app.openKnowledgeTarget.value=target;knowledgeCard=null;dismiss()}}
    reparent?.let{node->StudyDialog(compactWindow,onDismissRequest={reparent=null},title={Text("选择上级主题")},text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())){
        val active=ui.nodes.filter{!it.removed&&it.id!=node.id};val options=listOf<StudyNodeRow?>(null)+active
        options.forEach{p->TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REPARENT,mapId=currentMap,nodeId=node.id,expectedRevision=node.revision,parentId=p?.id))}){Text(p?.let{n->ui.cards.find{it.id==n.cardId}?.title}?:"无上级（根主题）")}}
    }},confirmButton={TextButton(onClick={reparent=null}){Text("取消")}})}
    }
    }
}
