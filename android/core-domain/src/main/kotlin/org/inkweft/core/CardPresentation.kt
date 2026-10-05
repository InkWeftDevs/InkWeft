// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Light opaque presets keep dark card text readable. Default defers to the existing surface. */
enum class CardTint(val label:String,val argb:Int?) {
    DEFAULT("默认",null), CREAM("米黄",0xfffff4cf.toInt()), BLUE("浅蓝",0xffe5f0ff.toInt()),
    GREEN("浅绿",0xffe4f3e8.toInt()), ROSE("浅粉",0xfffbe8ed.toInt()),
}
object CardPresentationRules {
    // Modified UTF-8 needs at most three bytes per UTF-16 unit; leave room for identity and presets.
    const val MAX_ANNOTATION=10_000
}
