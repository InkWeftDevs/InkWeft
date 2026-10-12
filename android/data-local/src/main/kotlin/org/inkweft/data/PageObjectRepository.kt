// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.inkweft.core.*
import java.security.MessageDigest
import java.util.UUID

data class ObjectSnapshot(val revision:Long=0,val objects:List<PageObject> = emptyList())

/** Page-scoped CAS and durable replay receipts, including writes whose outcome was unknown. */
class PageObjectRepository(private val db:NoteDatabase,private val afterCommit:()->Unit={}) {
    private val previews=db.validatedImagePreviews
    suspend fun read(id:String):ObjectSnapshot {
        require(db.pages().get(id)!=null)
        return PageObjectStore(db).read(id)
    }
    suspend fun originals(id:String,objects:List<PageObject>)=ImageSourceRepository(db).forPage(id,objects)
    suspend fun originalFile(source:ImageSource)=ImageSourceRepository(db).openFile(source)
    suspend fun originalSize(pageId:String,hash:String):Int? {
        require(ImageSource.validHash(hash))
        val page=db.pages().get(pageId)?:return null
        return db.images().source(page.notebookId,hash)?.byteCount?.also{require(it in 1..ImageSource.MAX_BYTES)}
    }
    fun observe(id:String)=db.objects().observeRevision(id).distinctUntilChanged().map{read(id)}.flowOn(Dispatchers.IO)
    fun observePreview(id:String)=observe(id).map{it.objects}
    suspend fun save(pageId:String,expected:Long,command:String,objects:List<PageObject>,expectedInk:Long?=null,originals:List<ImageSource> = emptyList(),layerScope:LayerWriteScope?=null):Long {
        UUID.fromString(command);require(expected>=0)
        val bytes=PageObjectCodec.encode(objects.toList())
        val content=PageObjectCodec.decode(bytes)
        val prepared=PageObjectStore.prepare(pageId,content)
        val digest=MessageDigest.getInstance("SHA-256").digest((pageId+":"+expected+":"+expectedInk+":"+(layerScope?.let{"layers-v1:${it.layerId}:${it.configurationRevision}:"}?:"")).toByteArray()+bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
        if(content.any{it.kind==PageObjectKind.IMAGE})withContext(Dispatchers.Default){previews.validate(content){validateImages(listOf(it))}}
        val next=db.withTransaction {
            db.objects().receipt(command)?.let{require(it.pageId==pageId&&it.digest==digest){"OBJECT_COMMAND_REUSED"};return@withTransaction it.revision}
            val owner=checkNotNull(db.pages().get(pageId))
            require(owner.trashedAt==null&&db.workspace().get(owner.notebookId)?.trashedAt==null)
            require((db.objects().get(pageId)?.revision?:0)==expected){"OBJECT_CONFLICT"}
            if(expectedInk!=null)require((db.ink().page(pageId)?.revision?:0)==expectedInk){"INK_CONFLICT"}
            val authoring=PageAuthoringRepository(db).acceptObjects(pageId,content,layerScope,prepared.records)
            MapEmbedRepository(db).validateReferences(owner.notebookId,content)
            val images=ImageSourceRepository(db)
            require(originals.all{source->content.any{it.imageSource==source.sha256}})
            images.attach(owner.notebookId,originals);images.validateReferences(owner.notebookId,content)
            validateSources(pageId,content)
            validateBounds(content,owner.world)
            db.pages().invalidateSearch(pageId)
            PageObjectStore(db).write(pageId,expected+1,prepared)
            authoring?.let{db.authoring().put(it)}
            db.objects().record(ObjectReceiptRow(command,pageId,digest,expected+1))
            db.notes().touch(owner.notebookId,System.currentTimeMillis());expected+1
        }
        afterCommit();return next
    }
    private suspend fun validateSources(pageId:String,objects:List<PageObject>){
        val refs=objects.flatMap{it.sourceStrokeIds};require(refs.distinct().size==refs.size)
        for(batch in refs.chunked(500)){
            val count=db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ink_strokes WHERE noteId=? AND id IN (${batch.joinToString(","){"?"}})",(listOf(pageId)+batch).toTypedArray()).use{check(it.moveToFirst());it.getInt(0)}
            require(count==batch.size)
        }
    }
    internal suspend fun import(pageId:String,objects:List<PageObject>,strokeIds:Map<String,String> = emptyMap(),originals:List<ImageSource> = emptyList(),validated:ValidatedImportOriginals?=null):Map<String,String> {
        if(objects.isEmpty())return emptyMap()
        require(db.objects().get(pageId)==null)
        val page=checkNotNull(db.pages().get(pageId))
        ImageSourceRepository(db).attach(page.notebookId,originals,validated)
        ImageSourceRepository(db).validateReferences(page.notebookId,objects)
        validateBounds(objects,page.world);previews.validate(objects){validateImages(listOf(it))}
        val mapped=objects.map{it.copy(id=UUID.randomUUID().toString(),sourceStrokeIds=it.sourceStrokeIds.mapNotNull(strokeIds::get),textRuns=it.textRuns.map{r->r.copy(sourceIds=r.sourceIds.mapNotNull(strokeIds::get))})}
        MapEmbedRepository(db).validateReferences(checkNotNull(db.pages().get(pageId)).notebookId,mapped)
        validateSources(pageId,mapped)
        PageObjectCodec.encode(mapped)
        PageObjectStore(db).write(pageId,1,PageObjectStore.prepare(pageId,mapped))
        return objects.zip(mapped).associate{it.first.id to it.second.id}
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
