// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

@Composable
fun InkScreen(note:NoteDraft,workspace:WorkspaceViewModel=viewModel(),onBack:()->Unit={},onRename:()->Unit={},onText:()->Unit={},onDiagnostics:()->Unit={},fullScreen:Boolean=false,onFullScreen:(Boolean)->Unit={},notebookSwitcher:@Composable (Modifier)->Unit={}){
    BoxWithConstraints {
    val paneWidth=maxWidth
    val density=LocalDensity.current
    var chromeHeight by remember(note.base.id){mutableStateOf(if(fullScreen)0.dp else 48.dp)}
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val readLock=rememberBookReadLock(note.base.id)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    val readOnlyRequest=remember(note.base.id){mutableStateOf<((Boolean)->Boolean)?>(null)}
    val pageToolRequest=remember(note.base.id){mutableStateOf<((String)->Unit)?>(null)}
    val studyReviewLeaveRequest=remember(note.base.id){mutableStateOf<(() -> Boolean)?>(null)}
    val workModeRequest=remember(note.base.id){mutableStateOf<((StudyWorkMode)->Boolean)?>(null)}
    var recalling by remember(note.base.id){mutableStateOf(false)}
    fun leaveStudyReview():Boolean=studyReviewLeaveRequest.value?.invoke()!=false
    val hasAuthorDraft by readLock.hasDraft.collectAsStateWithLifecycle()
    val vm:BookPagesViewModel=viewModel(key="book-${note.base.id}",factory=BookPagesViewModel.Factory(note.base.id,app.pages,app.workspaceRepository,app.resourcePacks))
    val ui by vm.ui.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    SideEffect{vm.authorAllowed={readLock.canWrite}}
    val requestedPages by workspace.pendingPageNavigation.collectAsStateWithLifecycle()
    val requested=requestedPages[note.base.id]
    LaunchedEffect(requested,ui.loading,ui.pages.size){
        if(requested!=null&&!ui.loading&&ui.pages.any{it.id==requested}){
            vm.select(requested);workspace.consumePageNavigation(note.base.id,requested)
        }
    }
    val readingPrefs=remember(context){context.getSharedPreferences("inkweft-reading",android.content.Context.MODE_PRIVATE)}
    val newPagePaperPreference=remember(readingPrefs,note.base.id){NewPagePaperPreference(readingPrefs,note.base.id)}
    var newPagePaper by remember(newPagePaperPreference){mutableStateOf<PaperStyle?>(null)}
    var newPagePaperReady by remember(newPagePaperPreference){mutableStateOf(false)}
    LaunchedEffect(newPagePaperPreference){newPagePaperPreference.observe().collect{newPagePaper=it;newPagePaperReady=true}}
    var newPagePaperPicker by remember(note.base.id){mutableStateOf(false)}
    var continuous by rememberSaveable(note.base.id){mutableStateOf(readingPrefs.getBoolean("continuous-v20-${note.base.id}",true))}
    fun readingMode(value:Boolean){continuous=value;readingPrefs.edit().putBoolean("continuous-v20-${note.base.id}",value).apply()}
    var canNavigate by remember{mutableStateOf(false)};var directory by remember{mutableStateOf(false)}
    androidx.activity.compose.BackHandler(enabled=!canNavigate||hasAuthorDraft||ui.busy||ui.actionUnknown||ui.insertionUnknown){
        Toast.makeText(context,"请先抬笔或核对当前操作，笔记仍保持在当前页。",Toast.LENGTH_SHORT).show()
    }
    var searchTarget by remember{mutableStateOf<PageSearchDraft?>(null)}
    var exportBytes by remember{mutableStateOf<ByteArray?>(null)};var exporting by remember{mutableStateOf(false)}
    var confirmBook by remember{mutableStateOf(false)}
    var insertion by remember{mutableStateOf<Pair<String,PageInsertLocation>?>(null)}
    var pageActionId by rememberSaveable{mutableStateOf<String?>(null)}
    var pageActionKind by rememberSaveable{mutableStateOf(PageEditKind.MOVE.name)}
    var showRecycled by rememberSaveable{mutableStateOf(false)}
    var knowledgeOpen by rememberSaveable(note.base.id){mutableStateOf(false)}
    var resumeMapAfterAssociations by rememberSaveable(note.base.id){mutableStateOf(false)}
    var knowledgeAnchor by rememberSaveable(note.base.id,stateSaver=androidx.compose.runtime.saveable.Saver<KnowledgeData.Anchor?,ByteArray>(
        {it?.let(KnowledgeCodec::encode)}, {runCatching{KnowledgeCodec.decode(it) as? KnowledgeData.Anchor}.getOrNull()})){mutableStateOf<KnowledgeData.Anchor?>(null)}
    var mapSearchOpen by remember{mutableStateOf(false)}
    var searchOpen by rememberSaveable(note.base.id){mutableStateOf(false)}
    val searchStateHolder=rememberSaveableStateHolder()
    var documentMore by remember{mutableStateOf(false)}
    var documentSettings by remember{mutableStateOf(false)}
    var classify by remember{mutableStateOf(false)}
    val entries by workspace.entries.collectAsStateWithLifecycle()
    val studyPanel:StudyPanelSession=viewModel(key="study-panel-${note.base.id}")
    var initialStudyCard by studyPanel.card
    var initialStudyCardRequest by rememberSaveable(note.base.id){mutableLongStateOf(0L)}
    var studyOpen by studyPanel.opened
    var studySource by studyPanel.source
    var sourceNavigation by remember(note.base.id){mutableStateOf<Job?>(null)}
    fun cancelSourceNavigation(){val request=sourceNavigation;sourceNavigation=null;request?.cancel()}
    DisposableEffect(note.base.id){onDispose{cancelSourceNavigation()}}
    var mapMinimized by rememberSaveable(note.base.id){mutableStateOf(false)}
    val studyUiState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var sourceFocus by remember{mutableStateOf<Pair<String,CanvasBounds>?>(null)}
    var sourceFocusRequest by remember(note.base.id){mutableIntStateOf(0)}
    // A temporary reading layout keeps its target through resize/recreation, without saving window geometry.
    var sourceReadingTarget by rememberSaveable(note.base.id,stateSaver=androidx.compose.runtime.saveable.Saver<Pair<String,CanvasBounds>?,ArrayList<String>>(
        {target->target?.let{(id,b)->arrayListOf(id,b.left.toString(),b.top.toString(),b.right.toString(),b.bottom.toString())}},
        {values->runCatching{values[0] to CanvasBounds(values[1].toDouble(),values[2].toDouble(),values[3].toDouble(),values[4].toDouble())}.getOrNull()})){
        mutableStateOf<Pair<String,CanvasBounds>?>(null)
    }

    val requestedCards by workspace.pendingStudyCardNavigation.collectAsStateWithLifecycle()
    val requestedCard=requestedCards[note.base.id]
    val externalAnchor by workspace.focusAnchor.collectAsStateWithLifecycle()

    fun requestAction(p:NotebookPageRow,kind:PageEditKind){if(!readLock.canWrite)return;directory=false;pageActionKind=kind.name;pageActionId=p.id}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val bytes=exportBytes;exportBytes=null
        if(uri!=null&&bytes!=null)scope.launch{val ok=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes)}};true}catch(c:CancellationException){throw c}catch(_:Exception){false};Toast.makeText(context,if(ok)"整本内容副本已导出"else"导出失败，原笔记保留",Toast.LENGTH_LONG).show()}
    }
    val page=ui.pages.firstOrNull{it.id==ui.selectedId}
    LaunchedEffect(note.base.id,page?.id){page?.let{app.learningStore.visit(StableTargetRef(LearningTargetKind.PAGE,note.base.id,it.id))}}
    var paperPicker by remember{mutableStateOf(false)}
    var gridView by rememberSaveable{mutableStateOf(false)}
    var overviewTab by rememberSaveable(note.base.id){mutableIntStateOf(0)}
    var excerptRequest by remember{mutableIntStateOf(0)}
    val marksModel:OverviewViewModel=viewModel(key="overview-${note.base.id}",factory=OverviewViewModel.Factory(note.base.id,app))
    val marks by marksModel.marks.collectAsStateWithLifecycle()
    val marksBusy by marksModel.busy.collectAsStateWithLifecycle()
    val marksError by marksModel.error.collectAsStateWithLifecycle()
    val studySession:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val studyLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(studySession,studyLifecycle){studyLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){studySession.attach();try{awaitCancellation()}finally{studySession.detach()}}}
    val studyState by studySession.ui.collectAsStateWithLifecycle()
    val selectedMapId by studySession.mapId.collectAsStateWithLifecycle()
    val studyCanLeave=if(studyOpen){
        val mapWriter:KnowledgeViewModel=viewModel(key="study-map-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
        val mapWrite by mapWriter.ui.collectAsStateWithLifecycle()
        val portalWriter:KnowledgeViewModel=viewModel(key="map-portal-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
        val portalWrite by portalWriter.ui.collectAsStateWithLifecycle()
        !studyState.busy&&!studyState.unknown&&!mapWrite.busy&&!mapWrite.unknown&&!portalWrite.busy&&!portalWrite.unknown
    }else !studyState.busy&&!studyState.unknown
    LaunchedEffect(studySession.captureGeneration){if(studyPanel.captureDraft.value!=null&&studySession.captureGeneration>0){
        studyPanel.captureResult.value=studyPanel.captureTarget.value;studyPanel.captureDraft.value=null
        studyPanel.captureResult.value?.takeIf{it.second!="inbox"}?.let{(target,parent)->
            val prefs=context.getSharedPreferences("inkweft-map-destinations",0);val recent=prefs.getString("${note.base.id}-recent","").orEmpty().split(',').filter{it.isNotBlank()&&it!=target.key}
            prefs.edit().putString("${note.base.id}-last",target.key).putString("${note.base.id}-parent",parent).putString("${note.base.id}-recent",(listOf(target.key)+recent).take(8).joinToString(",")).apply()
        }
    }}
    var excerptsOpen by rememberSaveable(note.base.id){mutableStateOf(false)}
    val excerptsVm:StudyViewModel=viewModel(key="excerpt-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val excerptsState by excerptsVm.ui.collectAsStateWithLifecycle()
    val excerptReady=!excerptsState.busy&&!excerptsState.unknown
    SideEffect{studySession.authorAllowed={readLock.canWrite};excerptsVm.authorAllowed={readLock.canWrite}}
    val bookDraft=studyPanel.captureDraft.value!=null||studyPanel.embedInsertion.value!=null||pageActionId!=null||insertion!=null||searchTarget!=null||newPagePaperPicker
    ReadLockGuard(readLock,"book-writers",ui.busy||ui.actionUnknown||ui.insertionUnknown||!studyCanLeave||!excerptReady||bookDraft,bookDraft)
    val panelOpen=directory||documentSettings||excerptsOpen
    var floatingMap by rememberSaveable(note.base.id){mutableStateOf(false)}
    var mapActive by rememberSaveable(note.base.id){mutableStateOf(true)}
    var mapFocused by remember(note.base.id){mutableStateOf(false)}
    var paperAuthorDraft by remember(note.base.id){mutableStateOf(false)}
    var mapAuthorDraft by remember(note.base.id){mutableStateOf(false)}
    val effectiveMapActive=when{mapAuthorDraft->true;paperAuthorDraft->false;else->mapActive}
    val docked=maxWidth>=700.dp
    val panelWidth=minOf(320.dp,maxWidth-24.dp)
    val paneLayout=studyPaneLayout(maxWidth,(maxHeight-chromeHeight).coerceAtLeast(0.dp),density.fontScale)
    val sourceReading=studyOpen&&sourceReadingTarget!=null
    val effectiveMapDocked=paneLayout.sideBySide&&(!floatingMap||sourceReading)
    val mapVisible=studyOpen&&!mapMinimized&&(paneLayout.sideBySide||(!sourceReading&&effectiveMapActive))
    // A compact map overlays the retained page; only an explicit focus window covers it.
    val paperVisible=!mapFocused||!mapVisible
    val paperEndInset=maxOf(if(panelOpen&&docked)panelWidth else 0.dp,
        if(studyOpen&&effectiveMapDocked&&!mapMinimized)paneLayout.mapWidth+12.dp else 0.dp)
    fun endSourceReading(){cancelSourceNavigation();sourceFocusRequest++;sourceReadingTarget=null;sourceFocus=null}
    fun showDocumentPanel(target:String,anchor:KnowledgeData.Anchor?=null):Boolean {
        // The association workspace owns its draft/unknown-result exit guard.
        if(knowledgeOpen||(target=="map"&&!paneLayout.sideBySide&&!mapVisible&&hasAuthorDraft)||!leaveStudyReview())return false
        resumeMapAfterAssociations=target=="associations"&&studyOpen&&!mapMinimized
        endSourceReading();documentMore=false
        directory=target=="overview";documentSettings=target=="settings";excerptsOpen=target=="excerpts"
        knowledgeOpen=target=="associations";searchOpen=target=="search";mapSearchOpen=target=="map-search"
        if(knowledgeOpen)knowledgeAnchor=anchor
        mapMinimized=target!="map"
        if(target=="map"){studyOpen=true;if(!hasAuthorDraft)mapActive=true}
        return true
    }
    fun revealSource(id:String,bounds:CanvasBounds){
        readingMode(false);directory=false;documentSettings=false;excerptsOpen=false
        sourceFocusRequest++;sourceFocus=id to bounds;sourceReadingTarget=sourceFocus.takeIf{studyOpen}
    }
    LaunchedEffect(externalAnchor,ui.loading,ui.selectedId){externalAnchor?.let{anchor->
        if(ui.selectedId==anchor.pageId&&!ui.loading){revealSource(anchor.pageId,anchor.bounds);workspace.focusAnchor.value=null}
    }}
    LaunchedEffect(page?.id){val actual=vm.ui.value
        if(page!=null&&!actual.loading&&actual.selectedId==page.id&&sourceReadingTarget?.first?.let{it!=actual.selectedId}==true)endSourceReading()
    }
    val pageActionsReady=canNavigate&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown&&studyCanLeave&&excerptReady
    val pageAuthorReady=pageActionsReady&&!readOnly
    val pageInsertReady=pageAuthorReady&&newPagePaperReady
    LaunchedEffect(note.base.id,requestedCard,pageActionsReady,hasAuthorDraft,knowledgeOpen){requestedCard?.let{if(pageActionsReady&&!hasAuthorDraft&&showDocumentPanel("map")){initialStudyCard=it;initialStudyCardRequest++;workspace.consumeStudyCardNavigation(note.base.id,it)}}}
    val sourceReady by rememberUpdatedState(pageActionsReady&&!hasAuthorDraft&&studyOpen&&!mapMinimized)
    SideEffect{if(!sourceReady)cancelSourceNavigation();app.navigationReady.value=pageActionsReady&&!hasAuthorDraft}
    androidx.activity.compose.BackHandler(panelOpen&&pageActionsReady&&!hasAuthorDraft){if(leaveStudyReview()){endSourceReading();directory=false;documentSettings=false;mapMinimized=true;excerptsOpen=false}}
    val compactHeader=paneWidth<760.dp*density.fontScale.coerceAtLeast(1f)
    val toolbar by rememberEditorToolbarPreferences()
    @Composable fun documentAction(label:String,icon:String,enabled:Boolean,tag:String,action:()->Unit){
        EditorAction(label,icon,enabled,tag,action)
    }
    @Composable fun documentDestination(id:String,inMenu:Boolean=false){
        val label=when(id){"map"->if(sourceReading)"返回导图"else"导图";"associations"->"关联";else->"摘录"}
        val tag=when(id){"map"->if(sourceReading)"study-window-source-return"else"quick-study";"associations"->"document-associations";else->"read-excerpts"}
        val enabled=pageActionsReady&&(!hasAuthorDraft||(id=="map"&&(paneLayout.sideBySide||mapVisible)))
        val action:()->Unit={
            if(id=="map"&&sourceReading){if(leaveStudyReview()){documentMore=false;endSourceReading();mapActive=true}}
            else showDocumentPanel(id)
        }
        if(inMenu)DropdownMenuItem(text={Text(label)},leadingIcon={Glyph(EditorToolOrder.icon(id))},
            enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag(tag),onClick=action)
        else documentAction(label,EditorToolOrder.icon(id),enabled,tag,action)
    }
    val documentTools:@Composable ()->Unit={
        toolbar.destinations(hidden=true).forEach{documentDestination(it,inMenu=true)}
        DropdownMenuItem(text={Text("文档概览")},leadingIcon={Glyph("overview")},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.testTag("quick-overview"),onClick={showDocumentPanel(if(directory)"none"else"overview")})
        DropdownMenuItem(text={Text("查找笔记")},leadingIcon={Glyph("search")},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.testTag("book-search"),onClick={showDocumentPanel("search")})
        DropdownMenuItem(text={Text("文档设置")},leadingIcon={Glyph("settings")},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.testTag("quick-settings"),onClick={showDocumentPanel(if(documentSettings)"none"else"settings")})
        DropdownMenuItem(text={Text("容量与整理")},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.testTag("document-capacity"),onClick={if(showDocumentPanel("map"))studySession.openCapacity()})
        if(!readOnly)DropdownMenuItem(text={Text("添加页面")},leadingIcon={Glyph("add-page")},enabled=pageInsertReady&&page?.world==false,modifier=Modifier.testTag("document-add-page"),onClick={page?.let{if(showDocumentPanel("none"))insertion=it.id to PageInsertLocation.AFTER}})
        HorizontalDivider()
        DropdownMenuItem(text={Text("全屏专注")},leadingIcon={Glyph("fullscreen")},enabled=pageActionsReady,modifier=Modifier.testTag("quick-fullscreen"),onClick={documentMore=false;onFullScreen(true)})
        DropdownMenuItem(text={Text("导出文档")},leadingIcon={Glyph("export")},enabled=pageActionsReady,modifier=Modifier.testTag("quick-export"),onClick={documentMore=false;pageToolRequest.value?.invoke("export")})
        DropdownMenuItem(text={Text("计时器")},leadingIcon={Glyph("timer")},modifier=Modifier.testTag("quick-timer"),onClick={documentMore=false;pageToolRequest.value?.invoke("timer")})
        if(studyOpen&&sourceReading&&!paneLayout.sideBySide)DropdownMenuItem(text={Text("关闭导图")},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.testTag("study-close"),onClick={if(leaveStudyReview()){documentMore=false;endSourceReading();studyOpen=false}})
    }
    val destinations:@Composable ()->Unit={
        if(studyOpen)EditorTool("原文","readonly",!mapVisible,pageActionsReady&&!hasAuthorDraft,"study-pane-source"){
            if(leaveStudyReview()){endSourceReading();mapActive=false;mapMinimized=true}}
        toolbar.destinations().forEach{documentDestination(it)}
    }
    fun chooseWorkMode(mode:StudyWorkMode){
        val handler=workModeRequest.value
        val accepted=when{
            !readLock.canChangeMode(pageActionsReady&&!knowledgeOpen)->false
            handler!=null->handler(mode)
            mode==StudyWorkMode.RECALL->{studyOpen=true;studyPanel.reviewRequest.value++;true}
            paperVisible->readOnlyRequest.value?.invoke(mode==StudyWorkMode.READ)==true
            else->readLock.request(mode==StudyWorkMode.READ,pageActionsReady)
        }
        if(!accepted)Toast.makeText(context,readLock.reason.ifBlank{"页面尚未准备好，请先核对当前操作"},Toast.LENGTH_SHORT).show()
    }
    val compactModes=paneWidth<320.dp*density.fontScale.coerceAtLeast(1f)
    val documentModes:@Composable ()->Unit={StudyWorkModes(if(recalling)StudyWorkMode.RECALL else if(readOnly)StudyWorkMode.READ else StudyWorkMode.WRITE,choose=::chooseWorkMode)}
    val documentMenu:@Composable ()->Unit={
                Box{
                    documentAction("文档","more",pageActionsReady,"document-more"){documentMore=true}
                    DropdownMenu(documentMore,{documentMore=false},modifier=Modifier.testTag("document-more-menu"),containerColor=Color.White){documentTools()}
                }
    }
    val documentBar:@Composable ()->Unit={
        Surface(color=MaterialTheme.colorScheme.surface){
            Column(Modifier.fillMaxWidth().padding(horizontal=4.dp).testTag("document-toolbar")){
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton({if(leaveStudyReview()){endSourceReading();onBack()}},enabled=pageActionsReady&&!hasAuthorDraft,modifier=Modifier.size(48.dp).testTag("back-library").describedAs("返回资料库")){Glyph("back")}
                notebookSwitcher(Modifier.weight(1f))
                if(!compactHeader)destinations()
                if(!compactModes)documentModes()
                documentMenu()
            }
            if(compactHeader||compactModes)FlowRow(Modifier.fillMaxWidth().testTag("document-destinations"),horizontalArrangement=Arrangement.Center){
                if(compactModes)documentModes()
                if(compactHeader)destinations()
            }
            }
        }
    }
    Box(Modifier.fillMaxSize()){
    RetainedStudyPane(paperVisible,Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().padding(top=chromeHeight,end=paperEndInset)){
        if(ui.error!=null)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(ui.error!!,Modifier.weight(1f),fontSize=12.sp);if(!ui.insertionUnknown&&!ui.actionUnknown)TextButton(onClick=vm::clearError){Text("知道了")}}
        if(ui.actionUnknown)Surface(color=androidx.compose.ui.graphics.Color.White){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("页面整理结果尚待核对，原操作身份已保留。",Modifier.weight(1f),fontSize=12.sp)
                TextButton(onClick=vm::retryPageEdit,enabled=!ui.busy,modifier=Modifier.testTag("retry-page-edit")){Text("核对页面操作")}
            }
        }
        if(ui.insertionUnknown)Surface(color=androidx.compose.ui.graphics.Color.White){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text("上次插页尚待核对；先核对原操作，勿另建一批。",Modifier.weight(1f),fontSize=12.sp)
                TextButton(onClick=vm::retryInsertion,enabled=!ui.busy,modifier=Modifier.testTag("retry-page-insertion")){Text("核对原插页")}
            }
        }
        if(page==null)Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){if(ui.loading)CircularProgressIndicator()else Text("页面未能载入，原数据保留")}
        else Box(Modifier.weight(1f)){val pendingSourceRequest=sourceFocusRequest;val pendingSourceFocus=sourceFocus?.takeIf{it.first==page.id};InkPageScreen(note,workspace,page,{if(!it)cancelSourceNavigation();canNavigate=it},{_->if(!hasAuthorDraft)showDocumentPanel("search")},paperVisible&&!mapAuthorDraft&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown&&pageActionId==null,
            readOnlyRequest=readOnlyRequest,workspaceModesProvided=true,pageToolRequest=pageToolRequest,onAuthorDraft={paperAuthorDraft=it},embedRequest=studyPanel.embedInsertion.value?.takeIf{it.pageId==page.id},onEmbedConsumed={studyPanel.embedInsertion.value=null},
            onEditMap={embed->if(showDocumentPanel("map")){studySession.selectMap(embed.target.mapId);studySession.selectTab(2);studySession.focusedByMap[embed.target.key]=embed.branchId;embed.branchId?.let{studySession.revealByMap[embed.target.key]=it}}},
            onMapExcerpt={selection->if(readLock.canWrite){studySession.clear();studyPanel.captureResult.value=null;studyPanel.captureDraft.value=CaptureDraft(note.base.id,StudySourceDraft(page.id,selection.revision,selection.region.bounds,selection.strokes.map{it.id},selection.preview,selection.objectRevision,selection.authoringRevision),selection.excerptText)}},
            onAssociate={selection->if(readLock.canWrite)showDocumentPanel("associations",KnowledgeData.Anchor(page.id,selection.revision,selection.region.bounds,selection.strokes.map{it.id}))},
            onExcerpt={selection->
                if(excerptReady&&readLock.canWrite&&showDocumentPanel("excerpts")){excerptsVm.submit(StudyCommand(java.util.UUID.randomUUID().toString(),note.base.id,StudyAction.CREATE_EXCERPT,cardId=java.util.UUID.randomUUID().toString(),title="第${page.position+1}页摘录",body=selection.excerptText,source=StudySourceDraft(page.id,selection.revision,selection.region.bounds,selection.strokes.map{it.id},selection.preview,selection.objectRevision,selection.authoringRevision)))}
            },
            focusRegion=pendingSourceFocus?.second?:sourceReadingTarget?.takeIf{it.first==page.id}?.second,focusRequest=pendingSourceRequest,onFocusConsumed={if(sourceFocusRequest==pendingSourceRequest&&sourceFocus==pendingSourceFocus)sourceFocus=null},pageNavigation={
                if(!page.world)Text("${page.position+1} / ${ui.pages.size}",fontSize=12.sp,modifier=Modifier.testTag("page-counter"))
            },continuousPages=if(continuous&&!page.world)ui.pages else null,onContinuousPage={if(it!=ui.selectedId)vm.select(it)},leaveContinuous={readingMode(false)},onTags={classify=true},fullScreen=fullScreen,onFullScreen=onFullScreen,canAddPage=newPagePaperReady&&!page.world&&ui.pages.size+ui.recycled.size<500,
            onAppendPage=if(pageInsertReady&&!page.world&&ui.pages.size+ui.recycled.size<InsertPages.MAX_PAGES)({vm.appendBlankPage(ui.pages.last().id,newPagePaper)})else null,
            excerptRequest=excerptRequest,onDocumentAction={action->if(!hasAuthorDraft||action=="map"||action=="export")when(action){
                "map","excerpts","associations"->showDocumentPanel(action)
                "overview"->showDocumentPanel(if(directory)"none"else"overview")
                "settings"->showDocumentPanel(if(documentSettings)"none"else"settings")
                "add-page"->if(readLock.canWrite&&newPagePaperReady&&showDocumentPanel("none"))insertion=page.id to PageInsertLocation.AFTER
                "export"->confirmBook=true
            }})}
    }
    }
    Column(Modifier.fillMaxWidth().align(Alignment.TopStart).onSizeChanged{chromeHeight=with(density){it.height.toDp()}}){
        if(!fullScreen)documentBar()
        if(fullScreen)Surface(color=MaterialTheme.colorScheme.surface){
            FlowRow(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp).testTag("study-pane-switcher"),verticalArrangement=Arrangement.Center){
                documentModes()
                if(studyOpen){
                EditorTool("原文","readonly",!mapVisible,pageActionsReady&&!hasAuthorDraft,"study-pane-source"){
                    if(leaveStudyReview()){endSourceReading();mapActive=false;mapMinimized=true}}
                EditorTool(if(sourceReading)"返回导图"else"导图","mindmap",mapVisible,pageActionsReady&&!hasAuthorDraft,
                    if(sourceReading)"study-window-source-return"else"study-pane-map"){
                    if(sourceReading){if(leaveStudyReview()){endSourceReading();mapActive=true}}else showDocumentPanel("map")}
                if(sourceReading&&!paneLayout.sideBySide)IconButton({if(leaveStudyReview()){endSourceReading();studyOpen=false}},enabled=pageActionsReady&&!hasAuthorDraft,
                    modifier=Modifier.size(48.dp).testTag("study-close").describedAs("关闭导图")){Glyph("close")}
                }
            }
        }
    }
    if(excerptsOpen)DocumentSidePanel("摘录","excerpt-panel",{if(excerptReady)excerptsOpen=false},Modifier.align(Alignment.CenterEnd).padding(top=chromeHeight).width(panelWidth)){
        ExcerptCollection(excerptsVm,ui.pages,pageActionsReady,{source->vm.select(source.pageId);revealSource(source.pageId,CanvasBounds(source.left,source.top,source.right,source.bottom))}, {card->if(showDocumentPanel("map")){initialStudyCard=card;initialStudyCardRequest++;studySource=null}})
    }
    if(searchOpen)searchStateHolder.SaveableStateProvider("search-${note.base.id}"){BookSearchPanel(note.base.id,note.title,page?.id,{searchOpen=false},{id,bounds->
        if(ui.pages.any{it.id==id}){if(bounds!=null)revealSource(id,bounds);vm.select(id);searchOpen=false}
    },{draft->searchTarget=draft;searchOpen=false},onMapSearch={showDocumentPanel("map-search")})}
    if(mapSearchOpen)MapSearchPanel(MapRef(note.base.id,selectedMapId),{mapSearchOpen=false}){query,all,hit->if(showDocumentPanel("map")){studySession.selectTab(2);studySession.beginSearch(query,all,hit)}}
    if(documentSettings)DocumentSidePanel("其他设置","document-settings-dialog",{documentSettings=false},Modifier.align(Alignment.CenterEnd).padding(top=chromeHeight).width(panelWidth)){
        var readOnlyReason by remember(note.base.id){mutableStateOf<String?>(null)}
        val inputFocus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        Column(Modifier.verticalScroll(rememberScrollState())){
            DocumentAction(note.title,"settings-rename",pageActionsReady){documentSettings=false;onRename()}
            DocumentAction("文件夹与标签","settings-tags",pageActionsReady&&entries[note.base.id]!=null){documentSettings=false;classify=true}
            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
            DocumentSection("页面设置",page?.let{"第${it.position+1}页"}.orEmpty())
            if(page!=null){
                DocumentAction("更换模板","settings-paper",pageAuthorReady,value=PaperTemplates.title(PaperStyle.entries[page.paper])){paperPicker=true}
                if(!page.world){
                    DocumentAction("本笔记新增页纸面","settings-new-page-paper",pageInsertReady,value=if(newPagePaperReady)newPagePaper?.let(::paperLabel)?:"沿用"else"正在读取…"){newPagePaperPicker=true}
                    PageJumpRow(ui.pages.size,page.position,pageActionsReady){vm.select(ui.pages[it].id)}
                    DocumentAction("添加页面","settings-add-page",pageInsertReady&&ui.pages.size+ui.recycled.size<500){documentSettings=false;insertion=page.id to PageInsertLocation.AFTER}
                    DocumentAction("移动当前页","settings-move-page",pageAuthorReady&&ui.pages.size>1){documentSettings=false;requestAction(page,PageEditKind.MOVE)}
                    DocumentAction("复制当前页","settings-copy-page",pageAuthorReady){documentSettings=false;requestAction(page,PageEditKind.COPY)}
                    DocumentAction("删除当前页","settings-delete-page",pageAuthorReady&&ui.pages.size>1,danger=true){documentSettings=false;requestAction(page,PageEditKind.TRASH)}
                    DocumentAction("已删除页面","settings-deleted-pages",pageActionsReady,value=ui.recycled.size.toString()){overviewTab=0;showRecycled=true;documentSettings=false;directory=true}
                }
            }
            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
            DocumentSection("文档")
            DocumentAction("整理与复习","study-open",pageActionsReady){if(showDocumentPanel("map"))studySource=null}
            DocumentAction("编辑键入文字","mode-text",pageAuthorReady){documentSettings=false;onText()}
            DocumentAction("查找笔记","settings-search",pageActionsReady){showDocumentPanel("search")}
            DocumentAction("链接与反向引用","settings-knowledge",pageActionsReady){showDocumentPanel("associations")}
            if(page!=null&&!page.world)DocumentAction("导出整本内容副本","settings-export",pageActionsReady&&!exporting){documentSettings=false;confirmBook=true}
            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
            DocumentSection("阅读与操作")
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){
                Text("只读浏览",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(readOnly,{value->
                    val request=readOnlyRequest.value
                    if(request==null)readOnlyReason="页面尚未准备好，请稍后重试"
                    else if(request(value)){readOnlyReason=null;inputFocus.clearFocus(force=true);keyboard?.hide();documentSettings=false}
                    else readOnlyReason=readLock.reason
                },enabled=page!=null,modifier=Modifier.testTag("settings-readonly").describedAs("只读浏览，关闭后返回书写"))
            }
            readOnlyReason?.let{Text(it,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).testTag("settings-readonly-reason"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            if(page!=null&&!page.world)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){Text("上下连续翻页",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(continuous,{if(it)endSourceReading();readingMode(it)},enabled=pageActionsReady,modifier=Modifier.testTag("continuous-setting"))}
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){Text("全屏专注",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(fullScreen,onFullScreen,enabled=pageActionsReady,modifier=Modifier.testTag("settings-fullscreen"))}
            DocumentAction("页面整理","settings-page-directory",pageActionsReady){documentSettings=false;showRecycled=false;directory=true}
            DocumentAction("诊断与导出","settings-diagnostics",pageActionsReady){documentSettings=false;onDiagnostics()}
            Spacer(Modifier.height(12.dp))
        }
    }
    if(newPagePaperPicker)NewPagePaperDialog(newPagePaper,pageInsertReady&&page?.world==false,{newPagePaperPicker=false}){style->
        if(!readLock.canWrite||!pageInsertReady||page?.world!=false)false
        else newPagePaperPreference.save(style).also{if(it)newPagePaper=style}
    }
    if(paperPicker&&page!=null)PaperPickerDialog(PaperStyle.entries[page.paper],page.world,{paperPicker=false}){style->if(readLock.canWrite)workspace.paper(page.id,style);paperPicker=false}
    if(classify)entries[note.base.id]?.let{row->NotebookClassificationDialog(row,{classify=false},entries.values.flatMap{it.tags.lines()}.filter{it.isNotBlank()}){folder,tags->workspace.organize(row,folder=folder,tags=tags);classify=false}}
    if(knowledgeOpen)KnowledgeWorkspace(note.base.id,page?.let{TargetRef(TargetKind.PAGE,it.id)}?:TargetRef(TargetKind.NOTE,note.base.id),knowledgeAnchor,dismiss={knowledgeOpen=false;if(resumeMapAfterAssociations)mapMinimized=false;resumeMapAfterAssociations=false}){target->
        app.openKnowledgeTarget.value=target;knowledgeOpen=false;resumeMapAfterAssociations=false
    }
    studyPanel.captureDraft.value?.let{draft->CaptureDestination(draft,studySession,{studyPanel.captureDraft.value=null}){target,parent->studyPanel.captureTarget.value=target to parent}}
    studyPanel.captureResult.value?.let{(target,parent)->Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).testTag("capture-result"),color=Color.White,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),shadowElevation=6.dp){
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(parent=="inbox")"已存入待整理"else"已添加到导图",Modifier.padding(12.dp))
            if(parent!="inbox"){TextButton({if(showDocumentPanel("map")){studySession.selectMap(target.mapId);studySession.selectTab(2);studyPanel.captureResult.value=null}},enabled=pageActionsReady&&!knowledgeOpen){Text("查看")};TextButton({studySession.undoCaptureAt(target.mapId);studyPanel.captureResult.value=null},enabled=studyCanLeave&&!readOnly){Text("撤销")}}
            IconButton({studyPanel.captureResult.value=null},modifier=Modifier.describedAs("关闭添加结果")){Glyph("close")}
        }
    }}
    if(studyOpen)studyUiState.SaveableStateProvider(note.base.id){FloatingStudyWindow(note.base.id,enabled=pageActionsReady&&!hasAuthorDraft&&!knowledgeOpen,minimized=mapMinimized,
        onMinimize={if(it){endSourceReading();mapMinimized=true;mapActive=false}else showDocumentPanel("map")},docked=!floatingMap,onDock={endSourceReading();floatingMap=!it},close={endSourceReading();studyOpen=false},
        paneLayout=paneLayout,topInset=chromeHeight,paneActive=effectiveMapActive,onActivate={if(!hasAuthorDraft)mapActive=true},onAuthorDraft={if(it&&!mapAuthorDraft)endSourceReading();mapAuthorDraft=it},frameEnabled=pageActionsReady&&!knowledgeOpen,sourceReading=sourceReading,beforeContentExit={leaveStudyReview()},onFocusChanged={mapFocused=it}){
        StudyContent(note,studySource,{endSourceReading();studyOpen=false},initialCardId=initialStudyCard,cardOpenRequest=initialStudyCardRequest,documentReady=pageActionsReady,compactWindow=true,showReadControl=false,capacityVisible=mapVisible,sourceRequest=studyPanel.captureRequest.longValue,reviewLeaveRequest=studyReviewLeaveRequest,
            reviewRequest=studyPanel.reviewRequest.value,workModeRequest=workModeRequest,onReviewActive={recalling=it},onInsertEmbed={embed->
            if(readLock.canWrite)page?.let{p->endSourceReading();readingMode(false);studyPanel.embedInsertion.value=EmbedInsertion(p.id,java.util.UUID.randomUUID().toString(),embed);mapMinimized=true}
        }){source->
            currentCoroutineContext().ensureActive()
            if(!studyOpen||mapMinimized||!canNavigate||!sourceReady)throw CancellationException("Source navigation is no longer available")
            val request=currentCoroutineContext().job
            val requestedMap=studySession.mapId.value
            val requestedNode=studySession.selectedByMap[requestedMap?:"main"]
            sourceNavigation?.takeIf{it!==request}?.cancel();sourceNavigation=request
            try{
                val opened=vm.selectAwait(source.pageId)
                currentCoroutineContext().ensureActive()
                if(sourceNavigation!==request||!studyOpen||mapMinimized||!canNavigate||!sourceReady||studySession.mapId.value!=requestedMap||studySession.selectedByMap[requestedMap?:"main"]!=requestedNode)throw CancellationException("Source navigation is no longer available")
                if(opened){revealSource(source.pageId,CanvasBounds(source.left,source.top,source.right,source.bottom));true}else false
            }finally{if(sourceNavigation===request)sourceNavigation=null}
        }
    }
    }
    if(directory)DocumentSidePanel("文档概览","pages-directory-dialog",{directory=false},Modifier.align(Alignment.CenterEnd).padding(top=chromeHeight).width(panelWidth)){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
            listOf("页面" to "overview","大纲" to "list","页签" to "bookmark","摘录" to "excerpt").forEachIndexed{i,(label,icon)->
                Column(Modifier.weight(1f).selectable(selected=overviewTab==i,onClick={overviewTab=i;showRecycled=false},role=androidx.compose.ui.semantics.Role.Tab).heightIn(min=64.dp).padding(vertical=8.dp).testTag("overview-tab-$i"),horizontalAlignment=Alignment.CenterHorizontally){
                    Glyph(icon,if(overviewTab==i)Forest else Quiet);Text(label,style=MaterialTheme.typography.labelMedium,color=if(overviewTab==i)Forest else Quiet)
                    if(overviewTab==i)HorizontalDivider(Modifier.width(24.dp).padding(top=4.dp),color=Forest,thickness=2.dp)
                }
            }
        }
        if(overviewTab==0)OverviewRecovery(marksModel)
        if(overviewTab!=0)OverviewCollections(note.base.id,overviewTab,ui.pages,page,pageActionsReady,app,
            openPage={vm.select(it)},openExcerpt={item->vm.select(item.pageId);revealSource(item.pageId,CanvasBounds(item.left,item.top,item.right,item.bottom))},
            editExcerpt={showDocumentPanel("excerpts")},newExcerpt={if(showDocumentPanel("none")){readingMode(false);excerptRequest++}})
        else {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(showRecycled)"回收区 ${ui.recycled.size}"else"全部 ${ui.pages.size} 页",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=Quiet)
            if(showRecycled)TextButton({showRecycled=false},modifier=Modifier.testTag("pages-active")){Text("返回页面")}
            else IconButton({showRecycled=true},modifier=Modifier.testTag("pages-recycled").describedAs("已删除页面")){Glyph("trash")}
            IconButton({showDocumentPanel("search")},modifier=Modifier.testTag("overview-search").describedAs("查找笔记")){Glyph("search")}
            IconButton({gridView=!gridView},modifier=Modifier.testTag("overview-layout").describedAs(if(gridView)"列表视图"else"平铺视图")){Glyph(if(gridView)"list"else"grid")}
        }
        LazyVerticalGrid(columns=GridCells.Fixed(if(gridView)2 else 1),modifier=Modifier.weight(1f).testTag("page-grid"),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            items(if(showRecycled)ui.recycled else ui.pages,key={it.id}){p->
                var menu by remember{mutableStateOf(false)}
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Box(Modifier.widthIn(max=if(gridView)128.dp else 156.dp).fillMaxWidth().border(if(p.id==ui.selectedId&&!showRecycled)2.dp else 1.dp,if(p.id==ui.selectedId&&!showRecycled)Forest else Line).clickable(enabled=!showRecycled&&pageActionsReady){vm.select(p.id)}.testTag(if(showRecycled)"recycled-page-${p.id}"else"jump-page-${p.position+1}")){
                        PageThumb(p,Modifier.fillMaxWidth().aspectRatio(1000f/1414f))
                        if(!showRecycled){
                            val mark=marks.firstOrNull{val m=it.data() as KnowledgeData.PageMark;m.bookmark&&m.pageId==p.id}
                            IconToggleButton(mark!=null,{marksModel.save(mark,mark?.data() as? KnowledgeData.PageMark?:KnowledgeData.PageMark(p.id,"第${p.position+1}页",true),mark!=null)},
                                enabled=pageActionsReady&&!marksBusy&&marksError==null,modifier=Modifier.align(Alignment.TopEnd).size(48.dp).testTag("page-bookmark-${p.position+1}").describedAs(if(mark==null)"添加页签"else"移除页签")){
                                Glyph("bookmark",if(mark!=null)Forest else Quiet)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(if(showRecycled)"原第${p.position+1}页"else"第${p.position+1}页",Modifier.weight(1f),fontSize=12.sp,color=if(p.id==ui.selectedId)Forest else TextInk)
                        if(showRecycled)TextButton({requestAction(p,PageEditKind.RESTORE)},enabled=pageAuthorReady,modifier=Modifier.testTag("restore-page-${p.id}")){Text("恢复")}
                        else Box{
                            IconButton({menu=true},enabled=pageAuthorReady,modifier=Modifier.testTag("page-menu-${p.position+1}").describedAs("第${p.position+1}页操作")){Glyph("more")}
                            DropdownMenu(menu,{menu=false}){
                                DropdownMenuItem(text={Text("在此页之前插入")},enabled=pageInsertReady,onClick={menu=false;directory=false;insertion=p.id to PageInsertLocation.BEFORE})
                                DropdownMenuItem(text={Text("在此页之后插入")},enabled=pageInsertReady,onClick={menu=false;directory=false;insertion=p.id to PageInsertLocation.AFTER})
                                DropdownMenuItem(text={Text("移动页面…")},onClick={menu=false;requestAction(p,PageEditKind.MOVE)},modifier=Modifier.testTag("move-page-${p.position+1}"))
                                DropdownMenuItem(text={Text("复制此页…")},onClick={menu=false;requestAction(p,PageEditKind.COPY)},modifier=Modifier.testTag("copy-page-${p.position+1}"))
                                DropdownMenuItem(text={Text("移入页面回收区")},enabled=ui.pages.size>1,onClick={menu=false;requestAction(p,PageEditKind.TRASH)},modifier=Modifier.testTag("recycle-page-${p.position+1}"))
                            }
                        }
                    }
                }
            }
        }
        if(showRecycled&&ui.recycled.isEmpty())Text("没有已删除页面",Modifier.padding(16.dp),color=Quiet)
        if(!showRecycled&&page!=null&&!page.world)TextButton({directory=false;insertion=page.id to PageInsertLocation.AFTER},enabled=pageInsertReady&&ui.pages.size+ui.recycled.size<500,modifier=Modifier.fillMaxWidth().padding(8.dp).testTag("overview-add-page")){Glyph("add-page");Text("添加页面",Modifier.padding(start=8.dp))}
    }
    }
    pageActionId?.let{id->
        val source=(ui.pages+ui.recycled).firstOrNull{it.id==id}
        if(source==null)LaunchedEffect(id){pageActionId=null}
        else PageEditDialog(source,PageEditKind.valueOf(pageActionKind),ui.pages,{pageActionId=null}){where,anchor,order,head->
            pageActionId=null
            if(readLock.canWrite&&canNavigate&&!ui.busy&&!ui.actionUnknown&&!ui.insertionUnknown)vm.editPage(PageEditKind.valueOf(pageActionKind),id,where,anchor,order,head,source.trashedAt)
        }
    }
    insertion?.let{(anchor,location)->
        InsertPagesDialog(ui.pages,anchor,location,{insertion=null},ui.recycled.size,defaultPaper=newPagePaper){where,id,paper,count,open,order,installed->
            insertion=null
            if(readLock.canWrite&&canNavigate&&!ui.busy&&!ui.insertionUnknown&&!ui.actionUnknown)vm.insert(where,id,paper,count,open,order,installed)
        }
    }
    if(confirmBook)AlertDialog(onDismissRequest={confirmBook=false},title={Text("导出整本内容副本")},text={Text("包括本笔记所有可用页面、图片、文本框、胶带状态、局部擦除效果和已保存键入文字；不含页面回收区。明文 .iwbook，不含摘要卡/脑图、撤销历史、账号或密钥；不是完整资料库备份。目标可能由云盘提供。")},confirmButton={TextButton(onClick={confirmBook=false;exporting=true;scope.launch{try{val bytes=withContext(Dispatchers.IO){app.pages.exportBook(note.base.id).encode()};exportBytes=bytes;export.launch("墨织笔记本.iwbook")}catch(c:CancellationException){throw c}catch(e:Exception){Toast.makeText(context,e.mapExportExplanation()?:"无法导出整本内容，原数据保留；可尝试逐页导出",Toast.LENGTH_LONG).show()}finally{exporting=false}}}){Text("选择位置")}},dismissButton={TextButton(onClick={confirmBook=false}){Text("取消")}})
    searchTarget?.let{draft->PageSearchDialog(draft){searchTarget=null}}
    }
    }
}
@Composable
internal fun PageThumb(page:NotebookPageRow,modifier:Modifier=Modifier.size(84.dp,110.dp)){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val strokes by produceState<List<InkStroke>?>(null,page.id){value=withContext(Dispatchers.IO){runCatching{InkSession(app.inkRepository.read(page.id)).visibleDraft()}.getOrNull()}}
    val objects by produceState<List<PageObject>>(emptyList(),page.id){value=withContext(Dispatchers.IO){runCatching{app.pageObjects.read(page.id).objects}.getOrDefault(emptyList())}}
    Box(modifier){
        val loaded=strokes
        AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={it.configure(false,PaperStyle.entries[page.paper],null);it.showDocument(page.id);it.showStrokes(loaded.orEmpty());it.showObjects(objects)},modifier=Modifier.fillMaxSize())
    }
}
@Composable
private fun PageSearchDialog(draft:PageSearchDraft,dismiss:()->Unit){
    val pageId=draft.pageId;val revision=draft.inkRevision
    val app=LocalContext.current.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var objectRevision by remember(draft){mutableLongStateOf(draft.objectRevision)};var ocr by remember{mutableStateOf(false)}
    var text by remember(draft){mutableStateOf(draft.text)};var busy by remember{mutableStateOf(false)}
    var message by remember(draft){mutableStateOf(if(draft.stale)"本页手写已有变化，请重新识别并校对。"else null)}
    EditorPanel("校对手写识别","",{if(!busy)dismiss()},"page-search-dialog",footer={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=dismiss,enabled=!busy){Text("取消")};TextButton(onClick={busy=true;scope.launch{try{if(withContext(Dispatchers.IO){app.pages.saveSearchText(pageId,revision,text,objectRevision,if(ocr)"OCR"else"MANUAL",draft.authoringRevision)})dismiss()else message="页面内容或图层已更新，没有套用到新版本。请关闭后重新核对。"}catch(c:CancellationException){throw c}catch(_:Exception){message="校对尚未保存，请重试。"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("save-page-search")){Text("保存校对")}}}){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text("只修正查找用的文字，保留纸面手写。",fontSize=12.sp,color=Quiet)
        TextButton(onClick={busy=true;message="正在离线识别…";scope.launch{try{
            val frozen=withContext(Dispatchers.IO){app.authoring.exportPage(pageId)}
            check(frozen.authoring.inkRevision==revision&&frozen.authoring.revision==draft.authoringRevision)
            objectRevision=frozen.authoring.objectRevision
            val layers=frozen.authoring.state.layers
            val visibleObjects=frozen.objects.filter{layers.visible(LayerContent(LayerContentKind.OBJECT,it.id))}
            val suppressed=visibleObjects.flatMap{it.sourceStrokeIds}.toSet()
            val result=app.handwriting.recognize(frozen.ink.filter{layers.visible(LayerContent(LayerContentKind.INK,it.id))&&it.id !in suppressed})
            text=(listOf(result.text)+visibleObjects.filter{!it.hidden&&it.kind==PageObjectKind.TEXT}.map{it.visibleText()}).filter{it.isNotBlank()}.joinToString("\n").also{require(it.length<=20000)}
            ocr=true;message=if(text.isBlank())"未识别到文字，可手动补充关键词。"else"识别完成，请核对后保存。"
        }catch(c:CancellationException){throw c}catch(_:Exception){message="识别未完成或页面已经变化。原笔迹保留，可关闭后重试。"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("recognize-page")){Text("识别本页手写")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(text,{if(it.length<=20_000)text=it},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=96.dp,max=160.dp).testTag("page-search-text"),label={Text("识别出的文字")})
        message?.let{Text(it,fontSize=12.sp)}
    }
    }
}
