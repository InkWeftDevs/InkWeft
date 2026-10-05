// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import org.inkweft.core.*

@Entity(tableName="recall_questions",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=KnowledgeRow::class,parentColumns=["id"],childColumns=["questionId"]),ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class RecallQuestionRow(@PrimaryKey val questionId:String,val notebookId:String,val revision:Long,val position:Long,val payload:ByteArray){fun spec()=RecallCodec.spec(payload)}
@Entity(tableName="recall_question_revisions",primaryKeys=["questionId","revision"],indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=RecallQuestionRow::class,parentColumns=["questionId"],childColumns=["questionId"]),ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class RecallQuestionVersionRow(val questionId:String,val revision:Long,val notebookId:String,val payload:ByteArray){fun spec()=RecallCodec.spec(payload)}
@Entity(tableName="recall_schedules",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=RecallQuestionRow::class,parentColumns=["questionId"],childColumns=["questionId"]),ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class RecallScheduleRow(@PrimaryKey val questionId:String,val notebookId:String,val revision:Long,val specRevision:Long,
    val repetitions:Int=0,val intervalDays:Int=0,val easeHundredths:Int=250,val dueAt:Long=0,val lastAttemptId:String?=null,val algorithm:String=Sm2Schedule.VERSION){
    fun state()=Sm2State(repetitions,intervalDays,easeHundredths,dueAt,algorithm)
    fun snapshot()=RecallCodec.schedule(state(),revision,lastAttemptId,specRevision)
    fun withState(state:Sm2State,version:Long,attempt:String?)=copy(revision=version,repetitions=state.repetitions,intervalDays=state.intervalDays,easeHundredths=state.easeHundredths,dueAt=state.dueAt,lastAttemptId=attempt,algorithm=state.algorithm)
}
@Entity(tableName="recall_sessions",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class RecallSessionRow(@PrimaryKey val id:String,val notebookId:String,val revision:Long,val mode:String,val title:String,val createdAt:Long,
    val position:Int,val size:Int,val closed:Boolean=false,val mapId:String?=null,val branchId:String?=null)
@Entity(tableName="recall_attempts",indices=[Index("notebookId"),Index(value=["sessionId","position"],unique=true),Index(value=["questionId","specRevision"])],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"]),ForeignKey(entity=RecallSessionRow::class,parentColumns=["id"],childColumns=["sessionId"]),ForeignKey(entity=RecallQuestionVersionRow::class,parentColumns=["questionId","revision"],childColumns=["questionId","specRevision"])])
data class RecallAttemptRow(@PrimaryKey val id:String,val notebookId:String,val sessionId:String,val questionId:String,val specRevision:Long,val position:Int,
    val revision:Long=1,val status:String=RecallAttemptStatus.QUEUED.name,val mode:String,val startedAt:Long?=null,val completedAt:Long?=null,
    val answerText:String="",val answerInk:ByteArray=byteArrayOf(),val hintMask:Int=0,val revealedMasks:String="",val answerRevealed:Boolean=false,
    val requestedQuality:Int?=null,val effectiveQuality:Int?=null,val scheduleBefore:ByteArray?=null,val scheduleAfter:ByteArray?=null)
@Entity(tableName="recall_hint_events",indices=[Index("notebookId"),Index("attemptId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"]),ForeignKey(entity=RecallAttemptRow::class,parentColumns=["id"],childColumns=["attemptId"])])
data class RecallHintRow(@PrimaryKey val id:String,val notebookId:String,val attemptId:String,val kind:String,val target:Int?,val at:Long)
@Entity(tableName="recall_corrections",indices=[Index("notebookId"),Index(value=["attemptId"],unique=true)],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"]),ForeignKey(entity=RecallAttemptRow::class,parentColumns=["id"],childColumns=["attemptId"])])
data class RecallCorrectionRow(@PrimaryKey val id:String,val notebookId:String,val attemptId:String,val at:Long,val reason:String,val scheduleBefore:ByteArray?,val scheduleAfter:ByteArray?)
@Entity(tableName="recall_receipts",indices=[Index("notebookId")],foreignKeys=[ForeignKey(entity=NoteRow::class,parentColumns=["id"],childColumns=["notebookId"])])
data class RecallReceiptRow(@PrimaryKey val operationId:String,val notebookId:String,val kind:String,val digest:String,val resultId:String)

data class RecallAttemptSummary(val id:String,val notebookId:String,val sessionId:String,val questionId:String,val specRevision:Long,val position:Int,
    val revision:Long,val status:String,val mode:String,val startedAt:Long?,val completedAt:Long?,val hintMask:Int,val requestedQuality:Int?,val effectiveQuality:Int?)
data class RecallQuestionOrder(val questionId:String,val position:Long)
@Dao interface RecallDao {
    @Query("SELECT questionId,position FROM recall_questions WHERE notebookId=:book ORDER BY position,questionId") suspend fun questionOrder(book:String):List<RecallQuestionOrder>
    @Query("SELECT COUNT(*) FROM recall_attempts WHERE notebookId=:book") suspend fun attemptCount(book:String):Int
    @Query("SELECT id,notebookId,sessionId,questionId,specRevision,position,revision,status,mode,startedAt,completedAt,hintMask,requestedQuality,effectiveQuality FROM recall_attempts WHERE notebookId=:book ORDER BY COALESCE(completedAt,startedAt,0) DESC,sessionId,position") suspend fun attemptSummaries(book:String):List<RecallAttemptSummary>
    @Query("SELECT id,notebookId,sessionId,questionId,specRevision,position,revision,status,mode,startedAt,completedAt,hintMask,requestedQuality,effectiveQuality FROM recall_attempts WHERE sessionId=:id ORDER BY position") suspend fun sessionSummaries(id:String):List<RecallAttemptSummary>
    @Query("SELECT * FROM recall_attempts WHERE sessionId=:id AND position=:position") suspend fun atPosition(id:String,position:Int):RecallAttemptRow?
    @Query("SELECT * FROM recall_attempts WHERE sessionId=:id AND status IN ('QUEUED','OPEN') ORDER BY position") suspend fun unfinished(id:String):List<RecallAttemptRow>
    @Query("SELECT * FROM recall_receipts") suspend fun receipts():List<RecallReceiptRow>
    @Query("SELECT * FROM recall_questions WHERE questionId=:id") suspend fun question(id:String):RecallQuestionRow?
    @Query("SELECT * FROM recall_questions WHERE notebookId=:book ORDER BY position,questionId") suspend fun questions(book:String):List<RecallQuestionRow>
    @Query("SELECT * FROM recall_question_revisions WHERE questionId=:id AND revision=:revision") suspend fun questionVersion(id:String,revision:Long):RecallQuestionVersionRow?
    @Query("SELECT * FROM recall_question_revisions") suspend fun questionVersions():List<RecallQuestionVersionRow>
    @Query("SELECT * FROM recall_schedules WHERE questionId=:id") suspend fun schedule(id:String):RecallScheduleRow?
    @Query("SELECT * FROM recall_schedules WHERE notebookId=:book") suspend fun schedules(book:String):List<RecallScheduleRow>
    @Query("SELECT * FROM recall_sessions WHERE id=:id") suspend fun session(id:String):RecallSessionRow?
    @Query("SELECT * FROM recall_sessions WHERE notebookId=:book ORDER BY createdAt,id") suspend fun sessions(book:String):List<RecallSessionRow>
    @Query("SELECT * FROM recall_attempts WHERE id=:id") suspend fun attempt(id:String):RecallAttemptRow?
    @Query("SELECT * FROM recall_attempts WHERE sessionId=:id ORDER BY position") suspend fun sessionAttempts(id:String):List<RecallAttemptRow>
    @Query("SELECT * FROM recall_attempts WHERE notebookId=:book ORDER BY COALESCE(completedAt,startedAt,0) DESC,sessionId,position") suspend fun attempts(book:String):List<RecallAttemptRow>
    @Query("SELECT COALESCE(SUM(length(answerInk)),0) FROM recall_attempts") suspend fun answerInkBytes():Long
    @Query("SELECT * FROM recall_hint_events WHERE attemptId=:id ORDER BY at,id") suspend fun hints(id:String):List<RecallHintRow>
    @Query("SELECT * FROM recall_corrections WHERE attemptId=:id") suspend fun correction(id:String):RecallCorrectionRow?
    @Query("SELECT * FROM recall_corrections WHERE notebookId=:book ORDER BY at,id") suspend fun corrections(book:String):List<RecallCorrectionRow>
    @Query("SELECT * FROM recall_receipts WHERE operationId=:id") suspend fun receipt(id:String):RecallReceiptRow?
    @Query("SELECT COUNT(*) FROM recall_receipts WHERE notebookId=:book") suspend fun receiptCount(book:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallQuestionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallQuestionVersionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallScheduleRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallSessionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallAttemptRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallHintRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallCorrectionRow)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(row:RecallReceiptRow)
    @Update suspend fun update(row:RecallQuestionRow):Int
    @Update suspend fun update(row:RecallScheduleRow):Int
    @Update suspend fun update(row:RecallSessionRow):Int
    @Update suspend fun update(row:RecallAttemptRow):Int
}

/** Append after Room15. All eight tables have notebookId ownership for full-library backup closure. */
object RecallStorage {
    fun createTables(sql:SupportSQLiteDatabase){
        sql.execSQL("CREATE TABLE recall_questions (questionId TEXT NOT NULL, notebookId TEXT NOT NULL, revision INTEGER NOT NULL, position INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(questionId), FOREIGN KEY(questionId) REFERENCES knowledge_records(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_questions_notebookId ON recall_questions(notebookId)")
        sql.execSQL("CREATE TABLE recall_question_revisions (questionId TEXT NOT NULL, revision INTEGER NOT NULL, notebookId TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(questionId,revision), FOREIGN KEY(questionId) REFERENCES recall_questions(questionId) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_question_revisions_notebookId ON recall_question_revisions(notebookId)")
        sql.execSQL("CREATE TABLE recall_schedules (questionId TEXT NOT NULL, notebookId TEXT NOT NULL, revision INTEGER NOT NULL, specRevision INTEGER NOT NULL, repetitions INTEGER NOT NULL, intervalDays INTEGER NOT NULL, easeHundredths INTEGER NOT NULL, dueAt INTEGER NOT NULL, lastAttemptId TEXT, algorithm TEXT NOT NULL, PRIMARY KEY(questionId), FOREIGN KEY(questionId) REFERENCES recall_questions(questionId) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_schedules_notebookId ON recall_schedules(notebookId)")
        sql.execSQL("CREATE TABLE recall_sessions (id TEXT NOT NULL, notebookId TEXT NOT NULL, revision INTEGER NOT NULL, mode TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, position INTEGER NOT NULL, size INTEGER NOT NULL, closed INTEGER NOT NULL, mapId TEXT, branchId TEXT, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_sessions_notebookId ON recall_sessions(notebookId)")
        sql.execSQL("CREATE TABLE recall_attempts (id TEXT NOT NULL, notebookId TEXT NOT NULL, sessionId TEXT NOT NULL, questionId TEXT NOT NULL, specRevision INTEGER NOT NULL, position INTEGER NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL, mode TEXT NOT NULL, startedAt INTEGER, completedAt INTEGER, answerText TEXT NOT NULL, answerInk BLOB NOT NULL, hintMask INTEGER NOT NULL, revealedMasks TEXT NOT NULL, answerRevealed INTEGER NOT NULL, requestedQuality INTEGER, effectiveQuality INTEGER, scheduleBefore BLOB, scheduleAfter BLOB, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(sessionId) REFERENCES recall_sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(questionId,specRevision) REFERENCES recall_question_revisions(questionId,revision) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_attempts_notebookId ON recall_attempts(notebookId)")
        sql.execSQL("CREATE UNIQUE INDEX index_recall_attempts_sessionId_position ON recall_attempts(sessionId,position)")
        sql.execSQL("CREATE INDEX index_recall_attempts_questionId_specRevision ON recall_attempts(questionId,specRevision)")
        sql.execSQL("CREATE TABLE recall_hint_events (id TEXT NOT NULL, notebookId TEXT NOT NULL, attemptId TEXT NOT NULL, kind TEXT NOT NULL, target INTEGER, at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(attemptId) REFERENCES recall_attempts(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_hint_events_notebookId ON recall_hint_events(notebookId)")
        sql.execSQL("CREATE INDEX index_recall_hint_events_attemptId ON recall_hint_events(attemptId)")
        sql.execSQL("CREATE TABLE recall_corrections (id TEXT NOT NULL, notebookId TEXT NOT NULL, attemptId TEXT NOT NULL, at INTEGER NOT NULL, reason TEXT NOT NULL, scheduleBefore BLOB, scheduleAfter BLOB, PRIMARY KEY(id), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(attemptId) REFERENCES recall_attempts(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_corrections_notebookId ON recall_corrections(notebookId)")
        sql.execSQL("CREATE UNIQUE INDEX index_recall_corrections_attemptId ON recall_corrections(attemptId)")
        sql.execSQL("CREATE TABLE recall_receipts (operationId TEXT NOT NULL, notebookId TEXT NOT NULL, kind TEXT NOT NULL, digest TEXT NOT NULL, resultId TEXT NOT NULL, PRIMARY KEY(operationId), FOREIGN KEY(notebookId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("CREATE INDEX index_recall_receipts_notebookId ON recall_receipts(notebookId)")
    }
    private fun c(name:String,type:Char,nullable:Boolean=false)=LibraryArchive.Column(name,type,nullable)
    private fun t(name:String,key:String,vararg columns:LibraryArchive.Column)=LibraryArchive.Table(name,columns.toList(),key.split(','))
    val SCHEMA=listOf(
        t("recall_questions","questionId",c("questionId",'S'),c("notebookId",'S'),c("revision",'I'),c("position",'I'),c("payload",'B')),
        t("recall_question_revisions","questionId,revision",c("questionId",'S'),c("revision",'I'),c("notebookId",'S'),c("payload",'B')),
        t("recall_schedules","questionId",c("questionId",'S'),c("notebookId",'S'),c("revision",'I'),c("specRevision",'I'),c("repetitions",'I'),c("intervalDays",'I'),c("easeHundredths",'I'),c("dueAt",'I'),c("lastAttemptId",'S',true),c("algorithm",'S')),
        t("recall_sessions","id",c("id",'S'),c("notebookId",'S'),c("revision",'I'),c("mode",'S'),c("title",'S'),c("createdAt",'I'),c("position",'I'),c("size",'I'),c("closed",'I'),c("mapId",'S',true),c("branchId",'S',true)),
        t("recall_attempts","id",c("id",'S'),c("notebookId",'S'),c("sessionId",'S'),c("questionId",'S'),c("specRevision",'I'),c("position",'I'),c("revision",'I'),c("status",'S'),c("mode",'S'),c("startedAt",'I',true),c("completedAt",'I',true),c("answerText",'S'),c("answerInk",'B'),c("hintMask",'I'),c("revealedMasks",'S'),c("answerRevealed",'I'),c("requestedQuality",'I',true),c("effectiveQuality",'I',true),c("scheduleBefore",'B',true),c("scheduleAfter",'B',true)),
        t("recall_hint_events","id",c("id",'S'),c("notebookId",'S'),c("attemptId",'S'),c("kind",'S'),c("target",'I',true),c("at",'I')),
        t("recall_corrections","id",c("id",'S'),c("notebookId",'S'),c("attemptId",'S'),c("at",'I'),c("reason",'S'),c("scheduleBefore",'B',true),c("scheduleAfter",'B',true)),
        t("recall_receipts","operationId",c("operationId",'S'),c("notebookId",'S'),c("kind",'S'),c("digest",'S'),c("resultId",'S')))
}
