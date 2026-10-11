// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

class LayerCapacityRegressionTest {
    private fun refs(count:Int,kind:LayerContentKind=LayerContentKind.INK)=List(count){LayerContent(kind,UUID(kind.ordinal+1L,it+1L).toString())}
    private fun reject(block:()->Unit){assertThrows(IllegalArgumentException::class.java){block()}}

    @Test fun ownershipBeyondThePreviousCountAndByteCeilingsRoundTrips(){
        val ink=refs(60_000)
        val objects=refs(PageObjectCodec.MAX_RECORDS,LayerContentKind.OBJECT)
        val annotations=refs(PageAuthoring.MAX_ANNOTATIONS,LayerContentKind.ANNOTATION)
        val all=ink+objects+annotations
        val state=PageAuthoring(UserLayers.legacy(all));val encoded=PageAuthoringCodec.encode(state)
        assertEquals(0x49574134,ByteBuffer.wrap(encoded).int)
        assertTrue(encoded.size>PageAuthoringCodec.LEGACY_MAX_BYTES)
        val restored=PageAuthoringCodec.decode(encoded)
        assertEquals(all.toSet(),restored.layers.memberships.map{it.content}.toSet())
        assertTrue(all.all{restored.layers.visible(it)&&restored.layers.editable(it)})
        assertArrayEquals(encoded,PageAuthoringCodec.encode(restored))
        val extra=LayerContent(LayerContentKind.INK,UUID(9,1).toString())
        assertTrue(state.layers.assignNew(listOf(extra)).visible(extra))
        val header=PageAuthoringCodec.encodeHeader(state)
        assertTrue(header.size<100_000);assertTrue(PageAuthoringCodec.isHeader(header))
        assertEquals(annotations.toSet(),PageAuthoringCodec.decodeHeader(header).layers.memberships.map{it.content}.toSet())
        reject{PageAuthoringCodec.decode(header)} // A storage header cannot masquerade as a complete transfer.
    }

    @Test fun legacySizedStateKeepsTheFrozenIwa3BytesAndReadsAllPreviousHeaders(){
        // Frozen V81 IWA3 vector: one base-layer ink identity, no annotation.
        val golden=Base64.getDecoder().decode("SVdBMwAAAAEAJDAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMQAJ5Z+656GA5bGCAQABACQwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEAAAABAAAkMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwYWJjACQwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEAAAAAAAAAAAAAAAAAAAAA")
        val ref=LayerContent(LayerContentKind.INK,"00000000-0000-0000-0000-000000000abc")
        val state=PageAuthoring(UserLayers.legacy(listOf(ref)))
        assertArrayEquals(golden,PageAuthoringCodec.encode(state))
        for(magic in listOf(0x49574131,0x49574132,0x49574133)){
            val bytes=if(magic<0x49574133)golden.copyOf(golden.size-4)else golden.copyOf()
            ByteBuffer.wrap(bytes).putInt(magic)
            assertEquals(UserLayers.DEFAULT_ID,PageAuthoringCodec.decode(bytes).layers.layer(ref)?.id)
        }
    }

    @Test fun compactStackPreservesNonCanonicalIdentitySpellingAndEmbeddedPageRoundtrip(){
        val ordinary=refs(UserLayers.LEGACY_MAX_CONTENT)
        val unusual=listOf(LayerContent(LayerContentKind.INK,"ABCDEFAB-0000-0000-0000-000000000001"),
            LayerContent(LayerContentKind.INK,"1-1-1-1-1"))
        val layer=UserLayer("ABCDEFAB-0000-0000-0000-000000000002","大页层")
        val state=PageAuthoring(UserLayers(listOf(layer),layer.id,(ordinary+unusual).map{LayerMembership(it,layer.id)}))
        val bytes=PageAuthoringCodec.encode(state);assertEquals(0x49574134,ByteBuffer.wrap(bytes).int)
        val restored=PageAuthoringCodec.decode(bytes)
        unusual.forEach{assertEquals(layer.id,restored.layers.layer(it)?.id)}
        assertArrayEquals(bytes,PageAuthoringCodec.encode(restored))
        val page=InkPageFile("紧凑图层副本","",emptyList(),authoring=state)
        assertArrayEquals(bytes,PageAuthoringCodec.encode(checkNotNull(InkPageFile.decode(page.encode()).authoring)))
    }

    @Test fun largeDeletedProjectionStaysDisjointAndCannotReassignOriginalIdentity(){
        val retained=refs(15_000);val deleted=refs(15_000,LayerContentKind.OBJECT)
        val other=UserLayer(UUID(8,1).toString(),"迁入层")
        val layers=UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),other),UserLayers.DEFAULT_ID,
            retained.map{LayerMembership(it,UserLayers.DEFAULT_ID)},deleted)
        val chosen=retained.take(InkSelectionEdit.MAX_SELECTED)
        val moved=layers.transfer(chosen,other.id)
        assertTrue(chosen.all{moved.layer(it)?.id==other.id})
        assertTrue(retained.drop(chosen.size).all{moved.layer(it)?.id==UserLayers.DEFAULT_ID})
        assertTrue(deleted.all{moved.owns(it)&&moved.isDeleted(it)&&!moved.visible(it)})
        reject{moved.assignNew(listOf(deleted.first()))}
        reject{UserLayers(memberships=listOf(LayerMembership(retained.first(),UserLayers.DEFAULT_ID)),deleted=listOf(retained.first()))}
    }

    @Test fun deletingHalfOfAFullRetainedStackDoesNotFallBackToOversizedLegacyEncoding(){
        val refs=refs(40_000);val second=UserLayer(UUID(8,2).toString(),"待删除层")
        val before=UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),second),UserLayers.DEFAULT_ID,
            refs.mapIndexed{i,c->LayerMembership(c,if(i<20_000)UserLayers.DEFAULT_ID else second.id)})
        val after=before.remove(second.id,LayerDelete.DeleteContents)
        assertEquals(20_000,after.memberships.size);assertEquals(20_000,after.deleted.size)
        val bytes=PageAuthoringCodec.encode(PageAuthoring(after))
        assertEquals(0x49574134,ByteBuffer.wrap(bytes).int);assertTrue(bytes.size<PageAuthoringCodec.LEGACY_MAX_BYTES)
        val restored=PageAuthoringCodec.decode(bytes).layers
        assertTrue(refs.take(20_000).all{restored.visible(it)})
        assertTrue(refs.drop(20_000).all{restored.isDeleted(it)&&restored.owns(it)&&!restored.visible(it)})
    }

    @Test fun compactStackRejectsTrailingDataUnknownVersionsAndCountsLargerThanInput(){
        val bytes=PageAuthoringCodec.encode(PageAuthoring(UserLayers.legacy(refs(22_001))))
        reject{PageAuthoringCodec.decode(bytes+byteArrayOf(0))}
        val forged=bytes.copyOf()
        java.io.DataInputStream(java.io.ByteArrayInputStream(forged)).use{d->
            d.readInt();repeat(d.readInt()){d.readUTF();d.readUTF();d.readBoolean();d.readBoolean()}
            if(d.readBoolean())d.readUTF()
            ByteBuffer.wrap(forged).putInt(forged.size-d.available(),Int.MAX_VALUE)
        }
        reject{PageAuthoringCodec.decode(forged)}
        val unknown=bytes.copyOf();ByteBuffer.wrap(unknown).putInt(0x49574135)
        reject{PageAuthoringCodec.decode(unknown)}
    }
}
