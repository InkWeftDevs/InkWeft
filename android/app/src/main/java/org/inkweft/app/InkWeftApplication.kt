// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import org.inkweft.data.*

class InkWeftApplication:Application(){
    val diagnostics by lazy{AppDiagnostics(this)}
    private val database by lazy{NoteDatabase.open(this)}
    val repository by lazy{NoteRepository(database)}
    val inkRepository by lazy{InkRepository(database)}
    val workspaceRepository by lazy{WorkspaceRepository(database)}
    override fun onCreate(){super.onCreate();diagnostics}
}
