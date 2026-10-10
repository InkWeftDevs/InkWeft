// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Stable names shared by templates, author layout previews and the closed codec. */
object MapLayouts {
    val labels: Map<String, String> = linkedMapOf(
        "right" to "右向树", "bilateral" to "双侧中心", "left" to "左向树", "organization" to "组织图",
    )
    val supported: Set<String> = labels.keys
    fun label(layout: String): String = labels.getValue(layout)
}
