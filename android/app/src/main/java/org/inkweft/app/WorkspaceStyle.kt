// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.inkweft.app.ui.designsystem.InkTheme
internal val Forest=InkTheme.Accent
internal val Leaf=InkTheme.Selected
internal val TextInk=InkTheme.Text
internal val Quiet=InkTheme.Secondary
internal val Line=InkTheme.Divider
internal val Side=InkTheme.Navigation
@Composable fun InkWeftTheme(content:@Composable ()->Unit){InkTheme.Content(content)}
/** Packaged Material Symbols; unknown names must not silently draw an unrelated icon. */
@Composable internal fun Glyph(kind:String,tint:Color=LocalContentColor.current,modifier:Modifier=Modifier){
    val resource=when(kind){
        "add"->R.drawable.ic_add
        "back"->R.drawable.ic_back
        "close"->R.drawable.ic_close
        "search"->R.drawable.ic_search
        "grid"->R.drawable.ic_grid
        "list"->R.drawable.ic_list
        "menu"->R.drawable.ic_menu
        "folder"->R.drawable.ic_folder
        "trash"->R.drawable.ic_trash
        "star"->R.drawable.ic_star
        "board"->R.drawable.ic_board
        "pen"->R.drawable.ic_pen
        "eraser"->R.drawable.ic_eraser
        "undo"->R.drawable.ic_undo
        "redo"->R.drawable.ic_redo
        "more"->R.drawable.ic_more
        "tag"->R.drawable.ic_tag
        "sort"->R.drawable.ic_sort
        "diagnostics"->R.drawable.ic_diagnostics
        "check"->R.drawable.ic_check
        "export"->R.drawable.ic_export
        "note"->R.drawable.ic_note
        "select"->R.drawable.ic_select
        "link"->R.drawable.ic_link
        "learn"->R.drawable.ic_learn
        "review"->R.drawable.ic_review
        "settings"->R.drawable.ic_settings
        "pin"->R.drawable.ic_pin
        else->error("Unknown icon: $kind")
    }
    Icon(painterResource(resource),null,modifier.size(24.dp),tint)
}
internal fun Modifier.describedAs(label:String)=semantics { contentDescription=label }
