// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.graphics.BitmapFactory
import androidx.room.*
import org.inkweft.core.*

@Entity(tableName="notebook_covers",foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"])])
data class NotebookCoverRow(@PrimaryKey val noteId:String,val payload:ByteArray)
@Dao interface NotebookCoverDao {
    @Query("SELECT * FROM notebook_covers WHERE noteId=:id") suspend fun get(id:String):NotebookCoverRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:NotebookCoverRow)
    @Query("SELECT COALESCE(SUM(length(payload)),0) FROM notebook_covers WHERE noteId!=:id") suspend fun otherBytes(id:String):Long
}
internal fun validateCoverPayload(bytes:ByteArray){
    val c=CustomCoverCodec.decode(bytes)
    if(c.image.isNotEmpty()){
        val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(c.image,0,c.image.size,o)
        require(o.outMimeType=="image/jpeg"&&o.outWidth in 1..1024&&o.outHeight in 1..1024){"COVER_IMAGE_INVALID"}
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(c.image,0,c.image.size)){"COVER_IMAGE_INVALID"};bitmap.recycle()
    }
}
