// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.inkweft.core.*
import kotlinx.coroutines.*
import java.util.UUID

internal data class EmbedInsertion(val pageId:String,val objectId:String,val embed:MapEmbed)
@Composable internal fun MapInsertPanel(inputScene:MapScene,branch:String?,embedded:Boolean,dismiss:()->Unit,insert:(MapEmbed)->Unit){
    val scene=remember{inputScene}
    val app=LocalContext.current.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    val readLock=rememberBookReadLock(inputScene.ref.notebookId);val readOnly by readLock.readOnly.collectAsStateWithLifecycle()
    var policy by rememberSaveable{mutableIntStateOf(0)};var onlyBranch by rememberSaveable{mutableStateOf(branch!=null)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    var unknown by rememberSaveable{mutableStateOf(false)}
    val operation=rememberSaveable{UUID.randomUUID().toString()}
    val sourceBook=rememberSaveable{inputScene.ref.notebookId};val sourceMap=rememberSaveable{inputScene.ref.mapId.orEmpty()};val signature=rememberSaveable{inputScene.signature()}
    val guardKey=remember{ "map-insert-${UUID.randomUUID()}" }
    ReadLockGuard(readLock,guardKey,blocked=true,draft=true)
    StudyDialog(embedded,{if(!busy&&!unknown)dismiss()},title={Text("放入当前笔记")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        listOf("实时导图视图" to "跟随原图和共享卡片更新", "固定快照" to "保留节点结构和内容，不含跨图入口关系", "复制为独立导图" to "复制结构与卡片；带跨图入口的源图需先处理入口").forEachIndexed{i,(title,description)->
            Row{RadioButton(policy==i,{policy=i},enabled=!readOnly&&!busy&&!unknown,modifier=Modifier.testTag("map-insert-policy-$i"));Column{Text(title);Text(description,style=MaterialTheme.typography.bodySmall)}}
        }
        if(branch!=null&&policy!=2)Row{Checkbox(onlyBranch,{onlyBranch=it},enabled=!readOnly&&!busy&&!unknown);Text("仅显示所选分支")}
        if(policy==2)Text("复制整张导图和卡片内容。",style=MaterialTheme.typography.bodySmall)
        Text("插入后先选择或移动外框，再点“编辑导图”。",style=MaterialTheme.typography.bodySmall)
        message?.let{Text(it)}
    }},confirmButton={TextButton({
        if(!unknown&&!readLock.canWrite){message=readLock.reason.ifBlank{"当前为阅读模式，请返回书写后插入。"};return@TextButton}
        if(policy==2){busy=true;unknown=true;scope.launch{try{
            val target=withContext(Dispatchers.IO){app.mapEmbeds.duplicate(MapRef(sourceBook,sourceMap.ifEmpty{null}),signature,operation)}
            unknown=false
            insert(MapEmbed(target));dismiss()
        }catch(c:CancellationException){throw c}catch(e:Exception){unknown=e !is IllegalArgumentException&&e.message!="MAP_UNAVAILABLE"
            message=(if(!unknown)studyCapacityRejection(e.message.orEmpty())else null)?:if(e.message=="MAP_PORTAL_COPY_UNSUPPORTED")"此图带有跨图入口，尚不能独立复制。可改用实时视图；节点快照不包含入口关系，完整保存请使用资料库备份。"else if(!unknown)"源图或内容已变化，未创建副本；请取消后重新核对。"else"独立副本尚未确认，请核对原操作后重试。"
        }finally{busy=false}}}
        else{insert(MapEmbed(scene.ref,if(onlyBranch)branch else null,policy=if(policy==1)MapEmbedPolicy.PINNED else MapEmbedPolicy.LIVE,snapshot=if(policy==1)scene else null));dismiss()}
    },enabled=!busy&&(unknown||(!readOnly&&scene.available)),modifier=Modifier.testTag("map-insert-confirm")){Text(if(busy)"正在准备"else if(unknown)"核对原操作"else"插入")}},dismissButton={TextButton(dismiss,enabled=!busy&&!unknown){Text("取消")}})
}
