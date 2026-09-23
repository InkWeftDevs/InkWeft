// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NoteDraftTest {
    private val note = Note("c80eab80-fc6b-4d26-a2e2-872355622a6a", 1, "概率", "旧备注")
    private fun id() = UUID.randomUUID().toString()

    @Test fun emptyContentRemainsEmpty() {
        val d = NoteDraft(note).edit(text = "").beginSave(::id)
        val saved = note.copy(revision = 2, text = "")
        assertEquals("", d.finish(d.pending!!, SaveResult.Committed(saved)).text)
    }
    @Test fun draftKeepsOriginalExpectedRevision() {
        val d = NoteDraft(note).edit(text = "我的输入").beginSave(::id)
        assertEquals(1L, d.pending!!.expectedRevision)
    }
    @Test fun saveFailurePreservesTextAndCommandIdentity() {
        val d = NoteDraft(note).edit(text = "新内容").beginSave(::id)
        val failed = d.finish(d.pending!!, SaveResult.OutcomeUnknown)
        val retry = failed.beginSave { error("Must reuse original command") }
        assertEquals(d.pending, retry.pending)
        assertEquals("新内容", retry.text)
    }
    @Test(expected = IllegalArgumentException::class)
    fun unknownCommandCannotChangePayload() {
        val d = NoteDraft(note).edit(text = "新内容").beginSave(::id)
        d.finish(d.pending!!, SaveResult.OutcomeUnknown).edit(text = "又改了")
    }
    @Test fun conflictDoesNotRebaseOrLoseDraft() {
        val d = NoteDraft(note).edit(text = "我的版本").beginSave(::id)
        val conflict = d.finish(d.pending!!, SaveResult.Conflict(note.copy(revision = 2)))
        assertEquals(1L, conflict.base.revision)
        assertEquals("我的版本", conflict.text)
        assertEquals(SavePhase.CONFLICT, conflict.phase)
    }
    @Test fun twoNotesHaveIndependentDrafts() {
        val first = NoteDraft(note).edit(text = "第一份")
        val second = NoteDraft(note.copy(id = id())).edit(text = "第二份")
        assertNotEquals(first.base.id, second.base.id)
        assertEquals("第一份", first.text)
    }
    @Test fun unknownOutcomeCannotShowSaved() {
        val d = NoteDraft(note).edit(text = "改动").beginSave(::id)
        val failed = d.finish(d.pending!!, SaveResult.OutcomeUnknown)
        assertTrue(failed.dirty)
        assertEquals(SavePhase.UNKNOWN, failed.phase)
    }
    @Test fun digestIncludesExpectedRevisionAndPayload() {
        val cmd = SaveNote(id(), note.id, 1, "标题", "")
        assertEquals(64, cmd.digest().length)
        assertNotEquals(cmd.digest(), cmd.copy(text = "x").digest())
        assertNotEquals(cmd.digest(), cmd.copy(expectedRevision = 2).digest())
        assertEquals(cmd.digest(), cmd.copy().digest())
    }
    @Test fun lengthDelimitedDigestAvoidsFieldConcatenationAmbiguity() {
        val cmd = SaveNote(id(), note.id, 1, "ab", "c")
        assertNotEquals(cmd.digest(), cmd.copy(title = "a", text = "bc").digest())
    }
    @Test(expected = IllegalArgumentException::class)
    fun mismatchedReceiptCannotReplaceDraft() {
        val d = NoteDraft(note).edit(text = "未确认内容").beginSave(::id)
        d.finish(d.pending!!, SaveResult.Committed(note.copy(revision = 2, text = "其他内容")))
    }
    @Test fun newOperationGetsNewIdentity() {
        val d = NoteDraft(note).edit(text = "第一改").beginSave(::id)
        val accepted = d.finish(d.pending!!, SaveResult.Committed(note.copy(revision = 2, text = "第一改")))
        val next = accepted.edit(text = "第二改").beginSave(::id)
        assertNotEquals(d.pending!!.commandId, next.pending!!.commandId)
    }
    @Test(expected = IllegalArgumentException::class)
    fun outOfBoundsTextRejected() {
        NoteDraft(note).edit(text = "a".repeat(100_001))
    }
}
