// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RecallQuestionEditor(repository:RecallStudyRepository,book:String,item:RecallQueueItem,dismiss:()->Unit,saved:()->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication;val lock=rememberBookReadLock(book)
    var card by remember{mutableStateOf<StudyCardRow?>(null)};var question by remember{mutableStateOf<KnowledgeRow?>(null)}
    var original by remember{mutableStateOf<RecallQuestionRow?>(null)};var schedule by remember{mutableStateOf<RecallScheduleRow?>(null)}
    var sources by remember{mutableStateOf<FrozenStudySources?>(null)}
    var presentation by remember{mutableStateOf<KnowledgeRow?>(null)}
    var kind by rememberSaveable{mutableStateOf(item.kind)};var prompt by rememberSaveable{mutableStateOf(item.prompt)}
    // Empty masks can be an intentional draft edit, including after recreation.
    var initialized by rememberSaveable(book,item.reference.questionId){mutableStateOf(false)}
    var rangeText by rememberSaveable{mutableStateOf("")};var maskText by rememberSaveable{mutableStateOf("")}
    var text by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue())}
    var selectedSource by rememberSaveable{mutableIntStateOf(-1)}
    var left by rememberSaveable{mutableStateOf("0")};var top by rememberSaveable{mutableStateOf("0")};var right by rememberSaveable{mutableStateOf("100")};var bottom by rememberSaveable{mutableStateOf("100")}
    var busy by remember{mutableStateOf(false)};var unknown by rememberSaveable{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    val commandFile=remember(book,item.reference.questionId){AtomicFile(File(app.filesDir,"recall-config-$book-${item.reference.questionId}.pending"))}
    var command by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    ReadLockGuard(lock,"recall-question-editor-${item.reference.questionId}",busy||unknown,draft=busy||unknown)
    fun ranges()=if(rangeText.isEmpty())emptyList()else rangeText.split(';').map{value->val parts=value.split(':');RecallCloze(parts[0].toInt(),parts[1].toInt())}
    fun masks()=if(maskText.isEmpty())emptyList()else maskText.split(';').map{value->val p=value.split('|');RecallRegion(StudySourceVersionRef(p[0],p[1].toLong()),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble())}
    fun encodeMask(v:RecallRegion)="${v.source.sourceId}|${v.source.revision}|${v.left}|${v.top}|${v.right}|${v.bottom}"
    LaunchedEffect(item.reference.questionId){
        busy=true
        try{withContext(Dispatchers.IO){
            val pending=try{commandFile.readFully().also{require(it.size<=100_000)}.toString(Charsets.UTF_8)}catch(_:FileNotFoundException){null}
            if(pending!=null){val saved=JSONObject(pending);require(saved.getString("book")==book&&saved.getString("question")==item.reference.questionId)
                withContext(Dispatchers.Main){command=pending;unknown=true}
            }
            val q=app.knowledge.observeBook(book).first().single{it.id==item.reference.questionId&&!it.removed}
            val c=app.study.cards(book).first().single{it.id==item.reference.cardId&&it.trashedAt==null}
            val shownPresentation=repository.presentation(c.id);val config=repository.configuration(q.id);val scheduleRow=repository.schedule(q.id);val frozen=app.study.sources(c.id,c.revision)
            withContext(Dispatchers.Main){question=q;card=c;original=config;schedule=scheduleRow;sources=frozen;presentation=shownPresentation
                if(text.text!=c.body)text=TextFieldValue(c.body)
                if(pending!=null){val spec=RecallCodec.spec(Base64.decode(JSONObject(pending).getString("spec"),Base64.NO_WRAP))
                    prompt=spec.prompt;kind=spec.kind;rangeText=spec.clozes.joinToString(";"){"${it.start}:${it.end}"};maskText=spec.regions.joinToString(";",transform=::encodeMask)
                }else if(!initialized){
                    prompt=(q.data() as KnowledgeData.Question).prompt
                    config?.spec()?.let{spec->
                        kind=spec.kind;rangeText=spec.clozes.joinToString(";"){"${it.start}:${it.end}"};maskText=spec.regions.joinToString(";",transform=::encodeMask)
                    }
                }
                initialized=true
                if(selectedSource<0&&frozen.sources.size==1)selectedSource=0
            }
        }}catch(c:CancellationException){throw c}catch(e:Exception){error=recallError(e.message)}finally{busy=false}
    }
    fun apply(){
        if(busy||!lock.canWrite&&command==null)return
        val raw=command?:run {
        val c=card?:return;val q=question?:return
        val clozes=try{if(kind==RecallQuestionKind.TEXT_CLOZE)ranges()else emptyList()}catch(e:Exception){error="空位格式无效，请重新选择";return}
        val regions=try{if(kind==RecallQuestionKind.SOURCE_MASK)masks()else emptyList()}catch(e:Exception){error="遮挡区域无效，请重新选择";return}
        try{
            val spec=RecallQuestionSpec(q.id,q.revision,c.id,c.revision,prompt,kind,sources?.refs.orEmpty(),sources?.complete?:false,clozes,regions,presentation?.let{RecallPresentationRef(it.id,it.revision)})
            spec.validateBody(c.body)
            JSONObject().put("id",UUID.randomUUID().toString()).put("book",book).put("question",q.id).put("questionRevision",q.revision)
                .put("specRevision",original?.revision?:0).put("scheduleRevision",schedule?.revision?:0)
                .put("spec",Base64.encodeToString(RecallCodec.spec(spec),Base64.NO_WRAP)).toString()
        }catch(e:Exception){error=recallError(e.message);return}
        }
        command=raw;unknown=true;busy=true;error=null
        scope.launch{try{
            val result=withContext(Dispatchers.IO){
                val bytes=raw.toByteArray();require(bytes.size<=100_000)
                val stream=commandFile.startWrite();try{stream.write(bytes);commandFile.finishWrite(stream)}catch(t:Throwable){commandFile.failWrite(stream);throw t}
                val intent=JSONObject(raw);val spec=RecallCodec.spec(Base64.decode(intent.getString("spec"),Base64.NO_WRAP))
                repository.configure(intent.getString("id"),book,intent.getString("question"),intent.getLong("questionRevision"),intent.getLong("specRevision"),intent.getLong("scheduleRevision"),
                    spec.cardId,spec.cardRevision,spec.prompt,spec.kind,spec.clozes,spec.regions,spec.presentation,true)
            }
            when(result){
                is RecallOutcome.Success->{withContext(Dispatchers.IO){commandFile.delete()};command=null;unknown=false;saved()}
                is RecallOutcome.Rejected->{withContext(Dispatchers.IO){commandFile.delete()};command=null;unknown=false;error=recallError(result.reason)}
                RecallOutcome.Unknown->{error="保存结果待核对，请用原操作重试；不要另建同题"}
            }
        }catch(c:CancellationException){throw c}catch(e:Exception){unknown=true;error=recallError(e.message)}finally{busy=false}}
    }

    AlertDialog(onDismissRequest={if(!busy&&!unknown)dismiss()},modifier=Modifier.testTag("recall-config-dialog"),title={Text("题型与固定答案")},text={Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("保存会固定当前题目/知识/来源版本，并重置本题到首次到期；旧作答与原排程历史不改写。",modifier=Modifier.testTag("recall-config-policy"))
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(prompt,{prompt=it},enabled=!busy&&!unknown,label={Text("问法")},modifier=Modifier.fillMaxWidth().testTag("recall-config-prompt"))
        FlowRow{RecallQuestionKind.entries.forEach{value->FilterChip(kind==value,{kind=value},enabled=!busy&&!unknown,label={Text(value.label)})}}
        card?.let{c->
            Text("固定知识版本 ${c.revision} · ${c.title}")
            Text("共享注释固定版本 ${presentation?.revision?:0}："+((presentation?.data() as? KnowledgeData.CardPresentation)?.annotation.orEmpty().ifEmpty{"（空）"}))
            if(unknown)Text("正在核对之前已封存的配置命令；以上当前内容只作参考，不会改变原命令的版本和参数。")
            if(kind==RecallQuestionKind.TEXT_CLOZE){
                Text("在原卡正文中选中文字，再添加空位。此处不代表PDF字形坐标。")
                OutlinedTextField(text,{text=it},readOnly=true,enabled=!busy&&!unknown,minLines=4,maxLines=10,modifier=Modifier.fillMaxWidth().testTag("recall-cloze-select-text"))
                TextButton({try{
                    val selected=text.selection;require(selected.start!=selected.end)
                    val updated=(ranges()+RecallCloze(minOf(selected.start,selected.end),maxOf(selected.start,selected.end))).sortedBy{it.start}
                    RecallQuestionSpec(item.reference.questionId,1,c.id,c.revision,prompt,RecallQuestionKind.TEXT_CLOZE,clozes=updated).validateBody(c.body)
                    rangeText=updated.joinToString(";"){"${it.start}:${it.end}"};error=null
                }catch(_:Exception){error="请选择不重叠的完整文字，最多32个空位"}},enabled=!busy&&!unknown){Text("把选中文字设为空位")}
                runCatching{ranges()}.getOrDefault(emptyList()).forEachIndexed{i,range->Row{
                    Text("空位 ${i+1}：${c.body.substring(range.start.coerceAtMost(c.body.length),range.end.coerceAtMost(c.body.length))}",Modifier.weight(1f))
                    TextButton({rangeText=ranges().filterIndexed{index,_->index!=i}.joinToString(";"){"${it.start}:${it.end}"}},enabled=!busy&&!unknown){Text("移除")}
                }}
            }
            if(kind==RecallQuestionKind.SOURCE_MASK){
                val frozen=sources
                Text("按固定原迹区域设置遮挡，百分比从左上角计算；预览确认全部答案均已遮住。")
                if(frozen?.complete!=true)Text("来源不完整，不能建立原迹遮挡题",color=MaterialTheme.colorScheme.error)
                frozen?.sources?.forEachIndexed{i,source->FilterChip(selectedSource==i,{selectedSource=i},enabled=!busy&&!unknown,label={Text("来源 ${i+1} · v${source.revision}")})}
                val source=frozen?.sources?.getOrNull(selectedSource)
                if(source!=null){
                    FlowRow{listOf("左%" to left,"上%" to top,"右%" to right,"下%" to bottom).forEachIndexed{i,(label,value)->OutlinedTextField(value,{v->when(i){0->left=v;1->top=v;2->right=v;else->bottom=v}},label={Text(label)},enabled=!busy&&!unknown,modifier=Modifier.width(120.dp))}}
                    TextButton({try{val region=RecallRegion(source.ref(),left.toDouble()/100,top.toDouble()/100,right.toDouble()/100,bottom.toDouble()/100)
                        val all=masks()+region;require(all.size<=32);maskText=all.joinToString(";",transform=::encodeMask);error=null
                    }catch(_:Exception){error="区域需满足0≤左<右≤100、0≤上<下≤100，最多32块"}},enabled=!busy&&!unknown){Text("添加遮挡区域")}
                    var snapshot by remember(source.ref()){mutableStateOf<InkPageFile?>(null)}
                    LaunchedEffect(source.ref()){try{snapshot=withContext(Dispatchers.IO){InkPageFile.decode(source.snapshot)}}catch(c:CancellationException){throw c}catch(e:Exception){error="固定原迹预览不可用，请勿确认未检查的遮挡"}}
                    snapshot?.let{file->key(source.ref()){AndroidView(factory={RecallMaskedSourceView(it)},onRelease={it.clear()},update={view->view.interactionsEnabled=false;view.show(file,source,runCatching{masks().withIndex().filter{it.value.source==source.ref()}.map{it.index to it.value}}.getOrDefault(emptyList()))},modifier=Modifier.fillMaxWidth().height(240.dp))}}
                }
                runCatching{masks()}.getOrDefault(emptyList()).forEachIndexed{i,region->Row{Text("区域 ${i+1} · v${region.source.revision}",Modifier.weight(1f));TextButton({maskText=masks().filterIndexed{index,_->index!=i}.joinToString(";",transform=::encodeMask)},enabled=!busy&&!unknown){Text("移除")}}}
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(::apply,enabled=!busy&&(command!=null||lock.canWrite&&card!=null&&prompt.isNotBlank()),modifier=Modifier.testTag("recall-config-save")){Text(if(unknown)"核对原操作并重试"else"保存固定答案并重置到期")}},dismissButton={TextButton(dismiss,enabled=!busy&&!unknown){Text("取消")}})
}
