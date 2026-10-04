// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** One device-local order/visibility scope for document and writing shortcuts. */
internal data class EditorToolbarPreferences(val order:List<String>,val hidden:Set<String>){
    fun destinations(hidden:Boolean=false)=order.filter{it in EditorToolOrder.destinations&&(it in this.hidden)==hidden}
}

@Composable internal fun rememberEditorToolbarPreferences():State<EditorToolbarPreferences>{
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("inkweft-editor",0)}
    fun read():EditorToolbarPreferences{
        val savedOrder=prefs.getString("toolbar-order-v32","").orEmpty().split(',')
        val hidden=(prefs.getStringSet("toolbar-hidden-v32",EditorToolOrder.defaultHidden).orEmpty()+
            EditorToolOrder.defaultHidden.filter{it !in savedOrder})-EditorToolOrder.fixed
        return EditorToolbarPreferences(EditorToolOrder.read(prefs),hidden)
    }
    val state=remember(prefs){mutableStateOf(read())}
    DisposableEffect(prefs){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
            if(key==null||key=="toolbar-order-v32"||key=="toolbar-hidden-v32")state.value=read()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        state.value=read()
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return state
}
