// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LayerHeaderProjectionTest {
    @Test fun largestLayerHeaderFitsAndPreservesRemoteWritePermissionsAcrossStoredVersions(){
        val layers=List(32){UserLayer(UUID.randomUUID().toString(),"层".repeat(60),visible=true,locked=it<31)}
        val memberships=List(25_000){LayerMembership(LayerContent(LayerContentKind.INK,UUID.randomUUID().toString()),layers[it%32].id)}
        for(count in listOf(100,25_000)){
            val state=PageAuthoring(UserLayers(layers,layers.last().id,memberships.take(count)))
            val bytes=PageAuthoringCodec.encode(state);assertTrue(bytes.size>PageAuthoringCodec.LAYER_HEADER_BYTES)
            val prefix=bytes.copyOf(PageAuthoringCodec.LAYER_HEADER_BYTES)
            val header=PageAuthoringCodec.layerHeader(prefix)
            assertEquals(state.layers.layers,header.layers);assertEquals(state.layers.currentId,header.currentId)
            assertEquals(state.layers.writeScope(34),header.writeScope(34));assertTrue(header.memberships.isEmpty())
            if(count==100)for(version in listOf(0x31,0x32,0x33)){prefix[3]=version.toByte();assertEquals(header.currentId,PageAuthoringCodec.layerHeader(prefix).currentId)}
        }
    }
    @Test fun missingOrDamagedHeaderDoesNotFallbackToAnEditableDefault(){
        assertThrows(Exception::class.java){PageAuthoringCodec.layerHeader(ByteArray(3))}
        assertThrows(IllegalArgumentException::class.java){PageAuthoringCodec.layerHeader(ByteArray(20))}
        val state=PageAuthoring(UserLayers(currentId=null));val header=PageAuthoringCodec.layerHeader(PageAuthoringCodec.encode(state))
        assertNull(header.currentId);assertThrows(IllegalStateException::class.java){header.writeScope(1)}
    }
}
