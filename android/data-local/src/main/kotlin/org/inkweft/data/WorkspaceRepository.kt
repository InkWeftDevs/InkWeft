// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.inkweft.core.*
import java.util.UUID

/** Presentation/organization lives in the same database, never in diagnostic logs. */
@Entity(tableName="notebook_workspace",foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["noteId"],onDelete=ForeignKey.NO_ACTION)])
data class WorkspaceRow(@PrimaryKey val noteId:String,val world:Boolean=false,val paper:Int=1,
    val folder:String="",val tags:String="",val favorite:Boolean=false,val trashedAt:Long?=null,
    val centerX:Double=500.0,val centerY:Double=707.0,val zoom:Double=0.0,val revision:Long=0)

data class LibraryInkCount(val noteId:String,val visibleCount:Int,val modifiedRevision:Long)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM notebook_workspace") fun observe():Flow<List<WorkspaceRow>>
    @Query("SELECT * FROM notebook_workspace WHERE noteId=:id") suspend fun get(id:String):WorkspaceRow?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:WorkspaceRow)
    @Update suspend fun update(row:WorkspaceRow):Int
    @Query("UPDATE notebook_workspace SET centerX=:x,centerY=:y,zoom=:zoom WHERE noteId=:id")
    suspend fun viewport(id:String,x:Double,y:Double,zoom:Double):Int
    @Query("SELECT n.id AS noteId,COALESCE(SUM(CASE WHEN s.visible=1 THEN 1 ELSE 0 END),0) AS visibleCount,COALESCE(p.revision,0) AS modifiedRevision FROM notes n LEFT JOIN ink_pages p ON p.noteId=n.id LEFT JOIN ink_strokes s ON s.noteId=n.id GROUP BY n.id")
    fun observeInkCounts():Flow<List<LibraryInkCount>>
    @Query("SELECT updatedAt FROM notes WHERE id=:id") suspend fun updatedAt(id:String):Long?
}

/** No destructive deletion. The trash is a reversible shelf membership flag. */
class WorkspaceRepository(private val db:NoteDatabase) {
    fun observe()=db.workspace().observe()
    fun observeInkCounts()=db.workspace().observeInkCounts()
    suspend fun get(id:String):WorkspaceRow=db.withTransaction {
        checkNotNull(db.notes().note(id))
        db.workspace().get(id)?:WorkspaceRow(id).also { db.workspace().insert(it) }
    }
    suspend fun create(title:String,world:Boolean,paper:PaperStyle):Note=db.withTransaction {
        val clean=title.trim();require(clean.isNotBlank() && clean.length<=120)
        val id=UUID.randomUUID().toString();val at=System.currentTimeMillis()
        db.notes().insertNote(NoteRow(id,1,clean,"",at));db.notes().insertRevision(NoteRevisionRow(id,1,clean,"",at))
        db.workspace().insert(WorkspaceRow(id,world,paper.ordinal,centerX=if(world)0.0 else 500.0,centerY=if(world)0.0 else 707.0))
        Note(id,1,clean,"")
    }
    suspend fun organize(id:String,expected:Long,folder:String,tags:String,favorite:Boolean,trash:Boolean):Boolean=db.withTransaction {
        checkNotNull(db.notes().note(id))
        val row=db.workspace().get(id)?:WorkspaceRow(id).also { db.workspace().insert(it) }
        if(row.revision!=expected)return@withTransaction false
        val cleanFolder=folder.trim();require(cleanFolder.length<=48 && !cleanFolder.contains('\n'))
        val cleanTags=tags.split(',', '，','\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(cleanTags.size<=12 && cleanTags.all { it.length<=24 })
        check(db.workspace().update(row.copy(folder=cleanFolder,tags=cleanTags.joinToString("\n"),favorite=favorite,
            trashedAt=if(trash)row.trashedAt?:System.currentTimeMillis() else null,revision=row.revision+1))==1)
        true
    }
    suspend fun changePaper(id:String,paper:PaperStyle)=db.withTransaction {
        val row=get(id);check(db.workspace().update(row.copy(paper=paper.ordinal,revision=row.revision+1))==1)
    }
    suspend fun saveViewport(id:String,v:CanvasViewport)=db.withTransaction {
        get(id);check(db.workspace().viewport(id,v.centerX,v.centerY,v.zoom)==1)
    }
    suspend fun modifiedAt(id:String)=db.workspace().updatedAt(id)?:0L
}
