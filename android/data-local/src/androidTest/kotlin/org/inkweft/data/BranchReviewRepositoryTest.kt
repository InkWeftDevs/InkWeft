package org.inkweft.data

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BranchReviewRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(block: suspend (NoteDatabase, String) -> Unit) = runBlocking {
        val name = "branch-review-" + id() + ".db"
        val db = NoteDatabase.open(context, name)
        try { block(db, WorkspaceRepository(db).create("分支回忆验收", false, PaperStyle.DOTS).id) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private suspend fun card(db: NoteDatabase, book: String, parent: String? = null, map: String? = null,
                             title: String = "知识卡", body: String = "冻结答案"): StudyCommand {
        val command = StudyCommand(id(), book, StudyAction.CREATE, cardId = id(), nodeId = id(),
            parentId = parent, title = title, body = body, mapId = map)
        StudyRepository(db).submit(command)
        return command
    }
    private suspend fun question(db: NoteDatabase, book: String, card: String, prompt: String = "独立问题"): KnowledgeCommand {
        val command = KnowledgeCommand(id(), book, id(), 0, KnowledgeData.Question(card, prompt))
        KnowledgeRepository(db).submit(command)
        return command
    }
    private suspend fun map(db: NoteDatabase, book: String, title: String, root: String? = null): String {
        val map = id()
        val structure = root?.let { listOf(MapStructure(it, null, "结构分支", 40.0, 80.0)) }.orEmpty()
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, map, 0, KnowledgeData.MapDefinition(title, structures = structure)))
        return map
    }
    private suspend fun reject(message: String, action: suspend () -> Unit) {
        try { action(); fail("Expected " + message) } catch (e: IllegalArgumentException) { assertEquals(message, e.message) }
    }

    private suspend fun record(db: NoteDatabase, book: String, data: KnowledgeData): KnowledgeCommand {
        val command = KnowledgeCommand(id(), book, id(), 0, data)
        KnowledgeRepository(db).submit(command)
        return command
    }

    /** Covers saved content, metadata, immutable history and receipts, not just visible questions. */
    private suspend fun authorStamp(db: NoteDatabase): List<String> = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        buildList {
            for (table in tables) sql.query("SELECT * FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) {
                    val values = (0 until cursor.columnCount).joinToString("|") { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> "i:" + cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> "f:" + cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> "b:" + ContentTransfer.hash(cursor.getBlob(column))
                            else -> "s:" + ContentTransfer.hash(cursor.getString(column).toByteArray(Charsets.UTF_8))
                        }
                    }
                    add(table + ":" + values)
                }
            }
        }.sorted()
    }

    @Test fun twoMapsRepeatedPositionsAndRemovedNodeKeepIndependentQuestionScope() = fixture { db, book ->
        val root = id()
        val firstMap = map(db, book, "甲图", root)
        val secondMap = map(db, book, "乙图")
        val shared = card(db, book, root, firstMap)
        val sharedCardId = checkNotNull(shared.cardId)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REUSE, cardId = shared.cardId, nodeId = id(), parentId = root, mapId = firstMap))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REUSE, cardId = shared.cardId, nodeId = id(), mapId = secondMap))
        card(db, book, root, firstMap, title = "无题卡")
        val removed = card(db, book, root, firstMap, title = "已移除出现位置")
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = removed.nodeId, expectedRevision = 1, mapId = firstMap))
        val one = question(db, book, sharedCardId, "第一题")
        val two = question(db, book, sharedCardId, "第二题")
        question(db, book, removed.cardId!!)
        val before = db.knowledge().all().map { it.id to it.revision }
        val repository = BranchReviewRepository(db)
        val branch = repository.prepare(MapRef(book, firstMap), root)
        assertEquals("结构分支", branch.title)
        assertEquals(2, branch.cardCount)
        assertEquals(1, branch.withoutQuestionCount)
        assertEquals(setOf(one.id, two.id), branch.entries.map { it.questionId }.toSet())
        assertEquals(2, repository.prepare(MapRef(book, secondMap), null).entries.size)
        assertEquals(1, repository.prepare(MapRef(book, secondMap), null).cardCount)
        assertEquals(before, db.knowledge().all().map { it.id to it.revision })
        assertEquals(3, db.study().cards(book).size)
    }

    @Test fun nativeFortyLevelBranchIncludesDeepQuestionAndAllDistinctCards() = fixture { db, book ->
        var parent: String? = null
        var root: String? = null
        var last: StudyCommand? = null
        repeat(40) { depth ->
            last = card(db, book, parent, title = "层 " + depth)
            parent = last!!.nodeId
            if (root == null) root = parent
        }
        val deepQuestion = question(db, book, last!!.cardId!!)
        val plan = BranchReviewRepository(db).prepare(MapRef(book), root)
        assertEquals(40, plan.cardCount)
        assertEquals(39, plan.withoutQuestionCount)
        assertEquals(listOf(deepQuestion.id), plan.entries.map { it.questionId })
    }

    @Test fun notebookIncludesUnplacedManualCardWhileWholeMapKeepsMapScope() = fixture { db, book ->
        val placed = card(db, book)
        val unplaced = card(db, book, title = "未入图手工卡")
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = unplaced.nodeId, expectedRevision = 1))
        val placedQuestion = question(db, book, placed.cardId!!)
        val unplacedQuestion = question(db, book, unplaced.cardId!!)
        val repository = BranchReviewRepository(db)
        assertEquals(listOf(placedQuestion.id), repository.prepare(MapRef(book), null).entries.map { it.questionId })
        val notebook = repository.prepareNotebook(book)
        assertEquals("本笔记", notebook.title)
        assertEquals(2, notebook.cardCount)
        assertEquals(0, notebook.withoutQuestionCount)
        assertEquals(setOf(placedQuestion.id, unplacedQuestion.id), notebook.entries.map { it.questionId }.toSet())
    }

    @Test fun frozenLoadKeepsQuestionAndAnswerDuringConcurrentEditsAndNewQuestions() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val originalQuestion = question(db, book, cardId, "原题")
        val repository = BranchReviewRepository(db)
        val plan = repository.prepare(MapRef(book), card.nodeId)
        coroutineScope {
            val edit = async(Dispatchers.IO) {
                StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = card.cardId,
                    expectedRevision = 1, title = "新标题", body = "新答案"))
                KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, originalQuestion.id, 1, KnowledgeData.Question(cardId, "新题")))
                question(db, book, cardId, "新增题")
            }
            val read = async(Dispatchers.IO) { repository.load(plan) }
            assertEquals("冻结答案", read.await().single().card.body)
            edit.await()
        }
        val frozen = repository.load(plan).single()
        assertEquals("原题", (frozen.question.data() as KnowledgeData.Question).prompt)
        assertEquals(1L, frozen.question.revision)
        assertEquals("冻结答案", frozen.card.body)
        assertEquals(1L, frozen.card.revision)
        assertEquals(2, repository.prepare(MapRef(book), card.nodeId).entries.size)
    }

    @Test fun missingHistoricalRevisionFailsInsteadOfUsingCurrentQuestionOrAnswer() = fixture { db, book ->
        val card = card(db, book)
        question(db, book, card.cardId!!)
        val repository = BranchReviewRepository(db)
        val plan = repository.prepare(MapRef(book), card.nodeId)
        val entry = plan.entries.single()
        fun changed(reference: BranchReviewEntryRef) = BranchReviewPlan(plan.ref, plan.branchId, plan.title, 1, 0, listOf(reference))
        reject("BRANCH_REVIEW_QUESTION_REVISION_MISSING") { repository.load(changed(entry.copy(questionRevision = 999))) }
        reject("BRANCH_REVIEW_CARD_REVISION_MISSING") { repository.load(changed(entry.copy(cardRevision = 999))) }
    }

    @Test fun removedQuestionKeepsItsFrozenIdentityButDisappearsFromNewPlan() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val kept = question(db, book, cardId, "保留的问题")
        val removed = question(db, book, cardId, "随后移除的问题")
        val repository = BranchReviewRepository(db)
        val plan = repository.prepare(MapRef(book), card.nodeId)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, removed.id, 1, removed.data, true))
        assertEquals(setOf(kept.id, removed.id), repository.load(plan).map { it.question.id }.toSet())
        assertTrue(repository.load(plan).all { it.question.revision == 1L && !it.question.removed })
        assertEquals(listOf(kept.id), repository.prepare(MapRef(book), card.nodeId).entries.map { it.questionId })
    }

    @Test fun frozenReferencesRejectSwappedCardAndForeignNotebook() = fixture { db, book ->
        val original = card(db, book)
        question(db, book, checkNotNull(original.cardId))
        val other = card(db, book)
        val foreignBook = WorkspaceRepository(db).create("另一笔记", false, PaperStyle.DOTS).id
        val repository = BranchReviewRepository(db)
        val plan = repository.prepare(MapRef(book), original.nodeId)
        val swapped = plan.entries.single().copy(cardId = checkNotNull(other.cardId))
        reject("BRANCH_REVIEW_QUESTION_CARD_CHANGED") {
            repository.load(BranchReviewPlan(plan.ref, plan.branchId, plan.title, 1, 0, listOf(swapped)))
        }
        reject("BRANCH_REVIEW_QUESTION_SCOPE_CHANGED") {
            repository.load(BranchReviewPlan(MapRef(foreignBook), null, plan.title, 1, 0, plan.entries))
        }
    }

    @Test fun missingBranchAndRecycledMapNeverFallBackToNotebookOrMainMap() = fixture { db, book ->
        val target = map(db, book, "空结构图", id())
        val repository = BranchReviewRepository(db)
        reject("BRANCH_REVIEW_BRANCH_UNAVAILABLE") { repository.prepare(MapRef(book, target), id()) }
        val row = db.knowledge().get(target)!!
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, row.id, row.revision, row.data(), true))
        reject("BRANCH_REVIEW_MAP_UNAVAILABLE") { repository.prepare(MapRef(book, target), null) }
    }

    @Test fun startedSessionKeepsArchivedAnswerButGuardedMarkRejectsRecycledCard() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val question = question(db, book, cardId)
        val repository = BranchReviewRepository(db)
        val plan = repository.prepare(MapRef(book), card.nodeId)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = card.nodeId, expectedRevision = 1))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.TRASH_CARD, cardId = card.cardId, expectedRevision = 1))
        assertEquals("冻结答案", repository.load(plan).single().card.body)
        val mark = KnowledgeCommand(id(), book, question.id, 1, KnowledgeData.Question(cardId, "独立问题", ManualState.UNDERSTOOD))
        assertTrue(KnowledgeRepository(db).reviewOutcome(mark, 1) is KnowledgeOutcome.Rejected)
        assertEquals(1L, db.knowledge().get(question.id)!!.revision)
        assertTrue(repository.prepareNotebook(book).entries.isEmpty())
    }

    @Test fun guardedMarkRejectsChangedAnswerWithoutAdvancingQuestionRevision() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val question = question(db, book, cardId)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = card.cardId,
            expectedRevision = 1, title = "知识卡", body = "已修改的答案"))
        val mark = KnowledgeCommand(id(), book, question.id, 1, KnowledgeData.Question(cardId, "独立问题", ManualState.UNDERSTOOD))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.CONFLICT), KnowledgeRepository(db).reviewOutcome(mark, 1))
        assertEquals(1L, db.knowledge().get(question.id)!!.revision)
        assertEquals(ManualState.REVIEW, (db.knowledge().get(question.id)!!.data() as KnowledgeData.Question).state)
    }

    @Test fun committedMarkReplaysReceiptAfterAnswerChangesWithoutSecondRevision() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val question = question(db, book, cardId)
        val mark = KnowledgeCommand(id(), book, question.id, 1, KnowledgeData.Question(cardId, "独立问题", ManualState.UNDERSTOOD))
        try {
            KnowledgeRepository(db) { if (it == KnowledgeFault.AFTER_COMMIT) error("lost response") }.submit(mark, 1)
            fail("Expected response loss")
        } catch (_: IllegalStateException) { }
        assertEquals(2L, db.knowledge().get(question.id)!!.revision)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = card.cardId,
            expectedRevision = 1, title = "知识卡", body = "后续新答案"))
        assertEquals(KnowledgeOutcome.Success(question.id), KnowledgeRepository(db).reviewOutcome(mark, 1))
        assertEquals(2L, db.knowledge().get(question.id)!!.revision)
        assertEquals(mark.operationId, db.knowledge().receipt(mark.operationId)!!.operationId)
    }

    @Test fun failedMarkRollsBackAndOriginalCommandCanBeRetriedOnce() = fixture { db, book ->
        val card = card(db, book)
        val cardId = checkNotNull(card.cardId)
        val question = question(db, book, cardId)
        val mark = KnowledgeCommand(id(), book, question.id, 1, KnowledgeData.Question(cardId, "独立问题", ManualState.UNDERSTOOD))
        assertEquals(KnowledgeOutcome.Unknown, KnowledgeRepository(db) {
            if (it == KnowledgeFault.BEFORE_RECEIPT) error("rollback")
        }.reviewOutcome(mark, 1))
        assertEquals(1L, db.knowledge().get(question.id)!!.revision)
        assertNull(db.knowledge().receipt(mark.operationId))
        assertEquals(KnowledgeOutcome.Success(question.id), KnowledgeRepository(db).reviewOutcome(mark, 1))
        assertEquals(2L, db.knowledge().get(question.id)!!.revision)
    }

    @Test fun collectionAndOrUseCardPropertiesAndKeepEveryIndependentQuestionWithoutWrites() = fixture { db, book ->
        val both = card(db, book)
        val tagOnly = card(db, book)
        val stateOnly = card(db, book)
        val default = card(db, book)
        val unasked = card(db, book)
        val bothId = checkNotNull(both.cardId)
        record(db, book, KnowledgeData.Properties(bothId, ManualState.REVIEW, listOf("数学")))
        record(db, book, KnowledgeData.Properties(tagOnly.cardId!!, ManualState.UNDERSTOOD, listOf("数学")))
        record(db, book, KnowledgeData.Properties(stateOnly.cardId!!, ManualState.REVIEW, listOf("物理")))
        record(db, book, KnowledgeData.Properties(unasked.cardId!!, ManualState.REVIEW, listOf("数学")))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = both.nodeId, expectedRevision = 1))
        val one = question(db, book, bothId, "待复习的独立题")
        val two = record(db, book, KnowledgeData.Question(bothId, "已经理解的独立题仍纳入集合", ManualState.UNDERSTOOD))
        val tagQuestion = question(db, book, tagOnly.cardId!!)
        val stateQuestion = question(db, book, stateOnly.cardId!!)
        val defaultQuestion = record(db, book, KnowledgeData.Question(default.cardId!!, "题目INBOX不等于卡片属性", ManualState.INBOX))
        val removed = question(db, book, bothId, "已移除题")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, removed.id, 1, removed.data, true))
        val foreignBook = WorkspaceRepository(db).create("同标签的其他笔记", false, PaperStyle.DOTS).id
        val foreign = card(db, foreignBook)
        record(db, foreignBook, KnowledgeData.Properties(foreign.cardId!!, ManualState.REVIEW, listOf("数学")))
        question(db, foreignBook, foreign.cardId!!)
        val fullTitle = "集".repeat(120)
        val and = record(db, book, KnowledgeData.Collection(fullTitle, "数学", ManualState.REVIEW))
        val or = record(db, book, KnowledgeData.Collection("数学或待复习", "数学", ManualState.REVIEW, matchAny = true))
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        val andPlan = repository.prepareCollection(book, and.id, 1)
        assertEquals(fullTitle, andPlan.title)
        assertEquals(MapRef(book), andPlan.ref)
        assertNull(andPlan.branchId)
        assertEquals(2, andPlan.cardCount)
        assertEquals(1, andPlan.withoutQuestionCount)
        assertEquals(setOf(one.id, two.id), andPlan.entries.map { it.questionId }.toSet())
        assertEquals(setOf(ManualState.REVIEW, ManualState.UNDERSTOOD), repository.load(andPlan).map { (it.question.data() as KnowledgeData.Question).state }.toSet())
        val orPlan = repository.prepareCollection(book, or.id, 1)
        assertEquals(4, orPlan.cardCount)
        assertEquals(1, orPlan.withoutQuestionCount)
        assertEquals(setOf(one.id, two.id, tagQuestion.id, stateQuestion.id), orPlan.entries.map { it.questionId }.toSet())
        assertFalse(repository.prepare(MapRef(book), null).entries.any { it.cardId == bothId })
        assertTrue(repository.prepareNotebook(book).entries.any { it.questionId == defaultQuestion.id })
        assertEquals(before, authorStamp(db))
    }

    @Test fun collectionDefaultAndRemovedPropertiesUseInboxWhileRecycledCardsStayOut() = fixture { db, book ->
        val default = card(db, book)
        val explicitInbox = card(db, book)
        val removedProperty = card(db, book)
        val understood = card(db, book)
        val recycled = card(db, book)
        record(db, book, KnowledgeData.Properties(explicitInbox.cardId!!, ManualState.INBOX, listOf("数学")))
        val oldProperty = record(db, book, KnowledgeData.Properties(removedProperty.cardId!!, ManualState.UNDERSTOOD, listOf("物理")))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, oldProperty.id, 1, oldProperty.data, true))
        record(db, book, KnowledgeData.Properties(understood.cardId!!, ManualState.UNDERSTOOD))
        record(db, book, KnowledgeData.Properties(recycled.cardId!!, ManualState.INBOX))
        val defaultQuestion = record(db, book, KnowledgeData.Question(default.cardId!!, "问题已理解但卡仍待整理", ManualState.UNDERSTOOD))
        val inboxQuestion = question(db, book, explicitInbox.cardId!!)
        val removedPropertyQuestion = question(db, book, removedProperty.cardId!!)
        val understoodQuestion = question(db, book, understood.cardId!!)
        question(db, book, recycled.cardId!!)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE, nodeId = recycled.nodeId, expectedRevision = 1))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.TRASH_CARD, cardId = recycled.cardId, expectedRevision = 1))
        val inbox = record(db, book, KnowledgeData.Collection("默认待整理", state = ManualState.INBOX))
        val unrestricted = record(db, book, KnowledgeData.Collection("不设条件的集合"))
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        val inboxPlan = repository.prepareCollection(book, inbox.id, 1)
        assertEquals(3, inboxPlan.cardCount)
        assertEquals(0, inboxPlan.withoutQuestionCount)
        assertEquals(setOf(defaultQuestion.id, inboxQuestion.id, removedPropertyQuestion.id), inboxPlan.entries.map { it.questionId }.toSet())
        val all = repository.prepareCollection(book, unrestricted.id, 1)
        assertEquals(4, all.cardCount)
        assertEquals(setOf(defaultQuestion.id, inboxQuestion.id, removedPropertyQuestion.id, understoodQuestion.id), all.entries.map { it.questionId }.toSet())
        assertTrue(repository.load(inboxPlan).any { (it.question.data() as KnowledgeData.Question).state == ManualState.UNDERSTOOD })
        assertEquals(before, authorStamp(db))
    }

    @Test fun emptyCollectionAndMatchedCardsWithoutQuestionsNeverFallBackToNotebook() = fixture { db, book ->
        val outside = card(db, book)
        val noQuestion = card(db, book)
        record(db, book, KnowledgeData.Properties(outside.cardId!!, tags = listOf("数学")))
        record(db, book, KnowledgeData.Properties(noQuestion.cardId!!, tags = listOf("物理")))
        val outsideQuestion = question(db, book, outside.cardId!!)
        val empty = record(db, book, KnowledgeData.Collection("没有匹配卡", "化学"))
        val unasked = record(db, book, KnowledgeData.Collection("匹配但尚未设题", "物理"))
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        assertEquals(listOf(outsideQuestion.id), repository.prepareNotebook(book).entries.map { it.questionId })
        val emptyPlan = repository.prepareCollection(book, empty.id, 1)
        assertEquals(0, emptyPlan.cardCount)
        assertEquals(0, emptyPlan.withoutQuestionCount)
        assertTrue(emptyPlan.entries.isEmpty())
        val unaskedPlan = repository.prepareCollection(book, unasked.id, 1)
        assertEquals(1, unaskedPlan.cardCount)
        assertEquals(1, unaskedPlan.withoutQuestionCount)
        assertTrue(unaskedPlan.entries.isEmpty())
        assertTrue(repository.load(emptyPlan).isEmpty())
        assertTrue(repository.load(unaskedPlan).isEmpty())
        assertEquals(before, authorStamp(db))
    }

    @Test fun collectionPreparationRejectsForeignMissingRecycledAndChangedIdentitiesWithoutFallback() = fixture { db, book ->
        val card = card(db, book)
        val question = question(db, book, card.cardId!!)
        val own = record(db, book, KnowledgeData.Collection("本册集合"))
        val foreignBook = WorkspaceRepository(db).create("其他笔记", false, PaperStyle.DOTS).id
        val foreign = record(db, foreignBook, KnowledgeData.Collection("同名异册集合"))
        val repository = BranchReviewRepository(db)
        var before = authorStamp(db)
        reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, foreign.id, 1) }
        reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, id(), 1) }
        reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, question.id, 1) }
        reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, own.id, 0) }
        reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, own.id, 2) }
        assertEquals(listOf(question.id), repository.prepareCollection(book, own.id, 1).entries.map { it.questionId })
        assertEquals(before, authorStamp(db))
        val changed = KnowledgeData.Collection("新集合名")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, own.id, 1, changed))
        before = authorStamp(db)
        reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, own.id, 1) }
        assertEquals("新集合名", repository.prepareCollection(book, own.id, 2).title)
        assertEquals(before, authorStamp(db))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, own.id, 2, changed, true))
        before = authorStamp(db)
        reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, own.id, 3) }
        assertEquals(before, authorStamp(db))
        val workspace = WorkspaceRepository(db).get(book)
        WorkspaceRepository(db).organize(book, workspace.revision, workspace.folder, workspace.tags, workspace.favorite, true)
        before = authorStamp(db)
        reject("BRANCH_REVIEW_BOOK_UNAVAILABLE") { repository.prepareCollection(book, own.id, 3) }
        assertEquals(before, authorStamp(db))
    }

    @Test fun collectionSessionKeepsFrozenQuestionAndAnswerWhenMembershipAndDefinitionChange() = fixture { db, book ->
        val originalCard = card(db, book)
        val cardId = checkNotNull(originalCard.cardId)
        val properties = record(db, book, KnowledgeData.Properties(cardId, ManualState.REVIEW, listOf("数学")))
        val originalQuestion = question(db, book, cardId, "原题")
        val collection = record(db, book, KnowledgeData.Collection("原集合", "数学", ManualState.REVIEW))
        val repository = BranchReviewRepository(db)
        val beforePrepare = authorStamp(db)
        val plan = repository.prepareCollection(book, collection.id, 1)
        assertEquals(listOf(originalQuestion.id), plan.entries.map { it.questionId })
        assertEquals(beforePrepare, authorStamp(db))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, properties.id, 1, KnowledgeData.Properties(cardId, ManualState.UNDERSTOOD, listOf("物理"))))
        val newlyMatched = card(db, book)
        record(db, book, KnowledgeData.Properties(newlyMatched.cardId!!, ManualState.REVIEW, listOf("数学")))
        val newQuestion = question(db, book, newlyMatched.cardId!!, "新匹配题")
        assertEquals(listOf(newQuestion.id), repository.prepareCollection(book, collection.id, 1).entries.map { it.questionId })
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, originalQuestion.id, 1, KnowledgeData.Question(cardId, "新题", ManualState.UNDERSTOOD)))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = cardId, expectedRevision = 1, title = "新卡名", body = "新答案"))
        val changed = KnowledgeData.Collection("修订后的集合", "物理", ManualState.UNDERSTOOD)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, collection.id, 1, changed))
        var before = authorStamp(db)
        val frozen = repository.load(plan).single()
        assertEquals("原集合", plan.title)
        assertEquals("原题", (frozen.question.data() as KnowledgeData.Question).prompt)
        assertEquals("冻结答案", frozen.card.body)
        assertEquals(1L, frozen.question.revision)
        assertEquals(1L, frozen.card.revision)
        reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, collection.id, 1) }
        val current = repository.prepareCollection(book, collection.id, 2)
        assertEquals("修订后的集合", current.title)
        assertEquals(listOf(originalQuestion.id), current.entries.map { it.questionId })
        assertEquals(2L, current.entries.single().questionRevision)
        assertEquals(2L, current.entries.single().cardRevision)
        val mark = KnowledgeCommand(id(), book, originalQuestion.id, 1,
            (originalQuestion.data as KnowledgeData.Question).copy(state = ManualState.UNDERSTOOD))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.CONFLICT), KnowledgeRepository(db).reviewOutcome(mark, 1))
        assertNull(db.knowledge().receipt(mark.operationId))
        assertEquals(before, authorStamp(db))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, collection.id, 2, changed, true))
        before = authorStamp(db)
        assertEquals("冻结答案", repository.load(plan).single().card.body)
        reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, collection.id, 3) }
        assertEquals(before, authorStamp(db))
    }

    private fun stateCounts(plan: BranchReviewPlan, scope: ReviewQuestionScope, cards: Int, total: Int,
                            selected: Int, unasked: Int, otherOnly: Int) {
        assertEquals(scope, plan.scope)
        assertEquals(cards, plan.cardCount)
        assertEquals(total, plan.totalQuestionCount)
        assertEquals(selected, plan.entries.size)
        assertEquals(unasked, plan.withoutQuestionCount)
        assertEquals(otherOnly, plan.otherStateOnlyCardCount)
    }

    @Test fun fourEntryRangesFilterQuestionStateAndPreserveIndependentQuestionsAndUnplacedCards() = fixture { db, book ->
        val root = id()
        val target = map(db, book, "状态范围图", root)
        val mixed = card(db, book, root, target)
        val mixedId = mixed.cardId!!
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REUSE, cardId = mixedId,
            nodeId = id(), parentId = root, mapId = target))
        val otherOnly = card(db, book, root, target)
        val unasked = card(db, book, root, target)
        val outside = card(db, book, map = target)
        val unplaced = card(db, book)
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.REMOVE_NODE,
            nodeId = unplaced.nodeId, expectedRevision = 1))
        for (owner in listOf(mixedId, otherOnly.cardId!!, unasked.cardId!!, unplaced.cardId!!)) {
            record(db, book, KnowledgeData.Properties(owner,
                if (owner == mixedId) ManualState.UNDERSTOOD else ManualState.REVIEW, listOf("同范围")))
        }
        record(db, book, KnowledgeData.Properties(outside.cardId!!, ManualState.REVIEW, listOf("范围外")))
        val pending = question(db, book, mixedId, "新题默认待复习")
        assertEquals(ManualState.REVIEW, (pending.data as KnowledgeData.Question).state)
        val understood = record(db, book, KnowledgeData.Question(mixedId, "同卡已理解题", ManualState.UNDERSTOOD))
        val inbox = record(db, book, KnowledgeData.Question(mixedId, "同卡待整理题", ManualState.INBOX))
        val excluded = record(db, book, KnowledgeData.Question(otherOnly.cardId!!, "卡片待复习但题已理解", ManualState.UNDERSTOOD))
        val outsideQuestion = question(db, book, outside.cardId!!)
        val unplacedQuestion = question(db, book, unplaced.cardId!!)
        val collection = record(db, book, KnowledgeData.Collection("同范围集合", "同范围"))
        val foreignBook = WorkspaceRepository(db).create("外册同范围", false, PaperStyle.BLANK).id
        val foreign = card(db, foreignBook)
        record(db, foreignBook, KnowledgeData.Properties(foreign.cardId!!, ManualState.REVIEW, listOf("同范围")))
        val foreignQuestion = question(db, foreignBook, foreign.cardId!!)
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        val defaults = listOf(repository.prepareNotebook(book), repository.prepareCollection(book, collection.id, 1),
            repository.prepare(MapRef(book, target), null), repository.prepare(MapRef(book, target), root))
        val selected = listOf(repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY),
            repository.prepareCollection(book, collection.id, 1, ReviewQuestionScope.REVIEW_ONLY),
            repository.prepare(MapRef(book, target), null, ReviewQuestionScope.REVIEW_ONLY),
            repository.prepare(MapRef(book, target), root, ReviewQuestionScope.REVIEW_ONLY))
        val cards = listOf(5, 4, 4, 3)
        val totals = listOf(6, 5, 5, 4)
        val branchIds = setOf(pending.id, understood.id, inbox.id, excluded.id)
        val allIds = listOf(branchIds + outsideQuestion.id + unplacedQuestion.id, branchIds + unplacedQuestion.id,
            branchIds + outsideQuestion.id, branchIds)
        val selectedIds = listOf(setOf(pending.id, outsideQuestion.id, unplacedQuestion.id),
            setOf(pending.id, unplacedQuestion.id), setOf(pending.id, outsideQuestion.id), setOf(pending.id))
        defaults.indices.forEach { index ->
            stateCounts(defaults[index], ReviewQuestionScope.ALL, cards[index], totals[index], totals[index], 1, 0)
            assertEquals(allIds[index], defaults[index].entries.map { it.questionId }.toSet())
            stateCounts(selected[index], ReviewQuestionScope.REVIEW_ONLY, cards[index], totals[index], selectedIds[index].size, 1, 1)
            assertEquals(selectedIds[index], selected[index].entries.map { it.questionId }.toSet())
            assertFalse(selected[index].entries.any { it.questionId == foreignQuestion.id })
            assertTrue(repository.load(selected[index]).all { (it.question.data() as KnowledgeData.Question).state == ManualState.REVIEW })
        }
        assertEquals(before, authorStamp(db))
    }

    @Test fun noPendingQuestionsRemainDistinctFromEmptyAndTrulyUnaskedCollections() = fixture { db, book ->
        val understood = card(db, book)
        val inbox = card(db, book)
        val unasked = card(db, book)
        val outside = card(db, book)
        for (owner in listOf(understood.cardId!!, inbox.cardId!!, unasked.cardId!!)) {
            record(db, book, KnowledgeData.Properties(owner, ManualState.REVIEW,
                if (owner == unasked.cardId) listOf("其他状态", "真无题") else listOf("其他状态")))
        }
        record(db, book, KnowledgeData.Question(understood.cardId!!, "已经理解", ManualState.UNDERSTOOD))
        record(db, book, KnowledgeData.Question(inbox.cardId!!, "仍待整理", ManualState.INBOX))
        val outsideQuestion = question(db, book, outside.cardId!!)
        val otherStates = record(db, book, KnowledgeData.Collection("有题但无待复习", "其他状态"))
        val noCards = record(db, book, KnowledgeData.Collection("没有匹配卡", "不存在"))
        val noQuestions = record(db, book, KnowledgeData.Collection("只有真无题卡", "真无题"))
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        stateCounts(repository.prepareCollection(book, otherStates.id, 1), ReviewQuestionScope.ALL, 3, 2, 2, 1, 0)
        val selected = repository.prepareCollection(book, otherStates.id, 1, ReviewQuestionScope.REVIEW_ONLY)
        stateCounts(selected, ReviewQuestionScope.REVIEW_ONLY, 3, 2, 0, 1, 2)
        val empty = repository.prepareCollection(book, noCards.id, 1, ReviewQuestionScope.REVIEW_ONLY)
        stateCounts(empty, ReviewQuestionScope.REVIEW_ONLY, 0, 0, 0, 0, 0)
        val unaskedPlan = repository.prepareCollection(book, noQuestions.id, 1, ReviewQuestionScope.REVIEW_ONLY)
        stateCounts(unaskedPlan, ReviewQuestionScope.REVIEW_ONLY, 1, 0, 0, 1, 0)
        assertTrue(repository.load(selected).isEmpty())
        assertTrue(repository.load(empty).isEmpty())
        assertTrue(repository.load(unaskedPlan).isEmpty())
        assertEquals(listOf(outsideQuestion.id), repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY)
            .entries.map { it.questionId })
        assertEquals(before, authorStamp(db))
    }

    @Test fun stateMarksChangeOnlyNextRoundWhileCurrentQuestionsAndAnswerStayFrozen() = fixture { db, book ->
        val owner = card(db, book, body = "原始冻结答案")
        val first = question(db, book, owner.cardId!!, "原题一")
        val second = question(db, book, owner.cardId!!, "原题二")
        val repository = BranchReviewRepository(db)
        val plan = repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY)
        stateCounts(plan, ReviewQuestionScope.REVIEW_ONLY, 1, 2, 2, 0, 0)
        val knowledge = KnowledgeRepository(db)
        val markFirst = KnowledgeCommand(id(), book, first.id, 1,
            (first.data as KnowledgeData.Question).copy(state = ManualState.UNDERSTOOD))
        assertEquals(KnowledgeOutcome.Success(first.id), knowledge.reviewOutcome(markFirst, 1))
        var before = authorStamp(db)
        val next = repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY)
        stateCounts(next, ReviewQuestionScope.REVIEW_ONLY, 1, 2, 1, 0, 0)
        assertEquals(listOf(second.id), next.entries.map { it.questionId })
        assertTrue(repository.load(plan).all { it.question.revision == 1L &&
            (it.question.data() as KnowledgeData.Question).state == ManualState.REVIEW })
        assertEquals(before, authorStamp(db))
        val markSecond = KnowledgeCommand(id(), book, second.id, 1,
            (second.data as KnowledgeData.Question).copy(state = ManualState.UNDERSTOOD))
        assertEquals(KnowledgeOutcome.Success(second.id), knowledge.reviewOutcome(markSecond, 1))
        StudyRepository(db).submit(StudyCommand(id(), book, StudyAction.EDIT, cardId = owner.cardId,
            expectedRevision = 1, title = "后续标题", body = "后续答案"))
        before = authorStamp(db)
        stateCounts(repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY), ReviewQuestionScope.REVIEW_ONLY, 1, 2, 0, 0, 1)
        stateCounts(repository.prepareNotebook(book), ReviewQuestionScope.ALL, 1, 2, 2, 0, 0)
        val frozen = repository.load(plan)
        assertEquals(setOf(first.id, second.id), frozen.map { it.question.id }.toSet())
        assertTrue(frozen.all { (it.question.data() as KnowledgeData.Question).state == ManualState.REVIEW &&
            it.question.revision == 1L && it.card.revision == 1L && it.card.body == "原始冻结答案" })
        assertEquals(before, authorStamp(db))
    }

    @Test fun removedQuestionsDoNotTurnOtherStateOnlyCardsIntoFalseUnaskedCards() = fixture { db, book ->
        val owner = card(db, book)
        val pending = question(db, book, owner.cardId!!)
        val understood = record(db, book, KnowledgeData.Question(owner.cardId!!, "仍保存的已理解题", ManualState.UNDERSTOOD))
        val repository = BranchReviewRepository(db)
        val plan = repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, pending.id, 1, pending.data, true))
        var before = authorStamp(db)
        stateCounts(repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY), ReviewQuestionScope.REVIEW_ONLY, 1, 1, 0, 0, 1)
        stateCounts(repository.prepareNotebook(book), ReviewQuestionScope.ALL, 1, 1, 1, 0, 0)
        assertEquals(pending.id, repository.load(plan).single().question.id)
        assertFalse(repository.load(plan).single().question.removed)
        assertEquals(before, authorStamp(db))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, understood.id, 1, understood.data, true))
        before = authorStamp(db)
        stateCounts(repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY), ReviewQuestionScope.REVIEW_ONLY, 1, 0, 0, 1, 0)
        assertEquals(pending.id, repository.load(plan).single().question.id)
        assertEquals(before, authorStamp(db))
    }

    @Test fun reviewOnlyStillRejectsInvalidCollectionMapAndBranchInsteadOfReturningAnEmptyFallback() = fixture { db, book ->
        val owner = card(db, book)
        val question = record(db, book, KnowledgeData.Question(owner.cardId!!, "全部已理解", ManualState.UNDERSTOOD))
        val collection = record(db, book, KnowledgeData.Collection("有效但本轮无题"))
        val foreignBook = WorkspaceRepository(db).create("外册", false, PaperStyle.BLANK).id
        val foreign = record(db, foreignBook, KnowledgeData.Collection("同名异册"))
        val emptyMap = map(db, book, "无待复习题的图")
        val repository = BranchReviewRepository(db)
        val before = authorStamp(db)
        for (scope in ReviewQuestionScope.entries) {
            reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, foreign.id, 1, scope) }
            reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, id(), 1, scope) }
            reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, question.id, 1, scope) }
            reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, collection.id, 0, scope) }
            reject("BRANCH_REVIEW_COLLECTION_VERSION_CHANGED") { repository.prepareCollection(book, collection.id, 2, scope) }
            reject("BRANCH_REVIEW_MAP_UNAVAILABLE") { repository.prepare(MapRef(book, id()), null, scope) }
            reject("BRANCH_REVIEW_BRANCH_UNAVAILABLE") { repository.prepare(MapRef(book, emptyMap), id(), scope) }
        }
        assertEquals(before, authorStamp(db))
        val mapRow = db.knowledge().get(emptyMap)!!
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, emptyMap, 1, mapRow.data(), true))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(), book, collection.id, 1, collection.data, true))
        val after = authorStamp(db)
        for (scope in ReviewQuestionScope.entries) {
            reject("BRANCH_REVIEW_MAP_UNAVAILABLE") { repository.prepare(MapRef(book, emptyMap), null, scope) }
            reject("BRANCH_REVIEW_COLLECTION_UNAVAILABLE") { repository.prepareCollection(book, collection.id, 2, scope) }
        }
        assertEquals(after, authorStamp(db))
    }

    @Test fun questionStateAndPinnedRevisionComeFromOneCoherentRoomSnapshot() = fixture { db, book ->
        val owner = card(db, book)
        val first = question(db, book, owner.cardId!!, "原待复习题")
        val second = record(db, book, KnowledgeData.Question(owner.cardId!!, "原已理解题", ManualState.UNDERSTOOD))
        val repository = BranchReviewRepository(db)
        val snapshots = coroutineScope {
            val reads = List(4) { async(Dispatchers.IO) { repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY) } }
            val write = async(Dispatchers.IO) {
                db.withTransaction {
                    val knowledge = KnowledgeRepository(db)
                    knowledge.submit(KnowledgeCommand(id(), book, first.id, 1,
                        (first.data as KnowledgeData.Question).copy(state = ManualState.UNDERSTOOD)))
                    knowledge.submit(KnowledgeCommand(id(), book, second.id, 1,
                        (second.data as KnowledgeData.Question).copy(state = ManualState.REVIEW)))
                }
            }
            val result = reads.map { it.await() }
            write.await()
            result
        }
        val before = authorStamp(db)
        for (plan in snapshots) {
            stateCounts(plan, ReviewQuestionScope.REVIEW_ONLY, 1, 2, 1, 0, 0)
            val selected = repository.load(plan).single()
            assertEquals(ManualState.REVIEW, (selected.question.data() as KnowledgeData.Question).state)
            assertTrue((selected.reference.questionId to selected.reference.questionRevision) in
                setOf(first.id to 1L, second.id to 2L))
        }
        val next = repository.prepareNotebook(book, ReviewQuestionScope.REVIEW_ONLY)
        assertEquals(second.id, next.entries.single().questionId)
        assertEquals(2L, next.entries.single().questionRevision)
        assertEquals(before, authorStamp(db))
    }
}
