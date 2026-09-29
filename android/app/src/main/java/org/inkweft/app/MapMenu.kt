// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
/** Graph-level commands, grouped without replacing the graph with a management page. */
@Composable internal fun MapMenu(expanded:Boolean,dismiss:()->Unit,group:Int,choose:(Int)->Unit,content:@Composable ColumnScope.()->Unit){
 DropdownMenu(expanded,dismiss,modifier=Modifier.width(296.dp).heightIn(max=400.dp).testTag("map-menu")){
  Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf("视图","整理","输出").forEachIndexed{i,label->FilterChip(group==i,{choose(i)},label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("map-menu-group-$i"))}
  }
  content()
 }
}
