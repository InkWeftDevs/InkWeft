// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.inkweft.core.*

@Composable internal fun CoverPickerDialog(title:String,world:Boolean,current:NotebookCover,saving:Boolean,error:String?,dismiss:()->Unit,
    initialCustom:ByteArray?=null,loading:Boolean=false,save:(NotebookCover,ByteArray?)->Unit){
    var choice by rememberSaveable(current){mutableStateOf(current)}
    var payload by rememberSaveable(initialCustom){mutableStateOf(initialCustom?:CustomCoverCodec.encode(CustomCover()))}
    val custom=remember(payload){CustomCoverCodec.decode(payload)}
    var colorText by rememberSaveable(initialCustom){mutableStateOf("%06X".format(custom.color and 0xffffff))}
    var imageBusy by remember{mutableStateOf(false)};var imageError by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val blocked=saving||loading||imageBusy
    fun change(c:CustomCover){payload=CustomCoverCodec.encode(c);choice=NotebookCover.CUSTOM}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
        imageBusy=true;imageError=null
        try{val image=withContext(Dispatchers.IO){CoverImages.read(context,uri)};change(custom.copy(image=image,zoom=1f,focusX=.5f,focusY=.5f))}
        catch(c:CancellationException){throw c}catch(_:Exception){imageError="图片未能读取。请选择不超过 20 MB 的 JPG、PNG、WebP 或 HEIF 图片，原封面保留。"}
        finally{imageBusy=false}
    }}
    Dialog(onDismissRequest={if(!saving&&!imageBusy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("cover-dialog"),color=Color.White){Column(Modifier.safeDrawingPadding().imePadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=dismiss,enabled=!saving&&!imageBusy,modifier=Modifier.testTag("cancel-cover")){Text("取消")}
                Text("设计封面",Modifier.weight(1f),fontSize=22.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Button(onClick={save(choice,if(choice==NotebookCover.CUSTOM)payload else null)},enabled=!blocked&&(choice!=NotebookCover.CUSTOM||colorText.matches(Regex("[0-9a-fA-F]{6}"))),modifier=Modifier.testTag("confirm-cover")){Text("使用此封面",maxLines=1)}
            }
            HorizontalDivider(color=Line)
            if(blocked)LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val wide=maxWidth>=720.dp
                val preview:@Composable ()->Unit={Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(choice==NotebookCover.CUSTOM)CustomCoverArt(custom,title,Modifier.width(if(wide)220.dp else 120.dp).height(if(wide)300.dp else 164.dp).testTag("custom-cover-preview"))
                    else NotebookCoverArt(choice,"preview",title,world,Modifier.width(if(wide)220.dp else 120.dp).height(if(wide)300.dp else 164.dp))
                    Text(if(choice==NotebookCover.CONTENT)"书架将显示实际首页缩略图"else"封面预览",fontSize=12.sp,color=Quiet)
                }}
                val controls:@Composable ()->Unit={Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text("内置封面",fontSize=18.sp)
                    CoverChoices(choice,title,world){if(!blocked)choice=it}
                    OutlinedButton(onClick={choice=NotebookCover.CUSTOM},enabled=!blocked,modifier=Modifier.testTag("cover-custom")){Text("自定义封面")}
                    if(choice==NotebookCover.CUSTOM){
                        Text("图片",fontSize=18.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Button(onClick={picker.launch(arrayOf("image/jpeg","image/png","image/webp","image/heif","image/heic"))},enabled=!blocked,modifier=Modifier.testTag("cover-import-image")){Text(if(custom.image.isEmpty())"从图片导入"else"更换图片")}
                            if(custom.image.isNotEmpty())TextButton(onClick={change(custom.copy(image=byteArrayOf(),zoom=1f,focusX=.5f,focusY=.5f))},enabled=!blocked,modifier=Modifier.testTag("cover-remove-image")){Text("移除图片")}
                        }
                        if(custom.image.isNotEmpty()){
                            Text("取景 · 拖动滑杆调整，不裁掉原始导入副本",fontSize=12.sp,color=Quiet)
                            Text("缩放");Slider(custom.zoom,{change(custom.copy(zoom=it))},valueRange=1f..3f,enabled=!blocked,modifier=Modifier.testTag("cover-zoom"))
                            Text("左右位置");Slider(custom.focusX,{change(custom.copy(focusX=it))},enabled=!blocked,modifier=Modifier.testTag("cover-focus-x"))
                            Text("上下位置");Slider(custom.focusY,{change(custom.copy(focusY=it))},enabled=!blocked,modifier=Modifier.testTag("cover-focus-y"))
                            TextButton(onClick={change(custom.copy(zoom=1f,focusX=.5f,focusY=.5f))},enabled=!blocked){Text("重置取景")}
                        }
                        Text("配色与标题",fontSize=18.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            listOf(0xffdceae0,0xffe5eaf4,0xffefe9dc,0xfff0e5e5,0xffeae6f5,0xff234e42,0xff26354c,0xffb9573c).forEach{color->
                                OutlinedButton(onClick={colorText="%06X".format(color.toInt() and 0xffffff);change(custom.copy(color=color.toInt()))},enabled=!blocked,colors=ButtonDefaults.outlinedButtonColors(containerColor=Color(color)),contentPadding=PaddingValues(0.dp),modifier=Modifier.size(48.dp).describedAs("封面颜色 #${"%06X".format(color.toInt() and 0xffffff)}")){if(custom.color==color.toInt())Glyph("check",if(Color(color).luminance()>.35f)TextInk else Color.White)}
                            }
                        }
                        OutlinedTextField(colorText,{colorText=it.removePrefix("#").take(6);if(colorText.matches(Regex("[0-9a-fA-F]{6}")))change(custom.copy(color=0xff000000.toInt() or colorText.toInt(16)))},enabled=!blocked,singleLine=true,label={Text("底色 · 六位色号")},isError=!colorText.matches(Regex("[0-9a-fA-F]{6}")),modifier=Modifier.fillMaxWidth().testTag("cover-color"))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("显示封面文字",Modifier.weight(1f));Switch(custom.showTitle,{change(custom.copy(showTitle=it))},enabled=!blocked,modifier=Modifier.testTag("cover-show-title"))}
                        if(custom.showTitle){
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){CoverLayout.entries.forEach{layout->FilterChip(custom.layout==layout,{change(custom.copy(layout=layout))},enabled=!blocked,label={Text(when(layout){CoverLayout.LABEL->"书签标签";CoverLayout.BAND->"底部色带";CoverLayout.MINIMAL->"简约标题"})},modifier=Modifier.testTag("cover-layout-${layout.name.lowercase()}"))}}
                            OutlinedTextField(custom.title,{if(it.length<=80&&it.none{c->c<' '||c=='\u007f'})change(custom.copy(title=it))},enabled=!blocked,singleLine=true,label={Text("封面标题 · 留空跟随笔记名称")},modifier=Modifier.fillMaxWidth().testTag("cover-title"))
                            OutlinedTextField(custom.subtitle,{if(it.length<=80&&it.none{c->c<' '||c=='\u007f'})change(custom.copy(subtitle=it))},enabled=!blocked,singleLine=true,label={Text("副标题 · 课程、学期或一句话")},modifier=Modifier.fillMaxWidth().testTag("cover-subtitle"))
                        }
                    }
                    if(imageError!=null)Text(imageError!!,color=MaterialTheme.colorScheme.error)
                    if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("cover-error"))
                    Text("仅改变书架封面，不增加正文页。图片保存为本地副本，并随复制笔记与整库备份保留。",fontSize=12.sp,color=Quiet)
                }}
                if(wide)Row(Modifier.fillMaxSize()){Box(Modifier.width(300.dp).fillMaxHeight().background(Side),contentAlignment=Alignment.TopCenter){preview()};Box(Modifier.weight(1f).verticalScroll(rememberScrollState())){controls()}}
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Box(Modifier.fillMaxWidth().background(Side),contentAlignment=Alignment.Center){preview()};controls()}
            }
        }}
    }
}
