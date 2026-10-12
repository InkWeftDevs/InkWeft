// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import org.inkweft.core.*

/** A null layer is an author tombstone, independent of ink/object visibility. */
@Entity(tableName="page_layer_memberships",primaryKeys=["pageId","kind","contentId"],indices=[Index(value=["pageId","layerId"])],foreignKeys=[
    ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.NO_ACTION)])
data class PageLayerMembershipRow(val pageId:String,val kind:String,val contentId:String,val layerId:String?) {
    fun content():LayerContent {
        val type=LayerContentKind.valueOf(kind);require(type!=LayerContentKind.ANNOTATION)
        return LayerContent(type,contentId)
    }
}

/** PAGE stores only its bounded annotation/configuration header in canvas_authoring. */
internal class PageLayerStore(private val db:NoteDatabase) {
    suspend fun ensureHeader(page:String,book:String):PageAuthoringRow {
        val row=db.authoring().get("PAGE",page)
        if(row!=null){
            require(row.notebookId==book&&row.revision>=0)
            if(PageAuthoringCodec.isHeader(row.payload))return row
            require(db.authoring().membershipCount(page)==0L){"LAYER_STORAGE_MIXED"}
            return write(page,book,row.revision,row.data())
        }
        require(db.authoring().membershipCount(page)==0L){"LAYER_HEADER_MISSING"}
        val sql=db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO page_layer_memberships(pageId,kind,contentId,layerId) SELECT noteId,'INK',id,? FROM ink_strokes WHERE noteId=?",arrayOf(UserLayers.DEFAULT_ID,page))
        sql.execSQL("INSERT INTO page_layer_memberships(pageId,kind,contentId,layerId) SELECT pageId,'OBJECT',id,? FROM page_object_records WHERE pageId=?",arrayOf(UserLayers.DEFAULT_ID,page))
        return PageAuthoringRow("PAGE",page,book,0,PageAuthoringCodec.encodeHeader(PageAuthoring())).also{db.authoring().put(it)}
    }

    suspend fun write(page:String,book:String,revision:Long,state:PageAuthoring):PageAuthoringRow {
        val row=PageAuthoringRow("PAGE",page,book,revision,PageAuthoringCodec.encodeHeader(state))
        writeMemberships(db.openHelper.writableDatabase,page,state)
        db.authoring().put(row)
        return row
    }

    suspend fun selected(page:String,contents:Collection<LayerContent>):List<PageLayerMembershipRow> {
        val result=ArrayList<PageLayerMembershipRow>()
        contents.distinct().groupBy{it.kind}.forEach{(kind,refs)->
            require(kind!=LayerContentKind.ANNOTATION)
            refs.chunked(900).forEach{batch->result+=db.authoring().memberships(page,kind.name,batch.map{it.id})}
        }
        return result
    }

    companion object {
        fun state(row:PageAuthoringRow,memberships:List<PageLayerMembershipRow>):PageAuthoring {
            if(!PageAuthoringCodec.isHeader(row.payload)){
                require(memberships.isEmpty()){"LAYER_STORAGE_MIXED"};return row.data()
            }
            require(row.kind=="PAGE")
            val header=PageAuthoringCodec.decodeHeader(row.payload);val layers=header.layers
            require(memberships.all{it.pageId==row.scopeId})
            val live=memberships.mapNotNull{entry->entry.layerId?.let{LayerMembership(entry.content(),it)}}
            val deleted=memberships.filter{it.layerId==null}.map{it.content()}
            return header.withLayers(UserLayers(layers.layers,layers.currentId,layers.memberships+live,layers.deleted+deleted))
        }

        fun migrate(sql:SupportSQLiteDatabase) {
            sql.execSQL("CREATE TABLE IF NOT EXISTS page_layer_memberships (pageId TEXT NOT NULL,kind TEXT NOT NULL,contentId TEXT NOT NULL,layerId TEXT,PRIMARY KEY(pageId,kind,contentId),FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            promote(sql)
        }

        /** Used by schema migration and old supported archive import, before validation. */
        fun promote(sql:SupportSQLiteDatabase) {
            sql.query("SELECT scopeId,payload FROM canvas_authoring WHERE kind='PAGE'").use{cursor->
                while(cursor.moveToNext()){
                    val page=cursor.getString(0);val bytes=cursor.getBlob(1)
                    if(PageAuthoringCodec.isHeader(bytes))continue
                    val state=PageAuthoringCodec.decode(bytes)
                    writeMemberships(sql,page,state)
                    sql.execSQL("UPDATE canvas_authoring SET payload=? WHERE kind='PAGE' AND scopeId=?",arrayOf(PageAuthoringCodec.encodeHeader(state),page))
                }
            }
        }

        private fun writeMemberships(sql:SupportSQLiteDatabase,page:String,state:PageAuthoring) {
            check(sql.inTransaction())
            sql.execSQL("DELETE FROM page_layer_memberships WHERE pageId=?",arrayOf(page))
            sql.compileStatement("INSERT INTO page_layer_memberships(pageId,kind,contentId,layerId) VALUES(?,?,?,?)").use{statement->
                fun insert(content:LayerContent,layer:String?){
                    if(content.kind==LayerContentKind.ANNOTATION)return
                    statement.clearBindings();statement.bindString(1,page);statement.bindString(2,content.kind.name);statement.bindString(3,content.id)
                    if(layer==null)statement.bindNull(4)else statement.bindString(4,layer)
                    statement.executeInsert()
                }
                state.layers.memberships.forEach{insert(it.content,it.layerId)}
                state.layers.deleted.forEach{insert(it,null)}
            }
        }
    }
}
