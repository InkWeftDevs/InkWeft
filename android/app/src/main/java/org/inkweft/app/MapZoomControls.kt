// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable internal fun MapZoomControls(view:MindMapView?,scale:Float,selected:Set<String>,enabled:Boolean){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
        IconButton({view?.zoom(1/1.2f)},enabled=enabled,modifier=Modifier.testTag("map-zoom-out").describedAs("缩小思维导图")){Text("−")}
        val percent=scale*100
        TextButton({view?.zoomTo(1f)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("map-zoom-reset").describedAs("当前缩放，点击恢复百分之百")){
            Text(if(percent<10)String.format(Locale.ROOT,"%.1f%%",percent)else "${kotlin.math.round(percent).toInt()}%")
        }
        IconButton({view?.zoom(1.2f)},enabled=enabled,modifier=Modifier.testTag("map-zoom-in").describedAs("放大思维导图")){Text("＋")}
        TextButton({view?.fitOverview()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("map-zoom-fit-all")){Text("全图")}
        TextButton({view?.fit()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("map-zoom-readable")){Text("可读大小")}
        if(selected.isNotEmpty())TextButton({view?.fitSelection(selected)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("map-zoom-fit-selection")){Text("适配所选")}
    }
}
