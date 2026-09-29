// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Window-based modal support panel. Outside tap is consumed; author canvas stays mounted. */
@Composable internal fun ContextPanel(sidePanel:Boolean,onDismiss:()->Unit,content:@Composable ()->Unit){
    BoxWithConstraints(Modifier.fillMaxSize()){
        val docked=sidePanel&&maxWidth>=912.dp
        Row(Modifier.fillMaxSize()){
            if(docked)Spacer(Modifier.weight(1f).fillMaxHeight().clickable(onClick=onDismiss).testTag("context-outside"))
            Surface((if(docked)Modifier.width(320.dp)else Modifier.weight(1f)).fillMaxHeight().testTag("knowledge-workspace"),content=content)
        }
    }
}
