// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.inkweft.core.DiagnosticCode
class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();acceptRegion(intent);setContent{LibraryBackupHost{WorkspaceApp()}}}
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);acceptRegion(intent)}
    private fun acceptRegion(intent:android.content.Intent?){
        val uri=intent?.data?:return
        if(uri.scheme!="inkweft"||uri.host!="region"||uri.pathSegments.size!=1)return
        val id=uri.pathSegments.single();if(runCatching{java.util.UUID.fromString(id).toString()==id}.getOrDefault(false))
            (application as InkWeftApplication).openKnowledgeTarget.value=org.inkweft.core.TargetRef(org.inkweft.core.TargetKind.ANCHOR,id)
    }
    override fun onStart(){super.onStart();(application as InkWeftApplication).diagnostics.event(DiagnosticCode.ACTIVITY_START)}
    override fun onStop(){(application as InkWeftApplication).diagnostics.event(DiagnosticCode.ACTIVITY_STOP);super.onStop()}
}
