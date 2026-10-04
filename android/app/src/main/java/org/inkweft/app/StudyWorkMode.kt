// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription

internal enum class StudyWorkMode { READ, WRITE, RECALL }

/** Three states of the same material. A short, always visible label accompanies each state. */
@Composable internal fun StudyWorkModes(mode:StudyWorkMode,tagPrefix:String="",choose:(StudyWorkMode)->Unit){
    Row(Modifier.testTag("workspace-modes")){
        StudyWorkMode.entries.forEach { value ->
            val label=when(value){StudyWorkMode.READ->"读";StudyWorkMode.WRITE->"写";StudyWorkMode.RECALL->"忆"}
            val description=when(value){StudyWorkMode.READ->"阅读资料";StudyWorkMode.WRITE->"书写批注";StudyWorkMode.RECALL->"主动回忆"}
            TextButton({choose(value)},contentPadding=PaddingValues(horizontal=6.dp),
                modifier=Modifier.widthIn(min=48.dp).heightIn(min=48.dp)
                    .testTag(if(tagPrefix.isNotEmpty())"$tagPrefix-mode-${value.name.lowercase()}" else when(value){StudyWorkMode.READ->"quick-readonly";StudyWorkMode.WRITE->"exit-readonly";StudyWorkMode.RECALL->"workspace-recall"})
                    .editorSelected(value==mode).describedAs(description).semantics{selected=value==mode;stateDescription=if(value==mode)"当前工作状态"else"切换工作状态"}){
                Text(if(value==mode)"✓$label"else label,maxLines=1)
            }
        }
    }
}
