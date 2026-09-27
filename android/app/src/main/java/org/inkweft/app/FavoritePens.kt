package org.inkweft.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.InkPen
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FavoritePen(val id:String,val kind:InkPen,val width:Float,val color:Int){
    val slot get()=if(kind==InkPen.HIGHLIGHTER)2 else 0
    fun matches(k:InkPen,w:Float,c:Int)=kind==k&&width==w&&color==c
}
internal class FavoritePenStore(context:Context,name:String="inkweft-favorite-pens"){
    private val prefs=context.applicationContext.getSharedPreferences(name,0)
    fun read():List<FavoritePen> = runCatching{
        val rows=JSONArray(prefs.getString("pens","[]"))
        (0 until minOf(rows.length(),12)).mapNotNull{i->runCatching{
            val r=rows.getJSONObject(i);val p=FavoritePen(r.getString("id"),InkPen.valueOf(r.getString("kind")),r.getDouble("width").toFloat(),r.getInt("color"))
            UUID.fromString(p.id);require(p.width.isFinite()&&p.width in PenWidthStore.range(p.slot)&&PenWidthStore.validColor(p.slot,p.color));p
        }.getOrNull()}.distinctBy{it.id}
    }.getOrDefault(emptyList())
    suspend fun save(pens:List<FavoritePen>):Boolean=withContext(Dispatchers.IO){
        require(pens.size<=12&&pens.map{it.id}.distinct().size==pens.size)
        val rows=JSONArray();pens.forEach{p->UUID.fromString(p.id);require(p.width.isFinite()&&p.width in PenWidthStore.range(p.slot)&&PenWidthStore.validColor(p.slot,p.color));rows.put(JSONObject().put("id",p.id).put("kind",p.kind.name).put("width",p.width).put("color",p.color))}
        prefs.edit().putString("pens",rows.toString()).commit()
    }
}
