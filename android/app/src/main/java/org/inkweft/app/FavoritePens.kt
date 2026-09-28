package org.inkweft.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FavoritePen(val id:String,val kind:InkPen,val width:Float,val color:Int,val recipe:BrushRecipe=BrushRecipe()){
    val slot get()=when(kind){InkPen.HIGHLIGHTER->2;InkPen.PEN->1;else->0}
    fun matches(k:InkPen,w:Float,c:Int,r:BrushRecipe=BrushRecipe())=kind==k&&width==w&&color==c&&recipe==r
}
internal class FavoritePenStore(context:Context,name:String="inkweft-favorite-pens"){
    private val prefs=context.applicationContext.getSharedPreferences(name,0)
    fun read():List<FavoritePen> = runCatching{
        val rows=JSONArray(prefs.getString("pens","[]"))
        (0 until minOf(rows.length(),12)).mapNotNull{i->runCatching{
            val r=rows.getJSONObject(i);val p=FavoritePen(r.getString("id"),InkPen.valueOf(r.getString("kind")),r.getDouble("width").toFloat(),r.getInt("color"),if(r.has("recipe"))BrushRecipe.decode(android.util.Base64.decode(r.getString("recipe"),android.util.Base64.NO_WRAP))else BrushRecipe())
            UUID.fromString(p.id);require(p.width.isFinite()&&p.width in PenWidthStore.range(p.slot)&&PenWidthStore.validColor(p.slot,p.color));p
        }.getOrNull()}.distinctBy{it.id}
    }.getOrDefault(emptyList())
    fun apply(pens:List<FavoritePen>){prefs.edit().putString("pens",encode(pens)).apply()}
    suspend fun save(pens:List<FavoritePen>):Boolean=withContext(Dispatchers.IO){prefs.edit().putString("pens",encode(pens)).commit()}
    private fun encode(pens:List<FavoritePen>):String {
        require(pens.size<=12&&pens.map{it.id}.distinct().size==pens.size)
        val rows=JSONArray();pens.forEach{p->UUID.fromString(p.id);require(p.width.isFinite()&&p.width in PenWidthStore.range(p.slot)&&PenWidthStore.validColor(p.slot,p.color));rows.put(JSONObject().put("id",p.id).put("kind",p.kind.name).put("width",p.width).put("color",p.color).put("recipe",android.util.Base64.encodeToString(p.recipe.encode(),android.util.Base64.NO_WRAP)))}
        return rows.toString()
    }
}
