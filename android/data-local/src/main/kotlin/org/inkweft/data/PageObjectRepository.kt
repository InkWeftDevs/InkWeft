// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName="page_objects",foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class PageObjectRow(@PrimaryKey val pageId:String,val revision:Long,val payload:ByteArray)
@Entity(tableName="object_receipts",indices=[Index("pageId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class ObjectReceiptRow(@PrimaryKey val commandId:String,val pageId:String,val digest:String,val revision:Long)
@Dao interface PageObjectDao {
    @Query("SELECT * FROM page_objects WHERE pageId=:id") suspend fun get(id:String):PageObjectRow?
    @Query("SELECT * FROM page_objects WHERE pageId=:id") fun observe(id:String):Flow<PageObjectRow?>
    @Query("SELECT * FROM object_receipts WHERE commandId=:id") suspend fun receipt(id:String):ObjectReceiptRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:PageObjectRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(row:ObjectReceiptRow)
}
data class ObjectSnapshot(val revision:Long=0,val objects:List<PageObject> = emptyList())

/** Page-scoped CAS and durable replay receipts, including writes whose outcome was unknown. */
class PageObjectRepository(private val db:NoteDatabase,private val afterCommit:()->Unit={}) {
    suspend fun read(id:String):ObjectSnapshot {
        require(db.pages().get(id)!=null)
        return db.objects().get(id)?.let{require(it.revision>0);ObjectSnapshot(it.revision,PageObjectCodec.decode(it.payload))}?:ObjectSnapshot()
    }
    fun observe(id:String)=db.objects().observe(id)
    suspend fun save(pageId:String,expected:Long,command:String,objects:List<PageObject>,expectedInk:Long?=null):Long {
        UUID.fromString(command);require(expected>=0)
        val bytes=PageObjectCodec.encode(objects)
        val digest=MessageDigest.getInstance("SHA-256").digest((pageId+":"+expected+":"+expectedInk+":").toByteArray()+bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
        val next=db.withTransaction {
            db.objects().receipt(command)?.let{require(it.pageId==pageId&&it.digest==digest){"OBJECT_COMMAND_REUSED"};return@withTransaction it.revision}
            val owner=checkNotNull(db.pages().get(pageId))
            require(owner.trashedAt==null&&db.workspace().get(owner.notebookId)?.trashedAt==null)
            require((db.objects().get(pageId)?.revision?:0)==expected){"OBJECT_CONFLICT"}
            if(expectedInk!=null)require((db.ink().page(pageId)?.revision?:0)==expectedInk){"INK_CONFLICT"}
            validateSources(pageId,objects)
            validateBounds(objects,owner.world);validateImages(objects)
            db.pages().invalidateSearch(pageId)
            db.objects().put(PageObjectRow(pageId,expected+1,bytes))
            db.objects().record(ObjectReceiptRow(command,pageId,digest,expected+1))
            db.notes().touch(owner.notebookId,System.currentTimeMillis());expected+1
        }
        afterCommit();return next
    }
    private suspend fun validateSources(pageId:String,objects:List<PageObject>){
        val refs=objects.flatMap{it.sourceStrokeIds};require(refs.distinct().size==refs.size)
        if(refs.isNotEmpty()){val owned=db.ink().strokes(pageId).map{it.id}.toSet();require(refs.all{it in owned})}
    }
    internal suspend fun import(pageId:String,objects:List<PageObject>,strokeIds:Map<String,String> = emptyMap()) {
        if(objects.isEmpty())return
        require(db.objects().get(pageId)==null)
        validateBounds(objects,checkNotNull(db.pages().get(pageId)).world);validateImages(objects)
        val mapped=objects.map{it.copy(id=UUID.randomUUID().toString(),sourceStrokeIds=it.sourceStrokeIds.mapNotNull(strokeIds::get))}
        validateSources(pageId,mapped)
        db.objects().put(PageObjectRow(pageId,1,PageObjectCodec.encode(mapped)))
    }
    companion object {
        fun validateImages(objects:List<PageObject>) {
            objects.filter{it.kind==PageObjectKind.IMAGE}.distinctBy{it.image}.forEach { o ->
                val bytes=java.util.Base64.getDecoder().decode(o.image)
                val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                require(opts.outWidth in 1..1024&&opts.outHeight in 1..1024){"OBJECT_IMAGE_SIZE"}
                checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)).recycle()
            }
        }
        fun validateBounds(objects:List<PageObject>,world:Boolean) {
            if(!world)require(objects.all{it.x>=0&&it.y>=0&&it.x+it.width<=1000.01f&&it.y+it.height<=1414.01f}){"OBJECT_OUTSIDE_PAGE"}
        }
    }
}
