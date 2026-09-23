// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

class DraftReloadTest {
    private val base = Note("c80eab80-fc6b-4d26-a2e2-872355622a6a", 1, "概率", "旧内容")
    private val saved = base.copy(revision = 2, text = "已保存的其他版本")
    private val requested = NoteDraft(base).edit(text = "冲突草稿").copy(phase = SavePhase.CONFLICT)

    @Test fun explicitReloadReplacesTheUnchangedRequestedDraft() {
        assertEquals(NoteDraft(saved), requested.acceptExplicitReload(requested, saved))
    }

    @Test fun lateReadDoesNotOverwriteNewTyping() {
        val newer = requested.edit(text = "读取期间新输入")
        assertSame(newer, newer.acceptExplicitReload(requested, saved))
        assertEquals("读取期间新输入", newer.text)
    }

    @Test fun structurallyEqualReplacementStillInvalidatesOldRequest() {
        val newer = requested.copy()
        assertEquals(requested, newer)
        assertNotSame(requested, newer)
        assertSame(newer, newer.acceptExplicitReload(requested, saved))
    }

    @Test fun lateReadCannotInterruptNewSave() {
        val saving = requested.edit(text = "新修改").beginSave()
        assertSame(saving, saving.acceptExplicitReload(requested, saved))
        assertNotNull(saving.pending)
    }

    @Test fun savingDraftCannotBeDiscardedEvenIfPassedAsRequest() {
        val saving = NoteDraft(base).edit(text = "待提交").beginSave()
        assertSame(saving, saving.acceptExplicitReload(saving, saved))
    }

    @Test fun unknownOutcomeCannotLoseItsOriginalCommand() {
        val saving = NoteDraft(base).edit(text = "待确认").beginSave()
        val unknown = saving.finish(saving.pending!!, SaveResult.OutcomeUnknown)
        assertSame(unknown, unknown.acceptExplicitReload(unknown, saved))
        assertEquals(saving.pending, unknown.pending)
    }

    @Test fun duplicateLateReadCannotUndoAnAlreadyAcceptedRead() {
        val first = requested.acceptExplicitReload(requested, saved)
        val olderResult = base.copy(text = "迟到的旧读取")
        assertSame(first, first.acceptExplicitReload(requested, olderResult))
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignNoteCannotReplaceDraft() {
        requested.acceptExplicitReload(requested,
            saved.copy(id = "49eeb8c5-6f67-4c3d-a1dd-2a60845a3ce9"))
    }
}
