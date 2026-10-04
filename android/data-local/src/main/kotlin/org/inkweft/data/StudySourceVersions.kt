// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.map
import org.inkweft.core.*
import java.util.UUID

@Entity(tableName="study_source_revisions",primaryKeys=["sourceId","revision"],indices=[Index("notebookId"),Index("pageId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"]),ForeignKey(entity=NotebookPageRow::class,parentColumns=["id"],childColumns=["pageId"])])
data class StudySourceRevisionRow(val sourceId:String,val revision:Long,val notebookId:String,val pageId:String,val inkRevision:Long,
    val left:Double,val top:Double,val right:Double,val bottom:Double,val strokeIds:String,val snapshot:ByteArray) {
    fun legacy(cardId:String)=StudySourceRow(cardId,pageId,inkRevision,left,top,right,bottom,strokeIds,snapshot.copyOf())
    fun ref()=StudySourceVersionRef(sourceId,revision)
}
@Entity(tableName="study_card_source_sets",primaryKeys=["cardId","cardRevision"],foreignKeys=[ForeignKey(entity=StudyCardRevisionRow::class,parentColumns=["cardId","revision"],childColumns=["cardId","cardRevision"])])
data class StudyCardSourceSetRow(val cardId:String,val cardRevision:Long,val sourceRefs:String,val complete:Boolean) {
    fun refs()=StudySourceRefs.decode(sourceRefs)
}
@Dao interface StudySourceVersionDao {
    @Query("SELECT * FROM study_source_revisions WHERE sourceId=:id AND revision=:revision") suspend fun get(id:String,revision:Long):StudySourceRevisionRow?
    @Query("SELECT * FROM study_source_revisions ORDER BY sourceId,revision") suspend fun all():List<StudySourceRevisionRow>
    @Query("SELECT COALESCE(MAX(revision),0) FROM study_source_revisions WHERE sourceId=:id") suspend fun latestRevision(id:String):Long
    @Query("SELECT * FROM study_card_source_sets WHERE cardId=:id AND cardRevision=:revision") suspend fun set(id:String,revision:Long):StudyCardSourceSetRow?
    @Query("SELECT * FROM study_card_source_sets ORDER BY cardId,cardRevision") suspend fun sets():List<StudyCardSourceSetRow>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:StudySourceRevisionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:StudyCardSourceSetRow)
}
data class FrozenStudySources(val refs:List<StudySourceVersionRef>,val sources:List<StudySourceRevisionRow>,val complete:Boolean) {
    /** A multi-source card must offer a choice; callers must never silently pick the first source. */
    fun singleLegacy(cardId:String):StudySourceRow?=if(complete)sources.singleOrNull()?.legacy(cardId)else null
}
class StudySourceVersions(private val db:NoteDatabase) {
    fun observe(cardId:String)=db.invalidationTracker.createFlow("study_cards","study_sources","study_source_revisions","study_card_source_sets").map{read(cardId)}
    suspend fun read(cardId:String,cardRevision:Long?=null):FrozenStudySources=db.withTransaction {
        val card=requireNotNull(db.study().card(cardId)){"SOURCE_CARD_UNAVAILABLE"}
        val revision=cardRevision?:card.revision
        require(db.study().cardVersion(cardId,revision)!=null){"SOURCE_CARD_VERSION_UNAVAILABLE"}
        val set=db.sourceVersions().set(cardId,revision)
        if(set!=null){
            val refs=set.refs();val values=refs.mapNotNull{db.sourceVersions().get(it.sourceId,it.revision)}
            return@withTransaction FrozenStudySources(refs,values,set.complete&&values.size==refs.size)
        }
        // Compatibility for data inserted by older adapters. Never guess a historical snapshot.
        if(db.sourceVersions().latestRevision(cardId)>0)return@withTransaction FrozenStudySources(emptyList(),emptyList(),false)
        val legacy=db.study().legacySource(cardId)
        if(revision!=card.revision)return@withTransaction FrozenStudySources(emptyList(),emptyList(),legacy==null)
        val source=legacy?.version(card.notebookId,1)
        FrozenStudySources(source?.let{listOf(it.ref())}.orEmpty(),listOfNotNull(source),true)
    }
    internal suspend fun freezeCurrent(card:StudyCardRow) {
        val frozen=read(card.id,card.revision);materialize(frozen);writeSet(card.id,card.revision,frozen.refs,frozen.complete)
        db.study().cardVersions(card.id).filter{it.revision<card.revision}.forEach{old->
            if(db.sourceVersions().set(card.id,old.revision)==null)writeSet(card.id,old.revision,emptyList(),frozen.refs.isEmpty()&&frozen.complete)
        }
    }
    internal suspend fun capture(card:StudyCardRow,source:StudySourceRow) {
        val latest=db.sourceVersions().latestRevision(card.id)
        val value=source.version(card.notebookId,latest+1)
        db.sourceVersions().insert(value)
        writeSet(card.id,card.revision,listOf(value.ref()),true)
    }
    internal suspend fun copyVersion(card:StudyCardRow,fromRevision:Long) {
        val sources=read(card.id,fromRevision)
        materialize(sources)
        writeSet(card.id,card.revision,sources.refs,sources.complete)
    }
    internal suspend fun materialize(sources:FrozenStudySources) {
        sources.sources.forEach{source->if(db.sourceVersions().get(source.sourceId,source.revision)==null)db.sourceVersions().insert(source)}
    }
    internal suspend fun writeSet(cardId:String,revision:Long,refs:List<StudySourceVersionRef>,complete:Boolean) {
        require(refs.all{db.sourceVersions().get(it.sourceId,it.revision)!=null}){"SOURCE_VERSION_UNAVAILABLE"}
        val row=StudyCardSourceSetRow(cardId,revision,StudySourceRefs.encode(refs),complete)
        val old=db.sourceVersions().set(cardId,revision)
        require(old==null||old==row){"SOURCE_SET_IMMUTABLE"}
        if(old==null)db.sourceVersions().insert(row)
    }
    suspend fun validateArchive() {
        val sources=db.sourceVersions().all();val byRef=sources.associateBy{it.ref()}
        require(db.openHelper.readableDatabase.query("SELECT 1 FROM study_card_revisions r WHERE EXISTS(SELECT 1 FROM study_source_revisions v WHERE v.sourceId=r.cardId) AND NOT EXISTS(SELECT 1 FROM study_card_source_sets s WHERE s.cardId=r.cardId AND s.cardRevision=r.revision)").use{!it.moveToFirst()}){"SOURCE_SET_MISSING"}
        for(source in sources){
            UUID.fromString(source.sourceId);require(source.revision in 1 until Long.MAX_VALUE)
            val page=requireNotNull(db.pages().get(source.pageId));require(page.notebookId==source.notebookId)
            validateSnapshot(db,source.legacy(source.sourceId))
        }
        for(set in db.sourceVersions().sets()){
            val current=db.study().card(set.cardId)
            if(current?.revision==set.cardRevision){
                val legacy=db.study().legacySource(set.cardId)
                if(legacy!=null){
                    val source=set.refs().singleOrNull()?.let(byRef::get)
                    require(set.complete&&source!=null&&source.pageId==legacy.pageId&&source.inkRevision==legacy.inkRevision&&
                        source.left==legacy.left&&source.top==legacy.top&&source.right==legacy.right&&source.bottom==legacy.bottom&&
                        source.strokeIds==legacy.strokeIds&&source.snapshot.contentEquals(legacy.snapshot)){"SOURCE_CACHE_MISMATCH"}
                }
            }
            require(set.cardRevision>0&&db.study().cardVersion(set.cardId,set.cardRevision)!=null)
            require(set.refs().all{it in byRef}){"SOURCE_VERSION_UNAVAILABLE"}
        }
    }
    companion object {
        internal fun createTables(sql:SupportSQLiteDatabase) {
            sql.execSQL("CREATE TABLE study_source_revisions (sourceId TEXT NOT NULL, revision INTEGER NOT NULL, notebookId TEXT NOT NULL, pageId TEXT NOT NULL, inkRevision INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, strokeIds TEXT NOT NULL, snapshot BLOB NOT NULL, PRIMARY KEY(sourceId,revision), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(pageId) REFERENCES notebook_pages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            sql.execSQL("CREATE INDEX index_study_source_revisions_notebookId ON study_source_revisions(notebookId)")
            sql.execSQL("CREATE INDEX index_study_source_revisions_pageId ON study_source_revisions(pageId)")
            sql.execSQL("CREATE TABLE study_card_source_sets (cardId TEXT NOT NULL, cardRevision INTEGER NOT NULL, sourceRefs TEXT NOT NULL, complete INTEGER NOT NULL, PRIMARY KEY(cardId,cardRevision), FOREIGN KEY(cardId,cardRevision) REFERENCES study_card_revisions(cardId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        }
        /** Upgrade copies only the currently surviving bytes. Earlier overwritten crops remain unavailable. */
        internal fun promoteLegacy(sql:SupportSQLiteDatabase) {
            sql.execSQL("INSERT INTO study_source_revisions SELECT s.cardId,1,c.notebookId,s.pageId,s.inkRevision,s.`left`,s.`top`,s.`right`,s.`bottom`,s.strokeIds,s.snapshot FROM study_sources s JOIN study_cards c ON c.id=s.cardId")
            sql.execSQL("INSERT INTO study_card_source_sets SELECT r.cardId,r.revision,CASE WHEN r.revision=c.revision AND s.cardId IS NOT NULL THEN s.cardId||'@1' ELSE '' END,CASE WHEN r.revision=c.revision OR s.cardId IS NULL THEN 1 ELSE 0 END FROM study_card_revisions r JOIN study_cards c ON c.id=r.cardId LEFT JOIN study_sources s ON s.cardId=c.id")
        }
        internal suspend fun validateSnapshot(db:NoteDatabase,source:StudySourceRow) {
            val ids=source.strokeIds.split(',').filter{it.isNotBlank()};val bounds=CanvasBounds(source.left,source.top,source.right,source.bottom)
            require(source.snapshot.size<=StudyCapacity.MAX_SOURCE_BYTES)
            val snapshot=InkPageFile.decode(source.snapshot)
            val preview=snapshot.objects.singleOrNull()?.takeIf{it.kind==PageObjectKind.IMAGE&&snapshot.strokes.isEmpty()}?.let{java.util.Base64.getDecoder().decode(it.image)}
            StudySourceDraft(source.pageId,source.inkRevision,bounds,ids,preview)
            if(preview==null)require(snapshot.strokes.map{it.id}.toSet()==ids.toSet())
            for(id in ids)require(db.ink().stroke(id)?.noteId==source.pageId)
            if(preview==null)require(snapshot.world==db.pages().get(source.pageId)?.world)
            require(source.inkRevision<=(db.ink().page(source.pageId)?.revision?:0))
        }
    }
}
private fun StudySourceRow.version(book:String,revision:Long)=StudySourceRevisionRow(cardId,revision,book,pageId,inkRevision,left,top,right,bottom,strokeIds,snapshot.copyOf())
