// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class StorageValidationTest {
    private fun image(data:String)=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,image=data)

    @Test fun geometryAndObjectIdentityDoNotRepeatSuccessfulContentDecoding() {
        val cache=ValidatedImagePreviews();val original=image("unchanged preview");var decodes=0
        cache.validate(listOf(original)){decodes++}
        cache.validate(listOf(original.copy(x=120f,width=500f),original.copy(id=UUID.randomUUID().toString()))){decodes++}
        assertEquals(1,decodes)
        cache.validate(listOf(original.copy(image="changed preview"))){decodes++}
        assertEquals(2,decodes)
    }

    @Test fun invalidChangedBytesNeverBecomeTrustedEvenAfterRepeatedFailures() {
        val cache=ValidatedImagePreviews();val valid=image("valid preview");var decodes=0
        val decode:(PageObject)->Unit={decodes++;require(it.image==valid.image){"damaged preview"}}
        cache.validate(listOf(valid),decode)
        repeat(2){assertThrows(IllegalArgumentException::class.java){cache.validate(listOf(valid.copy(image="damaged preview")),decode)}}
        cache.validate(listOf(valid.copy(y=300f)),decode)
        assertEquals(3,decodes)
    }

    @Test fun boundedValidationMemoryEvictsLeastRecentlyUsedProofsAndRechecksThem() {
        val cache=ValidatedImagePreviews();val images=List(65){image("preview $it")};var decodes=0
        images.take(64).forEach{cache.validate(listOf(it)){decodes++}}
        cache.validate(listOf(images[0])){decodes++}
        cache.validate(listOf(images[64])){decodes++}
        cache.validate(listOf(images[0])){decodes++}
        assertEquals(65,decodes)
        cache.validate(listOf(images[1])){decodes++}
        assertEquals(66,decodes)
    }

    @Test fun concurrentSavesShareOnlyCompletedContentValidation() {
        val cache=ValidatedImagePreviews();val image=image("shared preview");val decodes=AtomicInteger()
        val pool=Executors.newFixedThreadPool(4)
        try{pool.invokeAll(List(16){java.util.concurrent.Callable{cache.validate(listOf(image)){decodes.incrementAndGet()}}}).forEach{it.get()}
            assertEquals(1,decodes.get())
        }finally{pool.shutdownNow()}
    }

    @Test fun changedOriginalCannotPassTheTransactionalWriterUsingAnEarlierValidationDigest()=runBlocking {
        val validated=OriginalBytes.memory(ByteArray(13){it.toByte()});var inserted=0
        val changed=ByteArray(13){(it+1).toByte()}
        val failure=runCatching{storeOriginal(validated.size,validated.sha256,4,{changed.inputStream()}){_,bytes->inserted+=bytes.size}}.exceptionOrNull()
        assertEquals("ORIGINAL_CHECKSUM_CHANGED",failure?.message)
        // The failure may follow the final insert, so callers must keep those inserts in one transaction.
        assertEquals(validated.size,inserted)
        for(bytes in listOf(changed.dropLast(1).toByteArray(),changed+byteArrayOf(1))){
            assertTrue(runCatching{storeOriginal(validated.size,validated.sha256,4,{bytes.inputStream()}){_,_->}}.isFailure)
        }
    }
}
