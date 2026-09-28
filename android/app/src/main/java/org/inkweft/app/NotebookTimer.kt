// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable internal fun NotebookTimer(noteId:String,dismiss:()->Unit){
    val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("inkweft-timers",0)}
    var elapsed by remember(noteId){mutableLongStateOf(prefs.getLong("$noteId-elapsed",0))}
    var since by remember(noteId){mutableLongStateOf(prefs.getLong("$noteId-since",0))}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(since){while(since>0){now=System.currentTimeMillis();delay(250)}}
    fun total()=elapsed+if(since>0)(System.currentTimeMillis()-since).coerceAtLeast(0)else 0
    fun save(){prefs.edit().putLong("$noteId-elapsed",elapsed).putLong("$noteId-since",since).apply()}
    val seconds=(elapsed+if(since>0)(now-since).coerceAtLeast(0)else 0)/1000
    EditorPanel("计时器","",dismiss,"notebook-timer"){
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
            Text(String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60),style=MaterialTheme.typography.displaySmall,modifier=Modifier.padding(20.dp).testTag("timer-value"))
            Row {
                TextButton(onClick={elapsed=0;since=0;save()},modifier=Modifier.testTag("timer-reset")){Text("重置")}
                Button(onClick={if(since>0){elapsed=total();since=0}else{since=System.currentTimeMillis();now=since};save()},modifier=Modifier.testTag("timer-toggle")){Text(if(since>0)"暂停"else if(elapsed>0)"继续"else"开始")}
            }
        }
    }
}
