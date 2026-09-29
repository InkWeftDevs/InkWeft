// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject

internal data class LearningConfiguration(val widgets:List<WidgetInstance>,val recent:List<StableTargetRef>,val shortcuts:List<StableTargetRef>,val error:Boolean=false)
internal class LearningStore(context:Context){
    private val prefs=context.getSharedPreferences("inkweft-learning",Context.MODE_PRIVATE)
    private fun ref(r:StableTargetRef)=JSONObject().put("kind",r.kind.name).put("book",r.notebookId).put("id",r.id).put("map",r.mapId)
    private fun ref(j:JSONObject)=StableTargetRef(LearningTargetKind.valueOf(j.getString("kind")),j.getString("book"),j.optString("id").ifEmpty{null},j.optString("map").ifEmpty{null})
    private fun refs(a:JSONArray)=List(a.length()){ref(a.getJSONObject(it))}.also{require(it.size<=64)}
    private fun widgets(a:JSONArray)=List(a.length()){i->val j=a.getJSONObject(i);WidgetInstance(j.getString("id"),j.getString("definition"),j.getInt("version"),j.getBoolean("visible"),WidgetSize.valueOf(j.getString("size")),refs(j.optJSONArray("targets")?:JSONArray()))}.also{require(it.size<=24&&it.map{w->w.id}.distinct().size==it.size)}
    private fun json(w:WidgetInstance)=JSONObject().put("id",w.id).put("definition",w.definition).put("version",w.version).put("visible",w.visible).put("size",w.size.name).put("targets",JSONArray(w.targets.map(::ref)))
    init{if(!prefs.contains("widgets"))prefs.edit().putString("widgets",JSONArray(LearningWidgets.defaults().map(::json)).toString()).apply()}
    fun read():LearningConfiguration=try{LearningConfiguration(widgets(JSONArray(prefs.getString("widgets","[]"))),refs(JSONArray(prefs.getString("recent","[]"))),refs(JSONArray(prefs.getString("shortcuts","[]"))))}catch(_:Exception){LearningConfiguration(emptyList(),emptyList(),emptyList(),true)}
    fun observe()=callbackFlow{
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->trySend(read())}
        prefs.registerOnSharedPreferenceChangeListener(listener);trySend(read())
        awaitClose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }.distinctUntilChanged()
    fun configure(value:List<WidgetInstance>){require(value.size<=24);prefs.edit().putString("widgets",JSONArray(value.map(::json)).toString()).apply()}
    fun visit(target:StableTargetRef){val c=read();if(c.error)return;val list=(listOf(target)+c.recent.filterNot{it==target}).take(20);prefs.edit().putString("recent",JSONArray(list.map(::ref)).toString()).apply()}
    fun shortcut(target:StableTargetRef,enabled:Boolean){val c=read();if(c.error)return;val list=if(enabled)(c.shortcuts+target).distinct().take(32)else c.shortcuts-target;prefs.edit().putString("shortcuts",JSONArray(list.map(::ref)).toString()).apply()}
    fun viewport(ref:MapRef):MapViewport?=runCatching{val a=JSONArray(prefs.getString("view-${ref.notebookId}-${ref.mapId?:"main"}","[]"));MapViewport(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat()).also{require(it.scale.isFinite()&&it.scale>0&&it.x.isFinite()&&it.y.isFinite())}}.getOrNull()
    fun viewport(ref:MapRef,value:MapViewport){if(value.scale.isFinite()&&value.scale>0&&value.x.isFinite()&&value.y.isFinite())prefs.edit().putString("view-${ref.notebookId}-${ref.mapId?:"main"}",JSONArray(listOf(value.scale,value.x,value.y)).toString()).apply()}
    fun sharedLayout():String=JSONObject().put("format","inkweft.widget-layout").put("version",1).put("widgets",JSONArray(LearningWidgets.shareLayout(read().widgets).map(::json))).toString(2)
    fun importLayout(text:String){require(text.length<=32768);val j=JSONObject(text);require(j.getString("format")=="inkweft.widget-layout"&&j.getInt("version")==1)
        // Reconstruct only known structural fields, stripping private targets and arbitrary metadata.
        configure(LearningWidgets.shareLayout(widgets(j.getJSONArray("widgets"))))
    }
}
