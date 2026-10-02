// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*

private data class CardTextProjection(
    val text:String, val targets:List<KnowledgeTextTarget>, val spans:List<KnowledgeTextSpan>
)

private fun previewTrace(id:Long,event:String){
    if(BuildConfig.DEBUG)android.util.Log.d("InkWeftLinks","$id $event")
}

/** A read-only view of confirmed links. The author's plain text is never rewritten. */
@Composable
internal fun KnowledgeLinkedCardBody(source:TargetRef,body:String,enabled:Boolean,onOpenTarget:(TargetRef)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var retry by remember{mutableIntStateOf(0)}
    var readError by remember{mutableStateOf(false)}
    val links=remember(source,retry){app.knowledgeText.observe(source)
        .onEach{readError=false}
        .catch{if(it is CancellationException)throw it;readError=true;emit(emptyList())}}
    val targets by links.collectAsStateWithLifecycle(initialValue=emptyList())
    var projection by remember{mutableStateOf<CardTextProjection?>(null)}
    LaunchedEffect(body,targets){
        projection=withContext(Dispatchers.Default){CardTextProjection(body,targets,KnowledgeTextLinks.spans(body,targets))}
    }
    // A new body or link snapshot must never render offsets from the previous one.
    val spans=projection?.takeIf{it.text==body&&it.targets==targets}?.spans.orEmpty()
    val currentTargets by rememberUpdatedState(targets)
    var showAll by rememberSaveable(source.id){mutableStateOf(false)}
    var candidates by rememberSaveable(source.id){mutableStateOf<List<String>>(emptyList())}
    var selected by rememberSaveable(source.id){mutableStateOf<String?>(null)}
    var selectedRevision by rememberSaveable(source.id){mutableStateOf<Long?>(null)}
    fun choose(ids:List<String>){
        if(!enabled)return
        val available=currentTargets.filter{it.linkId in ids}
        if(available.size==1){
            candidates=emptyList();showAll=false
            selectedRevision=available.single().linkRevision;selected=available.single().linkId
        }else if(available.isNotEmpty()){
            showAll=false;candidates=available.map{it.linkId}
        }
    }
    val annotated=remember(body,spans,enabled){buildAnnotatedString{
        append(body.ifBlank{"尚未填写摘要"})
        if(enabled)spans.forEach{span->
            addLink(LinkAnnotation.Clickable(
                tag="knowledge-${span.start}-${span.end}",
                styles=TextLinkStyles(SpanStyle(color=Forest,textDecoration=TextDecoration.Underline)),
                linkInteractionListener={choose(span.linkIds)}
            ),span.start,span.end)
        }
    }}
    val viewConfiguration=LocalViewConfiguration.current
    val textViewConfiguration=remember(viewConfiguration){object:ViewConfiguration by viewConfiguration{
        override val minimumTouchTargetSize:DpSize get()=DpSize.Zero
    }}
    SelectionContainer{
        // Expanded inline link targets must not capture plain text on adjacent lines.
        CompositionLocalProvider(LocalViewConfiguration provides textViewConfiguration){
            Text(annotated,modifier=Modifier.testTag("card-full-body"))
        }
    }
    if(targets.isNotEmpty())TextButton(
        onClick={showAll=true;candidates=emptyList()},enabled=enabled,
        modifier=Modifier.testTag("card-knowledge-links")
    ){Text("知识关联 · ${targets.size}")}
    if(readError)Column{
        Text("知识关联读取失败，摘要原文仍可查看。",fontSize=12.sp,color=Quiet)
        TextButton(onClick={retry++},modifier=Modifier.testTag("card-links-retry")){Text("重试读取")}
    }
    val windowSize=LocalWindowInfo.current.containerSize
    val contentHeight=with(LocalDensity.current){(windowSize.height.toDp()*.5f).coerceAtLeast(100.dp)}
    if(selected==null&&(showAll||candidates.isNotEmpty())){
        val choices=if(showAll)targets else targets.filter{it.linkId in candidates}
        AlertDialog(
            onDismissRequest={showAll=false;candidates=emptyList()},
            modifier=Modifier.fillMaxWidth(.96f).widthIn(max=640.dp).testTag("card-link-picker"),
            properties=DialogProperties(usePlatformDefaultWidth=false),
            title={Text(if(showAll)"已确认的知识关联"else"选择知识目标")},
            text={LazyColumn(Modifier.heightIn(max=contentHeight)){
                if(choices.isEmpty())item{Text("关联已变化，请返回摘要重新选择。")}
                items(choices,key={it.linkId}){target->
                    TextButton(onClick={selectedRevision=target.linkRevision;selected=target.linkId},
                        enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("card-link-choice-${target.linkId}")){
                        Column(Modifier.fillMaxWidth()){
                            Text(target.label)
                            Text("${target.relation.label} · "+(target.pinnedRevision?.let{"固定版本 $it"}?:"当前内容")+
                                if(!target.available)" · 目标不可打开"else"",fontSize=12.sp,color=Quiet)
                        }
                    }
                }
            }},
            confirmButton={TextButton(onClick={showAll=false;candidates=emptyList()},
                modifier=Modifier.testTag("card-link-close-picker")){Text("返回摘要")}}
        )
    }
    selected?.let{linkId->selectedRevision?.let{revision->
        key(source,linkId,revision){
            val readId=remember{android.os.SystemClock.elapsedRealtimeNanos()}
            DisposableEffect(Unit){previewTrace(readId,"mounted");onDispose{previewTrace(readId,"disposed")}}
            var preview by remember{mutableStateOf<KnowledgeTextPreview?>(null)}
            var failure by remember{mutableStateOf<String?>(null)}
            var reading by remember{mutableStateOf(true)}
            var opening by remember{mutableStateOf(false)}
            var attempt by remember{mutableIntStateOf(0)}
            val scope=rememberCoroutineScope()
            fun failed(error:Exception){
                failure=if(error is KnowledgeRejected)"关联已变化，请返回摘要重新选择。"else"预览读取失败，请重试。"
                preview=null
            }
            LaunchedEffect(targets,attempt){
                previewTrace(readId,"read-start targets=${targets.size} attempt=$attempt")
                withContext(Dispatchers.Main.immediate){reading=true;failure=null;preview=null}
                try{
                    val value=withContext(Dispatchers.IO){previewTrace(readId,"io-enter");app.knowledgeText.preview(source,linkId,revision)}
                    previewTrace(readId,"read-return")
                    withContext(Dispatchers.Main.immediate){
                        preview=value;reading=false
                        previewTrace(readId,"published loading=$reading value=${preview!=null} main=${android.os.Looper.myLooper()==android.os.Looper.getMainLooper()}")
                    }
                }
                catch(c:CancellationException){previewTrace(readId,"read-cancel");throw c}
                catch(e:Exception){withContext(Dispatchers.Main.immediate){failed(e);reading=false};previewTrace(readId,"read-failed ${e.javaClass.simpleName}")}
            }
            fun close(){if(!opening){selected=null;selectedRevision=null}}
            AlertDialog(
                onDismissRequest={close()},
                modifier=Modifier.fillMaxWidth(.96f).widthIn(max=640.dp).testTag("card-link-preview"),
                properties=DialogProperties(usePlatformDefaultWidth=false),
                title={Text(preview?.title?:"知识预览")},
                text={Column(Modifier.heightIn(max=contentHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(8.dp)){
                    SideEffect{previewTrace(readId,"render loading=$reading value=${preview!=null} error=${failure!=null}")}
                    Text(preview?.pinnedRevision?.let{"固定版本 $it"}?:"当前内容",fontSize=12.sp,color=Quiet)
                    if(reading)CircularProgressIndicator(Modifier.size(24.dp))
                    preview?.let{value->
                        SelectionContainer{Text(value.body.ifBlank{"尚未填写内容"},modifier=Modifier.testTag("card-link-preview-body"))}
                        if(value.pinnedRevision!=null&&value.canOpen)Text("此处预览固定版本；打开目标会查看当前卡片。",fontSize=12.sp,color=Quiet)
                        if(!value.canOpen)Text("目标不可打开，预览仍保留可读取的内容。",fontSize=12.sp,color=Quiet)
                    }
                    failure?.let{Text(it,modifier=Modifier.testTag("card-link-preview-error"))
                        TextButton(onClick={attempt++},enabled=!opening){Text("重试预览")}}
                }},
                confirmButton={TextButton(onClick={
                    if(!opening&&enabled){opening=true;scope.launch{
                        try{
                            val checked=withContext(Dispatchers.IO){app.knowledgeText.preview(source,linkId,revision)}
                            withContext(Dispatchers.Main.immediate){preview=checked;if(checked.canOpen)onOpenTarget(checked.target)}
                        }catch(c:CancellationException){throw c}
                        catch(e:Exception){withContext(Dispatchers.Main.immediate){failed(e)}}
                        finally{if(kotlinx.coroutines.currentCoroutineContext().isActive)withContext(Dispatchers.Main.immediate){opening=false}}
                    }}
                },enabled=enabled&&!reading&&!opening&&failure==null&&preview?.canOpen==true,
                    modifier=Modifier.testTag("card-link-open-target")){Text("打开目标")}},
                dismissButton={TextButton(onClick={close()},enabled=!opening,
                    modifier=Modifier.testTag("card-link-close-preview")){Text("返回摘要")}}
            )
        }
    }}
}
