// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.inkweft.core.LibraryArchive
import org.inkweft.core.ContentTransfer
import org.json.*
import java.util.UUID

data class ShadowConflict(val key:String,val title:String,val kind:String,val frozen:String,val choices:List<ShadowChoice>)
data class ShadowChoice(val revision:String,val title:String,val detail:String,val local:Boolean)

/** Bounded lab snapshots keep a card revision/body or a page branch indivisible. */
internal class ShadowBundles(private val sql:SupportSQLiteDatabase,private val schema:List<LibraryArchive.Table>){
    init{
        sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_bundles (entity TEXT NOT NULL,revision TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(entity,revision))")
        sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_bundle_selected (entity TEXT PRIMARY KEY,revision TEXT NOT NULL)")
    }
    fun group(row:JSONObject):String?{
        val t=row.getInt("table");val a=row.getJSONArray("row")
        val (kind,column)=when(t){
            0->"笔记" to "id";1,2->"笔记" to "noteId"
            13->"知识卡" to "id";14,15->"知识卡" to "cardId"
            5,6,7,8->"页面" to "noteId";9,22,23,26->"页面" to "pageId"
            else->return null
        }
        return "$kind:${a.getString(schema[t].columns.indexOfFirst{it.name==column})}"
    }
    private fun hash(s:String)=ContentTransfer.hash(s.toByteArray())
    private fun heads(key:String)=sql.query("SELECT value FROM shadow_bundles WHERE entity=? ORDER BY revision",arrayOf(key)).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}
    private fun selected(key:String)=sql.query("SELECT revision FROM shadow_bundle_selected WHERE entity=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else null}
    private fun keys()=sql.query("SELECT DISTINCT entity FROM shadow_bundles ORDER BY entity").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    fun applyTo(rows:Map<String,JSONObject>):Map<String,JSONObject>{
        val result=rows.toMutableMap()
        for(key in keys()){
            result.entries.removeAll{group(it.value)==key}
            val chosen=heads(key).single{it.getString("revision")==selected(key)}.getJSONArray("members")
            repeat(chosen.length()){val row=chosen.getJSONObject(it);result[row.getString("key")]=row}
        }
        return result.toSortedMap()
    }
    fun authored(before:Map<String,JSONObject>,after:Map<String,JSONObject>,origin:String):JSONArray{
        fun semantic(row:JSONObject?):String?{row?:return null;val values=JSONArray(row.getJSONArray("row").toString());if(row.getInt("table")==0)values.put(4,0);return values.toString()}
        val affected=(before.keys+after.keys).filter{semantic(before[it])!=semantic(after[it])}.mapNotNull{group(after[it]?:before.getValue(it))}.distinct()
        return JSONArray(affected.map{key->
            val existing=heads(key);require(existing.size<=1){"SHADOW_RESOLUTION_REQUIRED"}
            JSONObject().put("key",key).put("revision",UUID.randomUUID().toString()).put("parents",JSONArray(existing.map{it.getString("revision")})).put("origin",origin)
                .put("members",JSONArray(after.values.filter{group(it)==key}.map{JSONObject().put("table",it.getInt("table")).put("key",it.getString("key")).put("row",it.getJSONArray("row"))}))
        })
    }
    fun merge(bundles:JSONArray){
        require(bundles.length()<=2048)
        repeat(bundles.length()){i->val b=bundles.getJSONObject(i);val key=b.getString("key");val revision=b.getString("revision");UUID.fromString(revision)
            require(b.keys().asSequence().toSet()==setOf("key","revision","parents","origin","members"))
            require(key.substringBefore(':') in setOf("笔记","知识卡","页面"));UUID.fromString(key.substringAfter(':'))
            val members=b.getJSONArray("members");require(members.length()<=2048);val seen=mutableSetOf<String>()
            repeat(members.length()){j->val r=members.getJSONObject(j);require(r.keys().asSequence().toSet()==setOf("key","table","row"));val t=r.getInt("table");require(t in schema.indices)
                val row=r.getJSONArray("row");require(row.length()==schema[t].columns.size&&group(r)==key)
                val identity="$t:"+hash(JSONArray(schema[t].keys.map{k->row.get(schema[t].columns.indexOfFirst{it.name==k})}).toString())
                require(identity==r.getString("key")&&seen.add(identity)){"SHADOW_BUNDLE_IDENTITY"}
            }
            val existing=heads(key);val active=selected(key);val parents=b.getJSONArray("parents");require(parents.length()<=32)
            require(b.getString("origin") in setOf("human","derived"))
            repeat(parents.length()){j->val parent=parents.getString(j);UUID.fromString(parent)
                if(!(b.getString("origin")=="derived"&&existing.any{it.getString("revision")==parent&&it.getString("origin")=="human"}))
                    sql.execSQL("DELETE FROM shadow_bundles WHERE entity=? AND revision=?",arrayOf(key,parent))
            }
            sql.execSQL("INSERT INTO shadow_bundles VALUES (?,?,?)",arrayOf(key,revision,b.toString()))
            val next=heads(key);require(next.size<=32);val humans=next.filter{it.getString("origin")=="human"};val choices=humans.ifEmpty{next}
            val chosen=choices.find{it.getString("revision")==active}?:choices.first()
            sql.execSQL("INSERT OR REPLACE INTO shadow_bundle_selected VALUES (?,?)",arrayOf(key,chosen.getString("revision")))
        }
    }
    private fun frozen(key:String)=hash(JSONArray(heads(key).map{it.toString()}).toString())
    private fun label(b:JSONObject):Pair<String,String>{
        val members=b.getJSONArray("members");val rows=List(members.length()){members.getJSONObject(it)}
        val title=rows.find{it.getInt("table") in setOf(0,13)}?.let{r->val t=schema[r.getInt("table")];r.getJSONArray("row").getString(t.columns.indexOfFirst{it.name=="title"})}
        val body=rows.find{it.getInt("table")==13}?.getJSONArray("row")?.getString(4)
        return (title?:"页面内容分支") to (body?.take(160)?:"${rows.count{it.getInt("table")==6}} 段笔迹 · ${rows.count{it.getInt("table")==22}} 份页面对象")
    }
    fun conflicts()=keys().mapNotNull{key->val h=heads(key);if(h.size<2)null else ShadowConflict(key,label(h.first()).first,key.substringBefore(':'),frozen(key),h.map{b->val (title,detail)=label(b);ShadowChoice(b.getString("revision"),title,detail,b.getString("revision")==selected(key))})}
    fun resolve(key:String,frozen:String,revision:String):JSONObject{
        require(frozen(key)==frozen){"SHADOW_CONFLICT_CHANGED"}
        val h=heads(key);require(h.size>1)
        return JSONObject(h.single{it.getString("revision")==revision}.toString()).put("revision",UUID.randomUUID().toString()).put("origin","human").put("parents",JSONArray(h.map{it.getString("revision")}))
    }
}
