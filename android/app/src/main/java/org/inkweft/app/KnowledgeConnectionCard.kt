// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun KnowledgeConnectionCard(
    title: String, context: String, relation: String, summary: String,
    tag: String, enabled: Boolean, open: () -> Unit, remove: (() -> Unit)? = null,
) {
    OutlinedCard(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(relation, style = MaterialTheme.typography.labelLarge, color = Forest)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(context, style = MaterialTheme.typography.bodySmall, color = Quiet)
            Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = open, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("只读预览") }
                if (remove != null) TextButton(onClick = remove, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-remove")) { Text("移除此关联") }
            }
        }
    }
}
