// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.graphics.ImageDecoder
import androidx.room.*
import org.inkweft.core.*
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Entity(tableName="image_sources",primaryKeys=["notebookId","digest"],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"],onDelete=ForeignKey.NO_ACTION)])
data class ImageSourceRow(val notebookId:String,val digest:String,val byteCount:Int)
@Entity(tableName="image_chunks",primaryKeys=["notebookId","digest","position"],foreignKeys=[ForeignKey(entity=ImageSourceRow::class,parentColumns=["notebookId","digest"],childColumns=["notebookId","digest"],onDelete=ForeignKey.NO_ACTION)])
data class ImageChunkRow(val notebookId:String,val digest:String,val position:Int,val payload:ByteArray)
@Dao interface ImageSourceDao {
    @Query("SELECT * FROM image_sources WHERE notebookId=:book AND digest=:hash") suspend fun source(book:String,hash:String):ImageSourceRow?
    @Query("SELECT * FROM image_sources ORDER BY notebookId,digest") suspend fun all():List<ImageSourceRow>
    @Query("SELECT * FROM image_chunks WHERE notebookId=:book AND digest=:hash ORDER BY position") suspend fun chunks(book:String,hash:String):List<ImageChunkRow>
    @Query("SELECT COUNT(*) FROM image_chunks WHERE notebookId=:book AND digest=:hash") suspend fun chunkCount(book:String,hash:String):Int
    @Query("SELECT COALESCE(SUM(byteCount),0) FROM image_sources") suspend fun totalBytes():Long
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:ImageSourceRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:ImageChunkRow)
}

/** Writes participate in their caller's object/import transaction; no detached file can be lost. */
class ImageSourceRepository(private val db:NoteDatabase) {
    suspend fun read(book:String,hash:String):ImageSource {
        require(ImageSource.validHash(hash))
        val row=requireNotNull(db.images().source(book,hash)){"IMAGE_ORIGINAL_MISSING"}
        require(row.byteCount in 1..ImageSource.MAX_BYTES)
        require(db.images().chunkCount(book,hash)==(row.byteCount+CHUNK-1)/CHUNK){"IMAGE_CHUNKS_INVALID"}
        return ImageSource.fromStream(OriginalBytes.stream(row.byteCount,hash){OriginalChunkInput(row.byteCount,CHUNK){position->
            db.openHelper.readableDatabase.query("SELECT payload FROM image_chunks WHERE notebookId=? AND digest=? AND position=?",arrayOf<Any>(book,hash,position)).use{c->if(c.moveToFirst())c.getBlob(0)else null}
        }})
    }
    suspend fun forPage(pageId:String,objects:List<PageObject>):List<ImageSource> {
        val book=requireNotNull(db.pages().get(pageId)).notebookId
        return objects.mapNotNull{it.imageSource}.distinct().map{read(book,it)}
    }
    internal suspend fun attach(book:String,sources:List<ImageSource>) {
        for(source in sources.distinctBy{it.sha256}){
            if(db.images().source(book,source.sha256)!=null){require(read(book,source.sha256).size==source.size);continue}
            val context=currentCoroutineContext()
            require(checkNotNull(db.documentScratch).usableSpace>=source.size.toLong()*2+32L*1024*1024){"ORIGINAL_LOW_SPACE"}
            validate(source,checkNotNull(db.documentScratch)){context.ensureActive()}
            db.images().insert(ImageSourceRow(book,source.sha256,source.size))
            storeOriginal(source.size,source.sha256,CHUNK,source::openStream){position,bytes->db.images().insert(ImageChunkRow(book,source.sha256,position,bytes))}
        }
    }
    suspend fun openFile(source:ImageSource):OriginalFileCache.Lease=
        db.originalFiles.acquire(source.size,source.sha256){out,active->source.copyTo(out,active)}
    internal suspend fun validateReferences(book:String,objects:List<PageObject>) {
        objects.mapNotNull{it.imageSource}.distinct().forEach{require(db.images().source(book,it)!=null){"IMAGE_ORIGINAL_MISSING"}}
    }
    companion object {
        const val CHUNK=512_000
        /** Decode a bounded sample to verify the format, respecting EXIF without rewriting the original. */
        fun validate(source:ImageSource,directory:File,checkActive:()->Unit={}) {
            val file=File.createTempFile("inkweft-image-validate-",".original",directory)
            try{file.outputStream().use{source.copyTo(it,checkActive)};checkActive()
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){decoder,info,_->
                require(info.mimeType in setOf("image/jpeg","image/png","image/webp","image/heif","image/heic")){"IMAGE_FORMAT_UNSUPPORTED"}
                require(!info.isAnimated){"IMAGE_ANIMATION_UNSUPPORTED"}
                val w=info.size.width;val h=info.size.height
                require(w in 1..20000&&h in 1..20000&&w.toLong()*h<=100_000_000){"IMAGE_DIMENSIONS_INVALID"}
                val scale=minOf(1.0,256.0/maxOf(w,h));decoder.setTargetSize(maxOf(1,(w*scale).toInt()),maxOf(1,(h*scale).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap.recycle()
            }finally{file.delete()}
        }
    }
}
