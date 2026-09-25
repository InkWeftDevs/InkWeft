// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.inkweft.core.DiagnosticCode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiagnosticsApp() }
    }
    override fun onStart() { super.onStart(); (application as InkWeftApplication).diagnostics.event(DiagnosticCode.ACTIVITY_START) }
    override fun onStop() { (application as InkWeftApplication).diagnostics.event(DiagnosticCode.ACTIVITY_STOP); super.onStop() }
}
