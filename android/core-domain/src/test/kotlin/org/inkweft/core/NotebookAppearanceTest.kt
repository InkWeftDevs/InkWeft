// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotebookAppearanceTest {
    private val id=UUID.randomUUID().toString()
    private fun note()=Note(id,1,"旧标题","已保存正文")
    @Test fun coverKeysAreUniqueAndUnknownIsNotAContentWrite(){assertEquals(NotebookCover.entries.size,NotebookCover.entries.map{it.key}.toSet().size);assertEquals(NotebookCover.AUTO,NotebookCover.fromKey("unknown"));assertFalse(NotebookCover.validKey("unknown"))}
    @Test fun automaticCoverDependsOnIdentityNotTitle(){assertEquals(NotebookCover.AUTO.resolved(id),NotebookCover.AUTO.resolved(id));assertNotEquals(NotebookCover.AUTO,NotebookCover.AUTO.resolved(id))}
    @Test fun explicitCoverDoesNotDependOnIdentity(){assertEquals(NotebookCover.WAVE,NotebookCover.WAVE.resolved(id))}
    @Test fun titleValidationRejectsEmptyControlsAndTooLong(){for(s in listOf("","  ","a\nb","a\u0000b","a".repeat(121)))assertFalse(RenameNote.validTitle(s));assertTrue(RenameNote.validTitle("概率 / 条件 · 数学"))}
    @Test fun semanticDigestUsesCommandAndExactTitle(){val c=RenameNote(UUID.randomUUID().toString(),id,1,"新标题");assertEquals(c.digest(),c.copy().digest());assertNotEquals(c.digest(),c.copy(title="另一标题").digest());assertNotEquals(c.digest(),c.copy(expectedRevision=2).digest())}
    @Test fun renameRetainsDirtyBody(){val n=note();val d=NoteDraft(n).edit(text="尚未保存的理解");val after=d.acceptRenamedHead(n,d.title,n.copy(revision=2,title="新标题"));assertEquals("新标题",after.title);assertEquals("尚未保存的理解",after.text);assertEquals(n.text,after.base.text);assertTrue(after.dirty)}
    @Test fun cleanDraftTracksNewTitle(){val n=note();val after=NoteDraft(n).acceptRenamedHead(n,n.title,n.copy(revision=2,title="新标题"));assertFalse(after.dirty);assertEquals(2L,after.base.revision)}
    @Test fun laterTitleEditNotOverwritten(){val n=note();val d=NoteDraft(n).edit(title="稍后输入");val after=d.acceptRenamedHead(n,n.title,n.copy(revision=2,title="确认的名称"));assertEquals("稍后输入",after.title);assertTrue(after.dirty)}
    @Test fun staleDraftCannotRebase(){val n=note();val d=NoteDraft(n.copy(revision=3));assertSame(d,d.acceptRenamedHead(n,n.title,n.copy(revision=2,title="新标题")))}
    @Test fun pendingDraftCannotRebase(){val n=note();val d=NoteDraft(n).edit(text="pending").beginSave();assertSame(d,d.acceptRenamedHead(n,n.title,n.copy(revision=2,title="新标题")))}
    @Test fun renameCannotChangeBody(){val n=note();try{NoteDraft(n).acceptRenamedHead(n,n.title,n.copy(revision=2,text="lost"));fail("body rewrite accepted")}catch(_:IllegalArgumentException){}}
    @Test fun renameKeepsIdentityAndDoesNotNeedUniquenessOfTitle(){val a=RenameNote(UUID.randomUUID().toString(),id,1,"相同标题");val b=RenameNote(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"相同标题");assertNotEquals(a.noteId,b.noteId);assertNotEquals(a.digest(),b.digest())}
}
