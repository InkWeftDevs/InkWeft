// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.inkweft.core.*

/** No note/page identity or repository is installed on this canvas. Samples belong only to the attempt. */
@Composable internal fun RecallAnswerPad(attemptId:String,bytes:ByteArray,enabled:Boolean,onChange:(ByteArray)->Unit,
    onCheckpoint:(InkStroke)->Unit={},onCancelCheckpoint:()->Unit={},onGesture:(Boolean)->Unit={}){
    val strokes=remember(bytes){RecallStudyViewModel.answerStrokes(bytes)}
    val latestStrokes by rememberUpdatedState(strokes);val latestChange by rememberUpdatedState(onChange)
    var finger by rememberSaveable(attemptId){mutableStateOf(false)}
    fun encode(values:List<InkStroke>)=InkPageFile("回忆作答","",values,false,PaperStyle.BLANK).encode()
    Column(Modifier.testTag("recall-answer-ink")){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("本次手写作答 · 与原笔记隔离")
            FilterChip(finger,{finger=!finger},enabled=enabled,label={Text("手指作答")},modifier=Modifier.testTag("recall-answer-finger"))
        }
        // Match visible input bounds to the fixed answer coordinates; wide blank gutters reject ink.
        key(attemptId){AndroidView(factory={context->InkCanvasView(context).apply{
            configure(false,PaperStyle.BLANK,null);showDocument(null);allowInput=enabled;fingerWrites=finger
            contentDescription="本次手写作答区域，不会修改原页笔迹"
            addOnLayoutChangeListener{_,l,t,r,b,ol,ot,or,ob->if(r>l&&b>t&&(or==ol||ob==ot))fixedRegion(CanvasBounds(0.0,0.0,1000.0,420.0))}
        }},onRelease={it.cancelGesture(false);it.showStrokes(emptyList());onGesture(false)},update={view->
            view.allowInput=enabled;view.fingerWrites=finger;view.showStrokes(strokes)
            view.onGesture=onGesture;view.onCheckpoint=onCheckpoint;view.onCheckpointCancel={onCancelCheckpoint()}
            view.onStroke={stroke->latestChange(encode(latestStrokes.filterNot{it.id==stroke.id}+stroke))}
        },modifier=Modifier.align(Alignment.CenterHorizontally).widthIn(max=240.dp*(1000f/420f)).fillMaxWidth()
            .aspectRatio(1000f/420f).border(1.dp,MaterialTheme.colorScheme.outlineVariant).testTag("recall-answer-ink-canvas"))}
        Row{TextButton({onChange(encode(strokes.dropLast(1)))},enabled=enabled&&strokes.isNotEmpty()){Text("撤回最后一笔")}
            Text("${strokes.size} 笔",Modifier.padding(12.dp))}
    }
}
