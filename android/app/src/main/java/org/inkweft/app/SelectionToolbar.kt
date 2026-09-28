package org.inkweft.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import org.inkweft.core.*
import kotlin.math.roundToInt

/** Same canvas coordinates as the selection, in a non-modal window above the pen case. */
@Composable internal fun SelectionToolbar(region:InkRegion?,viewport:CanvasViewport,content:@Composable ()->Unit){
    val density=LocalDensity.current.density
    val position=remember(region,viewport,density){object:PopupPositionProvider{
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset{
            val margin=(8*density).roundToInt();val gap=(10*density).roundToInt()
            val safeTop=anchorBounds.top+(58*density).roundToInt()
            val b=region?.bounds
            val a=b?.let{viewport.worldToScreen(it.left,it.top,anchorBounds.width.toDouble(),anchorBounds.height.toDouble(),density.toDouble())}
            val z=b?.let{viewport.worldToScreen(it.right,it.bottom,anchorBounds.width.toDouble(),anchorBounds.height.toDouble(),density.toDouble())}
            val x=anchorBounds.left+if(a!=null&&z!=null)((a.x+z.x-popupContentSize.width)/2).roundToInt()else(anchorBounds.width-popupContentSize.width)/2
            val y=if(a==null||z==null)safeTop else {
                val above=anchorBounds.top+a.y-popupContentSize.height-gap
                if(above>=safeTop)above.roundToInt()else(anchorBounds.top+z.y+gap).roundToInt()
            }
            return IntOffset(x.coerceIn(margin,(windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin)),
                y.coerceIn(safeTop.coerceAtMost((windowSize.height-popupContentSize.height-margin).coerceAtLeast(0)),(windowSize.height-popupContentSize.height-margin).coerceAtLeast(0)))
        }
    }}
    Box(Modifier.fillMaxSize()){
        Popup(popupPositionProvider=position,properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)){
            Surface(Modifier.widthIn(max=460.dp).testTag("selection-context-menu"),color=Color.White,shape=RoundedCornerShape(12.dp),
                shadowElevation=4.dp,border=BorderStroke(1.dp,Line),content=content)
        }
    }
}
