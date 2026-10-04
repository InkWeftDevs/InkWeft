// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class CardTrashCommandTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private val book="00000000-0000-0000-0000-000000000002"
    private val card="00000000-0000-0000-0000-000000000003"
    private fun command(impact:String="")=StudyCommand(id,book,StudyAction.TRASH_CARD,cardId=card,expectedRevision=1,expectedTrashImpact=impact)
    @Test fun absentImpactRetainsLegacyDigestWhileConfirmedSetChangesIdentity(){
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{out->
            out.writeUTF("inkweft.study.v1")
            listOf(id,book,"TRASH_CARD",card,"","","","").forEach(out::writeUTF)
            out.writeInt(0);out.writeLong(1);out.writeDouble(40.0);out.writeDouble(80.0);out.writeBoolean(false)
        }
        assertEquals(ContentTransfer.hash(bytes.toByteArray()),command().digest())
        assertNotEquals(command().digest(),command("a".repeat(64)).digest())
        assertNotEquals(command("a".repeat(64)).digest(),command("b".repeat(64)).digest())
    }
    @Test fun impactMustBeAHashOnARecycleCommand(){
        try{command("not-a-fingerprint");fail()}catch(_:IllegalArgumentException){}
        try{StudyCommand(id,book,StudyAction.RESTORE_CARD,cardId=card,expectedRevision=1,expectedTrashImpact="a".repeat(64));fail()}catch(_:IllegalArgumentException){}
    }
}
