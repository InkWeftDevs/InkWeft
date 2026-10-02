// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** A display lease. Original author views and their unsaved state remain mounted underneath. */
internal class RecallWindowShield {
    private data class Covered(val importance:Int,val cover:RecallWindowCover)
    private val permitted=mutableMapOf<View,Int>()
    private val covered=mutableMapOf<ViewGroup,Covered>()
    private val drawListener=ViewTreeObserver.OnPreDrawListener { refresh();true }
    private var active=false
    private var closed=false

    fun setActive(value:Boolean) {
        if(closed)return
        active=value
        if(active)refresh() else restoreAll()
    }
    fun permit(root:View) {
        if(closed)return
        permitted[root]=(permitted[root]?:0)+1
        if(permitted[root]==1)root.viewTreeObserver.addOnPreDrawListener(drawListener)
        (root as? ViewGroup)?.let(::restore)
        refresh()
    }
    fun release(root:View) {
        val count=permitted[root]?:return
        if(count>1)permitted[root]=count-1 else {
            permitted.remove(root)
            if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(drawListener)
        }
        refresh()
    }
    private fun refresh() {
        if(!active||closed)return
        val roots=WindowInspector.getGlobalWindowViews().filterIsInstance<ViewGroup>()
        covered.keys.filter{it !in roots||it in permitted}.toList().forEach(::restore)
        roots.filter{it !in permitted&&it !in covered}.forEach { root ->
            val cover=RecallWindowCover(root.context)
            covered[root]=Covered(root.importantForAccessibility,cover)
            root.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            root.overlay.add(cover)
        }
        covered.forEach { (root,saved)->saved.cover.layout(0,0,root.width,root.height) }
    }
    private fun restore(root:ViewGroup) {
        covered.remove(root)?.let { saved ->
            root.overlay.remove(saved.cover)
            root.importantForAccessibility=saved.importance
        }
    }
    private fun restoreAll()=covered.keys.toList().forEach(::restore)
    fun close() {
        if(closed)return
        closed=true
        permitted.keys.forEach { root ->
            if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(drawListener)
        }
        permitted.clear()
        restoreAll()
    }
}

/** Opaque native cover keeps ancestor rendering neutral without changing author children. */
internal class RecallWindowCover(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xff626d7e.toInt();textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,18f,resources.displayMetrics)
        textAlign=Paint.Align.CENTER
    }
    init {
        tag="recall-window-cover"
        isClickable=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(canvas:Canvas) {
        canvas.drawColor(0xfff7f7f3.toInt())
        canvas.drawText("回忆进行中",width/2f,height/2f,paint)
    }
}

internal val LocalRecallWindowShield=staticCompositionLocalOf<RecallWindowShield?>{null}

@Composable internal fun RecallWindowIsolation(content:@Composable ()->Unit) {
    val context=LocalContext.current
    val shield=remember(context){RecallWindowShield()}
    DisposableEffect(shield){onDispose{shield.close()}}
    CompositionLocalProvider(LocalRecallWindowShield provides shield,content=content)
}

/** Only the review and explicitly authorized source windows may show their own contents. */
@Composable internal fun RecallWindowPermit() {
    val shield=LocalRecallWindowShield.current
    val view=LocalView.current
    DisposableEffect(shield,view) {
        val root=view.rootView
        shield?.permit(root)
        onDispose{shield?.release(root)}
    }
}
