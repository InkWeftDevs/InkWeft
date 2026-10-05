// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A side reader needs room for a whole map card, even in a portrait workspace. */
internal fun cardInspectorWidth(width:Dp,height:Dp,fontScale:Float):Dp =
 if(width>=680.dp&&height>=360.dp*fontScale.coerceAtLeast(1f))360.dp else width

/** Explicit reading view, separate from selection and renaming. */
@Composable internal fun CardInspector(embedded:Boolean,onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,panelWidth:Dp,modifier:Modifier=Modifier,containerColor:Color=Color.White){
 if(!embedded)StudyDialog(false,onDismissRequest,title,text,confirmButton,modifier=modifier,containerColor=containerColor)
 else Box(Modifier.fillMaxSize()){
  androidx.activity.compose.BackHandler(onBack=onDismissRequest)
  Surface(modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight(),color=containerColor,shadowElevation=4.dp){Column(Modifier.fillMaxSize().padding(12.dp)){
   title();Box(Modifier.weight(1f).fillMaxWidth().padding(vertical=8.dp)){text()};Row(Modifier.fillMaxWidth().padding(end=36.dp),horizontalArrangement=Arrangement.End){confirmButton()}
  }}
 }
}
