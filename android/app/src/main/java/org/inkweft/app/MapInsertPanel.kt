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
import org.inkweft.core.*
import kotlinx.coroutines.*
import java.util.UUID

internal data class EmbedInsertion(val pageId:String,val objectId:String,val embed:MapEmbed)
@Composable internal fun MapInsertPanel(inputScene:MapScene,branch:String?,embedded:Boolean,dismiss:()->Unit,insert:(MapEmbed)->Unit){
    val scene=remember{inputScene}
    val app=LocalContext.current.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var policy by rememberSaveable{mutableIntStateOf(0)};var onlyBranch by rememberSaveable{mutableStateOf(branch!=null)}
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    val operation=rememberSaveable{UUID.randomUUID().toString()}
    StudyDialog(embedded,{if(!busy)dismiss()},title={Text("放入当前笔记")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        listOf("实时导图视图" to "跟随原图和共享卡片更新", "固定快照" to "保留当前结构和内容，不再跟随修改", "复制为独立导图" to "复制结构与卡片，后续分别编辑").forEachIndexed{i,(title,description)->
            Row{RadioButton(policy==i,{policy=i},enabled=!busy,modifier=Modifier.testTag("map-insert-policy-$i"));Column{Text(title);Text(description,style=MaterialTheme.typography.bodySmall)}}
        }
        if(branch!=null&&policy!=2)Row{Checkbox(onlyBranch,{onlyBranch=it},enabled=!busy);Text("仅显示所选分支")}
        if(policy==2)Text("复制整张导图和卡片内容。",style=MaterialTheme.typography.bodySmall)
        Text("插入后先选择或移动外框，再点“编辑导图”。",style=MaterialTheme.typography.bodySmall)
        message?.let{Text(it)}
    }},confirmButton={TextButton({
        if(policy==2){busy=true;scope.launch{try{
            val target=withContext(Dispatchers.IO){app.mapEmbeds.duplicate(scene.ref,scene.signature(),operation)}
            insert(MapEmbed(target));dismiss()
        }catch(c:CancellationException){throw c}catch(_:Exception){message="独立副本尚未确认，请核对原操作后重试。"}finally{busy=false}}}
        else{insert(MapEmbed(scene.ref,if(onlyBranch)branch else null,policy=if(policy==1)MapEmbedPolicy.PINNED else MapEmbedPolicy.LIVE,snapshot=if(policy==1)scene else null));dismiss()}
    },enabled=!busy&&scene.available,modifier=Modifier.testTag("map-insert-confirm")){Text(if(busy)"正在准备"else"插入")}},dismissButton={TextButton(dismiss,enabled=!busy){Text("取消")}})
}
