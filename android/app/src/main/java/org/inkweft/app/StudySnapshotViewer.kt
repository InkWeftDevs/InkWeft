// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.InkPageFile
import org.inkweft.data.StudySourceRow

/** Reads only the saved excerpt; viewing never follows or changes the current source page. */
@Composable internal fun StudySnapshotViewer(source:StudySourceRow,dismiss:()->Unit){
    var loaded by remember(source){mutableStateOf<InkPageFile?>(null)}
    var error by remember(source){mutableStateOf<String?>(null)}
    var view by remember(source){mutableStateOf<InkCanvasView?>(null)}
    LaunchedEffect(source){
        try{loaded=withContext(Dispatchers.IO){InkPageFile.decode(source.snapshot)}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){error="摘录快照无法读取，请返回原卡后重试。"}
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        RecallWindowPermit()
        Surface(Modifier.fillMaxSize().testTag("study-snapshot-viewer")){
            Column(Modifier.safeDrawingPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("摘录时快照 · 只读",Modifier.weight(1f))
                    TextButton(dismiss,modifier=Modifier.testTag("study-snapshot-close")){Text("返回原卡")}
                }
                val file=loaded
                if(file!=null){
                    AndroidView(factory={context->InkCanvasView(context).also{native->
                        native.preview=false;native.allowInput=false;native.fingerWrites=false
                        native.contentDescription="摘录时保存的快照，只读，可缩放与移动。"
                        native.addOnLayoutChangeListener{_,l,t,r,b,oldL,oldT,oldR,oldB->
                            if(r>l&&b>t&&(oldR==oldL||oldB==oldT))native.fitContent(false)
                        }
                        view=native
                    }},onRelease={native->
                        if(view===native)view=null
                        native.showObjects(emptyList());native.showStrokes(emptyList())
                    },update={native->
                        native.preview=false;native.allowInput=false;native.fingerWrites=false
                        native.configure(file.world,file.paper,null)
                        native.showAuthoring(file.authoring);native.showImageSources(file.imageSources);native.showStrokes(file.strokes);native.showObjects(file.objects)
                    },modifier=Modifier.fillMaxWidth().weight(1f).testTag("study-snapshot-canvas"))
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
                        TextButton({view?.zoomBy(1/1.2)},modifier=Modifier.testTag("study-snapshot-zoom-out")){Text("缩小")}
                        TextButton({view?.zoomBy(1.2)},modifier=Modifier.testTag("study-snapshot-zoom-in")){Text("放大")}
                        TextButton({view?.fitContent()},modifier=Modifier.testTag("study-snapshot-fit")){Text("全部内容")}
                    }
                }else Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp),contentAlignment=Alignment.Center){
                    if(error!=null)Text(error!!)
                    else Column(horizontalAlignment=Alignment.CenterHorizontally){
                        CircularProgressIndicator()
                        Text("正在读取摘录快照…",Modifier.padding(top=16.dp))
                    }
                }
            }
        }
    }
}
