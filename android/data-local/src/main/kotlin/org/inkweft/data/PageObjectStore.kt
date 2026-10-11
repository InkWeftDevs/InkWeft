// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.util.Base64

@Entity(tableName="page_objects",foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class PageObjectRow(@PrimaryKey val pageId:String,val revision:Long)
@Entity(tableName="object_previews")
data class ObjectPreviewRow(@PrimaryKey val digest:String,val payload:ByteArray)
@Entity(tableName="page_object_records",primaryKeys=["pageId","id"],indices=[Index("previewHash")],foreignKeys=[
    ForeignKey(entity=PageObjectRow::class,parentColumns=["pageId"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION),
    ForeignKey(entity=ObjectPreviewRow::class,parentColumns=["digest"],childColumns=["previewHash"],onDelete=ForeignKey.NO_ACTION)])
data class PageObjectRecordRow(val pageId:String,val id:String,val position:Int,val payload:ByteArray,val previewHash:String?)
@Entity(tableName="object_receipts",indices=[Index("pageId")],foreignKeys=[ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class ObjectReceiptRow(@PrimaryKey val commandId:String,val pageId:String,val digest:String,val revision:Long)

@Dao interface PageObjectDao {
    @Query("SELECT * FROM page_objects WHERE pageId=:id") suspend fun get(id:String):PageObjectRow?
    @Query("SELECT revision FROM page_objects WHERE pageId=:id") fun observeRevision(id:String):Flow<Long?>
    @Query("SELECT * FROM page_object_records WHERE pageId=:id ORDER BY position,id") suspend fun records(id:String):List<PageObjectRecordRow>
    @Query("SELECT id FROM page_object_records WHERE pageId=:id ORDER BY position,id") suspend fun ids(id:String):List<String>
    @Query("SELECT * FROM object_previews WHERE digest=:hash") suspend fun preview(hash:String):ObjectPreviewRow?
    @Query("SELECT * FROM object_previews WHERE digest IN (:hashes)") suspend fun previews(hashes:List<String>):List<ObjectPreviewRow>
    @Query("SELECT EXISTS(SELECT 1 FROM object_previews WHERE digest=:hash)") suspend fun hasPreview(hash:String):Boolean
    @Query("SELECT * FROM object_receipts WHERE commandId=:id") suspend fun receipt(id:String):ObjectReceiptRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(row:PageObjectRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRecord(row:PageObjectRecordRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun putPreview(row:ObjectPreviewRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(row:ObjectReceiptRow)
    @Query("DELETE FROM page_object_records WHERE pageId=:page AND id=:id") suspend fun remove(page:String,id:String)
    @Query("DELETE FROM object_previews WHERE digest=:hash AND NOT EXISTS (SELECT 1 FROM page_object_records WHERE previewHash=:hash)") suspend fun removeUnusedPreview(hash:String)
}

/** All headers, ordered records and immutable previews belong to one committed page. */
internal class PageObjectStore(private val db:NoteDatabase) {
    private data class Frozen(val head:PageObjectRow,val records:List<PageObjectRecordRow>,val previews:List<ObjectPreviewRow>)
    data class Prepared(val records:List<PageObjectRecordRow>,val previews:Map<String,ByteArray>)
    suspend fun read(page:String):ObjectSnapshot {
        val frozen=db.withTransaction {
            val dao=db.objects();val head=dao.get(page)?:return@withTransaction null
            val records=dao.records(page)
            Frozen(head,records,dao.previews(records.mapNotNull{it.previewHash}.distinct()))
        }?:return ObjectSnapshot()
        // Decode the immutable snapshot after this reader releases its transaction.
        // Callers already inside an author transaction retain their outer lock.
        val head=frozen.head
        require(head.revision>0)
        val previews=frozen.previews.associate{row->
            require(ContentTransfer.hash(row.payload)==row.digest){"OBJECT_PREVIEW_CHANGED"}
            row.digest to Base64.getEncoder().encodeToString(row.payload)
        }
        val objects=frozen.records.mapIndexed{index,row->
            require(row.position==index)
            val preview=row.previewHash?.let{hash->checkNotNull(previews[hash]){"OBJECT_PREVIEW_MISSING"}}
            PageObjectCodec.decodeRecord(row.payload,preview).also{require(it.id==row.id)}
        }
        PageObjectCodec.encode(objects) // Preserve the existing aggregate capacity contract.
        return ObjectSnapshot(head.revision,objects)
    }

    /** Call only inside the author's CAS/receipt transaction. Unchanged rows remain untouched. */
    suspend fun write(page:String,revision:Long,prepared:Prepared) {
        check(db.inTransaction());require(revision>0)
        val dao=db.objects();val before=dao.records(page).associateBy{it.id}
        val rows=prepared.records;val previews=prepared.previews
        val previousPreviews=before.values.mapNotNull{it.previewHash}.toSet()
        for((hash,bytes) in previews){
            if(hash in previousPreviews)require(dao.hasPreview(hash)){"OBJECT_PREVIEW_MISSING"}
            else {
                val old=dao.preview(hash)
                if(old==null)dao.putPreview(ObjectPreviewRow(hash,bytes))
                else require(old.payload.contentEquals(bytes)){"OBJECT_PREVIEW_CHANGED"}
            }
        }
        dao.put(PageObjectRow(page,revision))
        for(row in rows){val old=before[row.id];if(old==null||old.position!=row.position||old.previewHash!=row.previewHash||!old.payload.contentEquals(row.payload))dao.putRecord(row)}
        val retained=rows.map{it.id}.toSet();for(id in before.keys-retained)dao.remove(page,id)
        for(hash in before.values.mapNotNull{it.previewHash}.distinct()-previews.keys)dao.removeUnusedPreview(hash)
    }

    companion object {
        fun prepare(page:String,objects:List<PageObject>)=Prepared(objects.mapIndexed{index,value->record(page,index,value)},
            objects.filter{it.image.isNotEmpty()}.associate{value->val bytes=Base64.getDecoder().decode(value.image);ContentTransfer.hash(bytes) to bytes})
        fun record(page:String,position:Int,value:PageObject)=PageObjectRecordRow(page,value.id,position,PageObjectCodec.encodeRecord(value),
            value.image.takeIf{it.isNotEmpty()}?.let{ContentTransfer.hash(Base64.getDecoder().decode(it))})

        fun createTables(db:SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE object_previews (digest TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(digest))")
            db.execSQL("CREATE TABLE page_object_records (pageId TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, payload BLOB NOT NULL, previewHash TEXT, PRIMARY KEY(pageId,id), FOREIGN KEY(pageId) REFERENCES page_objects(pageId) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(previewHash) REFERENCES object_previews(digest) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            db.execSQL("CREATE INDEX index_page_object_records_previewHash ON page_object_records(previewHash)")
        }

        /** Shared by schema migration and the already-supported older archive readers. */
        fun promote(db:SupportSQLiteDatabase,page:String,payload:ByteArray) {
            PageObjectCodec.decode(payload).forEachIndexed{index,value->
                val row=record(page,index,value)
                row.previewHash?.let{hash->
                    val bytes=Base64.getDecoder().decode(value.image)
                    val old=db.query("SELECT payload FROM object_previews WHERE digest=?",arrayOf(hash)).use{if(it.moveToFirst())it.getBlob(0)else null}
                    if(old==null)db.execSQL("INSERT INTO object_previews(digest,payload) VALUES (?,?)",arrayOf(hash,bytes))else require(old.contentEquals(bytes))
                }
                db.execSQL("INSERT INTO page_object_records(pageId,id,position,payload,previewHash) VALUES (?,?,?,?,?)",arrayOf(page,row.id,index,row.payload,row.previewHash))
            }
        }

        fun migrate(db:SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE page_objects RENAME TO page_objects_legacy")
            db.execSQL("CREATE TABLE page_objects (pageId TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(pageId), FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            createTables(db)
            db.execSQL("INSERT INTO page_objects(pageId,revision) SELECT pageId,revision FROM page_objects_legacy")
            db.query("SELECT pageId,payload FROM page_objects_legacy").use{rows->while(rows.moveToNext())promote(db,rows.getString(0),rows.getBlob(1))}
            db.execSQL("DROP TABLE page_objects_legacy")
        }
    }
}
