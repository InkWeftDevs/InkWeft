// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

/** Only direct finger navigation can arm append; flings never enter this accumulator. */
internal class LastPagePull(private val threshold:Float) {
    private var active=false
    private var distance=0f
    fun begin(){active=true;distance=0f}
    fun cancel(){active=false;distance=0f}
    fun drag(unconsumedUp:Float,atEnd:Boolean,enabled:Boolean){
        if(!active)return
        if(!atEnd||!enabled){distance=0f;return}
        distance=(distance+unconsumedUp).coerceAtLeast(0f)
    }
    fun release():Boolean {
        val append=active&&distance>=threshold
        cancel()
        return append
    }
}
