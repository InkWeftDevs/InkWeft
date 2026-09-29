// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FontControls(font:TextFont,onFont:(TextFont)->Unit,bold:Boolean,onBold:(Boolean)->Unit,spacing:Float,onSpacing:(Float)->Unit,showSpacing:Boolean=true){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        TextFont.entries.forEach{f->FilterChip(font==f,{onFont(f)},label={Text(TextStyles.name(f))},modifier=Modifier.testTag("font-${f.name}"))}
    }
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        FilterChip(bold,{onBold(!bold)},label={Text("加粗")});if(showSpacing){Spacer(Modifier.width(8.dp));Text("行距 %.1f".format(spacing),style=MaterialTheme.typography.bodySmall)
        Slider(spacing,onSpacing,valueRange=1f..2f,modifier=Modifier.weight(1f).testTag("text-spacing"))}
    }
}
