// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun KnowledgeConnectionCard(
    title: String, context: String, relation: String, summary: String,
    tag: String, enabled: Boolean, open: () -> Unit, remove: (() -> Unit)? = null,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(relation, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Forest)
                if (remove != null) Box {
                    IconButton(onClick = { actionsOpen = true }, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("$tag-actions").describedAs("关联操作")) { Glyph("more") }
                    DropdownMenu(actionsOpen, { actionsOpen = false }) {
                        DropdownMenuItem(text = { Text("移除此关联") }, onClick = { actionsOpen = false; confirmRemoval = true },
                            modifier = Modifier.testTag("$tag-remove"))
                    }
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(context, style = MaterialTheme.typography.bodySmall, color = Quiet)
            Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = open, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("预览内容") }
        }
    }
    if (confirmRemoval && remove != null) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        title = { Text("移除此关联？") },
        text = { Text("将移除与“$title”的这条关系。原笔记、卡片和内容仍保留。") },
        confirmButton = { TextButton(onClick = { confirmRemoval = false; remove() }, enabled = enabled,
            modifier = Modifier.testTag("$tag-confirm-remove")) { Text("移除关联") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("取消") } },
    )
}
