// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Only an explicit opening click enters; a restored or remounted visible block is already final. */
@Composable
internal fun ClickEnterContent(event:Int,play:Boolean,consume:()->Unit,modifier:Modifier=Modifier,content:@Composable ()->Unit){
    key(event){
        val opacity=remember{Animatable(if(play)0f else 1f)}
        LaunchedEffect(Unit){
            if(play){consume();opacity.animateTo(1f,tween(InkTheme.MotionMillis))}
        }
        Box(modifier.graphicsLayer{alpha=opacity.value}){content()}
    }
}

private data class MapChangeFeedback(val graph:String,val collapsed:Set<String>?,val nodes:Set<String>,val anchor:String?)

@Composable
internal fun StudyWorkspace(note:NoteDraft,initialSource:StudySourceDraft?,dismiss:()->Unit,initialQuery:String="",initialCardId:String?=null,initialMap:MapRef?=null,initialBranch:String?=null,cardOpenRequest:Long=0L,openSource:suspend (StudySourceRow)->Boolean){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val readLock=rememberBookReadLock(note.base.id);val hasDraft by readLock.hasDraft.collectAsStateWithLifecycle()
    val vm:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study));val ui by vm.ui.collectAsStateWithLifecycle()
    val mapWriter:KnowledgeViewModel=viewModel(key="study-map-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks));val mapWrite by mapWriter.ui.collectAsStateWithLifecycle()
    val portalWriter:KnowledgeViewModel=viewModel(key="map-portal-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks));val portalWrite by portalWriter.ui.collectAsStateWithLifecycle()
    val canLeave=!hasDraft&&!ui.busy&&!ui.unknown&&!mapWrite.busy&&!mapWrite.unknown&&!portalWrite.busy&&!portalWrite.unknown
    val reviewLeaveRequest=remember(note.base.id){mutableStateOf<(() -> Boolean)?>(null)}
    Dialog(onDismissRequest={if(canLeave&&reviewLeaveRequest.value?.invoke()!=false)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        val chrome=StudyWindowChrome(Modifier){IconButton(onClick={if(canLeave&&reviewLeaveRequest.value?.invoke()!=false)dismiss()},enabled=canLeave,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("返回学习")){Glyph("close")}}
        Surface(Modifier.fillMaxSize().safeDrawingPadding(),color=Color.White){CompositionLocalProvider(LocalStudyWindowChrome provides chrome){Column(Modifier.fillMaxSize()){
            StudyContent(note,initialSource,dismiss,initialQuery,initialCardId,compactWindow=true,initialMap=initialMap,initialBranch=initialBranch,reviewLeaveRequest=reviewLeaveRequest,cardOpenRequest=cardOpenRequest,openSource=openSource)
        }}}
    }
}
@Composable
internal fun StudyContent(note:NoteDraft,initialSource:StudySourceDraft?,dismiss:()->Unit,initialQuery:String="",initialCardId:String?=null,documentReady:Boolean=true,compactWindow:Boolean=false,sourceRequest:Long=0L,initialCaptureText:String="",onInsertEmbed:((MapEmbed)->Unit)?=null,initialMap:MapRef?=null,initialBranch:String?=null,reviewLeaveRequest:MutableState<(() -> Boolean)?>?=null,showReadControl:Boolean=true,capacityVisible:Boolean=true,reviewRequest:Long=0L,workModeRequest:MutableState<((StudyWorkMode)->Boolean)?>?=null,onReviewActive:(Boolean)->Unit={},cardOpenRequest:Long=0L,openSource:suspend (StudySourceRow)->Boolean){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val focus=LocalFocusManager.current
    val vm:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val readLock=rememberBookReadLock(note.base.id)
    val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    val hasDraft by readLock.hasDraft.collectAsStateWithLifecycle()
    val guardKey=remember(vm){"study-${UUID.randomUUID()}"}
    SideEffect{vm.authorAllowed={readLock.canWrite}}
    DisposableEffect(readLock,guardKey){onDispose{readLock.guard("$guardKey-gesture",false)}}
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm,lifecycleOwner){lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){vm.attach();try{awaitCancellation()}finally{vm.detach()}}}
    val ui by vm.ui.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    val navigation=remember(note.base.id,scope){StudyNavigationState(scope,app.study::sources)}
    DisposableEffect(navigation){onDispose{navigation.cancelSourceNavigation()}}
    val capacitySnapshot=if(capacityVisible){val current by vm.snapshotUsage.collectAsStateWithLifecycle();current}else StudySnapshotUsage()
    var titleDraft by rememberSaveable(stateSaver=NodeTitleDraft.Saver){mutableStateOf<NodeTitleDraft?>(null)}
    var titleInput by rememberSaveable(stateSaver=androidx.compose.ui.text.input.TextFieldValue.Saver){mutableStateOf(androidx.compose.ui.text.input.TextFieldValue())}
    var titleSubmitted by rememberSaveable{mutableStateOf(false)}
    var titleOperation by rememberSaveable{mutableStateOf<String?>(null)}
    var titleSubmittedText by rememberSaveable{mutableStateOf("")}
    var titleContinue by rememberSaveable{mutableStateOf(false)}
    var titleVerified by rememberSaveable{mutableStateOf(false)}
    var titleCheckFailed by remember{mutableStateOf(false)}
    var titleCheckAttempt by remember{mutableIntStateOf(0)}
    var layoutPreview by remember{mutableStateOf<StudyLayoutPreview?>(null)}
    var layoutCamera by rememberSaveable{mutableStateOf<String?>(null)}
    var layoutUndoGraph by rememberSaveable{mutableStateOf<String?>(null)}
    var layoutFitGraph by rememberSaveable{mutableStateOf<String?>(null)}
    var layoutRestoreGraph by rememberSaveable{mutableStateOf<String?>(null)}
    var organizeNodeId by rememberSaveable{mutableStateOf<String?>(null)}
    var outlineHeldKey by remember{mutableStateOf<Triple<Key,String,Boolean>?>(null)}
    val outlineListState=rememberLazyListState()
    var outlineDrag by remember{mutableStateOf<OutlineDrag?>(null)}
    var outlineBounds by remember{mutableStateOf(Rect.Zero)}
    val outlineHandles=remember{mutableMapOf<String,Rect>()}
    var nodeMenu by remember{mutableStateOf(false)}
    var portalNodeId by rememberSaveable{mutableStateOf<String?>(null)}
    var portalMapKey by rememberSaveable{mutableStateOf("main")}
    var reviewPlan by rememberSaveable(stateSaver=BranchReviewPlanSaver){mutableStateOf<BranchReviewPlan?>(null)}
    var reviewCardOnly by rememberSaveable{mutableStateOf(false)}
    var reviewVisible by rememberSaveable{mutableStateOf(false)}
    var reviewConsultedOriginal by rememberSaveable{mutableStateOf(false)}
    var reviewSessionKey by rememberSaveable{mutableStateOf(UUID.randomUUID().toString())}
    val reviewStateHolder=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var reviewRequestSeen by rememberSaveable{mutableLongStateOf(0L)}
    var reviewQuestionScope by rememberSaveable(note.base.id){mutableStateOf(ReviewQuestionScope.ALL)}
    var reviewPreparation by remember(note.base.id){mutableStateOf<Pair<List<Any?>,Job>?>(null)}
    val reviewWriter:KnowledgeViewModel?=if(reviewPlan!=null)viewModel(key="branch-review-${note.base.id}",
        factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))else null
    val latestDocumentReady by rememberUpdatedState(documentReady)
    var inspectSource by rememberSaveable{mutableStateOf(false)}
    var selectedBounds by remember{mutableStateOf<android.graphics.RectF?>(null)}
    var controlsAwake by remember{mutableStateOf(true)}
    var controlPulse by remember{mutableIntStateOf(0)}
    LaunchedEffect(controlPulse){controlsAwake=true;delay(2500);controlsAwake=false}
    var insertMap by rememberSaveable{mutableStateOf(false)}
    var contentSearch by remember{mutableStateOf(false)}
    val graphFlow=remember(note.base.id){app.mapGraphs.observe(note.base.id)}
    val scenes by graphFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val excerptRows by remember(note.base.id){app.study.excerpts(note.base.id)}.collectAsStateWithLifecycle(initialValue=emptyList())
    val sourcePages by remember(note.base.id){app.pages.observeAll(note.base.id)}.collectAsStateWithLifecycle(initialValue=emptyList())
    val sourceSummaries by remember(note.base.id){app.study.sourceSummaries(note.base.id)}.collectAsStateWithLifecycle(initialValue=emptyList())
    val mapSources=remember(scenes,excerptRows,sourcePages,note.title,sourceSummaries){
        val known=scenes.flatMap{it.nodes}.filter{it.sourceState!="无来源"}.mapNotNull{it.cardId}.associateWith{MapSourceInfo("原迹摘录")}
        val legacy=known+excerptRows.associate{excerpt->
            val page=sourcePages.find{it.id==excerpt.pageId&&it.trashedAt==null}
            excerpt.id to MapSourceInfo(page?.let{"第 ${it.position+1} 页"}?:"来源页不可用",CanvasBounds(excerpt.left,excerpt.top,excerpt.right,excerpt.bottom))
        }
        legacy+sourceSummaries.filter{it.count>0||!it.complete}.associate{summary->
            summary.cardId to when{
                !summary.complete->MapSourceInfo("来源版本需核对",previewEnabled=false)
                summary.count>1->MapSourceInfo("${summary.count} 个来源 · 点开选择",previewEnabled=false)
                else->legacy[summary.cardId]?:MapSourceInfo("固定来源 · 点开查看")
            }
        }
    }
    val extraFlow=remember(app,note.base.id){app.knowledge.observeBook(note.base.id)};val extraRows by extraFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val presentations=remember(extraRows){extraRows.cardPresentations()}
    val annotations=remember(presentations){presentations.mapValues{it.value.annotation}}
    var presentationCardId by rememberSaveable{mutableStateOf<String?>(null)}
    var transformKind by rememberSaveable{mutableStateOf<CardTransformKind?>(null)}
    var transformCardIds by rememberSaveable{mutableStateOf(emptyList<String>())}
    var transformedCardIds by rememberSaveable{mutableStateOf(emptyList<String>())}
    var transformPending by remember{mutableStateOf(false)}
    var resolutionPending by remember{mutableStateOf(false)}
    val transforms=remember(app){app.study.transforms()}
    var reuseCardId by rememberSaveable{mutableStateOf<String?>(null)}
    val reuseCard=ui.cards.firstOrNull{it.id==reuseCardId}
    fun extraOccurrences(cardId:String)=extraRows.count{r->!r.removed&&r.notebookId==note.base.id&&when(val d=r.data()){is KnowledgeData.Placement->d.cardId==cardId;is KnowledgeData.MapOccurrence->d.cardId==cardId;else->false}}

    val currentMap by vm.mapId.collectAsStateWithLifecycle()
    val mapAuthoring:PageAuthoringViewModel=viewModel(key="map-authoring-${note.base.id}-${currentMap?:"main"}",factory=PageAuthoringViewModel.Factory(AuthoringScope.map(MapRef(note.base.id,currentMap)),app.authoring))
    val annotationUi by mapAuthoring.ui.collectAsStateWithLifecycle()
    var annotationNodeId by rememberSaveable(currentMap){mutableStateOf<String?>(null)}
    var annotationLayers by rememberSaveable(currentMap){mutableStateOf(false)}
    SideEffect{mapAuthoring.authorAllowed={readLock.canWrite}}
    val capacityUsage=ui.capacityUsage(currentMap)
    var initialMapApplied by rememberSaveable(initialMap,initialBranch){mutableStateOf(false)}
    LaunchedEffect(initialMap,initialBranch){if(!initialMapApplied)initialMap?.let{ref->require(ref.notebookId==note.base.id);vm.selectMap(ref.mapId);vm.selectTab(2);initialBranch?.let{vm.revealByMap[ref.mapId?:"main"]=it;vm.selectedByMap[ref.mapId?:"main"]=it};initialMapApplied=true}}
    LaunchedEffect(currentMap,ui.loading,vm.lastTab){if(!ui.loading&&vm.lastTab==2)app.learningStore.visit(StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap))}
    val mapKey=currentMap?:"main"
    var selectionMode by rememberSaveable(mapKey){mutableStateOf(false)}
    var selectedGroup by rememberSaveable(mapKey){mutableStateOf(emptyList<String>())}
    var groupTarget by remember{mutableStateOf<Pair<StudyGraphState,Set<String>>?>(null)}
    var knowledgeRelations by rememberSaveable(note.base.id,mapKey){mutableStateOf(false)}
    var mapChangeFeedback by remember(mapKey){mutableStateOf<MapChangeFeedback?>(null)}
    val expandedNodeId=vm.expandedByMap[mapKey]?.takeIf{it==vm.selectedByMap[mapKey]}
    LaunchedEffect(mapKey,vm.selectedByMap[mapKey]){
        if(vm.expandedByMap[mapKey]!=vm.selectedByMap[mapKey])vm.expandedByMap.remove(mapKey)
    }
    val maps=extraRows.filter{!it.removed&&it.notebookId==note.base.id&&it.data() is KnowledgeData.MapDefinition}
    var mapMenu by remember{mutableStateOf(false)};var newMapTitle by rememberSaveable{mutableStateOf<String?>(null)}
    var saveTemplate by rememberSaveable{mutableStateOf(false)}
    var keepTemplateTitles by rememberSaveable{mutableStateOf(false)}
    var templateTitle by rememberSaveable{mutableStateOf("我的结构模板")}
    var structuralEditorSubmitted by rememberSaveable{mutableStateOf(false)}
    val mapWriter:KnowledgeViewModel=viewModel(key="study-map-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    BindKnowledgeReadLock(mapWriter)
    val mapWrite by mapWriter.ui.collectAsStateWithLifecycle()
    val portalWriter:KnowledgeViewModel=viewModel(key="map-portal-writer-${note.base.id}",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks))
    BindKnowledgeReadLock(portalWriter)
    val portalWrite by portalWriter.ui.collectAsStateWithLifecycle()
    val mapSaving=mapWrite.busy||mapWrite.unknown||portalWrite.busy||portalWrite.unknown
    LaunchedEffect(mapWrite.completed){if(titleSubmitted&&titleDraft?.structural==true)return@LaunchedEffect;mapWrite.completed?.let{if(newMapTitle!=null)vm.selectMap(it);newMapTitle=null;saveTemplate=false;if(structuralEditorSubmitted){vm.editorState.value=null;structuralEditorSubmitted=false};mapWriter.consumed()}}
    val mainNodes=ui.mainNodes
    val graph=ui.graph?.takeIf{it.ref.mapId==currentMap}
    val definition=graph?.definition?.data() as? KnowledgeData.MapDefinition
    val structureCards=definition?.structures.orEmpty().map{StudyCardRow(it.id,note.base.id,checkNotNull(graph?.definition).revision,it.title,"")}
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
    var chosenNodeId by rememberSaveable{mutableStateOf<String?>(null)}
    var chosenCardId by rememberSaveable{mutableStateOf<String?>(null)}
    val chosenNode=ui.nodes.find{it.id==chosenNodeId&&!it.removed}
    val chosenCard=displayCards.find{it.id==chosenCardId}
    val inspectorState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val source=navigation.source;var stale by remember{mutableStateOf(false)}
    val sourceVersions=navigation.sourceVersions
    val sourceLoadFailed=navigation.sourceLoadFailed
    var sourceReload by remember{mutableIntStateOf(0)}
    var map by remember{mutableStateOf<MindMapView?>(null)};var dragging by remember{mutableStateOf(false)}
    LaunchedEffect(graph?.graphFingerprint,layoutFitGraph,layoutRestoreGraph,map,tab,mapKey){
        val canvas=map?:return@LaunchedEffect;val stamp=graph?.graphFingerprint?:return@LaunchedEffect
        if(tab!=2)return@LaunchedEffect
        withFrameNanos{}
        if(!canvas.isAttachedToWindow||canvas.captureBook!=note.base.id||canvas.captureMapKey!=mapKey||canvas.captureGraph!=stamp)return@LaunchedEffect
        if(stamp==layoutFitGraph){canvas.fitOverview();layoutFitGraph=null}
        if(stamp==layoutRestoreGraph){
            val origin=layoutCamera?.let(PortalReturn::restore)
            if(origin?.mapId==currentMap)origin?.viewport?.let{vm.viewports[mapKey]=it;canvas.restoreViewport(it)}
            layoutRestoreGraph=null;layoutUndoGraph=null;layoutCamera=null
        }
    }
    LaunchedEffect(mapKey,map){if(vm.viewports[mapKey]==null)app.learningStore.viewport(MapRef(note.base.id,currentMap))?.let{vm.viewports[mapKey]=it;map?.restoreViewport(it)}}
    DisposableEffect(vm,mapKey){val ref=MapRef(note.base.id,currentMap);onDispose{vm.viewports[mapKey]?.let{app.learningStore.viewport(ref,it)}}}
    var pendingExport by remember{mutableStateOf<String?>(null)};var localMessage by remember{mutableStateOf<String?>(null)}
    var knowledgeCardId by rememberSaveable{mutableStateOf<String?>(null)}
    var knowledgeBacklinks by rememberSaveable{mutableStateOf(false)}
    var knowledgeAllRelationKinds by rememberSaveable{mutableStateOf(false)}
    fun openCardKnowledge(cardId:String,backlinks:Boolean,allRelationKinds:Boolean=false){knowledgeAllRelationKinds=allRelationKinds;knowledgeBacklinks=backlinks;knowledgeCardId=cardId}
    fun closeCardKnowledge(){knowledgeCardId=null;knowledgeAllRelationKinds=false}
    val knowledgeCard=ui.cards.find{it.id==knowledgeCardId&&it.trashedAt==null}
    val knowledgeState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var reparentId by rememberSaveable{mutableStateOf<String?>(null)}
    var reparentRevision by rememberSaveable{mutableLongStateOf(0L)}
    var reparentMap by rememberSaveable{mutableStateOf<String?>(null)}
    var reparentFingerprint by rememberSaveable{mutableStateOf("")}
    val reparent=ui.nodes.find{it.id==reparentId}?.copy(revision=reparentRevision)
    fun chooseParent(node:StudyNodeRow){reparentId=node.id;reparentRevision=node.revision;reparentMap=currentMap;reparentFingerprint=graph?.graphFingerprint.orEmpty()}
    val restoreEpoch=vm.viewportRestore
    var collapsed by remember(mapKey,restoreEpoch){mutableStateOf(vm.collapsedByMap[mapKey].orEmpty())}
    var focusId by remember(mapKey,restoreEpoch){mutableStateOf(vm.focusedByMap[mapKey])}
    var organizationReveal by remember(mapKey){mutableStateOf<Pair<String,String>?>(null)}
    val chrome=LocalStudyWindowChrome.current
    val active=ui.nodes.filter{!it.removed}
    val nodeById=active.associateBy{it.id};val cardById=displayCards.associateBy{it.id}
    LaunchedEffect(mapKey,active.map{it.id}){selectedGroup=selectedGroup.filter{it in nodeById}}
    val selectedBranches=graph?.state?.let{state->selectedGroup.filter{it in state.orderedNodeIds}.toSet().takeIf{it.isNotEmpty()}?.let{StudyOrganization.selectedBranchIds(state,it)}}.orEmpty()
    val mapFontScale=LocalDensity.current.fontScale
    fun previewSourceInfo(cardId:String)=mapSources[cardId]?.let{map?.previewSourceInfo(cardId,it)?:it}
    fun nodeHeight(node:StudyNodeRow):Float{
        val card=cardById[node.cardId]
        return MapNodeMetrics.measure(card?.title.orEmpty(),card?.body.orEmpty(),previewSourceInfo(node.cardId),mapFontScale,structural=node.cardId in structureCards.map{it.id}).height
    }
    fun nextNodeY(parent:StudyNodeRow?):Double=(active.filter{it.parentId==parent?.id}.maxOfOrNull{it.y+nodeHeight(it)+32}
        ?:parent?.y?:80.0).coerceIn(-40000.0,40000.0)
    fun nextNodeX(parent:StudyNodeRow?):Double=parent?.let{
        val card=cardById[it.cardId]
        val width=MapNodeMetrics.measure(card?.title.orEmpty(),card?.body.orEmpty(),previewSourceInfo(it.cardId),mapFontScale).width
        (it.x+width+40).coerceIn(-40000.0,40000.0)
    }?:40.0
    val missingPortalBranch=focusId!=null&&vm.portalBranches[mapKey]==focusId&&focusId !in nodeById
    val projection=if(missingPortalBranch)StudyOutline.Projection(emptyList(),emptyList())else StudyOutline.project(active.map{it.model()},collapsed.toSet(),focusId)
    val shown=projection.rows.mapNotNull{nodeById[it.node.id]}
    val hiddenCounts=projection.rows.filter{it.node.id in collapsed}.associate{it.node.id to it.descendants}
    val relationNodes=shown.filter{node->structureCards.none{it.id==node.cardId}}
    val relationNodeId=vm.selectedByMap[mapKey]?.takeIf{selected->relationNodes.any{it.id==selected}}
    val knowledgeProjection=if(knowledgeRelations){
        val availableBooks=mapWrite.notes.map{it.id}.toSet()
        projectStudyRelations(relationNodeId,relationNodes,mapWrite.rows.filter{it.notebookId in availableBooks})
    }else StudyRelationProjection(emptyList(),0)
    val browseReady=!annotationUi.busy&&!annotationUi.pending&&documentReady&&!ui.loading&&!ui.readFailed&&!ui.busy&&!ui.unknown&&!mapSaving&&!resolutionPending&&!transformPending
    val editable=browseReady&&!readOnly&&!missingPortalBranch&&titleDraft==null&&layoutPreview==null&&graph!=null
    fun openTransform(kind:CardTransformKind,cardIds:List<String>){
        if(!editable||hasDraft)return
        val ids=cardIds.distinct().filter{id->ui.cards.any{it.id==id&&it.trashedAt==null}}
        if(kind==CardTransformKind.SPLIT&&ids.size!=1||kind!=CardTransformKind.SPLIT&&ids.size !in 2..16){localMessage="拆分请选择1张卡，合并或总结请选择2–16张内容卡";return}
        transformCardIds=ids;transformKind=kind;chosenCardId=null;chosenNodeId=null;nodeMenu=false
    }
    fun organizationPlan(node:StudyNodeRow,action:StudyOrganizationAction):StudyOrganizationPlan?=
        graph?.let{runCatching{StudyOrganization.plan(it.state,node.id,action)}.getOrNull()}
    fun organize(node:StudyNodeRow,action:StudyOrganizationAction){
        if(!editable||!readLock.canWrite)return
        val snapshot=graph?:return;val plan=organizationPlan(node,action)?:return
        vm.organize(snapshot.state,plan);organizeNodeId=null;nodeMenu=false
        organizationReveal=plan.expectedAfterGraph to node.id
        vm.selectedByMap[mapKey]=node.id
        plan.after.placements.find{it.nodeId==node.id}?.parentId?.let{collapsed=collapsed-it}
        localMessage="顺序与层级已提交。画布位置保留，可预览自动布局。"
    }
    fun cancelOutlineDrag(message:String="拖动已取消，顺序与层级保持不变"){
        if(outlineDrag!=null)localMessage=message
        outlineDrag=null;readLock.guard("$guardKey-outline",false)
    }
    fun updateOutlinePointer(point:Offset){
        val drag=outlineDrag?:return
        val info=outlineListState.layoutInfo
        val item=if(point.x !in 0f..outlineBounds.width||point.y<0||point.y>outlineBounds.height)null
            else info.visibleItemsInfo.minByOrNull{item->when{point.y<item.offset->item.offset-point.y;point.y>item.offset+item.size->point.y-item.offset-item.size;else->0f}}
        val row=item?.let{projection.rows.getOrNull(it.index)}
        val position=if(item==null)OutlineDropPosition.CHILD else when{
            point.y<item.offset+item.size*.25f->OutlineDropPosition.BEFORE
            point.y>item.offset+item.size*.75f->OutlineDropPosition.AFTER
            else->OutlineDropPosition.CHILD
        }
        val preview=if(row==null)null else if(drag.preview?.targetId==row.node.id&&drag.preview.position==position)drag.preview
            else outlineDropPreview(drag.state,drag.nodeId,row.node.id,position,cardById[row.node.cardId]?.title.orEmpty(),row.node.id in collapsed)
        outlineDrag=drag.copy(pointer=point,preview=preview)
    }
    val outlineDraggable by rememberUpdatedState(editable&&!hasDraft&&tab==1)
    val beginOutlineDrag by rememberUpdatedState<(Offset)->Boolean>({point->
        val node=outlineHandles.entries.firstOrNull{it.value.contains(point+outlineBounds.topLeft)}?.key
        val snapshot=graph
        if(node==null||snapshot==null||!editable||hasDraft||tab!=1)false else{
            focus.clearFocus();vm.selectedByMap[mapKey]=node;organizeNodeId=null
            outlineDrag=OutlineDrag(snapshot.state,node,point)
            readLock.guard("$guardKey-outline",true);updateOutlinePointer(point);true
        }
    })
    val openOutlineHandle by rememberUpdatedState<(Offset)->Unit>({point->
        if(editable&&!hasDraft)outlineHandles.entries.firstOrNull{it.value.contains(point+outlineBounds.topLeft)}?.key?.let{vm.selectedByMap[mapKey]=it;organizeNodeId=it}
    })
    val moveOutlineDrag by rememberUpdatedState<(Offset)->Unit>(::updateOutlinePointer)
    val endOutlineDrag by rememberUpdatedState<()->Unit>({
        val drag=outlineDrag
        val plan=drag?.preview?.plan
        cancelOutlineDrag()
        if(drag!=null&&graph?.graphFingerprint!=StudyOrganization.fingerprint(drag.state))localMessage="导图已变化，拖动已取消；请重新拖动"
        else if(drag!=null&&plan!=null&&plan.before!=plan.after&&readLock.canWrite){
            vm.organize(drag.state,plan);organizationReveal=plan.expectedAfterGraph to drag.nodeId
            plan.after.placements.first{it.nodeId==drag.nodeId}.parentId?.let{collapsed=collapsed-it}
            localMessage="整支顺序与层级已提交；导图共用同一结构，可撤销或重做"
        }else if(drag?.preview?.plan==null)localMessage=drag?.preview?.message?:"已移出大纲，拖动取消；原位置保留"
        else localMessage="位置未改变，没有写入"
    })
    val cancelOutlineGesture by rememberUpdatedState<()->Unit>({cancelOutlineDrag()})
    val outlineEdge=with(LocalDensity.current){48.dp.toPx()}
    LaunchedEffect(outlineDrag?.nodeId){
        if(outlineDrag==null)return@LaunchedEffect
        while(isActive&&outlineDrag!=null){
            delay(16)
            val point=outlineDrag?.pointer?:break
            val height=outlineBounds.height
            val speed=when{
                point.x !in 0f..outlineBounds.width||point.y !in 0f..height->0f
                point.y<outlineEdge->-((outlineEdge-point.y)/outlineEdge).coerceIn(0f,1f)*outlineEdge/5
                point.y>height-outlineEdge->((point.y-height+outlineEdge)/outlineEdge).coerceIn(0f,1f)*outlineEdge/5
                else->0f
            }
            if(speed!=0f){outlineListState.scrollBy(speed);updateOutlinePointer(point)}
        }
    }
    LaunchedEffect(graph?.graphFingerprint,tab,readOnly){
        outlineDrag?.let{if(tab!=1||readOnly||graph?.graphFingerprint!=StudyOrganization.fingerprint(it.state))cancelOutlineDrag("图或工作状态已变化，拖动取消；原操作未提交")}
    }
    DisposableEffect(Unit){onDispose{readLock.guard("$guardKey-outline",false)}}
    fun measuredNodeSizes()=active.associate{n->
        val card=cardById[n.cardId]
        val size=MapNodeMetrics.measure(card?.title.orEmpty(),card?.body.orEmpty(),previewSourceInfo(n.cardId),mapFontScale,n.id==expandedNodeId,n.cardId in structureCards.map{it.id})
        n.id to StudyNodeSize(size.width.toDouble(),size.height.toDouble())
    }
    fun previewLayout(selectionOnly:Boolean=false){
        if(!editable||!readLock.canWrite)return
        val snapshot=graph?:return
        val sizes=measuredNodeSizes()
        val selected=selectedGroup.toSet().takeIf{selectionOnly}.orEmpty()
        val plan=runCatching{if(selected.isEmpty())StudyOrganization.arrange(snapshot.state,sizes,definition?.layout?:"right")else StudyOrganization.arrangeSelection(snapshot.state,selected,sizes,definition?.layout?:"right")}.getOrElse{localMessage="当前主题无法在布局范围内排布，请先整理分支。";return}
        val frozenSources=mapSources.mapValues{(card,info)->(previewSourceInfo(card)?:info).let{it.copy(contentRatio=it.previewRatio)}}
        layoutPreview=StudyLayoutPreview(snapshot.state,plan,displayCards,frozenSources,structureCards.map{it.id}.toSet(),sizes,mapFontScale,expandedNodeId,selected)
        management=false;nodeMenu=false
    }
    fun nodeSourceContext()=StudyNavigationState.NodeOwner(vm.mapId.value,vm.selectedByMap[vm.mapId.value?:"main"],vm.lastTab,chosenCardId,knowledgeCardId,reviewPlan,titleDraft?.token,vm.editorState.value)
    val nodeSourceOwner=nodeSourceContext()
    DisposableEffect(navigation,nodeSourceOwner){onDispose{navigation.cancelNodeSource(nodeSourceOwner)}}
    fun openNodeSource(node:StudyNodeRow){
        if(!browseReady||hasDraft||titleDraft!=null)return
        navigation.openNodeSource(node,nodeSourceContext(),::nodeSourceContext,
            nodeIsCurrent={vm.selectedByMap[vm.mapId.value?:"main"]==node.id&&vm.ui.value.nodes.any{it.id==node.id&&!it.removed&&it.revision==node.revision}},
            documentReady={latestDocumentReady},openSource=openSource,
            chooseSources={chosenNodeId=node.id;chosenCardId=node.cardId;inspectSource=true},message={localMessage=it})
    }
    val authorDraft=reuseCardId!=null||transformKind!=null||resolutionPending||outlineDrag!=null||groupTarget!=null||presentationCardId!=null||titleDraft!=null||editor!=null||newMapTitle!=null||saveTemplate||templatePicker||reparentId!=null||insertMap||layoutPreview!=null
    ReadLockGuard(readLock,guardKey,blocked=ui.busy||ui.unknown||mapSaving||authorDraft,draft=authorDraft)
    ReadLockGuard(readLock,"$guardKey-annotation",blocked=annotationUi.busy||annotationUi.pending||annotationNodeId!=null,draft=annotationNodeId!=null)
    ReadLockGuard(readLock,"$guardKey-transform",transformPending,draft=transformKind!=null)
    ReadLockGuard(readLock,"$guardKey-resolution",resolutionPending,draft=resolutionPending)
    fun reviewReady(snapshot:StudyUi):Boolean=latestDocumentReady&&!snapshot.loading&&!snapshot.readFailed&&!snapshot.busy&&!snapshot.unknown&&
        !mapWriter.ui.value.busy&&!mapWriter.ui.value.unknown&&!portalWriter.ui.value.busy&&!portalWriter.ui.value.unknown&&!readLock.hasDraft.value&&
        titleDraft==null&&vm.editorState.value==null&&newMapTitle==null&&!saveTemplate&&!templatePicker&&reparentId==null&&!insertMap&&layoutPreview==null
    fun reviewPreparationContext(snapshot:StudyUi):List<Any?> = listOf(note.base.id,vm.mapId.value,vm.lastTab,reviewQuestionScope,
        vm.selectedByMap[vm.mapId.value?:"main"],focusId,chosenCardId,chosenNodeId,inspectSource,knowledgeCardId,knowledgeBacklinks,knowledgeAllRelationKinds,reviewReady(snapshot),
        snapshot.nodes.map{Triple(it.id,it.revision,it.removed)}.sortedBy{it.first},
        snapshot.cards.map{Triple(it.id,it.revision,it.trashedAt)}.sortedBy{it.first},
        extraRows.filter{it.notebookId==note.base.id}.map{Triple(it.id,it.revision,it.removed)}.sortedBy{it.first})
    fun cancelReviewPreparation(expectedContext:List<Any?>?=null){
        val pending=reviewPreparation?:return
        if(expectedContext!=null&&pending.first!=expectedContext)return
        reviewPreparation=null;pending.second.cancel()
    }
    fun leaveReviewContext(preserve:Boolean=false):Boolean{
        val live=vm.ui.value;val review=reviewWriter?.ui?.value
        if(live.busy||live.unknown||mapWriter.ui.value.busy||mapWriter.ui.value.unknown||portalWriter.ui.value.busy||portalWriter.ui.value.unknown||readLock.hasDraft.value||
            titleDraft!=null||vm.editorState.value!=null||newMapTitle!=null||saveTemplate||templatePicker||reparentId!=null||insertMap||layoutPreview!=null||review?.busy==true||review?.unknown==true)return false
        if(!readLock.canChangeMode(latestDocumentReady))return false
        cancelReviewPreparation();reviewVisible=false
        if(!preserve){reviewStateHolder.removeState(reviewSessionKey);reviewPlan=null;reviewCardOnly=false}
        return true
    }
    fun openRelatedTarget(target:TargetRef){
        if(!leaveReviewContext())return
        navigation.cancelSourceNavigation()
        app.openKnowledgeTarget.value=target;closeCardKnowledge();chosenCardId=null;chosenNodeId=null;inspectSource=false;dismiss()
    }
    fun openCapacity(){
        if(!browseReady||hasDraft||!leaveReviewContext())return
        management=false;mapMenu=false;nodeMenu=false;vm.openCapacity()
    }
    val latestReviewLeave=key(note.base.id){rememberUpdatedState({leaveReviewContext()})}
    val ownedReviewLeave=remember(note.base.id){{latestReviewLeave.value()}}
    DisposableEffect(note.base.id,reviewLeaveRequest){
        reviewLeaveRequest?.value=ownedReviewLeave
        onDispose{cancelReviewPreparation();if(reviewLeaveRequest!=null&&reviewLeaveRequest.value===ownedReviewLeave)reviewLeaveRequest.value=null}
    }
    val preparationContext=reviewPreparationContext(ui)
    DisposableEffect(note.base.id,preparationContext){onDispose{cancelReviewPreparation(preparationContext)}}
    fun chooseReviewScope(value:ReviewQuestionScope){val review=reviewWriter?.ui?.value;if(!reviewReady(vm.ui.value)||review?.busy==true||review?.unknown==true)return;if(reviewQuestionScope!=value){cancelReviewPreparation();reviewQuestionScope=value}}
    fun chooseMap(value:String?){if(titleDraft!=null||layoutPreview!=null)return;if(vm.mapId.value!=value)cancelReviewPreparation();organizeNodeId=null;vm.selectMap(value)}
    fun chooseTab(value:Int){if(titleDraft!=null||layoutPreview!=null)return;if(vm.lastTab!=value)cancelReviewPreparation();organizeNodeId=null;vm.selectTab(value)}
    fun prepareReview(target:MapRef,branch:StudyNodeRow?,card:StudyCardRow?=null){
        val live=vm.ui.value
        if(target.notebookId!=note.base.id||target.mapId!=vm.mapId.value||!reviewReady(live)||reviewPreparation!=null)return
        if(branch!=null){
            val current=live.nodes.find{it.id==branch.id&&!it.removed}
            if(current==null||current.revision!=branch.revision||vm.selectedByMap[target.key]!=branch.id)return
            if(card!=null&&current.cardId!=card.id)return
        }
        if(card!=null&&live.cards.none{it.id==card.id&&it.notebookId==target.notebookId&&it.revision==card.revision&&it.trashedAt==null})return
        val selectedScope=reviewQuestionScope
        val origin=reviewPreparationContext(live)
        val job=scope.launch(start=CoroutineStart.LAZY){
            val request=currentCoroutineContext().job
            try{
                val plan=withContext(Dispatchers.IO){
                    if(card==null)app.branchReview.prepare(target,branch?.id,selectedScope)
                    else app.branchReview.prepareCard(target,card.id,card.revision,branch?.id,selectedScope)
                }
                currentCoroutineContext().ensureActive()
                if(reviewPreparation?.second===request&&reviewPreparationContext(vm.ui.value)==origin){reviewStateHolder.removeState(reviewSessionKey);reviewSessionKey=UUID.randomUUID().toString();reviewCardOnly=card!=null;reviewPlan=plan;reviewConsultedOriginal=false;reviewVisible=true}
            }catch(c:CancellationException){throw c}
            catch(_:Exception){if(reviewPreparation?.second===request&&reviewPreparationContext(vm.ui.value)==origin)localMessage=if(card!=null)"卡片或所在导图已变化，回忆范围未读取。请重新选择；原内容不变。"else"导图或分支已变化，回忆范围未读取。请重新选择；原内容不变。"}
            finally{if(reviewPreparation?.second===request)reviewPreparation=null}
        }
        reviewPreparation=origin to job;job.start()
    }
    var originalGate by remember(note.base.id){mutableStateOf<RecallOriginalGate?>(null)}
    var studyMounted by remember(note.base.id){mutableStateOf(true)}
    DisposableEffect(note.base.id){onDispose{studyMounted=false;originalGate=null}}
    fun chooseWorkMode(mode:StudyWorkMode):Boolean{
        if(!readLock.canChangeMode(reviewReady(vm.ui.value)))return false
        if(mode==StudyWorkMode.RECALL){
            if(reviewPlan?.ref==MapRef(note.base.id,currentMap)){reviewVisible=true;return true}
            prepareReview(MapRef(note.base.id,currentMap),nodeById[vm.selectedByMap[mapKey]])
            return reviewPreparation!=null
        }
        val consulted=reviewVisible&&reviewPlan!=null
        val gate=originalGate
        if(consulted&&gate!=null){
            val ownerKey=reviewSessionKey;val ownerPlan=reviewPlan
            return gate {
                if(studyMounted&&reviewSessionKey==ownerKey&&reviewPlan===ownerPlan&&reviewVisible&&leaveReviewContext(preserve=true)){
                    reviewConsultedOriginal=true
                    if(readLock.request(mode==StudyWorkMode.READ,latestDocumentReady))focus.clearFocus()else localMessage=readLock.reason
                }
            }
        }
        if(!leaveReviewContext(preserve=true))return false
        if(consulted)reviewConsultedOriginal=true
        if(!readLock.request(mode==StudyWorkMode.READ,latestDocumentReady))return false
        focus.clearFocus();return true
    }
    val readControl:@Composable ()->Unit={
        StudyWorkModes(if(reviewVisible||reviewPreparation!=null)StudyWorkMode.RECALL else if(readOnly)StudyWorkMode.READ else StudyWorkMode.WRITE,tagPrefix="study"){mode->
            if(!chooseWorkMode(mode))localMessage=readLock.reason
        }
    }
    val latestWorkMode by rememberUpdatedState<(StudyWorkMode)->Boolean>(::chooseWorkMode)
    DisposableEffect(note.base.id,workModeRequest){
        val handler:(StudyWorkMode)->Boolean={latestWorkMode(it)}
        workModeRequest?.value=handler
        onDispose{if(workModeRequest!=null&&workModeRequest.value===handler)workModeRequest.value=null}
    }
    SideEffect{onReviewActive(reviewVisible||reviewPreparation!=null)}
    DisposableEffect(note.base.id){val report=onReviewActive;onDispose{report(false)}}
    LaunchedEffect(reviewRequest,browseReady){
        if(reviewRequest>reviewRequestSeen&&browseReady){
            reviewRequestSeen=reviewRequest
            if(!chooseWorkMode(StudyWorkMode.RECALL))localMessage=readLock.reason
        }
    }
    androidx.activity.compose.BackHandler(ui.busy||ui.unknown||mapSaving||hasDraft){android.widget.Toast.makeText(context,"请先完成或取消草稿，并核对当前导图操作",android.widget.Toast.LENGTH_SHORT).show()}
    fun changeCollapsed(next:List<String>,anchor:String?=null){
        if(titleDraft!=null||next.toSet()==collapsed.toSet())return
        val affected=if(anchor==null)active.map{it.id}.toSet()else StudyOutline.project(active.map{it.model()},focusId=anchor).rows.map{it.node.id}.toSet()
        graph?.let{mapChangeFeedback=MapChangeFeedback(it.graphFingerprint,next.toSet(),affected,anchor?:vm.selectedByMap[mapKey])}
        vm.searchSession?.changedByUser=true;collapsed=next
    }
    fun toggleBranch(nodeId:String){changeCollapsed(if(nodeId in collapsed)collapsed-nodeId else collapsed+nodeId,nodeId)}
    fun focusBranch(nodeId:String?){if(titleDraft!=null)return;if(focusId!=nodeId)cancelReviewPreparation();vm.searchSession?.changedByUser=true;if(nodeId!=null&&vm.portalBranches.containsKey(mapKey))vm.portalBranches[mapKey]=nodeId else vm.portalBranches.remove(mapKey);focus.clearFocus();focusId=nodeId}
    LaunchedEffect(graph?.graphFingerprint,organizationReveal){
        val reveal=organizationReveal?:return@LaunchedEffect
        if(graph?.graphFingerprint==reveal.first){
            val ancestors=mutableSetOf<String>();var parent=nodeById[reveal.second]?.parentId
            while(parent!=null&&ancestors.add(parent))parent=nodeById[parent]?.parentId
            collapsed=collapsed-ancestors
            if(focusId!=null&&focusId!=reveal.second&&focusId !in ancestors)focusBranch(null)
            vm.selectedByMap[mapKey]=reveal.second;vm.revealByMap[mapKey]=reveal.second;organizationReveal=null
        }
    }
    LaunchedEffect(ui.loading,active.map{it.id}){if(!ui.loading&&focusId!=null&&focusId !in nodeById&&vm.portalBranches[mapKey]!=focusId)focusId=null}
    SideEffect{vm.collapsedByMap[mapKey]=collapsed;vm.focusedByMap[mapKey]=focusId}
    DisposableEffect(vm,mapKey,restoreEpoch){onDispose{if(vm.viewportRestore==restoreEpoch){vm.collapsedByMap[mapKey]=collapsed;vm.focusedByMap[mapKey]=focusId}}}
    var sourcePending by rememberSaveable(initialSource){mutableStateOf(initialSource!=null)}
    SideEffect{chrome?.onAuthorDraft?.invoke(authorDraft||sourcePending)}
    DisposableEffect(note.base.id){val reporter=chrome?.onAuthorDraft;onDispose{reporter?.invoke(false)}}
    ReadLockGuard(readLock,"$guardKey-capture",blocked=sourcePending,draft=sourcePending)
    var sourceSeen by rememberSaveable{mutableLongStateOf(sourceRequest)}
    // Restoring a closed window must not restore an old dismissal over a fresh capture.
    LaunchedEffect(sourceRequest){if(sourceSeen!=sourceRequest){sourceSeen=sourceRequest;sourcePending=initialSource!=null}}
    var sourceParent by rememberSaveable(mapKey){mutableStateOf<String?>(null)}
    var inspectorMapKey by rememberSaveable{mutableStateOf(mapKey)}
    LaunchedEffect(mapKey){if(inspectorMapKey!=mapKey){chosenNodeId=null;chosenCardId=null;inspectSource=false;inspectorMapKey=mapKey}}
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


    var initialCardApplied by rememberSaveable(initialCardId,cardOpenRequest){mutableStateOf(false)}
    LaunchedEffect(initialCardId,cardOpenRequest,ui.loading){if(initialCardId!=null&&!ui.loading&&!initialCardApplied){chosenCardId=ui.cards.find{it.id==initialCardId&&it.trashedAt==null}?.id;chosenNodeId=vm.selectedByMap[mapKey]?.takeIf{nodeId->ui.nodes.any{it.id==nodeId&&it.cardId==initialCardId&&!it.removed}};initialCardApplied=true}}
    val id={UUID.randomUUID().toString()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->
        val text=pendingExport;pendingExport=null;if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}};localMessage="大纲与摘要已导出，共享卡片正文只保留一份。图形布局与来源原迹请用资料库备份保存。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认；卡片仍保留在本机。"}}
    }
    LaunchedEffect(chosenCard?.id,chosenCard?.revision,sourceReload){
        stale=false
        navigation.loadCardSources(chosenCard,chosenCard?.id in structureCards.map{it.id})
    }
    LaunchedEffect(source){
        val current=source
        stale=if(current==null)false else withContext(Dispatchers.IO){runCatching{app.pages.inkRevision(current.pageId)!=current.inkRevision}.getOrDefault(true)}
    }
    LaunchedEffect(ui.completed){if(ui.completed!=null&&!titleSubmitted){
        editor?.takeIf{it.card==null}?.let{e->
            e.parent?.id?.let{collapsed=collapsed-it}
            if(focusId!=null)focusId=e.parent?.id
        }
        sourcePending=false;returnTab=null;editor=null;chosenNodeId=null;chosenCardId=null;reparentId=null;vm.clear()
    }}
    fun openCard(card:StudyCardRow,node:StudyNodeRow?=null){if(!browseReady||titleDraft!=null)return;if(vm.selectedByMap[mapKey]!=node?.id)cancelReviewPreparation();focus.clearFocus();chosenNodeId=node?.id;chosenCardId=card.id;vm.selectedByMap[mapKey]=node?.id}
    fun editTitle(node:StudyNodeRow,create:Boolean=false,sibling:Boolean=false){
        if(!editable||!readLock.canWrite||titleDraft!=null)return
        val card=cardById[node.cardId]?:return
        val parent=if(sibling)nodeById[node.parentId]else node
        val fresh=id()
        titleDraft=NodeTitleDraft(id(),currentMap,if(create)fresh else node.id,if(create)id()else card.id,
            if(create)0 else card.revision,if(create)""else card.title,if(create)""else card.body,
            if(create)parent?.id else node.parentId,
            if(create)nextNodeX(parent)else node.x,
            if(create)nextNodeY(parent)else node.y,
            create,!create&&card.id in structureCards.map{it.id},if(create)graph?.graphFingerprint.orEmpty()else"",node.id)
        titleInput=androidx.compose.ui.text.input.TextFieldValue(if(create)""else card.title,androidx.compose.ui.text.TextRange(0,if(create)0 else card.title.length));titleSubmitted=false;titleOperation=null;titleContinue=false;titleVerified=false;titleCheckFailed=false;localMessage=null;nodeMenu=false;management=false;mapMenu=false;map?.revealNode(node.id)
    }
    fun clearTitle(){
        focus.clearFocus();titleDraft=null;titleSubmitted=false;titleOperation=null;titleContinue=false;titleVerified=false;titleCheckFailed=false
    }
    fun titleCommand(d:NodeTitleDraft,operation:String,title:String)=StudyCommand(operation,note.base.id,
        if(d.creating)StudyAction.CREATE else StudyAction.EDIT,mapId=d.mapId,cardId=d.cardId,
        nodeId=if(d.creating)d.nodeId else null,expectedRevision=d.revision,parentId=d.parentId,
        title=title,body=d.body,x=d.x,y=d.y,expectedGraph=d.graph,afterNodeId=d.anchorId.takeIf{d.creating&&it!=d.parentId})
    fun saveTitle(title:String,continueSibling:Boolean=false){
        if(!readLock.canWrite||titleSubmitted||vm.ui.value.busy||vm.ui.value.unknown||mapWriter.ui.value.busy||mapWriter.ui.value.unknown)return
        val d=titleDraft?:return
        if(d.mapId!=vm.mapId.value)return
        if(d.structural){
            val row=maps.find{it.id==d.mapId};val value=row?.data() as? KnowledgeData.MapDefinition
            if(row==null||row.revision!=d.revision||value==null||value.structures.none{it.id==d.cardId}){localMessage="主题已变化，草稿保留。取消后重新核对标题。";return}
            titleOperation=mapWriter.submit(note.base.id,value.copy(structures=value.structures.map{if(it.id==d.cardId)it.copy(title=title)else it}),row)?:return
        }else{
            val operation=id();titleOperation=operation;vm.submit(titleCommand(d,operation,title))
        }
        titleSubmittedText=title;titleContinue=continueSibling;titleSubmitted=true;titleVerified=false;titleCheckFailed=false;localMessage=null
    }
    fun retryTitle(){
        if(titleCheckFailed){titleCheckFailed=false;titleCheckAttempt++}
        else if(titleDraft?.structural==true)mapWriter.retry()else vm.retry()
    }
    // Keep native focus and the editor transition on Main after background reads.
    LaunchedEffect(titleOperation,titleVerified,titleCheckAttempt,ui.busy,ui.unknown,ui.completed,
        mapWrite.busy,mapWrite.unknown,mapWrite.completedOperation,mapWrite.rejectedOperation){withContext(Dispatchers.Main.immediate){
        val d=titleDraft?:return@withContext;val operation=titleOperation?:return@withContext
        if(!titleSubmitted||vm.ui.value.busy||vm.ui.value.unknown||mapWriter.ui.value.busy||mapWriter.ui.value.unknown)return@withContext
        try{
            if(!titleVerified){
                val saved=if(d.structural){
                    if(mapWriter.ui.value.rejectedOperation==operation){
                        titleSubmitted=false;titleContinue=false;titleOperation=null;return@withContext
                    }
                    mapWriter.ui.value.completedOperation==operation&&mapWriter.ui.value.completed==d.mapId
                }else withContext(Dispatchers.IO){vm.repo.lookup(titleCommand(d,operation,titleSubmittedText))==d.cardId}
                if(!saved){
                    // No successful receipt means there is no permission to continue.
                    if((if(d.structural)mapWriter.ui.value.message else vm.ui.value.message)==null)localMessage="未核对到成功保存，未继续；草稿保留，请核对后重试。"
                    titleSubmitted=false;titleContinue=false;titleOperation=null;return@withContext
                }
                titleVerified=true;return@withContext
            }
            val next=if(titleContinue&&vm.mapId.value==d.mapId&&vm.lastTab in 1..2&&readLock.canWrite){
                // A completed write can precede the UI Flow emission. Freeze from a fresh read.
                val fresh=withContext(Dispatchers.IO){vm.repo.readGraph(note.base.id,d.mapId)}
                val nodes=fresh.nodes
                val node=nodes.find{it.id==d.nodeId&&!it.removed}
                val parent=nodes.find{it.id==d.parentId&&!it.removed}
                val structures=(fresh.definition?.data() as? KnowledgeData.MapDefinition)?.structures.orEmpty().associateBy{it.id}
                fun measured(n:StudyNodeRow):MapNodeLayout{
                    val card=fresh.cards.find{it.id==n.cardId};val structure=structures[n.cardId]
                    return MapNodeMetrics.measure(structure?.title?:card?.title.orEmpty(),card?.body.orEmpty(),previewSourceInfo(n.cardId),mapFontScale,structural=structure!=null)
                }
                if(node==null||node.parentId!=d.parentId||(d.parentId!=null&&parent==null)){
                    localMessage="标题已保存；原主题或上级已变化，未继续同级主题。";null
                }else NodeTitleDraft(id(),d.mapId,id(),id(),0,"","",d.parentId,
                    (parent?.let{it.x+measured(it).width+40}?:node.x).coerceIn(-40000.0,40000.0),
                    (nodes.filter{!it.removed&&it.parentId==d.parentId}.maxOfOrNull{it.y+measured(it).height+32}?:node.y).coerceIn(-40000.0,40000.0),
                    creating=true,graph=fresh.graphFingerprint,anchorId=node.id)
            }else null
            if(titleDraft?.token!=d.token||titleOperation!=operation)return@withContext
            vm.selectedByMap[d.mapId?:"main"]=d.nodeId;vm.revealByMap[d.mapId?:"main"]=d.nodeId
            d.parentId?.let{collapsed=collapsed-it}
            if(d.structural)mapWriter.consumed(operation)else vm.clear()
            clearTitle()
            if(next!=null){titleDraft=next;titleInput=androidx.compose.ui.text.input.TextFieldValue()}
        }catch(c:CancellationException){throw c}
        catch(_:Exception){
            if(titleDraft?.token==d.token){
                if(titleVerified){
                    if(d.structural)mapWriter.consumed(operation)else vm.clear()
                    clearTitle();localMessage="标题已保存；同级位置读取失败，未继续。请重新选择主题。"
                }else {titleCheckFailed=true;localMessage="标题结果待核对；草稿已保留，请核对原操作。"}
            }
        }
    }}
    LaunchedEffect(tab,titleDraft?.token,projection.rows.map{it.node.id to (cardById[it.node.cardId]!=null)}){
        val d=titleDraft?:return@LaunchedEffect
        if(tab==1&&d.mapId==currentMap){
            val index=projection.rows.indexOfFirst{it.node.id==d.anchorId&&cardById[it.node.cardId]!=null}.let{if(it<0)projection.rows.size else it}
            snapshotFlow{outlineListState.layoutInfo.totalItemsCount}.first{it>index}
            // The item-count flow can resume during layout; scroll on the next measure pass.
            outlineListState.requestScrollToItem(index)
        }
    }
    Column(Modifier.fillMaxSize()){
    if(compactWindow&&chrome!=null)FlowRow(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("study-shared-header"),horizontalArrangement=Arrangement.SpaceBetween){
        Box(chrome.drag.size(32.dp,48.dp),contentAlignment=Alignment.Center){Text("⠿",color=Quiet)}
        Box(Modifier.weight(1f)){
            TextButton({mapMenu=true},enabled=browseReady&&!hasDraft,modifier=Modifier.fillMaxWidth().testTag("study-map-picker")){
                Text(currentMap?.let{m->(maps.find{it.id==m}?.data() as? KnowledgeData.MapDefinition)?.title}?:"主图",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(mapMenu,{mapMenu=false}){
                var mapQuery by remember{mutableStateOf("")}
                OutlinedTextField(mapQuery,{mapQuery=it},singleLine=true,label={Text("按图名查找")},modifier=Modifier.width(260.dp).padding(8.dp))
                DropdownMenuItem(text={Text("主图")},onClick={mapMenu=false;chooseMap(null)},modifier=Modifier.testTag("study-map-main"))
                maps.filter{(it.data() as KnowledgeData.MapDefinition).title.contains(mapQuery,true)}.forEach{m->DropdownMenuItem(text={Text((m.data() as KnowledgeData.MapDefinition).title)},onClick={mapMenu=false;chooseMap(m.id)},modifier=Modifier.testTag("study-map-${m.id}"))}
                DropdownMenuItem(text={Text("新建导图")},onClick={mapMenu=false;templatePicker=true},enabled=editable,modifier=Modifier.testTag("study-new-map"))
            }
        }
        IconButton({if(vm.searchSession!=null)vm.endSearch()else contentSearch=true},enabled=browseReady&&!hasDraft,modifier=Modifier.size(48.dp).testTag("study-content-search").describedAs(if(vm.searchSession!=null)"结束搜索并返回视野"else"查找导图内容")){Glyph(if(vm.searchSession!=null)"close"else"search")}
        Box{
        MapActionIcon("导图管理","more","study-management",browseReady&&!hasDraft){management=!management}
        MapMenu(management,{management=false},managementGroup,{managementGroup=it}){
            if(managementGroup==0)MapMenuSection("视图"){
                listOf("摘要卡","大纲","思维导图").forEachIndexed{i,label->DropdownMenuItem(text={Text(label)},onClick={chooseTab(i);management=false},modifier=Modifier.testTag("study-tab-$i"))}
                DropdownMenuItem(text={Text("显示知识关联")},onClick={knowledgeRelations=!knowledgeRelations;management=false},enabled=browseReady&&tab==2,
                    trailingIcon={Switch(knowledgeRelations,onCheckedChange=null)},modifier=Modifier.testTag("study-knowledge-relations-toggle"))
                DropdownMenuItem(text={Text("查看全图")},onClick={map?.fitOverview();management=false},enabled=tab==2,modifier=Modifier.testTag("study-fit-overview"))
                DropdownMenuItem(text={Text("可读大小")},onClick={map?.fit();management=false},enabled=tab==2,modifier=Modifier.testTag("study-fit-readable"))
                DropdownMenuItem(text={Text("查找导图内容")},onClick={contentSearch=true;management=false},modifier=Modifier.testTag("study-search-content"))
            }
            if(managementGroup==1)MapMenuSection("整理"){
                DropdownMenuItem(text={Text("容量与整理")},onClick={openCapacity()},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("study-capacity"))
                Column(Modifier.padding(horizontal=16.dp)){ReviewScopeSelector(reviewQuestionScope,browseReady&&!hasDraft,"map-review",{chooseReviewScope(it)})}
                DropdownMenuItem(text={Text("复习此图")},onClick={management=false;prepareReview(MapRef(note.base.id,currentMap),null)},enabled=browseReady&&!hasDraft&&reviewPreparation==null,modifier=Modifier.testTag("study-review-map"))
                DropdownMenuItem(text={Text("固定当前图到学习")},onClick={app.learningStore.shortcut(StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap),true);management=false},modifier=Modifier.testTag("study-pin-map"))
                if(vm.selectedByMap[mapKey]!=null)DropdownMenuItem(text={Text("固定所选分支到学习")},onClick={app.learningStore.shortcut(StableTargetRef(LearningTargetKind.BRANCH,note.base.id,vm.selectedByMap[mapKey],currentMap),true);management=false})
                DropdownMenuItem(text={Text("新建导图")},onClick={templatePicker=true;management=false},enabled=editable,modifier=Modifier.testTag("study-new-map"))
                DropdownMenuItem(text={Text("新建摘要卡")},onClick={returnTab=tab;editor=CardEditor();vm.selectTab(0);management=false},enabled=editable,modifier=Modifier.testTag("study-add-card"))
                DropdownMenuItem(text={Text("展开全部")},onClick={changeCollapsed(emptyList());management=false},enabled=browseReady&&titleDraft==null&&collapsed.isNotEmpty(),modifier=Modifier.testTag("study-expand-all"))
                DropdownMenuItem(text={Text("收起分支")},onClick={changeCollapsed(active.mapNotNull{it.parentId}.distinct());management=false},enabled=browseReady&&titleDraft==null,modifier=Modifier.testTag("study-collapse-all"))
                DropdownMenuItem(text={Text("全部主题")},onClick={focusBranch(null);management=false},modifier=Modifier.testTag("study-focus-all"))
                DropdownMenuItem(text={Text("自动布局预览")},onClick={previewLayout();management=false},enabled=editable,modifier=Modifier.testTag("study-arrange"))
            }
            if(managementGroup==2)MapMenuSection("输出"){
                if(onInsertEmbed!=null)DropdownMenuItem(text={Text("把导图放入笔记")},onClick={insertMap=true;management=false},enabled=editable,modifier=Modifier.testTag("study-insert-map"))
                DropdownMenuItem(text={Text("另存为结构模板")},onClick={saveTemplate=true;management=false},enabled=editable,modifier=Modifier.testTag("study-save-template"))
                DropdownMenuItem(text={Text("导出节点大纲")},onClick={pendingExport=StudyText.markdown(note.title,displayCards.map{StudyTextCard(it.id,it.title,it.body,annotations[it.id].orEmpty())},ui.nodes.map{it.model()})+"\n\n节点内容快照，不包含跨图入口关系；完整恢复请使用资料库备份。\n";export.launch("墨织大纲.md");management=false},enabled=browseReady)
            }
        }}
        if(showReadControl)readControl()
        chrome.controls()
    }
    if(tab==2&&vm.portalReturns.isNotEmpty())TextButton({
        val back=vm.portalReturns.last()
        val scene=scenes.find{it.ref==MapRef(note.base.id,back.mapId)&&it.available}
        if(scene==null)localMessage="原图已回收或不可用，保留当前图。"
        else {cancelReviewPreparation();if(vm.returnPortal(scene.nodes.map{it.id}.toSet()))navigation.cancelSourceNavigation()}
    },enabled=browseReady&&!hasDraft,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-portal-back")){
        Text("返回 ${vm.portalReturns.last().title}",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    Box(Modifier.weight(1f)){
    if(templatePicker){
        val installedMaps=rememberTemplateCatalog().filter{it.resource.map!=null}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text("新建导图",Modifier.weight(1f));TextButton({templatePicker=false}){Text("返回")}}
            (MapTemplates.builtins+extraRows.filter{!it.removed&&it.notebookId==note.base.id}.mapNotNull{it.data() as? KnowledgeData.MapTemplate}).forEachIndexed{i,t->
                TextButton({installedMapHash=null;installedMapId=null;template=t;templatePicker=false;newMapTitle=t.title},enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("map-template-$i")){Text(t.title,Modifier.weight(1f));Text("${t.nodes.size} 个结构主题",style=MaterialTheme.typography.labelSmall)}
            }
            if(installedMaps.isNotEmpty())Text("我的模板",style=MaterialTheme.typography.labelLarge,color=Quiet)
            installedMaps.forEach{entry->TextButton({template=checkNotNull(entry.resource.map);installedMapHash=entry.ref.hash;installedMapId=entry.ref.id;templatePicker=false;newMapTitle=entry.title},enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("installed-map-${entry.ref.id}")){Text(entry.title,Modifier.weight(1f));Text("v${entry.version}",style=MaterialTheme.typography.labelSmall)}}

        }
    }else Box(Modifier.fillMaxSize()){
        val studyHeaderContent:@Composable ColumnScope.()->Unit={
            val studyActions:@Composable RowScope.()->Unit={
                TextButton(onClick={app.learningStore.shortcut(vm.selectedByMap[mapKey]?.let{StableTargetRef(LearningTargetKind.BRANCH,note.base.id,it,currentMap)}?:StableTargetRef(LearningTargetKind.MAP,note.base.id,currentMap),true);localMessage="已固定到学习快捷入口"},enabled=browseReady,modifier=Modifier.testTag("study-pin-map")){Text(if(vm.selectedByMap[mapKey]!=null)"固定分支"else"固定到学习")}
                TextButton(onClick={editor=CardEditor()},enabled=editable,modifier=Modifier.testTag("study-add-card")){Text("＋ 新摘要卡")}
                TextButton(onClick={saveTemplate=true},enabled=editable,modifier=Modifier.testTag("study-save-template")){Text("另存为模板")}
                TextButton(onClick={pendingExport=StudyText.markdown(note.title,ui.cards.filter{it.trashedAt==null}.map{StudyTextCard(it.id,it.title,it.body,annotations[it.id].orEmpty())},ui.nodes.map{it.model()})+"\n\n节点内容快照，不包含跨图入口关系；完整恢复请使用资料库备份。\n";export.launch("墨织摘要.md")},enabled=browseReady&&ui.cards.isNotEmpty()){Text("导出节点大纲")}
            }

            if(!compactWindow)BoxWithConstraints(Modifier.fillMaxWidth()){
                val compact=maxWidth<600.dp||androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
                Column{
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text("笔记导图",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        if(showReadControl)readControl()
                        if(!compact)studyActions()
                        IconButton(onClick={if(!hasDraft&&leaveReviewContext())dismiss()},enabled=documentReady&&!ui.busy&&!ui.unknown&&!mapSaving&&!hasDraft,modifier=Modifier.size(48.dp).testTag("study-close").describedAs("返回笔记")){Glyph("close")}
                    }
                    if(compact)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),content=studyActions)
                }
            }
            if(!compactWindow)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                if(compactWindow)Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())){
                    (listOf(null to "主图")+maps.map{it.id to (it.data() as KnowledgeData.MapDefinition).title}).forEach{(mapId,label)->
                        TextButton({chooseMap(mapId)},enabled=browseReady&&!hasDraft,modifier=Modifier.heightIn(min=48.dp).testTag("map-tab-${mapId?:"main"}"),colors=ButtonDefaults.textButtonColors(contentColor=if(currentMap==mapId)Forest else Quiet)){Text(label,maxLines=1)}
                    }
                }
                Box(if(compactWindow)Modifier.width(48.dp)else Modifier.weight(1f)){
                    TextButton({mapMenu=true},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("study-map-picker")){if(!compactWindow)Text(currentMap?.let{id->(maps.find{it.id==id}?.data() as? KnowledgeData.MapDefinition)?.title}?:"主图",maxLines=1);Text("▾")}
                    DropdownMenu(mapMenu,{mapMenu=false}){
                        var mapQuery by remember{mutableStateOf("")}
                        OutlinedTextField(mapQuery,{mapQuery=it},singleLine=true,label={Text("查找导图")},modifier=Modifier.width(260.dp).padding(8.dp))
                        DropdownMenuItem(text={Text("主图")},onClick={mapMenu=false;chooseMap(null)},modifier=Modifier.testTag("study-map-main"))
                        maps.filter{(it.data() as KnowledgeData.MapDefinition).title.contains(mapQuery,true)}.forEach{m->DropdownMenuItem(text={Text((m.data() as KnowledgeData.MapDefinition).title)},onClick={mapMenu=false;chooseMap(m.id)},modifier=Modifier.testTag("study-map-${m.id}"))}
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
                    TextButton({val parent=nodeById[sourceParent];vm.submit(CaptureDraft(note.base.id,initialSource,initialCaptureText).command(MapRef(note.base.id,currentMap),sourceParent,graph?.graphFingerprint.orEmpty(),nextNodeX(parent),nextNodeY(parent)))},enabled=editable,modifier=Modifier.testTag("study-add-source")){Text("添加")}
                    IconButton({sourcePending=false},enabled=browseReady,modifier=Modifier.testTag("study-cancel-source").describedAs("取消摘录草稿")){Glyph("close")}
                }
            }
            if(tab!=0&&(!compactWindow||focusId!=null)){
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={focusBranch(null)},enabled=titleDraft==null&&focusId!=null,modifier=Modifier.testTag("study-focus-all")){Text("全部主题")}
                    projection.path.forEach{n->Text("›",color=Quiet);TextButton(onClick={focusBranch(n.id)},enabled=titleDraft==null,modifier=Modifier.testTag("study-breadcrumb-${n.id}")){Text(cardById[n.cardId]?.title.orEmpty(),maxLines=1)}}
                    Text("${shown.size} / ${active.size} 个主题",fontSize=12.sp,color=Quiet)
                TextButton({annotationLayers=true},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("map-annotation-layers")){Text("批注图层")}
                TextButton({annotationNodeId=mapAuthoring.scope.id},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("map-free-annotation")){Text("游离批注")}
                    if(!compactWindow)TextButton(onClick={changeCollapsed(emptyList())},enabled=titleDraft==null&&collapsed.isNotEmpty(),modifier=Modifier.testTag("study-expand-all")){Text("展开全部")}
                    if(!compactWindow)TextButton(onClick={changeCollapsed(active.mapNotNull{it.parentId}.distinct())},enabled=titleDraft==null&&active.any{it.parentId!=null},modifier=Modifier.testTag("study-collapse-all")){Text("收起分支")}
                }
            }
            if(!compactWindow)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("摘要卡","大纲","思维导图").forEachIndexed{i,label->FilterChip(selected=tab==i,onClick={focus.clearFocus();chooseTab(i)},enabled=browseReady&&!hasDraft,label={Text(label)},shape=RoundedCornerShape(10.dp),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Leaf,selectedLabelColor=Forest),modifier=Modifier.testTag("study-tab-$i"))}
                if(tab==0)FilterChip(selected=showTrash,onClick={showTrash=!showTrash},label={Text("卡片回收区")})
                if(tab==2){TextButton(onClick={previewLayout()},enabled=editable&&!dragging,modifier=Modifier.testTag("study-arrange")){Text("自动布局预览")}}
            }
            val msg=ui.message?:localMessage
            if(knowledgeRelations&&tab==2)Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text("知识关联 · 箭头表示方向",style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).testTag("study-knowledge-relations-legend"))
                TextButton({nodeById[relationNodeId]?.let{openCardKnowledge(it.cardId,true,true)}},
                    enabled=browseReady&&!hasDraft&&relationNodeId!=null,modifier=Modifier.heightIn(min=48.dp).testTag("study-knowledge-relations-outside")){
                    Text(if(relationNodeId==null)"选中卡片查看关联"else"查看关联 · 范围外 ${knowledgeProjection.outOfScopeCount}",maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(reviewPreparation!=null)Text("正在读取回忆范围…",fontSize=12.sp,color=Quiet,modifier=Modifier.testTag("study-review-preparing"))
            if(msg!=null)Text(msg,fontSize=12.sp,color=Forest,modifier=Modifier.testTag("study-message"))
            else if(capacityVisible)StudyCapacityWarning(capacityUsage,capacitySnapshot,browseReady&&!hasDraft,::openCapacity)
            if(vm.organizationUndo?.mapId==currentMap&&vm.organizationUndo!=null&&titleDraft==null)TextButton({
                localMessage=null
                vm.organizationUndo?.organization?.let{plan->
                    if(plan.expectedGraph==layoutUndoGraph){
                        layoutRestoreGraph=plan.expectedAfterGraph;layoutFitGraph=null;organizationReveal=null
                        mapChangeFeedback=MapChangeFeedback(plan.expectedAfterGraph,null,active.map{it.id}.toSet(),vm.selectedByMap[mapKey])
                    }
                    else vm.selectedByMap[mapKey]?.let{organizationReveal=plan.expectedAfterGraph to it}
                }
                vm.undoOrganization()
            },enabled=editable&&vm.organizationUndo?.expectedGraph==graph?.graphFingerprint,modifier=Modifier.heightIn(min=48.dp).testTag("study-undo-organization")){Glyph("undo");Spacer(Modifier.width(6.dp));Text("撤销上一步整理")}
            if(vm.organizationRedo?.mapId==currentMap&&vm.organizationRedo!=null&&titleDraft==null)TextButton({
                localMessage=null
                vm.organizationRedo?.organization?.let{plan->vm.selectedByMap[mapKey]?.let{organizationReveal=plan.expectedAfterGraph to it}}
                vm.redoOrganization()
            },enabled=editable&&vm.organizationRedo?.expectedGraph==graph?.graphFingerprint,modifier=Modifier.heightIn(min=48.dp).testTag("study-redo-organization")){Text("重做上一步整理")}
            if(tab!=0)FlowRow(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.Center){
                if(compactWindow){
                    TextButton({chooseTab(1)},enabled=browseReady&&!hasDraft,modifier=Modifier.heightIn(min=48.dp).testTag("study-direct-outline").editorSelected(tab==1)){Text("大纲")}
                    TextButton({chooseTab(2)},enabled=browseReady&&!hasDraft,modifier=Modifier.heightIn(min=48.dp).testTag("study-direct-map").editorSelected(tab==2)){Text("导图")}
                }
                TextButton({selectionMode=!selectionMode},enabled=browseReady&&!hasDraft,
                    modifier=Modifier.heightIn(min=48.dp).testTag("study-select-many").editorSelected(selectionMode)){Text(if(selectionMode)"结束多选"else if(tab==2)"框选"else"多选")}
                if(selectedGroup.isNotEmpty()){
                    Text("已选 ${selectedGroup.size} 项 · 整支 ${selectedBranches.size} 主题",style=MaterialTheme.typography.labelSmall,modifier=Modifier.align(Alignment.CenterVertically).testTag("study-selection-count"))
                    TextButton({graph?.state?.let{groupTarget=it to selectedGroup.toSet()}},enabled=editable&&!hasDraft,modifier=Modifier.testTag("study-group-selection")){Text("分组")}
                    TextButton({previewLayout(true)},enabled=editable&&!hasDraft,modifier=Modifier.testTag("study-arrange-selection")){Text("布局预览")}
                    val selectedCards=selectedGroup.mapNotNull{nodeById[it]?.cardId}.filter{it !in structureCards.map{card->card.id}}.distinct()
                    TextButton({openTransform(CardTransformKind.MERGE,selectedCards)},enabled=editable&&!hasDraft&&selectedCards.size in 2..16,modifier=Modifier.testTag("study-merge-selection")){Text("合并 ${selectedCards.size} 卡")}
                    TextButton({openTransform(CardTransformKind.SUMMARY,selectedCards)},enabled=editable&&!hasDraft&&selectedCards.size in 2..16,modifier=Modifier.testTag("study-summarize-selection")){Text("总结 ${selectedCards.size} 卡")}
                    TextButton({selectedGroup=emptyList()},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("study-clear-selection")){Text("清除")}
                }
            }
            if(tab==0&&transformedCardIds.isNotEmpty())Column(Modifier.fillMaxWidth().testTag("study-transform-results")){
                Text("新内容卡已保留来源。选择查看；需要时可在详情中复用到导图。",style=MaterialTheme.typography.bodySmall)
                transformedCardIds.forEach{id->
                    val card=ui.cards.firstOrNull{it.id==id&&it.trashedAt==null}
                    TextButton({card?.let{openCard(it)}},enabled=card!=null&&browseReady&&!hasDraft,modifier=Modifier.testTag("study-transform-result-$id")){Text(card?.title?:"正在读取新卡…")}
                }
                TextButton({transformedCardIds=emptyList()}){Text("收起结果入口")}
            }
            if(tab==2&&selectionMode)Text("在空白处拖框，点主题增减选择；拖动所选主题会移动各自整支。双指仍可缩放。",style=MaterialTheme.typography.bodySmall)
            if(tab==1)Text(outlineDrag?.preview?.message?:if(outlineDrag!=null)"移出大纲松手可取消"else"拖柄移动整支：上／下方为同级，中间为下级。整理按钮与键盘也可操作。",
                style=MaterialTheme.typography.bodySmall,modifier=Modifier.fillMaxWidth().heightIn(min=36.dp).testTag("outline-drag-feedback").semantics{liveRegion=LiveRegionMode.Polite})
            if(missingPortalBranch&&!ui.loading)Text("指定分支已变化或移除，暂不显示其他主题。可返回原图或明确查看全部主题。",fontSize=12.sp,modifier=Modifier.testTag("map-portal-branch-unavailable"))
            if(ui.unknown)TextButton(onClick=vm::retry,enabled=!ui.busy,modifier=Modifier.testTag("study-retry")){Text("核对原操作")}
            if(ui.readFailed)Row(verticalAlignment=Alignment.CenterVertically){Text("读取失败，保留上次内容",Modifier.weight(1f));TextButton(vm::refresh,modifier=Modifier.testTag("study-reload")){Text("重新加载")}}
            if(ui.loading||ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(tab==0)OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索摘要标题或正文")},
                trailingIcon={if(query.isNotEmpty())TextButton(onClick={query=""}){Text("清除")}},modifier=Modifier.fillMaxWidth().testTag("study-search"))
        }
        val studyMapContent:@Composable ColumnScope.()->Unit={
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)){key(mapKey){AndroidView(factory={MindMapView(it).also{v->map=v;vm.viewports[mapKey]?.let(v::restoreViewport);v.onViewport={vp->vm.viewports[mapKey]=vp}}},onReset=null,onRelease={v->if(map===v)map=null},update={v->v.captureBook=note.base.id;v.captureMapKey=mapKey;v.captureGraph=graph?.graphFingerprint.orEmpty();v.onCapture={transfer,parent,x,y,graph->
                if(editable&&readLock.canWrite)vm.submit(transfer.command(MapRef(note.base.id,currentMap),parent?.id,graph,x,y))
            };v.movementNodes=active;v.selectionMode=selectionMode;v.selectedNodeIds=selectedGroup.toSet();v.onSelectMany={selectedGroup=it.toList()};v.onGestureMessage={localMessage=it};v.onMoveSelection={roots,dx,dy,stamp->
                val snapshot=graph
                if(editable&&readLock.canWrite&&snapshot!=null&&snapshot.graphFingerprint==stamp){
                    runCatching{StudyOrganization.moveSelection(snapshot.state,roots,dx,dy)}.onSuccess{vm.organize(snapshot.state,it)}
                        .onFailure{localMessage="整支超出画布范围，未移动；所有来源与位置保留"}
                }else localMessage="导图已变化，移动取消；请重新拖动"
            };v.selectedNodeId=vm.selectedByMap[mapKey];v.branchIds=active.mapNotNull{it.parentId}.toSet();v.onToggleBranch=::toggleBranch;v.enabledInput=browseReady;v.authorEditing=editable;v.editingTitle=titleDraft!=null;v.onSelectionBounds={selectedBounds=it};v.expandedNodeId=expandedNodeId;val showScene={v.show(shown,displayCards,hiddenCounts,mapSources,structureCards.map{it.id}.toSet())};val feedback=mapChangeFeedback;if(feedback!=null&&feedback.graph==graph?.graphFingerprint&&(feedback.collapsed==null||feedback.collapsed==collapsed.toSet())){mapChangeFeedback=null;v.transitionScene(feedback.nodes,feedback.anchor,showScene)}else showScene();v.showAuthoring(annotationUi.state);v.setKnowledgeRelations(knowledgeProjection.edges);v.setSourceReading(chrome?.sourceReading==true);vm.revealByMap[mapKey]?.let{if(if(vm.searchHit?.nodeId==it||vm.focusedByMap[mapKey]==it)v.focusNode(it)else v.revealNode(it))vm.revealByMap.remove(mapKey)};v.setCardPresentations(presentations);v.onActive={readLock.guard("$guardKey-gesture",it);dragging=it;controlPulse++;if(it)vm.searchSession?.changedByUser=true};v.onSelect={n->if(vm.selectedByMap[mapKey]!=n?.id)cancelReviewPreparation();vm.selectedByMap[mapKey]=n?.id;nodeMenu=false};v.onEditTitle={n->editTitle(n)};v.onOpenDetails={n->cardById[n.cardId]?.let{openCard(it,n)}};v.onIndent={outdent->if(!hasDraft&&chosenCardId==null&&knowledgeCardId==null&&!reviewVisible&&organizeNodeId==null&&reparentId==null)nodeById[vm.selectedByMap[mapKey]]?.let{organize(it,if(outdent)StudyOrganizationAction.OUTDENT else StudyOrganizationAction.INDENT)}};v.onAddSibling={if(!hasDraft&&chosenCardId==null&&knowledgeCardId==null&&!reviewVisible&&organizeNodeId==null&&reparentId==null)nodeById[vm.selectedByMap[mapKey]]?.let{editTitle(it,true,true)}};v.onMove={n,x,y->if(editable&&readLock.canWrite)graph?.let{snapshot->runCatching{StudyOrganization.move(snapshot.state,n.id,x,y)}.getOrNull()?.let{vm.organize(snapshot.state,it)}}}},modifier=Modifier.fillMaxSize().then(if(compactWindow)Modifier else Modifier.clip(InkTheme.ToolShape)).testTag("study-map"))}
                val selected=nodeById[vm.selectedByMap[mapKey]]
                val density=LocalDensity.current
                var overlaySize by remember{mutableStateOf(IntSize.Zero)}
                var actionBounds by remember{mutableStateOf<android.graphics.RectF?>(null)}
                val bounds=selectedBounds
                if(selected!=null&&bounds!=null&&!dragging){
                    val w=with(density){maxWidth.toPx()};val h=with(density){maxHeight.toPx()}
                    val panelW=with(density){(if(titleDraft!=null)minOf(320.dp,maxWidth-16.dp)else minOf(288.dp,maxWidth-16.dp)).toPx()}
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
                        if(draft!=null)NodeTitleEditor(draft,occurrenceCount(draft.cardId),ui.busy||mapWrite.busy||(titleSubmitted&&!titleCheckFailed&&!ui.unknown&&!mapWrite.unknown),ui.unknown||mapWrite.unknown||titleCheckFailed,
                            localMessage?:if(draft.structural)mapWrite.message else ui.message,
                            Modifier.width(with(density){panelW.toDp()}).heightIn(max=editorMaxHeight),
                            text=titleInput,onText={titleInput=it},cancel={clearTitle();localMessage=null},submit={saveTitle(it)},retry=::retryTitle,continueSibling={saveTitle(it,true)})
                        else Column(Modifier.width(with(density){panelW.toDp()})){
                            NodeActions(editable,browseReady,{editTitle(selected)},{nodeMenu=true},
                                openSource=if(selected.cardId in mapSources)({openNodeSource(selected)})else null,
                                sourceOpening=navigation.nodeSourceOpening,
                                expand=if(map?.canExpandNode(selected.id)==true)({vm.expandedByMap[mapKey]=if(expandedNodeId==selected.id)null else selected.id})else null,
                                expanded=expandedNodeId==selected.id)
                            DropdownMenu(nodeMenu,{nodeMenu=false},modifier=Modifier.widthIn(max=280.dp).testTag("node-menu")){
                                MapMenuSection("内容"){
                                    DropdownMenuItem(text={Text("此处手写批注")},onClick={annotationNodeId=selected.id;nodeMenu=false},enabled=browseReady&&!hasDraft,modifier=Modifier.testTag("node-annotation-open"))
                                    if(selected.cardId !in structureCards.map{it.id})DropdownMenuItem(text={Text("查看关联")},onClick={nodeMenu=false;openCardKnowledge(selected.cardId,true)},enabled=browseReady,modifier=Modifier.testTag("node-links"))
                                    Column(Modifier.padding(horizontal=16.dp)){ReviewScopeSelector(reviewQuestionScope,browseReady&&!hasDraft,"branch-review",{chooseReviewScope(it)})}
                                    if(selected.cardId !in structureCards.map{it.id})DropdownMenuItem(text={Text("复习此卡")},onClick={nodeMenu=false;prepareReview(MapRef(note.base.id,currentMap),selected,cardById.getValue(selected.cardId))},enabled=browseReady&&!hasDraft&&reviewPreparation==null,modifier=Modifier.testTag("node-review-card"))
                                    DropdownMenuItem(text={Text("复习此分支")},onClick={nodeMenu=false;prepareReview(MapRef(note.base.id,currentMap),selected)},enabled=browseReady&&!hasDraft&&reviewPreparation==null,modifier=Modifier.testTag("node-review-branch"))
                                    DropdownMenuItem(text={Text("查看内容")},onClick={nodeMenu=false;openCard(cardById.getValue(selected.cardId),selected)},modifier=Modifier.testTag("node-view-content"))
                                    DropdownMenuItem(text={Text("跨图入口")},onClick={nodeMenu=false;portalMapKey=mapKey;portalNodeId=selected.id},enabled=browseReady,modifier=Modifier.testTag("node-map-portals"))
                                    if(scenes.find{it.ref.mapId==currentMap}?.nodes?.any{it.id==selected.id&&it.sourceState!="无来源"}==true)DropdownMenuItem(text={Text("查看来源")},onClick={nodeMenu=false;inspectSource=true;openCard(cardById.getValue(selected.cardId),selected)},modifier=Modifier.testTag("node-view-source"))
                                }
                                HorizontalDivider()
                                MapMenuSection("组织"){
                                    DropdownMenuItem(text={Text("新增子主题")},onClick={nodeMenu=false;editTitle(selected,true)},enabled=editable,modifier=Modifier.testTag("node-add-child"))
                                    DropdownMenuItem(text={Text("新增同级主题")},onClick={nodeMenu=false;editTitle(selected,true,true)},enabled=editable,modifier=Modifier.testTag("node-add-sibling"))
                                    DropdownMenuItem(text={Text("顺序与层级")},onClick={nodeMenu=false;organizeNodeId=selected.id},enabled=editable,modifier=Modifier.testTag("node-organize"))
                                    if(active.any{it.parentId==selected.id})DropdownMenuItem(text={Text(if(selected.id in collapsed)"展开下级主题"else"收起下级主题")},onClick={nodeMenu=false;toggleBranch(selected.id)},enabled=browseReady,modifier=Modifier.testTag("node-menu-fold"))
                                    DropdownMenuItem(text={Text("移入主题（分组）")},onClick={nodeMenu=false;chooseParent(selected)},enabled=editable,modifier=Modifier.testTag("node-menu-reparent"))
                                    DropdownMenuItem(text={Text("聚焦此分支")},onClick={nodeMenu=false;focusBranch(selected.id)},enabled=browseReady)
                                    if(selected.cardId !in structureCards.map{it.id})DropdownMenuItem(text={Text("复用到此图")},onClick={nodeMenu=false;vm.submit(StudyCommand(id(),note.base.id,StudyAction.REUSE,mapId=currentMap,cardId=selected.cardId,nodeId=id(),y=nextNodeY(null)))},enabled=editable)
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
                            val hits=MapSearch.find(scenes,search.query,MapRef(note.base.id,search.initialMap),search.allMaps,annotations)
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
                        MapActionIcon(if(selected.id in collapsed)"展开下级主题"else"收起下级主题",if(selected.id in collapsed)"add"else"collapse","node-fold",browseReady){toggleBranch(selected.id)}
                    }}
                    if(selected.cardId in mapSources)sourceBox?.let{b->Surface(Modifier.offset{IntOffset(b.left.roundToInt(),b.top.roundToInt())},color=Color.White,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Line)){
                        MapActionIcon("查看来源","link","node-source",browseReady){inspectSource=true;openCard(cardById.getValue(selected.cardId),selected)}
                    }}
                }
                if(active.isEmpty()&&!ui.loading&&!sourcePending)Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){TextButton({templatePicker=true},enabled=editable,modifier=Modifier.testTag("study-empty-create")){Text("新建图 · 选择模板")};Text("也可拖入摘录",style=MaterialTheme.typography.bodySmall,color=Quiet)}
            }
        }
        val studyListContent:@Composable ColumnScope.()->Unit={
            val cards=ui.cards.filter{(it.trashedAt!=null)==showTrash&&StudyText.matches(StudyTextCard(it.id,it.title,it.body,annotations[it.id].orEmpty()),query)}
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).imePadding()){
                val editorMaxHeight=(maxHeight-16.dp).coerceAtLeast(96.dp)
                val inlineTitle:@Composable (NodeTitleDraft)->Unit={draft->
                    Box(Modifier.fillMaxWidth().testTag("outline-title-${draft.anchorId}")){
                        NodeTitleEditor(draft,occurrenceCount(draft.cardId),ui.busy||mapWrite.busy||(titleSubmitted&&!titleCheckFailed&&!ui.unknown&&!mapWrite.unknown),
                            ui.unknown||mapWrite.unknown||titleCheckFailed,localMessage?:if(draft.structural)mapWrite.message else ui.message,
                            Modifier.fillMaxWidth().heightIn(max=editorMaxHeight),text=titleInput,onText={titleInput=it},
                            cancel={clearTitle();localMessage=null},submit={saveTitle(it)},retry=::retryTitle,continueSibling={saveTitle(it,true)})
                    }
                }
                LazyColumn(Modifier.fillMaxSize().onGloballyPositioned{outlineBounds=it.boundsInRoot()}.pointerInput(Unit){
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                        // The whole list owns this gesture, so auto-scroll may recycle the source row safely.
                        val handle=outlineDraggable&&outlineHandles.values.any{it.contains(down.position+outlineBounds.topLeft)}
                        if(handle){
                            down.consume()
                            var started=false
                            var ended=false
                            try{
                                while(true){
                                    // Consume in Initial before LazyColumn's own scroll detector.
                                    val event=awaitPointerEvent(PointerEventPass.Initial)
                                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                                    if(event.changes.count{it.pressed}>1){event.changes.forEach{it.consume()};break}
                                    if(!change.pressed){
                                        val released=change.changedToUp()
                                        change.consume()
                                        if(!released)break
                                        if(started){moveOutlineDrag(change.position);endOutlineDrag()}else openOutlineHandle(down.position)
                                        ended=true;break
                                    }
                                    if(!started&&(change.position-down.position).getDistance()>=viewConfiguration.touchSlop)started=beginOutlineDrag(down.position)
                                    change.consume()
                                    if(started)moveOutlineDrag(change.position)
                                }
                            }finally{if(started&&!ended)cancelOutlineGesture()}
                        }
                    }
                }.onPreviewKeyEvent{event->
                    val selected=nodeById[vm.selectedByMap[mapKey]]
                    if(tab!=1||!editable||hasDraft||selected==null||chosenCardId!=null||organizeNodeId!=null||knowledgeCardId!=null||reviewVisible||reparentId!=null){outlineHeldKey=null;false}
                    else if(event.key !in listOf(Key.Tab,Key.Enter,Key.NumPadEnter))false
                    else if(event.isCtrlPressed||event.isAltPressed||event.isMetaPressed){outlineHeldKey=null;false}
                    else{
                        if(event.type==KeyEventType.KeyDown&&event.nativeKeyEvent.repeatCount==0&&outlineHeldKey==null)outlineHeldKey=Triple(event.key,selected.id,event.isShiftPressed)
                        else if(event.type==KeyEventType.KeyUp){val held=outlineHeldKey;outlineHeldKey=null
                            if(held!=null&&held.first==event.key&&held.second==selected.id&&!event.nativeKeyEvent.isCanceled){if(event.key==Key.Tab)organize(selected,if(held.third)StudyOrganizationAction.OUTDENT else StudyOrganizationAction.INDENT)
                                else if(!held.third)editTitle(selected,true,true)}
                        };true
                    }
                }.testTag("study-list"),state=outlineListState,verticalArrangement=Arrangement.spacedBy(if(tab==1)4.dp else 10.dp),contentPadding=PaddingValues(vertical=8.dp)){
                if(tab==0){items(cards,key={it.id}){card->OutlinedCard(onClick={openCard(card)},enabled=browseReady&&titleDraft==null,colors=CardDefaults.outlinedCardColors(containerColor=(presentations[card.id]?.cardColor?:CardTint.DEFAULT).surface()),border=BorderStroke(1.dp,Line),modifier=Modifier.fillMaxWidth().testTag("study-card-${card.id}")){
                    Column(Modifier.padding(16.dp)){val excerpt=repeatsExcerptBody(card.title,card.body);Text(if(excerpt)card.body else card.title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=if(excerpt)FontWeight.Normal else FontWeight.SemiBold,modifier=Modifier.fillMaxWidth().background((presentations[card.id]?.titleBarColor?:CardTint.DEFAULT).argb?.let{Color(it)}?:Color.Transparent));if(card.body.isNotBlank()&&!excerpt)Text(card.body,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=14.sp,modifier=Modifier.padding(top=8.dp));Text("摘要卡 · ${occurrenceCount(card.id)} 个展示位置",fontSize=11.sp,color=Quiet)}}}
                    if(cards.isEmpty())item{Text(if(query.isNotBlank())"没有匹配的摘要卡"else if(showTrash)"卡片回收区为空"else"框选手写摘录，或新建摘要卡。摘要由你填写，不会自动发送到云端。",color=Quiet)}}
                else{items(projection.rows,key={it.node.id}){row->val node=nodeById.getValue(row.node.id);val depth=row.depth;val card=cardById[node.cardId]
                    DisposableEffect(node.id){onDispose{outlineHandles.remove(node.id)}}
                    // Capture in composition: a recycled row must not observe selection from semantics.
                    val isSelected=vm.selectedByMap[mapKey]==node.id
                    if(card!=null)OutlinedCard(onClick={if(vm.selectedByMap[mapKey]!=node.id)cancelReviewPreparation();vm.selectedByMap[mapKey]=node.id},enabled=browseReady&&titleDraft==null,colors=CardDefaults.outlinedCardColors(containerColor=if(isSelected)InkTheme.Selected else MaterialTheme.colorScheme.surface),border=BorderStroke(if(isSelected)2.dp else 1.dp,if(isSelected)InkTheme.Accent else Line),modifier=Modifier.fillMaxWidth().padding(start=minOf((depth.coerceAtMost(10)*20).dp,maxWidth/5)).testTag("outline-row-${node.id}").semantics{selected=isSelected;stateDescription="${depth+1}级主题"}.drawWithContent{
                        drawContent()
                        outlineDrag?.preview?.takeIf{it.targetId==node.id}?.let{drop->
                            val color=if(drop.plan==null)Color(0xffb3261e)else Forest
                            when(drop.position){
                                OutlineDropPosition.BEFORE->drawLine(color,Offset.Zero,Offset(size.width,0f),4.dp.toPx())
                                OutlineDropPosition.AFTER->drawLine(color,Offset(0f,size.height),Offset(size.width,size.height),4.dp.toPx())
                                OutlineDropPosition.CHILD->drawRect(color,style=Stroke(4.dp.toPx()))
                            }
                        }
                    }){
                        Column(Modifier.padding(horizontal=4.dp,vertical=2.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.size(48.dp).onGloballyPositioned{outlineHandles[node.id]=it.boundsInRoot()}
                                    .clickable(enabled=editable){vm.selectedByMap[mapKey]=node.id;organizeNodeId=node.id}
                                    .testTag("outline-drag-${node.id}").describedAs("拖动整支，${row.descendants+1}个主题；点按打开等价整理操作"),contentAlignment=Alignment.Center){Text("⠿",style=MaterialTheme.typography.titleLarge)}
                                if(selectionMode)Checkbox(node.id in selectedGroup,{checked->selectedGroup=if(checked)(selectedGroup+node.id).distinct()else selectedGroup-node.id},
                                    enabled=browseReady&&outlineDrag==null,modifier=Modifier.testTag("outline-select-${node.id}").describedAs("选择主题及其下级"))
                                if(row.descendants>0)IconButton(onClick={toggleBranch(node.id)},enabled=titleDraft==null,modifier=Modifier.size(48.dp).testTag("outline-fold-${node.id}").describedAs(if(node.id in collapsed)"展开下级 ${row.descendants} 个主题"else"收起下级主题")){Glyph(if(node.id in collapsed)"add"else"collapse")}
                                else Spacer(Modifier.width(8.dp))
                                Box(Modifier.width(3.dp).height(28.dp).background(presentations[card.id]?.cardColor?.argb?.let{Color(it)}?:Line))
                                Text(if(repeatsExcerptBody(card.title,card.body))card.body else card.title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=browseReady&&titleDraft==null){openCard(card,node)}.padding(horizontal=8.dp,vertical=10.dp).testTag("outline-node-${node.id}"))
                                IconButton(onClick={if(vm.selectedByMap[mapKey]!=node.id)cancelReviewPreparation();vm.selectedByMap[mapKey]=node.id},enabled=browseReady&&titleDraft==null,modifier=Modifier.size(48.dp).testTag("outline-actions-${node.id}").describedAs("选择此主题，显示整理操作")){Glyph("more")}
                            }
                            if(card.body.isNotBlank()&&!repeatsExcerptBody(card.title,card.body))Text(card.body,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Quiet,modifier=Modifier.padding(start=56.dp,end=8.dp,bottom=4.dp))
                            titleDraft?.takeIf{it.mapId==currentMap&&it.anchorId==node.id}?.let{inlineTitle(it)}
                            // Keep this group distinct from the clickable card in the merged accessibility tree.
                            if(isSelected)FlowRow(Modifier.fillMaxWidth().semantics(mergeDescendants=true){}.testTag("outline-context-actions")){
                                TextButton(onClick={editTitle(node)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("outline-rename-${node.id}")){Text("修改标题")}
                                TextButton(onClick={editTitle(node,true)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("outline-child-${node.id}")){Text("＋ 子主题")}
                                TextButton(onClick={editTitle(node,true,true)},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("outline-sibling-${node.id}")){Text("＋ 同级")}
                                TextButton(onClick={vm.selectedByMap[mapKey]=node.id;organizeNodeId=node.id},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("outline-organize-${node.id}")){Text("整理")}
                                TextButton(onClick={focusBranch(node.id)},enabled=titleDraft==null,modifier=Modifier.heightIn(min=48.dp).testTag("outline-focus-${node.id}")){Text("聚焦")}
                            }
                        }}
                };titleDraft?.takeIf{it.mapId==currentMap&&projection.rows.none{row->row.node.id==it.anchorId&&cardById[row.node.cardId]!=null}}?.let{draft->item(key="outline-title-fallback"){inlineTitle(draft)}};if(active.isEmpty())item{Text("大纲与脑图使用同一组节点和摘要卡，不另存一份正文。",color=Quiet)}}
            }
            }
        }
        val studyFooterContent:@Composable ColumnScope.()->Unit={
            if(tab!=2&&vm.captureUndo[mapKey]!=null)TextButton(vm::undoCapture,enabled=editable,modifier=Modifier.testTag("study-undo-capture")){Text("撤销此次摘录添加")}
            if(tab==2&&!compactWindow)Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(end=if(compactWindow)48.dp else 0.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton({map?.zoom(1/1.2f)},modifier=Modifier.describedAs("缩小思维导图")){Text("−")}
                TextButton({map?.fit()}){Text("适配")}
                IconButton({map?.zoom(1.2f)},modifier=Modifier.describedAs("放大思维导图")){Text("＋")}
                if(vm.captureUndo[mapKey]!=null)IconButton(vm::undoCapture,enabled=editable,modifier=Modifier.size(48.dp).testTag("study-undo-capture").describedAs("撤销此次摘录添加")){Glyph("undo")}
                else if(focusId==null)Text("${shown.size} / ${active.size} 个主题",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Quiet)
            }
        }
        Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(if(compactWindow)0.dp else 20.dp),color=Color.White,border=if(compactWindow)null else BorderStroke(1.dp,Line)){Column(Modifier.fillMaxSize().padding(if(compactWindow)4.dp else 16.dp)){
            studyHeaderContent()
            if(tab==2)studyMapContent()else studyListContent()
            studyFooterContent()
        }}
    }
    if(annotationLayers)PageLayersPanel(mapAuthoring,annotationUi,!readOnly&&!dragging,dismiss={annotationLayers=false})
    annotationNodeId?.let{targetId->
        val node=nodeById[targetId]
        val bound=node?.let{map?.annotationBounds(it.id)?:CanvasBounds(it.x,it.y,it.x+MapNodeMetrics.WIDTH,it.y+160.0)}
        BoundAnnotationPanel(mapAuthoring,annotationUi,org.inkweft.core.AnnotationTarget(if(node==null)AnnotationTargetKind.PAGE else AnnotationTargetKind.MAP_OCCURRENCE,targetId),node?.let{cardById[it.cardId]?.title}?:"游离",bound,!readOnly&&!ui.busy&&!dragging,dismiss={annotationNodeId=null})
    }
    reuseCard?.let{card->CardReuseDialog(card){reuseCardId=null}}
    if(reuseCardId!=null&&reuseCard==null&&!ui.loading)StudyDialog(compactWindow,onDismissRequest={reuseCardId=null},
        title={Text("原卡暂不可用")},text={Text("没有重新提交复用操作。请返回，重新读取原卡后再核对。")},
        confirmButton={TextButton({reuseCardId=null;vm.refresh()}){Text("返回并重新读取")}})
    transformKind?.let{kind->key(note.base.id,kind,transformCardIds){
        CardTransformDialog(transforms,note.base.id,transformCardIds,kind,
            onDismiss={if(!transformPending){transformKind=null;transformCardIds=emptyList()}},
            onCommitted={ids->transformedCardIds=ids;transformKind=null;transformCardIds=emptyList();query="";showTrash=false;vm.selectTab(0);vm.refresh()},
            embedded=compactWindow,authorAllowed={readLock.canWrite&&latestDocumentReady&&!vm.ui.value.busy&&!vm.ui.value.unknown&&!mapSaving&&!resolutionPending},
            onPendingChanged={transformPending=it},mapId=currentMap)
    }}
    groupTarget?.let{(frozen,selection)->
        val excluded=StudyOrganization.selectedBranchIds(frozen,selection)
        val current=graph?.graphFingerprint==StudyOrganization.fingerprint(frozen)
        fun group(parent:String?){
            if(!current||!readLock.canWrite)return
            val plan=runCatching{StudyOrganization.reparentSelection(frozen,selection,parent)}.getOrElse{localMessage="此分组会形成循环，未提交";return}
            groupTarget=null
            if(plan.before!=plan.after){vm.organize(frozen,plan);parent?.let{collapsed=collapsed-it};localMessage="已提交 ${StudyOrganization.selectionRoots(frozen,selection).size} 条分支分组，可撤销或重做"}
            else localMessage="分组未改变，没有写入"
        }
        StudyDialog(compactWindow,onDismissRequest={groupTarget=null},modifier=Modifier.testTag("study-group-dialog"),title={Text("将所选整支移入")},text={
            Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){
                Text("${selection.size} 个选中项去重为 ${StudyOrganization.selectionRoots(frozen,selection).size} 条分支，共 ${excluded.size} 个主题。仅修改层级与大纲顺序，保留卡片、来源和手动位置。")
                if(!current)Text("图已变化，请取消后重新选择",color=MaterialTheme.colorScheme.error)
                TextButton({group(null)},enabled=current,modifier=Modifier.fillMaxWidth().testTag("study-group-root")){Text("根层")}
                frozen.orderedNodeIds.filter{it !in excluded}.forEach{id->
                    val n=frozen.nodes.first{it.id==id}
                    TextButton({group(id)},enabled=current,modifier=Modifier.fillMaxWidth().testTag("study-group-target-$id")){Text(cardById[n.cardId]?.title.orEmpty())}
                }
            }
        },confirmButton={},dismissButton={TextButton({groupTarget=null}){Text("取消")}})
    }
    if(saveTemplate)StudyDialog(compactWindow,onDismissRequest={if(!mapSaving)saveTemplate=false},title={Text("另存为结构模板")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(templateTitle,{if(it.length<=120)templateTitle=it},enabled=editable,label={Text("模板名称")},modifier=Modifier.testTag("map-template-title"))
        Text("仅保留结构和位置，主题默认换成占位名称。",style=MaterialTheme.typography.bodySmall)
        Row(verticalAlignment=Alignment.CenterVertically){Text("保留普通主题标题",Modifier.weight(1f));Switch(keepTemplateTitles,{keepTemplateTitles=it},enabled=editable)}
        Text(if(keepTemplateTitles)"${active.size} 个主题，保留当前标题"else"${active.size} 个占位主题，不含来源和正文",style=MaterialTheme.typography.bodySmall)
        active.take(6).forEachIndexed{i,n->Text(if(keepTemplateTitles)cardById[n.cardId]?.title.orEmpty()else"主题 ${i+1}",style=MaterialTheme.typography.bodySmall)}
        localMessage?.let{Text(it)}
        mapWrite.message?.let{Text(it)}
    }},confirmButton={TextButton({if(mapWrite.unknown)mapWriter.retry()else {val draft=runCatching{MapTemplates.anonymize(templateTitle.trim(),definition?.layout?:"right",ui.nodes.map{it.model()},displayCards.associate{it.id to it.title},keepTemplateTitles)}.getOrElse{localMessage="模板最多支持 128 个主题、32 层；原图保留。";return@TextButton};mapWriter.submit(note.base.id,draft)}},enabled=!mapWrite.busy&&(mapWrite.unknown||(editable&&templateTitle.isNotBlank())),modifier=Modifier.testTag("save-map-template-confirm")){Text(if(mapWrite.unknown)"核对原操作"else"保存模板")}},dismissButton={TextButton({saveTemplate=false},enabled=!mapSaving){Text("取消")}})
    if(mapWrite.unknown&&newMapTitle==null&&!saveTemplate&&titleDraft?.structural!=true&&!structuralEditorSubmitted)StudyDialog(compactWindow,onDismissRequest={},title={Text("核对新建图")},text={Text("上次操作结果尚未确认，继续核对不会重复创建。")},confirmButton={TextButton(mapWriter::retry,enabled=!mapWrite.busy){Text("核对原操作")}})
    newMapTitle?.let{title->StudyDialog(compactWindow,onDismissRequest={if(!mapSaving)newMapTitle=null},title={Text("新建独立图")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(title,{if(it.length<=120)newMapTitle=it},enabled=editable,modifier=Modifier.testTag("study-new-map-title"))
        Text("${template.nodes.size} 个结构主题",style=MaterialTheme.typography.labelMedium)
        template.nodes.take(6).forEach{Text(it.title,style=MaterialTheme.typography.bodySmall)}
        mapWrite.message?.let{Text(it)}
    }},confirmButton={TextButton({if(mapWrite.unknown)mapWriter.retry()else mapWriter.submit(note.base.id,MapTemplates.instantiate(template,title.trim()),template=installedMapHash?.let{h->installedMapId?.let{TemplateRef(h,it)}})},enabled=!mapWrite.busy&&(mapWrite.unknown||(editable&&title.isNotBlank())),modifier=Modifier.testTag("study-new-map-save")){Text(if(mapWrite.unknown)"核对原操作"else"创建")}},dismissButton={TextButton({newMapTitle=null},enabled=!mapSaving){Text("取消")}})}
    editor?.let{e->key(e.card?.id,e.parent?.id,e.source?.pageId){
        var title by rememberSaveable{mutableStateOf(e.card?.title.orEmpty())};var text by rememberSaveable{mutableStateOf(e.card?.body.orEmpty())}
        StudyDialog(compactWindow,onDismissRequest={if(!ui.busy&&!ui.unknown&&!mapSaving){editor=null;structuralEditorSubmitted=false;returnTab?.let(vm::selectTab);returnTab=null}},modifier=Modifier.testTag("study-card-editor"),title={Text(if(e.card!=null)"编辑共享摘要卡"else"新建摘要卡")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(e.source!=null)Text("保留框选原迹快照及回源位置。下方是你的摘要，不是自动识别或 AI 生成。",fontSize=12.sp,color=Quiet)
            OutlinedTextField(title,{if(it.length<=120)title=it},enabled=editable,label={Text("标题")},modifier=Modifier.fillMaxWidth().testTag("study-card-title"))
            if(e.card?.id !in structureCards.map{it.id})OutlinedTextField(text,{if(it.length<=20000)text=it},enabled=editable,label={Text("正文 / 既有内容")},minLines=2,maxLines=if(compactWindow)4 else Int.MAX_VALUE,modifier=Modifier.fillMaxWidth().heightIn(min=if(compactWindow)96.dp else 160.dp).testTag("study-card-body"))
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
            localMessage?.let{Text(it,fontSize=12.sp)}
        }},confirmButton={TextButton(onClick={
            if(structuralEditorSubmitted&&mapWrite.unknown){mapWriter.retry();return@TextButton}
            if(ui.unknown){vm.retry();return@TextButton}
            if(!readLock.canWrite)return@TextButton
            val old=e.card;val parent=e.parent
            if(old!=null&&definition?.structures?.any{it.id==old.id}==true){val mapRow=maps.first{it.id==currentMap};if(mapRow.revision!=old.revision){localMessage="结构主题已经变化，草稿已保留，请核对后再编辑。";return@TextButton};structuralEditorSubmitted=true;mapWriter.submit(note.base.id,definition.copy(structures=definition.structures.map{if(it.id==old.id)it.copy(title=title.trim())else it}),mapRow)}
            else vm.submit(StudyCommand(id(),note.base.id,if(old==null)StudyAction.CREATE else StudyAction.EDIT,mapId=currentMap,cardId=old?.id?:id(),nodeId=if(old==null)id()else null,
                expectedRevision=old?.revision?:0L,parentId=parent?.id,title=title.trim(),body=text,x=nextNodeX(parent),y=nextNodeY(parent),source=e.source))
        },enabled=!ui.busy&&!mapWrite.busy&&!portalWrite.busy&&(ui.unknown||(structuralEditorSubmitted&&mapWrite.unknown)||(editable&&title.isNotBlank())),modifier=Modifier.testTag("study-save-card")){Text(if(ui.unknown||(structuralEditorSubmitted&&mapWrite.unknown))"核对原操作"else"保存")}},dismissButton={TextButton(onClick={editor=null;structuralEditorSubmitted=false;returnTab?.let(vm::selectTab);returnTab=null},enabled=!ui.busy&&!ui.unknown&&!mapSaving,modifier=Modifier.testTag("study-cancel-card")){Text("取消")}})
    }}
    presentationCardId?.let{cardId->CardPresentationEditor(note.base.id,cardId,embedded=compactWindow){presentationCardId=null}}
    chosenCard?.takeIf{presentationCardId==null&&reuseCardId==null}?.let{card->inspectorState.SaveableStateProvider(card.id){val node=chosenNode
        key(note.base.id,card.id,node?.id,mapKey){
        val sourceOwner=StudyNavigationState.CardOwner(currentMap,card.id,node?.id)
        val sourceOpening=navigation.cardSourceOpening
        DisposableEffect(navigation,sourceOwner){onDispose{navigation.cancelCardSource(sourceOwner)}}
        val frozenSources=sourceVersions?.takeIf{it.first.id==card.id&&it.first.revision==card.revision}?.second
        val cardSource=source?.takeIf{it.cardId==card.id&&frozenSources!=null}
        var showPositions by rememberSaveable(card.id){mutableStateOf(false)}
        var showSource by rememberSaveable(card.id){mutableStateOf(inspectSource)}
        var showSnapshot by rememberSaveable(card.id){mutableStateOf(false)}
        var positionsEnterEvent by remember(card.id){mutableIntStateOf(0)}
        var positionsEnterPending by remember(card.id){mutableStateOf(false)}
        var sourceEnterEvent by remember(card.id){mutableIntStateOf(0)}
        var sourceEnterPending by remember(card.id){mutableStateOf(false)}
        var sourceMessage by rememberSaveable(card.id){mutableStateOf<String?>(null)}
        LaunchedEffect(card.id,inspectSource){if(inspectSource)showSource=true}
        var moreActions by rememberSaveable(card.id){mutableStateOf(false)}
        val detailScroll=rememberScrollState()
        val sectionScope=rememberCoroutineScope()
        val annotationAnchor=remember{BringIntoViewRequester()}
        val sourceAnchor=remember{BringIntoViewRequester()}
        var sectionJump by remember{mutableStateOf<Job?>(null)}
        fun showSection(anchor:BringIntoViewRequester?=null){
            sectionJump?.cancel()
            sectionJump=sectionScope.launch{
                if(anchor==null)detailScroll.animateScrollTo(0)
                // Include the viewport so a section heading lands above its content, not at the bottom edge.
                else anchor.bringIntoView(Rect(0f,0f,1f,detailScroll.viewportSize.toFloat()))
            }
        }
        CardInspector(compactWindow,onDismissRequest={if(!resolutionPending){navigation.cancelCardSource();chosenCardId=null;chosenNodeId=null;inspectSource=false}else localMessage="请先核对这次撤销，再离开当前卡片"},modifier=Modifier.testTag("study-card-details"),containerColor=MaterialTheme.colorScheme.surface,title={Column{
            Text(if(inspectSource)"摘录来源"else"摘要卡")
            if(!inspectSource)FlowRow(Modifier.fillMaxWidth().testTag("card-section-navigation"),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                TextButton({showSection()},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("card-jump-body").describedAs("定位到正文")){Text("正文")}
                if(card.id !in structureCards.map{it.id})TextButton({showSection(annotationAnchor)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("card-jump-annotation").describedAs("定位到个人注释")){Text("注释")}
                TextButton({showSection(sourceAnchor)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("card-jump-source").describedAs("定位到来源")){Text("来源")}
            }
        }},text={Column(Modifier.testTag("card-reading-content").verticalScroll(detailScroll),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),verticalAlignment=Alignment.Top){
                Box(Modifier.width(4.dp).fillMaxHeight().background((presentations[card.id]?.cardColor?:CardTint.DEFAULT).argb?.let{Color(it)}?:InkTheme.Divider))
                Column(Modifier.weight(1f).padding(start=12.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(card.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth().testTag("card-full-title"))
                    presentations[card.id]?.titleBarColor?.argb?.let{tint->Box(Modifier.width(48.dp).height(3.dp).background(Color(tint)))}
                }
            }
            if(!inspectSource){
                Text("正文 / 既有内容",style=MaterialTheme.typography.labelMedium,color=Quiet,modifier=Modifier.testTag("card-body-heading").semantics{heading()})
                if(card.id in structureCards.map{it.id})androidx.compose.foundation.text.selection.SelectionContainer{Text(card.body.ifBlank{"尚未填写摘要"},modifier=Modifier.testTag("card-full-body"))}
                else KnowledgeLinkedCardBody(TargetRef(TargetKind.CARD,card.id),card.body,browseReady){target->
                    openRelatedTarget(target)
                }
            }
            if(!inspectSource&&card.id !in structureCards.map{it.id}){
                Text("个人注释",style=MaterialTheme.typography.labelMedium,color=Quiet,modifier=Modifier.bringIntoViewRequester(annotationAnchor).testTag("card-annotation-heading").semantics{heading()})
                androidx.compose.foundation.text.selection.SelectionContainer{Text(annotations[card.id].orEmpty().ifBlank{"尚未添加个人注释"},modifier=Modifier.testTag("card-full-annotation"))}
            }
            Text("来源",style=MaterialTheme.typography.labelMedium,color=Quiet,modifier=Modifier.bringIntoViewRequester(sourceAnchor).testTag("card-source-heading").semantics{heading()})
            frozenSources?.takeIf{it.complete}?.sources?.forEachIndexed{index,fixed->
                val page=(sourcePages+mapWrite.pages).firstOrNull{it.id==fixed.pageId&&it.notebookId==fixed.notebookId}
                val sourceTitle=if(fixed.notebookId==note.base.id)note.title else mapWrite.notes.firstOrNull{it.id==fixed.notebookId}?.title
                val label=when{
                    mapWrite.readFailed->"来源信息读取失败，固定摘录保留"
                    sourceTitle!=null&&page!=null->"$sourceTitle · 第${page.position+1}页"+if(page.trashedAt!=null)"（已回收）"else""
                    mapWrite.loading->"正在读取来源位置…"
                    sourceTitle==null->"来源笔记不可用，固定摘录保留"
                    else->"$sourceTitle · 来源页面不可用"
                }
                Text("${if(frozenSources.sources.size>1)"来源 ${index+1} · "else""}$label · 摘录时快照",
                    style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.testTag("card-source-summary-$index"))
            }
            if(frozenSources!=null)FrozenCardSources(frozenSources,card.id,{selected->if(source!==selected)navigation.selectSource(selected)},enabled=browseReady&&!sourceOpening)
            else if(sourceLoadFailed){Text("来源读取失败，原卡仍保留");TextButton({sourceReload++}){Text("重试来源")}}
            else Text("正在读取固定来源…",style=MaterialTheme.typography.bodySmall,color=Quiet)
            cardSource?.let{s->
                FlowRow(Modifier.fillMaxWidth().testTag("card-source-actions"),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    TextButton({
                        if(showSource){sourceEnterPending=false;showSource=false}
                        else{sourceEnterEvent++;sourceEnterPending=true;showSource=true}
                    },modifier=Modifier.heightIn(min=48.dp).testTag("card-source-section")){Text(if(showSource)"收起来源"else"查看来源")}
                    TextButton({showSnapshot=true},enabled=browseReady&&!sourceOpening,modifier=Modifier.heightIn(min=48.dp).testTag("study-view-snapshot")){Text("查看完整摘录 · 摘录时快照")}
                }
                if(showSource){
                    val enterEvent=sourceEnterEvent
                    ClickEnterContent(enterEvent,sourceEnterPending,{if(sourceEnterEvent==enterEvent)sourceEnterPending=false},
                        Modifier.testTag("card-source-content")){
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text(if(stale)"来源页面已变化，下方保留摘录时快照。"else"摘录时的原迹快照",fontSize=12.sp,color=Quiet)
                            SourceThumbnail(s,Modifier.fillMaxWidth().height(150.dp))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical=8.dp),color=Line)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                TextButton(onClick={editor=CardEditor(card);navigation.cancelCardSource();chosenCardId=null},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("study-edit-card")){Text(if(card.id in structureCards.map{it.id})"修改标题"else"编辑笔记")}
                if(card.id !in structureCards.map{it.id})TextButton({presentationCardId=card.id},enabled=editable&&card.trashedAt==null,modifier=Modifier.heightIn(min=48.dp).testTag("card-edit-presentation")){Text("编辑注释与配色")}
                if(node==null)TextButton(onClick={moreActions=!moreActions},modifier=Modifier.heightIn(min=48.dp).testTag("card-management-actions")){Text("卡片管理")}
            }
            if(card.trashedAt==null&&card.id !in structureCards.map{it.id})ReviewScopeSelector(reviewQuestionScope,reviewReady(ui)&&!sourceOpening,"card-review",{chooseReviewScope(it)})
            if(card.id !in structureCards.map{it.id}){
                CardTransformResolutionPanel(transforms,card.id,onOpenCard={id->if(!resolutionPending){navigation.cancelCardSource();chosenCardId=id;chosenNodeId=null;inspectSource=false}},
                    authorAllowed={readLock.canWrite&&latestDocumentReady&&!vm.ui.value.busy&&!vm.ui.value.unknown&&!mapSaving&&!transformPending},
                    onPendingChanged={resolutionPending=it})
                TextButton({openTransform(CardTransformKind.SPLIT,listOf(card.id))},enabled=editable&&card.trashedAt==null,modifier=Modifier.testTag("study-split-card")){Text("拆分此内容卡")}
            }
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
            if(localMessage!=null)Text(localMessage!!,fontSize=12.sp)
            sourceMessage?.let{Text(it,fontSize=12.sp)}
            if(card.trashedAt==null){
                val positions=mainNodes.filter{!it.removed&&it.cardId==card.id}.map{Triple<String?,String,String>(null,it.id,"主图")}+
                    extraRows.mapNotNull{r->(r.data() as? KnowledgeData.MapOccurrence)?.takeIf{!r.removed&&r.notebookId==note.base.id&&it.cardId==card.id}?.let{d->
                        Triple(d.mapId,r.id,(maps.find{it.id==d.mapId}?.data() as? KnowledgeData.MapDefinition)?.title?:"图不可用")}}
                if(positions.isNotEmpty()){
                    TextButton({
                        if(showPositions){positionsEnterPending=false;showPositions=false}
                        else{positionsEnterEvent++;positionsEnterPending=true;showPositions=true}
                    },modifier=Modifier.testTag("card-positions")){Text("引用位置 · ${positions.size} 处") }
                    if(showPositions){
                        val enterEvent=positionsEnterEvent
                        ClickEnterContent(enterEvent,positionsEnterPending,{if(positionsEnterEvent==enterEvent)positionsEnterPending=false},
                            Modifier.testTag("card-reference-content")){
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                                positions.forEachIndexed{i,(targetMap,targetNode,title)->TextButton(onClick={
                                    cancelReviewPreparation();vm.selectMap(targetMap);vm.selectedByMap[targetMap?:"main"]=targetNode
                                    vm.selectTab(2);navigation.cancelCardSource();chosenCardId=null;chosenNodeId=null
                                },enabled=browseReady,modifier=Modifier.testTag("study-position-$targetNode")){Text("$title · 位置 ${i+1}")}}
                            }
                        }
                    }
                }
            }
            TextButton(onClick={openCardKnowledge(card.id,false);navigation.cancelCardSource();chosenCardId=null},enabled=browseReady&&card.id !in structureCards.map{it.id},modifier=Modifier.testTag("card-properties")){Text("属性与回忆")}
            if(node!=null)TextButton({annotationNodeId=node.id},enabled=browseReady&&!sourceOpening,modifier=Modifier.testTag("card-local-annotation")){Text("此出现位置的手写批注")}
            if(node!=null)TextButton({moreActions=!moreActions},modifier=Modifier.testTag("card-node-actions")){Text("组织此主题")}
            if(card.trashedAt==null&&moreActions){
                if(card.id !in structureCards.map{it.id})TextButton({navigation.cancelCardSource();reuseCardId=card.id},enabled=editable,
                    modifier=Modifier.testTag("card-reuse-open")){Text("跨笔记复用 · 引用或独立副本")}
                if(card.id !in structureCards.map{it.id})TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REUSE,mapId=currentMap,cardId=card.id,nodeId=id(),y=nextNodeY(null)))},enabled=editable,modifier=Modifier.testTag("study-reuse-card")){Text("复用到脑图新位置")}
                if(node!=null){
                    TextButton(onClick={focusBranch(node.id);navigation.cancelCardSource();chosenCardId=null;chosenNodeId=null},enabled=browseReady,modifier=Modifier.testTag("study-focus-branch")){Text("聚焦此分支")}
                    if(active.any{it.parentId==node.id})TextButton(onClick={toggleBranch(node.id);navigation.cancelCardSource();chosenCardId=null;chosenNodeId=null},enabled=browseReady,modifier=Modifier.testTag("study-toggle-branch")){Text(if(node.id in collapsed)"展开下级主题"else"收起下级主题")}
                    TextButton(onClick={editor=CardEditor(parent=node);navigation.cancelCardSource();chosenCardId=null},enabled=editable,modifier=Modifier.testTag("study-add-child")){Text("添加子主题")}
                    TextButton(onClick={editor=CardEditor(parent=nodeById[node.parentId]);navigation.cancelCardSource();chosenCardId=null},enabled=editable,modifier=Modifier.testTag("study-add-sibling")){Text("添加同级主题")}
                    TextButton(onClick={chooseParent(node);navigation.cancelCardSource();chosenCardId=null},enabled=editable){Text("修改上级主题")}
                    TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REMOVE_NODE,mapId=currentMap,nodeId=node.id,expectedRevision=node.revision))},enabled=editable,modifier=Modifier.testTag("study-remove-node")){Text("移除此节点（保留摘要卡）")}
                }
                if(node==null&&card.id !in structureCards.map{it.id})TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.TRASH_CARD,mapId=currentMap,cardId=card.id,expectedRevision=card.revision))},enabled=editable&&occurrenceCount(card.id)==0){Text("移入卡片回收区")}
            }else if(card.trashedAt!=null)TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.RESTORE_CARD,mapId=currentMap,cardId=card.id,expectedRevision=card.revision))},enabled=editable){Text("恢复卡片")}
        }},confirmButton={FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            if(card.trashedAt==null&&card.id !in structureCards.map{it.id})TextButton(onClick={navigation.cancelCardSource();prepareReview(MapRef(note.base.id,currentMap),node,card)},enabled=reviewReady(ui)&&reviewPreparation==null&&!sourceOpening,
                modifier=Modifier.heightIn(min=48.dp).testTag("card-review")){Text("复习此卡")}
            if(card.id !in structureCards.map{it.id})TextButton(onClick={openCardKnowledge(card.id,true);navigation.cancelCardSource()},enabled=browseReady,
                modifier=Modifier.heightIn(min=48.dp).testTag("card-backlinks")){Glyph("link");Spacer(Modifier.width(6.dp));Text("关联")}
            cardSource?.let{s->TextButton(onClick={
                navigation.openCardSource(sourceOwner,s,
                    currentOwner={chosenCardId?.let{StudyNavigationState.CardOwner(vm.mapId.value,it,chosenNodeId)}},openSource=openSource,
                    opened={sourceMessage=null;chosenCardId=null;chosenNodeId=null;inspectSource=false;vm.selectedByMap[mapKey]=node?.id},
                    message={sourceMessage=it})
            },enabled=browseReady&&!sourceOpening,modifier=Modifier.heightIn(min=48.dp).testTag("study-open-source")){Text(if(sourceOpening)"正在定位…"else"回原文")}}
            KnowledgeReturnAction(enabled=browseReady&&!hasDraft&&!sourceOpening){
                leaveReviewContext(preserve=true).also{if(it)navigation.cancelSourceNavigation()}
            }
            TextButton(onClick={navigation.cancelCardSource();chosenCardId=null;chosenNodeId=null;inspectSource=false},enabled=!resolutionPending,modifier=Modifier.heightIn(min=48.dp).testTag("card-back")){Text(if(node!=null)"返回原节点"else"关闭")}
        }})
        if(showSnapshot)cardSource?.let{StudySnapshotViewer(it){showSnapshot=false}}
        }
    }}
    if(vm.capacityOpen)StudyCapacityPanel(compactWindow,
        if(currentMap==null)"主图"else (maps.find{it.id==currentMap}?.data() as? KnowledgeData.MapDefinition)?.title?:"当前导图",
        capacityUsage,capacitySnapshot,ui.readFailed||(!ui.loading&&capacityUsage==null),browseReady&&!hasDraft,editable&&!hasDraft,
        vm::closeCapacity,vm::refreshCapacity,capacityAction@{action->
            if(!browseReady||hasDraft||!leaveReviewContext())return@capacityAction
            if(action==StudyCapacityAction.NEW_MAP&&(!editable||!readLock.canWrite))return@capacityAction
            vm.closeCapacity();chosenCardId=null;chosenNodeId=null;inspectSource=false
            when(action){
                StudyCapacityAction.MAP->chooseTab(2)
                StudyCapacityAction.CARDS->{query="";showTrash=false;chooseTab(0)}
                StudyCapacityAction.TRASH->{query="";showTrash=true;chooseTab(0)}
                StudyCapacityAction.NEW_MAP->templatePicker=true
            }
        })
    if(insertMap&&onInsertEmbed!=null)scenes.find{it.ref==MapRef(note.base.id,currentMap)}?.let{scene->MapInsertPanel(scene,vm.selectedByMap[mapKey],compactWindow,{insertMap=false},onInsertEmbed)}
    if(contentSearch)MapSearchPanel(MapRef(note.base.id,currentMap),{contentSearch=false}){query,all,hit->cancelReviewPreparation();contentSearch=false;vm.selectTab(2);vm.beginSearch(query,all,hit)}
    portalNodeId?.let{node->key(portalMapKey,node){MapPortalPanel(MapRef(note.base.id,portalMapKey.takeUnless{it=="main"}),node,note.title,scenes,extraRows,compactWindow,browseReady,portalWriter,portalWrite,{portalNodeId=null}){preview->
        if(!browseReady||titleDraft!=null)false else {cancelReviewPreparation();vm.openPortal(preview,if(chrome?.sourceReading==true)vm.viewports[mapKey]else map?.snapshotViewport(),collapsed,focusId)}
    }}}
    if(reviewVisible)reviewPlan?.let{plan->reviewStateHolder.SaveableStateProvider(reviewSessionKey){
        BranchReviewDialog(plan,{leaveReviewContext()},showCardScope=reviewCardOnly,consultedOriginal=reviewConsultedOriginal,onOriginalGateChanged={originalGate=it},workModes={ready->
            StudyWorkModes(StudyWorkMode.RECALL,tagPrefix="review"){mode->
                if(!ready||!chooseWorkMode(mode))android.widget.Toast.makeText(context,
                    readLock.reason.ifBlank{"请先核对本题标记，再切换工作状态"},android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }}
    knowledgeCard?.let{card->
        val openKnowledgeTarget:(TargetRef)->Unit=::openRelatedTarget
        if(knowledgeBacklinks)KnowledgeWorkspace(note.base.id,TargetRef(TargetKind.CARD,card.id),initialBacklinks=true,includeAllRelationKinds=knowledgeAllRelationKinds,dismiss=::closeCardKnowledge,openTarget=openKnowledgeTarget)
        else knowledgeState.SaveableStateProvider(card.id){KnowledgeWorkspace(note.base.id,TargetRef(TargetKind.CARD,card.id),dismiss=::closeCardKnowledge,openTarget=openKnowledgeTarget)}
    }
    nodeById[organizeNodeId]?.let{node->StudyDialog(compactWindow,onDismissRequest={organizeNodeId=null},title={Text("顺序与层级")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text(cardById[node.cardId]?.title.orEmpty(),style=MaterialTheme.typography.titleMedium)
        Text("移动整个分支，保留卡片、出处与引用。调整顺序后可预览自动布局。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        listOf(Triple(StudyOrganizationAction.UP,"上移","node-order-up"),Triple(StudyOrganizationAction.DOWN,"下移","node-order-down"),
            Triple(StudyOrganizationAction.INDENT,"缩进为上一主题的子级 · Tab","node-indent"),Triple(StudyOrganizationAction.OUTDENT,"提升一级 · Shift+Tab","node-outdent")).forEach{(action,label,tag)->
            TextButton({organize(node,action)},enabled=editable&&organizationPlan(node,action)!=null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)){Text(label)}
        }
        TextButton({organizeNodeId=null;chooseParent(node)},enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("node-reparent")){Text("移入主题（分组）")}
        Text("画布或大纲选中主题后可使用 Tab / Shift+Tab；Enter 新增同级。编辑标题时 Enter 保存并继续同级。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    }},confirmButton={TextButton({organizeNodeId=null},modifier=Modifier.heightIn(min=48.dp)){Text("完成")}})}
    layoutPreview?.let{preview->
        val current=browseReady&&!readOnly&&graph?.graphFingerprint==preview.plan.expectedGraph&&
            mapFontScale==preview.fontScale&&expandedNodeId==preview.expandedNodeId&&measuredNodeSizes()==preview.sizes
        StudyLayoutPreviewDialog(preview,compactWindow,current,{layoutPreview=null}){
            if(current&&readLock.canWrite){
                layoutCamera=PortalReturn(currentMap,"",vm.selectedByMap[mapKey],map?.snapshotViewport()?:vm.viewports[mapKey],collapsed,focusId).saved()
                layoutUndoGraph=preview.plan.expectedAfterGraph;layoutFitGraph=preview.plan.expectedAfterGraph;layoutRestoreGraph=null
                mapChangeFeedback=MapChangeFeedback(preview.plan.expectedAfterGraph,null,active.map{it.id}.toSet(),vm.selectedByMap[mapKey])
                vm.organize(preview.state,preview.plan);layoutPreview=null;localMessage="布局已提交，撤销可恢复原位置和视野。"
            }
        }
    }
    reparent?.let{node->StudyDialog(compactWindow,onDismissRequest={if(!ui.busy&&!ui.unknown)reparentId=null},title={Text("移入主题（分组）")},text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())){
        Text("将整个分支放到所选主题末尾。卡片内容、出处和引用保留。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        val snapshot=graph
        val options=listOf<StudyNodeRow?>(null)+active.filter{it.id!=node.id}
        options.forEach{p->
            val plan=snapshot?.takeIf{it.graphFingerprint==reparentFingerprint&&it.ref.mapId==reparentMap}?.let{runCatching{StudyOrganization.reparent(it.state,node.id,p?.id)}.getOrNull()}
            if(plan!=null)TextButton(onClick={vm.organize(checkNotNull(snapshot).state,plan);organizationReveal=plan.expectedAfterGraph to node.id},enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("node-parent-${p?.id?:"root"}")){Text(p?.let{n->cardById[n.cardId]?.title}?:"根主题")}
        }
        if(snapshot?.graphFingerprint!=reparentFingerprint)Text("导图已变化，请取消后重新选择。")
    }},confirmButton={TextButton(onClick={reparentId=null},enabled=!ui.busy&&!ui.unknown){Text("取消")}})}
    if(reparentId!=null&&reparent==null&&!ui.loading)StudyDialog(compactWindow,onDismissRequest={if(!ui.busy&&!ui.unknown)reparentId=null},title={Text("主题已变化")},text={Text("原主题已移除或不可用，未保存上级变更；请取消后重新核对。")},confirmButton={TextButton({reparentId=null},enabled=!ui.busy&&!ui.unknown){Text("取消")}})
    }
    }
}
