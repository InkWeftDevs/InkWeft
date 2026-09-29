// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.json.*
import java.io.File
import java.util.UUID
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Disposable real-format laboratory. No constructor accepts an existing author database. */
class ShadowReplica private constructor(private val context:Context,val directory:File,val db:NoteDatabase,
    private val scope:List<String>,private val device:String,private val keys:Map<Int,ByteArray>,private val version:Int,
    private val fault:(String)->Unit):java.io.Closeable {
    private val sql get()=db.openHelper.writableDatabase
    private val schema=LibraryBackupRepository.SCHEMA
    private val schemaHash=ContentTransfer.hash(schema.joinToString(";"){t->t.name+":"+t.columns.joinToString(","){c->"${c.name}:${c.kind}:${c.nullable}"}}.toByteArray())
    private fun id()=UUID.randomUUID().toString()
    private fun hash(text:String)=ContentTransfer.hash(text.toByteArray())
    private fun rows():Map<String,JSONObject>{
        val result=linkedMapOf<String,JSONObject>()
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB 'shadow_*' AND name NOT IN ('room_master_table','android_metadata')").use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        require(tables==schema.map{it.name}.toSet()){"SHADOW_SCHEMA"}
        for(t in schema){val columns=sql.query("PRAGMA table_info(`${t.name}`)").use{c->buildList{while(c.moveToNext())add(c.getString(1))}};require(columns==t.columns.map{it.name}){"SHADOW_COLUMNS"}}

        schema.forEachIndexed{index,t->sql.query("SELECT * FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{c->while(c.moveToNext()){
            val row=JSONArray();t.columns.forEachIndexed{i,col->row.put(if(c.isNull(i))JSONObject.NULL else when(col.kind){'S'->c.getString(i);'I'->c.getLong(i);'F'->c.getDouble(i);'B'->Base64.getEncoder().encodeToString(c.getBlob(i));else->error("SHADOW_TYPE")})}
            val key=index.toString()+":"+hash(JSONArray(t.keys.map{row.get(t.columns.indexOfFirst{c->c.name==it})}).toString())
            result[key]=JSONObject().put("table",index).put("key",key).put("row",row)
        }}}
        require(result.size<=2048&&result.values.sumOf{it.toString().toByteArray().size}<=8*1024*1024){"SHADOW_SAMPLE_BUDGET"}
        return result
    }
    private fun heads(key:String):List<JSONObject> = sql.query("SELECT value FROM shadow_versions WHERE entity=? ORDER BY revision",arrayOf(key)).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}
    private val bundles by lazy{ShadowBundles(sql,schema)}
    private fun chosen():Map<String,JSONObject> = bundles.applyTo(sql.query("SELECT entity,revision FROM shadow_selected").use{c->buildMap{while(c.moveToNext()){
        val h=heads(c.getString(0)).single{it.getString("revision")==c.getString(1)};if(!h.getBoolean("deleted"))put(c.getString(0),h)
    }}}).mapValues{(key,value)->
        // Activity timestamps are metadata, not a competing notebook title/body version.
        if(value.getInt("table")!=0)value else JSONObject(value.toString()).also{row->
            heads(key).filter{!it.getBoolean("deleted")}.maxOfOrNull{it.getJSONArray("row").getLong(4)}?.let{row.getJSONArray("row").put(4,it)}
        }
    }
    private fun guardAuthorState(){
        val actual=rows().mapValues{it.value.getJSONArray("row").toString()}
        val known=chosen().mapValues{it.value.getJSONArray("row").toString()}
        require(actual==known){"SHADOW_UNJOURNALED_WRITE"}
    }
    private fun merge(changes:JSONArray){
        require(changes.length() in 0..2048)
        repeat(changes.length()){i->val c=changes.getJSONObject(i)
            require(c.keys().asSequence().toSet()==setOf("table","key","row","revision","parents","origin","deleted")){"SHADOW_FIELDS"}
            val t=c.getInt("table");require(t in schema.indices);val key=c.getString("key");val rev=c.getString("revision");UUID.fromString(rev)
            require(c.getString("origin") in listOf("human","derived"))
            val row=c.getJSONArray("row");require(row.length()==schema[t].columns.size)
            val expected=t.toString()+":"+hash(JSONArray(schema[t].keys.map{row.get(schema[t].columns.indexOfFirst{col->col.name==it})}).toString())
            require(key==expected){"SHADOW_IDENTITY"}
            val existing=heads(key);val selected=sql.query("SELECT revision FROM shadow_selected WHERE entity=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else null}
            val parents=c.getJSONArray("parents");require(parents.length()<=32)
            repeat(parents.length()){k->val parent=parents.getString(k);UUID.fromString(parent)
                if(!(c.getString("origin")=="derived"&&existing.any{it.getString("revision")==parent&&it.getString("origin")=="human"}))
                    sql.execSQL("DELETE FROM shadow_versions WHERE entity=? AND revision=?",arrayOf(key,parent))
            }
            sql.execSQL("INSERT INTO shadow_versions VALUES (?,?,?)",arrayOf(key,rev,c.toString()))
            val remaining=heads(key);require(remaining.size<=32){"SHADOW_CONFLICT_BUDGET"}
            val human=remaining.filter{it.getString("origin")=="human"};val candidates=human.ifEmpty{remaining}
            val active=candidates.find{it.getString("revision")==selected}?:candidates.first()
            sql.execSQL("INSERT OR REPLACE INTO shadow_selected VALUES (?,?)",arrayOf(key,active.getString("revision")))
        }
    }
    private suspend fun materialize(){
        val records=chosen();require(records.size<=2048)
        sql.execSQL("PRAGMA defer_foreign_keys=ON")
        schema.asReversed().forEach{sql.execSQL("DELETE FROM `${it.name}`")}
        for(index in schema.indices){val table=schema[index]
            for(c in records.values.filter{it.getInt("table")==index}){
                val row=c.getJSONArray("row");val values=table.columns.mapIndexed{i,col->
                    if(row.isNull(i)){require(col.nullable);null}else when(col.kind){
                        'S'->row.getString(i)
                        'I'->{require(row.get(i) is Long||row.get(i) is Int);row.getLong(i)}
                        'F'->row.getDouble(i).also{require(it.isFinite())}
                        'B'->Base64.getDecoder().decode(row.getString(i)).also{require(it.size<=1_048_576)}
                        else->error("SHADOW_TYPE")
                    }
                }.toTypedArray()
                sql.execSQL("INSERT INTO `${table.name}` VALUES ("+values.joinToString(","){"?"}+")",values)
            }
        }
        LibraryBackupRepository(context,db).validate(db)
    }
    /** Formal repositories run inside the same SQLite transaction as the durable outbox. */
    suspend fun author(origin:String="human",action:suspend (NoteDatabase)->Unit):String?=withContext(Dispatchers.IO){db.withTransaction{
        require(origin in listOf("human","derived"));guardAuthorState();val before=chosen();action(db);LibraryBackupRepository(context,db).validate(db)
        val after=rows();val changes=JSONArray()
        for(key in (before.keys+after.keys).sorted()){
            val old=before[key];val next=after[key]
            if(old?.optJSONArray("row")?.toString()==next?.optJSONArray("row")?.toString())continue
            val c=JSONObject((next?:old!!).toString());c.put("revision",id()).put("parents",JSONArray(heads(key).map{it.getString("revision")})).put("origin",origin).put("deleted",next==null)
            changes.put(c)
        }
        if(changes.length()==0)return@withTransaction null
        val grouped=bundles.authored(before,after,origin)
        val envelope=seal(changes,grouped);merge(changes);bundles.merge(grouped);materialize()
        sql.execSQL("INSERT INTO shadow_outbox VALUES (?,?)",arrayOf(envelope.getString("operation"),envelope.toString()))
        fault("before-author-commit");envelope.getString("operation")
    }}
    private fun header(e:JSONObject)=JSONObject().put("schema",e.getString("schema")).put("format",e.getString("format")).put("scope",e.getJSONArray("scope")).put("device",e.getString("device")).put("operation",e.getString("operation")).put("key_version",e.getInt("key_version"))
    private fun seal(changes:JSONArray,grouped:JSONArray=JSONArray()):JSONObject {
        val h=JSONObject().put("schema",schemaHash).put("format","inkweft.shadow-rows.v2").put("scope",JSONArray(scope)).put("device",device).put("operation",id()).put("key_version",version)
        val nonce=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)}
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(checkNotNull(keys[version]),"AES"),GCMParameterSpec(128,nonce));c.updateAAD(h.toString().toByteArray())
        val body=c.doFinal(JSONObject().put("changes",changes).put("bundles",grouped).toString().toByteArray())
        h.put("nonce",Base64.getEncoder().encodeToString(nonce)).put("ciphertext",Base64.getEncoder().encodeToString(body))
        require(h.toString().toByteArray().size<=1_048_576){"SHADOW_BATCH_BUDGET"};return h
    }
    suspend fun receive(cursor:Long,envelopes:List<String>)=withContext(Dispatchers.IO){
        require(envelopes.size<=16&&envelopes.all{it.toByteArray().size<=1_048_576})
        val batch=JSONArray(envelopes).toString()
        fault("before-inbox")
        db.withTransaction{
            sql.query("SELECT cursor,batch FROM shadow_inbox").use{c->
                if(c.moveToFirst())require(c.getLong(0)==cursor&&c.getString(1)==batch){"SHADOW_INBOX_PENDING"}
                else sql.execSQL("INSERT INTO shadow_inbox VALUES (?,?)",arrayOf<Any>(cursor,batch))
            }
        }
        fault("after-inbox")
        db.withTransaction{
        guardAuthorState();require(cursor>=cursor());require(envelopes.size<=16);if(envelopes.isEmpty())require(cursor==cursor())
        for(text in envelopes){require(text.toByteArray().size<=1_048_576);val e=JSONObject(text)
            require(e.keys().asSequence().toSet()==setOf("schema","format","scope","device","operation","key_version","nonce","ciphertext"))
            require(e.getString("schema")==schemaHash&&e.getString("format")=="inkweft.shadow-rows.v2"&&e.getJSONArray("scope").toString()==JSONArray(scope).toString()){"SHADOW_SCOPE"}
            UUID.fromString(e.getString("device"));val op=e.getString("operation");UUID.fromString(op);val digest=hash(text)
            val previous=sql.query("SELECT digest FROM shadow_applied WHERE operation=?",arrayOf(op)).use{if(it.moveToFirst())it.getString(0)else null}
            if(previous!=null){require(previous==digest);continue}
            val key=keys[e.getInt("key_version")]?:error("SHADOW_KEY_REQUIRED")
            val nonce=Base64.getDecoder().decode(e.getString("nonce"));require(nonce.size==12)
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(header(e).toString().toByteArray())
            val clear=c.doFinal(Base64.getDecoder().decode(e.getString("ciphertext")))
            // Locally authored operations already updated heads in the author transaction.
            val own=sql.query("SELECT 1 FROM shadow_outbox WHERE operation=?",arrayOf(op)).use{it.moveToFirst()}
            val payload=JSONObject(clear.toString(Charsets.UTF_8))
            require(payload.keys().asSequence().toSet()==setOf("changes","bundles"))
            fault("after-parse")
            if(!own){merge(payload.getJSONArray("changes"));bundles.merge(payload.getJSONArray("bundles"))}
            sql.execSQL("INSERT INTO shadow_applied VALUES (?,?)",arrayOf(op,digest))
        }
        fault("before-author-apply");materialize();fault("before-receive-commit")
        sql.execSQL("UPDATE shadow_meta SET cursor=?",arrayOf(cursor))
        sql.execSQL("DELETE FROM shadow_inbox")
        };fault("after-receive-commit")
    }
    suspend fun recoverInbox(){
        val pending=sql.query("SELECT cursor,batch FROM shadow_inbox").use{if(it.moveToFirst())it.getLong(0) to JSONArray(it.getString(1))else null}
        pending?.let{(cursor,a)->receive(cursor,List(a.length()){a.getString(it)})}
    }
    /** Explicit rejection only: relay cursor and unsent author changes remain intact. */
    suspend fun discardRejectedInbox()=db.withTransaction{sql.execSQL("DELETE FROM shadow_inbox")}
    suspend fun semanticConflicts()=withContext(Dispatchers.IO){db.withTransaction{bundles.conflicts()}}
    suspend fun resolve(conflict:ShadowConflict,revision:String,operation:String):String=withContext(Dispatchers.IO){db.withTransaction{
        UUID.fromString(operation)
        val digest=hash(conflict.key+":"+conflict.frozen+":"+revision)
        sql.query("SELECT digest,envelope FROM shadow_resolutions WHERE operation=?",arrayOf(operation)).use{c->if(c.moveToFirst()){require(c.getString(0)==digest);return@withTransaction c.getString(1)}}
        guardAuthorState();val resolution=bundles.resolve(conflict.key,conflict.frozen,revision);val grouped=JSONArray().put(resolution)
        val envelope=seal(JSONArray(),grouped);bundles.merge(grouped);materialize()
        sql.execSQL("INSERT INTO shadow_outbox VALUES (?,?)",arrayOf(envelope.getString("operation"),envelope.toString()))
        sql.execSQL("INSERT INTO shadow_resolutions VALUES (?,?,?)",arrayOf(operation,digest,envelope.getString("operation")))
        fault("before-resolution-commit");envelope.getString("operation")
    }}
    fun cursor()=sql.query("SELECT cursor FROM shadow_meta").use{it.moveToFirst();it.getLong(0)}
    fun outgoing():List<String> = sql.query("SELECT envelope FROM shadow_outbox ORDER BY rowid").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    suspend fun acknowledged(operation:String){fault("before-outbox-ack");db.withTransaction{require(sql.query("SELECT 1 FROM shadow_applied WHERE operation=?",arrayOf(operation)).use{it.moveToFirst()}){"SHADOW_PULL_BEFORE_ACK"};sql.execSQL("DELETE FROM shadow_outbox WHERE operation=?",arrayOf(operation))};fault("after-outbox-ack")}
    fun unsupportedConflicts():Int=sql.query("SELECT entity FROM shadow_versions GROUP BY entity HAVING COUNT(*)>1").use{c->var count=0;while(c.moveToNext())if(bundles.group(heads(c.getString(0)).first())==null)count++;count}
    fun conflicts():Int=unsupportedConflicts()+bundles.conflicts().size
    suspend fun authorFingerprint():String=withContext(Dispatchers.IO){db.withTransaction{hash(JSONArray(rows().values.toList()).toString())}}
    override fun close()=db.close()
    companion object {
        fun open(context:Context,scope:List<String>,device:String,keys:Map<Int,ByteArray>,version:Int=1,directory:File?=null,fault:(String)->Unit={}):ShadowReplica {
            require(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0){"SHADOW_TEST_ONLY"}
            require(scope.size==4&&scope.all{it.isNotBlank()&&it.length<=512});UUID.fromString(device);require(keys.values.all{it.size==32})
            val root=File(context.cacheDir,"shadow-sync").apply{mkdirs()}.canonicalFile
            val target=(directory?:File(root,UUID.randomUUID().toString())).canonicalFile
            require(target.parentFile==root);UUID.fromString(target.name);target.mkdirs()
            val db=NoteDatabase.open(context,File(target,"replica.db").absolutePath);val sql=db.openHelper.writableDatabase
            try{
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_meta (binding TEXT NOT NULL, cursor INTEGER NOT NULL)")
            val binding=JSONArray(scope+device).toString()
            sql.query("SELECT binding FROM shadow_meta").use{if(it.moveToFirst())require(it.getString(0)==binding)else sql.execSQL("INSERT INTO shadow_meta VALUES (?,0)",arrayOf(binding))}
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_versions (entity TEXT NOT NULL, revision TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(entity,revision))")
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_selected (entity TEXT PRIMARY KEY, revision TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_outbox (operation TEXT PRIMARY KEY, envelope TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_applied (operation TEXT PRIMARY KEY, digest TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_inbox (cursor INTEGER NOT NULL,batch TEXT NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS shadow_resolutions (operation TEXT PRIMARY KEY,digest TEXT NOT NULL,envelope TEXT NOT NULL)")
            return ShadowReplica(context,target,db,scope,device,keys,version,fault).also{it.bundles}
            }catch(t:Throwable){db.close();throw t}
        }
    }
}
