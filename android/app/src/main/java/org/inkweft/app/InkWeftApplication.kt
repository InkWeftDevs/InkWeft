// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.Application
import org.inkweft.data.InkRepository
import org.inkweft.data.NoteDatabase
import org.inkweft.data.NoteRepository

class InkWeftApplication : Application() {
    private val database by lazy { NoteDatabase.open(this) }
    val repository by lazy { NoteRepository(database) }
    val inkRepository by lazy { InkRepository(database) }
}
