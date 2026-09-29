// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
/** Explicit reading view, separate from selection and renaming. */
@Composable internal fun CardInspector(embedded:Boolean,onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,modifier:Modifier=Modifier){
 if(!embedded)StudyDialog(false,onDismissRequest,title,text,confirmButton,modifier=modifier)
 else BoxWithConstraints(Modifier.fillMaxSize()){
  androidx.activity.compose.BackHandler(onBack=onDismissRequest)
  Surface(modifier.align(Alignment.CenterEnd).width(if(maxWidth>=650.dp)360.dp else maxWidth).fillMaxHeight(),color=Color.White,shadowElevation=4.dp){Column(Modifier.fillMaxSize().padding(12.dp)){
   title();Box(Modifier.weight(1f).fillMaxWidth().padding(vertical=8.dp)){text()};Row(Modifier.fillMaxWidth().padding(end=36.dp),horizontalArrangement=Arrangement.End){confirmButton()}
  }}
 }
}
