// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Floating study forms replace the contents of the same window, not a second modal. */
@Composable internal fun StudyDialog(embedded:Boolean,onDismissRequest:()->Unit,title:@Composable ()->Unit,
    text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:@Composable ()->Unit={},modifier:Modifier=Modifier,containerColor:Color=Color.White){
    if(!embedded)AlertDialog(onDismissRequest,confirmButton,modifier,dismissButton,title=title,text=text,containerColor=containerColor)
    else {
        androidx.activity.compose.BackHandler(onBack=onDismissRequest)
        Surface(modifier.fillMaxSize(),color=containerColor){Column(Modifier.fillMaxSize().padding(12.dp)){
            title()
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical=8.dp)){text()}
            Row(Modifier.fillMaxWidth().padding(end=36.dp),horizontalArrangement=Arrangement.End){dismissButton();confirmButton()}
        }}
    }
}
