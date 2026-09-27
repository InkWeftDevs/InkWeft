// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import org.inkweft.core.*
import java.io.File
import java.util.Base64
import java.util.UUID

@Composable internal fun PageObjectTools(vm:PageObjectViewModel,ui:ObjectsUi,pageId:String,world:Boolean,
    active:Boolean,enabled:Boolean,selected:String?,onSelect:(String?)->Unit,onBlocked:(Boolean)->Unit,
    viewport:()->CanvasViewport?,onNotice:(String)->Unit,request:String?=null,onRequestConsumed:()->Unit={},onDone:()->Unit={}) {
    val context=LocalContext.current
    var picking by rememberSaveable(pageId){mutableStateOf(false)}
    var cameraPath by rememberSaveable(pageId){mutableStateOf<String?>(null)}
    var editing by rememberSaveable(stateSaver=Saver<PageObject?,String>(save={o->o?.let{Base64.getEncoder().encodeToString(PageObjectCodec.encode(listOf(it)))}?:""},restore={s->if(s.isEmpty())null else PageObjectCodec.decode(Base64.getDecoder().decode(s)).single()})){mutableStateOf<PageObject?>(null)}
    var reload by remember{mutableStateOf(false)}
    var dismissedError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(ui.error){if(ui.error==null)dismissedError=null}
    val available=enabled&&!ui.loading&&!ui.busy&&!ui.pending&&cameraPath==null&&!picking
    SideEffect{onBlocked(picking||cameraPath!=null||editing!=null)}
    fun newObject(kind:PageObjectKind):PageObject {
        val v=viewport()?:CanvasViewport();val x=(v.centerX-200).toFloat();val y=(v.centerY-100).toFloat()
        return PageObject(UUID.randomUUID().toString(),kind,if(world)x else x.coerceIn(0f,600f),if(world)y else y.coerceIn(0f,1194f),
            text=if(kind==PageObjectKind.TEXT)"文字"else "",
            color=if(kind==PageObjectKind.TAPE)0xffe5c66c.toInt()else 0xff24342f.toInt())
    }
    fun readImage(uri:Uri,cleanup:File?=null) {
        vm.importImage(context,uri,world,viewport()?:CanvasViewport(),cleanup){onSelect(it)}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->picking=false;if(uri!=null)readImage(uri)else onDone()}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        val file=cameraPath?.let(::File);cameraPath=null
        if(ok&&file!=null&&file.length()>0)readImage(FileProvider.getUriForFile(context,context.packageName+".diagnostics.files",file),file)
        else {file?.delete();onDone()}
    }
    fun insert(action:String){when(action){
        "image"->try{picking=true;picker.launch("image/*")}catch(_:Exception){picking=false;onNotice("无法打开图片选择器。");onDone()}
        "camera"->{val folder=File(context.cacheDir,"page-camera").apply{mkdirs()};val file=File(folder,"capture-${UUID.randomUUID()}.jpg")
            try{check(folder.usableSpace>32L*1024*1024);cameraPath=file.absolutePath;camera.launch(FileProvider.getUriForFile(context,context.packageName+".diagnostics.files",file))}
            catch(_:Exception){cameraPath=null;file.delete();onNotice("无法打开系统相机，请使用图片导入。");onDone()}}
        "text"->editing=newObject(PageObjectKind.TEXT)
        "tape"->{val o=newObject(PageObjectKind.TAPE).copy(height=70f);vm.put(o);onSelect(o.id)}
    }}
    LaunchedEffect(request,available){if(request!=null&&available){onRequestConsumed();insert(request)}}
    if(active&&selected!=null) {
        Column(Modifier.fillMaxWidth().background(Color.White)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                val item=ui.objects.find{it.id==selected&&!it.hidden}
                if(item!=null){
                if(item.kind==PageObjectKind.TEXT)TextButton(onClick={editing=item},enabled=available,modifier=Modifier.testTag("object-edit-text")){Text("编辑文字")}
                if(item.kind==PageObjectKind.TAPE)TextButton(onClick={vm.put(item.copy(revealed=!item.revealed))},enabled=available,modifier=Modifier.testTag("object-reveal")){Text(if(item.revealed)"盖上胶带"else"揭开胶带")}
                TextButton(onClick={val x=if(world)item.x+24 else (item.x+24).coerceAtMost(1000-item.width);val y=if(world)item.y+24 else (item.y+24).coerceAtMost(1414-item.height);val o=item.copy(id=UUID.randomUUID().toString(),x=x,y=y,sourceStrokeIds=emptyList());vm.put(o);onSelect(o.id)},enabled=available,modifier=Modifier.testTag("object-copy")){Text("复制")}
                TextButton(onClick={vm.change(ui.objects.filterNot{it.id==item.id}+item)},enabled=available,modifier=Modifier.testTag("object-front")){Text("移到同类前方")}
                TextButton(onClick={vm.delete(item.id);onSelect(null)},enabled=available,modifier=Modifier.testTag("object-delete")){Text("删除",color=Color(0xffab3939))}
                if(item.sourceStrokeIds.isNotEmpty())TextButton(onClick={vm.restoreOriginal(item.id);onSelect(null)},enabled=available,modifier=Modifier.testTag("object-restore-original")){Text("恢复原迹")}
                TextButton(onClick=onDone,modifier=Modifier.testTag("object-deselect")){Text("完成")}
                }else TextButton(onClick=onDone,modifier=Modifier.testTag("object-deselect")){Text("完成")}

            }
        }
    }
    if(ui.error!=null&&dismissedError!=ui.error)AlertDialog(onDismissRequest={if(!ui.pending)dismissedError=ui.error},title={Text("内容未保存")},text={Text(ui.error)},confirmButton={
        if(ui.pending)TextButton(onClick=vm::retry,enabled=!ui.busy){Text("核对重试")}else TextButton(onClick={dismissedError=ui.error}){Text("知道了")}
    },dismissButton={if(ui.pending)TextButton(onClick={reload=true},enabled=!ui.busy){Text("重新读取")}})
    if(reload)AlertDialog(onDismissRequest={reload=false},title={Text("重新读取已保存对象？")},text={Text("未确认的对象修改将被放弃，已保存的笔迹和对象保留。")},confirmButton={TextButton(onClick={reload=false;vm.reload()}){Text("读取已保存内容")}},dismissButton={TextButton(onClick={reload=false}){Text("取消")}})
    editing?.let { original ->
        var text by rememberSaveable(original.id){mutableStateOf(if(ui.objects.any{it.id==original.id})original.visibleText() else "")}
        var font by rememberSaveable(original.id){mutableFloatStateOf(original.fontSize)}
        var family by rememberSaveable(original.id){mutableStateOf(original.font)}
        var spacing by rememberSaveable(original.id){mutableFloatStateOf(original.lineSpacing)}
        var bold by rememberSaveable(original.id){mutableStateOf(original.bold)}
        var color by rememberSaveable(original.id){mutableIntStateOf(original.color)}
        var textError by remember{mutableStateOf<String?>(null)}
        EditorPanel("文本框","",{editing=null;if(selected==null)onDone()},"object-text-dialog",footer={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={editing=null;if(selected==null)onDone()}){Text("取消")};TextButton(onClick={
            if(original.glyphs.isNotEmpty()&&text==original.visibleText()){
                vm.put(original.copy(color=color,font=family,bold=bold));editing=null
                return@TextButton
            }
            val o=original.copy(text=text,fontSize=font,color=color,font=family,lineSpacing=spacing,bold=bold,glyphs=emptyList(),erasures=emptyList())
            val layout=TextStyles.layout(o)
            val height=maxOf(48f,layout.height.toFloat()+8f)
            if(height>4000||(!world&&height>1414-o.y))textError="文字超出当前页可用高度，请减少文字或字号后保存。"
            else {vm.put(o.copy(height=height));onSelect(o.id);editing=null}
        },enabled=text.isNotBlank(),modifier=Modifier.testTag("object-text-save")){Text("保存")}}}){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            OutlinedTextField(text,{if(it.length<=4000)text=it},modifier=Modifier.fillMaxWidth().heightIn(min=96.dp,max=150.dp).testTag("object-text-input"),label={Text("文字内容")})
            textError?.let{Text(it,color=Color(0xffab3939),fontSize=12.sp)}
            FontControls(family,{family=it},bold,{bold=it},spacing,{spacing=it})
            Row(verticalAlignment=Alignment.CenterVertically){Text("字号 ${font.toInt()}",Modifier.width(78.dp));Slider(font,{font=it},valueRange=12f..96f,modifier=Modifier.weight(1f).testTag("object-font"))}
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf(0xff24342f.toInt(),0xffb64035.toInt(),0xff305ca2.toInt(),0xff7355a2.toInt()).forEach{c->FilterChip(color==c,{color=c},label={Text("●",color=Color(c))})}}
        }
        }
    }
}
