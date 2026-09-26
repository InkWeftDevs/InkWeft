// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.inkweft.core.*
import org.inkweft.data.NotebookPageRow

@Composable
internal fun InsertPagesDialog(
    pages: List<NotebookPageRow>, initialAnchor: String, initialLocation: PageInsertLocation,
    onDismiss: () -> Unit, recycledCount:Int=0, onInsert: (PageInsertLocation, String?, PaperStyle, Int, Boolean, String) -> Unit,
) {
    var location by remember { mutableStateOf(initialLocation) }
    var anchorId by remember { mutableStateOf(initialAnchor) }
    var count by remember { mutableIntStateOf(1) }
    var inherited by remember { mutableStateOf(true) }
    var style by remember { mutableStateOf(PaperStyle.RULED) }
    var openNew by remember { mutableStateOf(true) }
    val anchor = pages.firstOrNull { it.id == anchorId }
    val index = when(location) {
        PageInsertLocation.START -> 0
        PageInsertLocation.END -> pages.size
        PageInsertLocation.BEFORE -> anchor?.position ?: -1
        PageInsertLocation.AFTER -> anchor?.position?.plus(1) ?: -1
    }
    val selectedStyle = if (inherited) PaperStyle.entries.getOrElse(anchor?.paper ?: 1) { PaperStyle.RULED } else style
    val valid = index >= 0 && pages.size + recycledCount + count <= InsertPages.MAX_PAGES && pages.none { it.world }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("insert-pages-dialog"),
        title = { Text("添加页面") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("插入位置", fontSize = 14.sp, color = TextInk)
                for (choices in listOf(listOf(PageInsertLocation.BEFORE,PageInsertLocation.AFTER),listOf(PageInsertLocation.START,PageInsertLocation.END))) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { item -> FilterChip(selected = location == item, onClick = { location = item },
                            modifier = Modifier.weight(1f).heightIn(min=48.dp).testTag("insert-${item.name.lowercase()}"),
                            label = { Text(when(item){PageInsertLocation.BEFORE->"所选页之前";PageInsertLocation.AFTER->"所选页之后";PageInsertLocation.START->"笔记本开头";PageInsertLocation.END->"笔记本末尾"},fontSize=12.sp) }) }
                    }
                }
                Text("选择目标页（不必先离开当前页）",fontSize=12.sp,color=Quiet)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().testTag("insertion-targets")) {
                    items(pages,key={it.id}) { p ->
                        Column(Modifier.width(76.dp).clipForPageSelection(anchorId==p.id).clickable{anchorId=p.id}.padding(5.dp)
                            .testTag("insert-target-${p.position+1}"),horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.size(60.dp,76.dp)) { PageThumb(p) }
                            Text("第 ${p.position+1} 页",fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
                        }
                    }
                }
                Text("纸面",fontSize=14.sp)
                FilterChip(selected=inherited,onClick={inherited=true},label={Text("沿用所选页纸面",fontSize=12.sp)},modifier=Modifier.testTag("insert-paper-inherit"))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    PaperStyle.entries.forEach { paper ->
                        Column(Modifier.weight(1f).clipForPageSelection(!inherited&&style==paper).clickable{inherited=false;style=paper}.padding(5.dp)
                            .testTag("insert-paper-${paper.name.lowercase()}"),horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(54.dp)) { PaperThumbnail(false,paper) }
                            Text(paperLabel(paper),fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("数量",Modifier.weight(1f),fontSize=14.sp)
                    OutlinedButton(onClick={count--},enabled=count>1,modifier=Modifier.testTag("insert-count-minus")){Text("−")}
                    Text(count.toString(),Modifier.padding(horizontal=14.dp).testTag("insert-count"))
                    OutlinedButton(onClick={count++},enabled=count<InsertPages.MAX_BATCH&&pages.size+recycledCount+count<InsertPages.MAX_PAGES,modifier=Modifier.testTag("insert-count-plus")){Text("＋")}
                }
                Surface(color=Leaf,shape=RoundedCornerShape(8.dp)) {
                    Text(if(valid) "将在第 ${index+1} 页位置插入 $count 页 · ${paperLabel(selectedStyle)}。" +
                        (if(index<pages.size) "原第 ${index+1} 页起顺延至第 ${index+count+1} 页。" else "原有页面不变。")
                        else "目标或页数已变化，请重新选择。",fontSize=12.sp,lineHeight=20.sp,color=Forest,
                        modifier=Modifier.padding(12.dp).testTag("insert-preview"))
                }
                Row(Modifier.fillMaxWidth().clickable{openNew=!openNew},verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=openNew,onCheckedChange={openNew=it},modifier=Modifier.testTag("insert-open-new"))
                    Text("完成后打开第一张新页",fontSize=12.sp)
                }
            }
        },
        confirmButton={Button(onClick={onInsert(location,if(location in listOf(PageInsertLocation.BEFORE,PageInsertLocation.AFTER))anchorId else null,selectedStyle,count,openNew,InsertPages.orderHash(pages.map { it.id }))},
            enabled=valid,modifier=Modifier.testTag("confirm-insert-pages")){Text("插入 $count 页")}},
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
private fun Modifier.clipForPageSelection(selected:Boolean)=this.border(if(selected)2.dp else 1.dp,if(selected)Forest else Line,RoundedCornerShape(7.dp)).background(Color.White,RoundedCornerShape(7.dp))
internal fun paperLabel(paper:PaperStyle)=when(paper){PaperStyle.BLANK->"空白";PaperStyle.RULED->"横线";PaperStyle.GRID->"方格";PaperStyle.DOTS->"点阵"}
