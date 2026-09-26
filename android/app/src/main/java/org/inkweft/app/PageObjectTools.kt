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
    viewport:()->CanvasViewport?,onNotice:(String)->Unit) {
    val context=LocalContext.current
    var picking by rememberSaveable(pageId){mutableStateOf(false)}
    var cameraPath by rememberSaveable(pageId){mutableStateOf<String?>(null)}
    var editing by rememberSaveable(stateSaver=Saver<PageObject?,String>(save={o->o?.let{Base64.getEncoder().encodeToString(PageObjectCodec.encode(listOf(it)))}?:""},restore={s->if(s.isEmpty())null else PageObjectCodec.decode(Base64.getDecoder().decode(s)).single()})){mutableStateOf<PageObject?>(null)}
    var reload by remember{mutableStateOf(false)}
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
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->picking=false;if(uri!=null)readImage(uri)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        val file=cameraPath?.let(::File);cameraPath=null
        if(ok&&file!=null&&file.length()>0)readImage(FileProvider.getUriForFile(context,context.packageName+".diagnostics.files",file),file)
        else file?.delete()
    }
    if(active) {
        Column(Modifier.fillMaxWidth().background(Color.White)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={try{picking=true;picker.launch("image/*")}catch(_:Exception){picking=false;onNotice("无法打开图片选择器。")}},enabled=available,modifier=Modifier.testTag("object-image")){Text("图片")}
                TextButton(onClick={
                    val folder=File(context.cacheDir,"page-camera").apply{mkdirs()}
                    val file=File(folder,"capture-${UUID.randomUUID()}.jpg")
                    try{check(folder.usableSpace>32L*1024*1024);cameraPath=file.absolutePath;camera.launch(FileProvider.getUriForFile(context,context.packageName+".diagnostics.files",file))}
                    catch(_:Exception){cameraPath=null;file.delete();onNotice("无法打开系统相机，请检查可用空间或使用图片导入。")}
                },enabled=available,modifier=Modifier.testTag("object-camera")){Text("拍照")}
                TextButton(onClick={editing=newObject(PageObjectKind.TEXT)},enabled=available,modifier=Modifier.testTag("object-text")){Text("文本框")}
                TextButton(onClick={val o=newObject(PageObjectKind.TAPE).copy(height=70f);vm.put(o);onSelect(o.id)},enabled=available,modifier=Modifier.testTag("object-tape")){Text("胶带")}
                TextButton(onClick=vm::undo,enabled=available&&ui.undo,modifier=Modifier.testTag("object-undo")){Text("撤销对象")}
                TextButton(onClick=vm::redo,enabled=available&&ui.redo,modifier=Modifier.testTag("object-redo")){Text("重做对象")}
            }
            val item=ui.objects.find{it.id==selected}
            if(item!=null)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(item.kind==PageObjectKind.TEXT)TextButton(onClick={editing=item},enabled=available,modifier=Modifier.testTag("object-edit-text")){Text("编辑文字")}
                if(item.kind==PageObjectKind.TAPE)TextButton(onClick={vm.put(item.copy(revealed=!item.revealed))},enabled=available,modifier=Modifier.testTag("object-reveal")){Text(if(item.revealed)"盖上胶带"else"揭开胶带")}
                TextButton(onClick={val x=if(world)item.x+24 else (item.x+24).coerceAtMost(1000-item.width);val y=if(world)item.y+24 else (item.y+24).coerceAtMost(1414-item.height);val o=item.copy(id=UUID.randomUUID().toString(),x=x,y=y);vm.put(o);onSelect(o.id)},enabled=available,modifier=Modifier.testTag("object-copy")){Text("复制")}
                TextButton(onClick={vm.change(ui.objects.filterNot{it.id==item.id}+item)},enabled=available,modifier=Modifier.testTag("object-front")){Text("移到同类前方")}
                TextButton(onClick={vm.delete(item.id);onSelect(null)},enabled=available,modifier=Modifier.testTag("object-delete")){Text("删除",color=Color(0xffab3939))}
            }
            Text(if(ui.busy)"正在保存对象…"else "点选对象后拖动；右下圆点缩放。图片和文字在笔迹下方，胶带覆盖笔迹。",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp).testTag("object-status"))
        }
    }
    if(ui.error!=null)Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(ui.error,Modifier.weight(1f),fontSize=12.sp)
        if(ui.pending){TextButton(onClick=vm::retry,enabled=!ui.busy){Text("核对重试")};TextButton(onClick={reload=true},enabled=!ui.busy){Text("重新读取")}}
    }
    if(reload)AlertDialog(onDismissRequest={reload=false},title={Text("重新读取已保存对象？")},text={Text("未确认的对象修改将被放弃，已保存的笔迹和对象保留。")},confirmButton={TextButton(onClick={reload=false;vm.reload()}){Text("读取已保存内容")}},dismissButton={TextButton(onClick={reload=false}){Text("取消")}})
    editing?.let { original ->
        var text by rememberSaveable(original.id){mutableStateOf(if(ui.objects.any{it.id==original.id})original.text else "")}
        var font by rememberSaveable(original.id){mutableFloatStateOf(original.fontSize)}
        var color by rememberSaveable(original.id){mutableIntStateOf(original.color)}
        var textError by remember{mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={editing=null},modifier=Modifier.testTag("object-text-dialog"),title={Text("页内文本框")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(text,{if(it.length<=4000)text=it},modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=260.dp).testTag("object-text-input"),label={Text("文字内容")})
            textError?.let{Text(it,color=Color(0xffab3939),fontSize=12.sp)}
            Text("字号 ${font.toInt()} · ${text.length}/4000",fontSize=12.sp)
            Slider(font,{font=it},valueRange=12f..96f,modifier=Modifier.testTag("object-font"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(0xff24342f.toInt(),0xffb64035.toInt(),0xff305ca2.toInt(),0xff7355a2.toInt()).forEach{c->FilterChip(color==c,{color=c},label={Text("●",color=Color(c))})}}
        }},confirmButton={TextButton(onClick={
            val o=original.copy(text=text,fontSize=font,color=color)
            val layout=android.text.StaticLayout.Builder.obtain(text,0,text.length,android.text.TextPaint().apply{textSize=font},o.width.toInt()).setIncludePad(false).build()
            val height=maxOf(48f,layout.height.toFloat()+8f)
            if(height>4000||(!world&&height>1414-o.y))textError="文字超出当前页可用高度，请减少文字或字号后保存。"
            else {vm.put(o.copy(height=height));onSelect(o.id);editing=null}
        },enabled=text.isNotBlank(),modifier=Modifier.testTag("object-text-save")){Text("保存")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})
    }
}
