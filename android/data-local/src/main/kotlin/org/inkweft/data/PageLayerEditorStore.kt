// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import org.inkweft.core.*
import java.util.UUID

/** Session undo data, never the authoritative ownership projection. */
@Entity(tableName="page_layer_changes",primaryKeys=["pageId","commandId","kind","contentId"],foreignKeys=[
    ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"],onDelete=ForeignKey.CASCADE)])
data class PageLayerChangeRow(val pageId:String,val commandId:String,val kind:String,val contentId:String,
    val beforeLayerId:String?,val afterLayerId:String?)

/** Null means the ordinary bounded selection diff. Whole-layer edits stay SQL predicates. */
sealed interface LayerOwnershipEdit {
    data class Layer(val source:String,val target:String?):LayerOwnershipEdit {
        init { UUID.fromString(source);target?.let(UUID::fromString);require(source!=target) }
    }
    data class Replay(val command:String,val reverse:Boolean):LayerOwnershipEdit { init { UUID.fromString(command) } }
}

internal class PageLayerEditorStore(private val db:NoteDatabase) {
    fun counts(page:String,inkOnly:Boolean=false):Map<String,Long> = PageLayerTotals.read(db.openHelper.readableDatabase,page,inkOnly)

    fun write(page:String,command:String,before:PageAuthoring,after:PageAuthoring,edit:LayerOwnershipEdit?,retained:List<String>) {
        val sql=db.openHelper.writableDatabase;check(sql.inTransaction())
        require(retained.size<=20&&retained.distinct().size==retained.size);retained.forEach(UUID::fromString)
        if(edit is LayerOwnershipEdit.Replay){
            replay(sql,page,edit);requireKnownLayers(sql,page,after)
            return
        }
        // A marker also authenticates an empty diff (rename, visibility, annotations).
        sql.execSQL("INSERT INTO page_layer_changes VALUES(?,?,'','',NULL,NULL)",arrayOf(page,command))
        if(edit is LayerOwnershipEdit.Layer){
            val source=before.layers.layers.single{it.id==edit.source}
            val removed=after.layers.layers.none{it.id==edit.source}
            if(removed){
                val hasContents=sql.query("SELECT 1 FROM page_layer_memberships WHERE pageId=? AND layerId=? LIMIT 1",arrayOf(page,edit.source)).use{it.moveToFirst()}
                require(!hasContents||!source.locked){"LAYER_UNLOCK_BEFORE_DELETE"}
            }else require(source.writable&&edit.target!=null){"LAYER_SELECTION_LOCKED"}
            if(edit.target!=null)require(after.layers.layers.any{it.id==edit.target&&it.writable}){"LAYER_TARGET_NOT_WRITABLE"}
            sql.execSQL("INSERT INTO page_layer_changes SELECT pageId,?,kind,contentId,layerId,? FROM page_layer_memberships WHERE pageId=? AND layerId=?",
                arrayOf(command,edit.target,page,edit.source))
            sql.execSQL("UPDATE page_layer_memberships SET layerId=? WHERE pageId=? AND layerId=?",arrayOf(edit.target,page,edit.source))
        }
        val old=ownership(before);val next=ownership(after)
        require(old.keys==next.keys){"LAYER_CONTENT_LOST"}
        for((content,target) in next){
            val origin=old[content]
            if(origin==target)continue
            // The whole-layer statement already captured these rows.
            if(edit is LayerOwnershipEdit.Layer&&origin==edit.source){require(target==edit.target);continue}
            require(before.layers.editable(content)){"LAYER_SELECTION_LOCKED"}
            require(target!=null&&after.layers.layers.any{it.id==target&&it.writable}){"LAYER_TARGET_NOT_WRITABLE"}
            sql.execSQL("INSERT INTO page_layer_changes VALUES(?,?,?,?,?,?)",arrayOf(page,command,content.kind.name,content.id,origin,target))
            sql.execSQL("UPDATE page_layer_memberships SET layerId=? WHERE pageId=? AND kind=? AND contentId=?",arrayOf(target,page,content.kind.name,content.id))
        }
        requireKnownLayers(sql,page,after)
        val keep=(retained+command).distinct()
        sql.execSQL("DELETE FROM page_layer_changes WHERE pageId=? AND commandId NOT IN (${keep.joinToString(","){"?"}})",(listOf(page)+keep).toTypedArray())
    }

    private fun requireKnownLayers(sql:SupportSQLiteDatabase,page:String,after:PageAuthoring){
        // A projected header must account for all unobserved members, including on replay.
        val live=after.layers.layers.map{it.id}
        require(!sql.query("SELECT 1 FROM page_layer_memberships WHERE pageId=? AND layerId IS NOT NULL AND layerId NOT IN (${live.joinToString(","){"?"}}) LIMIT 1",
            (listOf(page)+live).toTypedArray()).use{it.moveToFirst()}){"LAYER_CONTENT_LOST"}
    }
    private fun replay(sql:SupportSQLiteDatabase,page:String,edit:LayerOwnershipEdit.Replay){
        require(sql.query("SELECT 1 FROM page_layer_changes WHERE pageId=? AND commandId=? AND kind='' AND contentId=''",arrayOf(page,edit.command)).use{it.moveToFirst()}){"LAYER_HISTORY_MISSING"}
        val from=if(edit.reverse)"afterLayerId"else"beforeLayerId";val to=if(edit.reverse)"beforeLayerId"else"afterLayerId"
        require(!sql.query("SELECT 1 FROM page_layer_changes c LEFT JOIN page_layer_memberships m ON m.pageId=c.pageId AND m.kind=c.kind AND m.contentId=c.contentId WHERE c.pageId=? AND c.commandId=? AND c.kind!='' AND (m.contentId IS NULL OR m.layerId IS NOT c.$from) LIMIT 1",arrayOf(page,edit.command)).use{it.moveToFirst()}){"AUTHORING_CONFLICT"}
        sql.execSQL("UPDATE page_layer_memberships SET layerId=(SELECT c.$to FROM page_layer_changes c WHERE c.pageId=page_layer_memberships.pageId AND c.kind=page_layer_memberships.kind AND c.contentId=page_layer_memberships.contentId AND c.commandId=?) WHERE pageId=? AND EXISTS (SELECT 1 FROM page_layer_changes c WHERE c.pageId=page_layer_memberships.pageId AND c.kind=page_layer_memberships.kind AND c.contentId=page_layer_memberships.contentId AND c.commandId=?)",arrayOf(edit.command,page,edit.command))
    }
    private fun ownership(state:PageAuthoring):Map<LayerContent,String?> = buildMap{
        state.layers.memberships.filter{it.content.kind!=LayerContentKind.ANNOTATION}.forEach{put(it.content,it.layerId)}
        state.layers.deleted.filter{it.kind!=LayerContentKind.ANNOTATION}.forEach{put(it,null)}
    }
    companion object {
        const val TABLE="page_layer_changes"
        fun create(sql:SupportSQLiteDatabase){sql.execSQL("CREATE TABLE IF NOT EXISTS `page_layer_changes` (`pageId` TEXT NOT NULL, `commandId` TEXT NOT NULL, `kind` TEXT NOT NULL, `contentId` TEXT NOT NULL, `beforeLayerId` TEXT, `afterLayerId` TEXT, PRIMARY KEY(`pageId`,`commandId`,`kind`,`contentId`), FOREIGN KEY(`pageId`) REFERENCES `notebook_pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")}
    }
}
