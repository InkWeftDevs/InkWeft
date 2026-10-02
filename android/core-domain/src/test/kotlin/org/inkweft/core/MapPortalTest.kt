// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MapPortalTest {
    private fun id()=UUID.randomUUID().toString()
    private fun rejected(block:()->Unit){try{block();fail("Expected rejected payload")}catch(_:IllegalArgumentException){}}

    @Test fun namedMapsRoundTripWithAnExplicitVersionedTag(){
        val portal=KnowledgeData.MapPortal(id(),id(),id())
        val payload=KnowledgeCodec.encode(portal)
        assertEquals(portal,KnowledgeCodec.decode(payload))
        assertTrue(payload.toString(Charsets.ISO_8859_1).contains("MAP_PORTAL_V1"))
    }

    @Test fun nullAlwaysRepresentsMainMapInEitherDirection(){
        val named=id();val node=id()
        val values=listOf(KnowledgeData.MapPortal(null,node,named),KnowledgeData.MapPortal(named,node,null))
        values.forEach{assertEquals(it,KnowledgeCodec.decode(KnowledgeCodec.encode(it)))}
        assertNotEquals(values[0],values[1])
    }

    @Test fun sameMapAndMainToMainAreRejected(){
        val map=id()
        rejected{KnowledgeCodec.encode(KnowledgeData.MapPortal(map,id(),map))}
        rejected{KnowledgeCodec.encode(KnowledgeData.MapPortal(null,id(),null))}
    }

    @Test fun eachEndpointIdMustBeAUuid(){
        rejected{KnowledgeCodec.encode(KnowledgeData.MapPortal("wrong",id(),id()))}
        rejected{KnowledgeCodec.encode(KnowledgeData.MapPortal(id(),"wrong",null))}
        rejected{KnowledgeCodec.encode(KnowledgeData.MapPortal(null,id(),"wrong"))}
    }

    @Test fun trailingBytesAndUnknownPortalVersionsNeverFallBack(){
        rejected{KnowledgeCodec.decode(KnowledgeCodec.encode(KnowledgeData.MapPortal(null,id(),id()))+byteArrayOf(0))}
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{it.writeInt(0x49574b31);it.writeUTF("MAP_PORTAL_V3")}
        try{KnowledgeCodec.decode(bytes.toByteArray());fail("Expected unknown version")}catch(e:IllegalStateException){assertEquals("UNKNOWN_KNOWLEDGE_KIND",e.message)}
    }

    @Test fun portalDoesNotChangeContentTargetKindsOrLegacyMapRecords(){
        assertEquals(listOf("NOTE","PAGE","CARD","ANCHOR"),TargetKind.entries.map{it.name})
        val link=KnowledgeData.Link(TargetRef(TargetKind.PAGE,id()),TargetRef(TargetKind.CARD,id()),pinnedRevision=1)
        assertEquals(link,KnowledgeCodec.decode(KnowledgeCodec.encode(link)))
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{it.writeInt(0x49574b31);it.writeUTF("MAP");it.writeUTF("旧图")}
        assertEquals(KnowledgeData.MapDefinition("旧图"),KnowledgeCodec.decode(bytes.toByteArray()))
    }

    @Test fun commandsFreezeExactSourceOccurrenceTargetAndRemovalState(){
        val operation=id();val book=id();val record=id();val source=id();val node=id();val target=id()
        val portal=KnowledgeData.MapPortal(source,node,target)
        val command=KnowledgeCommand(operation,book,record,4,portal)
        val digest=command.digest();command.payload.fill(0)
        assertEquals(portal,command.data);assertEquals(digest,command.digest())
        assertNotEquals(digest,KnowledgeCommand(operation,book,record,4,portal.copy(sourceNodeId=id())).digest())
        assertNotEquals(digest,KnowledgeCommand(operation,book,record,4,portal.copy(targetMapId=null)).digest())
        assertNotEquals(digest,KnowledgeCommand(operation,book,record,4,portal,true).digest())
    }

    @Test fun exactBranchesUseV2WhileWholeMapBytesRemainV1(){
        val portal=KnowledgeData.MapPortal(id(),id(),null,id())
        val payload=KnowledgeCodec.encode(portal)
        assertEquals(portal,KnowledgeCodec.decode(payload))
        assertTrue(payload.toString(Charsets.ISO_8859_1).contains("MAP_PORTAL_V2"))
        val whole=portal.copy(targetBranchId=null)
        val legacy=ByteArrayOutputStream()
        DataOutputStream(legacy).use{d->d.writeInt(0x49574b31);d.writeUTF("MAP_PORTAL_V1");d.writeUTF(whole.sourceMapId!!);d.writeUTF(whole.sourceNodeId);d.writeUTF("")}
        assertArrayEquals(legacy.toByteArray(),KnowledgeCodec.encode(whole))
        assertEquals(whole,KnowledgeCodec.decode(legacy.toByteArray()))
    }

    @Test fun branchPayloadRejectsInvalidIdentityEmptyV2AndTrailingBytes(){
        val portal=KnowledgeData.MapPortal(null,id(),id(),id())
        rejected{KnowledgeCodec.encode(portal.copy(targetBranchId="wrong"))}
        rejected{KnowledgeCodec.decode(KnowledgeCodec.encode(portal)+byteArrayOf(0))}
        val malformed=ByteArrayOutputStream()
        DataOutputStream(malformed).use{d->d.writeInt(0x49574b31);d.writeUTF("MAP_PORTAL_V2");d.writeUTF("");d.writeUTF(portal.sourceNodeId);d.writeUTF(portal.targetMapId!!);d.writeUTF("")}
        rejected{KnowledgeCodec.decode(malformed.toByteArray())}
    }

    @Test fun changingBranchChangesFrozenCommandDigest(){
        val operation=id();val book=id();val record=id()
        val portal=KnowledgeData.MapPortal(null,id(),id(),id())
        val command=KnowledgeCommand(operation,book,record,0,portal)
        val digest=command.digest();command.payload.fill(0)
        assertEquals(portal,command.data);assertEquals(digest,command.digest())
        assertNotEquals(digest,KnowledgeCommand(operation,book,record,0,portal.copy(targetBranchId=id())).digest())
        assertNotEquals(digest,KnowledgeCommand(operation,book,record,0,portal.copy(targetBranchId=null)).digest())
    }
}
