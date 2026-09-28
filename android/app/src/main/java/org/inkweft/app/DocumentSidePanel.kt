package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.*

@Composable internal fun DocumentSidePanel(title:String,tag:String,dismiss:()->Unit,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
 Surface(modifier.fillMaxHeight().testTag(tag),color=Color.White,border=BorderStroke(1.dp,Line),shadowElevation=4.dp){
  Column {
   Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(dismiss,modifier=Modifier.testTag("$tag-close").describedAs("关闭$title")){Glyph("close")}}
   HorizontalDivider(color=Line)
   content()
  }
 }
}
@Composable internal fun DocumentSection(title:String,value:String=""){
 Row(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=20.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=Quiet);if(value.isNotEmpty())Text(value,color=Forest,style=MaterialTheme.typography.labelMedium)}
}
@Composable internal fun DocumentAction(title:String,tag:String,enabled:Boolean=true,danger:Boolean=false,value:String="",action:()->Unit){
 Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=action).heightIn(min=52.dp).padding(horizontal=16.dp,vertical=8.dp).testTag(tag),verticalAlignment=Alignment.CenterVertically){
  Text(title,Modifier.weight(1f),color=if(!enabled)Quiet else if(danger)Color(0xffb64149)else TextInk,style=MaterialTheme.typography.bodyMedium)
  if(value.isNotEmpty())Text(value,color=Quiet,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(start=8.dp))
  Text("›",color=Quiet,modifier=Modifier.padding(start=12.dp))
 }
}
@Composable internal fun PageJumpRow(count:Int,current:Int,enabled:Boolean,choose:(Int)->Unit){
 val focus=LocalFocusManager.current
 var text by remember(current){mutableStateOf("")};var invalid by remember{mutableStateOf(false)}
 Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Text("跳转页面",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
   OutlinedTextField(text,{text=it.filter(Char::isDigit).take(3);invalid=false},singleLine=true,enabled=enabled,isError=invalid,placeholder={Text("1–$count")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.width(90.dp).testTag("document-page-number"))
   TextButton({val n=text.toIntOrNull();if(n==null||n !in 1..count)invalid=true else{choose(n-1);text="";focus.clearFocus()}},enabled=enabled,modifier=Modifier.testTag("document-page-go")){Text("前往")}
  }
  if(invalid)Text("请输入1到${count}之间的页码",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
 }
}
