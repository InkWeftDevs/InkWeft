// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.view.View
import org.inkweft.core.StudySourceDraft

/** Process-local drag payload. The clipboard contains neither text nor private source images. */
internal typealias CaptureTransfer=org.inkweft.core.CaptureDraft
internal class CaptureShadow(view:View):View.DragShadowBuilder(view){
    private val d=view.resources.displayMetrics.density
    override fun onProvideShadowMetrics(size:Point,touch:Point){size.set((136*d).toInt(),(48*d).toInt());touch.set(size.x/2,size.y/2)}
    override fun onDrawShadow(c:Canvas){val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=Color.WHITE;c.drawRoundRect(0f,0f,136*d,48*d,10*d,10*d,p);p.color=0xff286aca.toInt();p.textSize=14*d;c.drawText("摘录 · 拖入导图",12*d,29*d,p)}
}
