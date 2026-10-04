// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.inkweft.data.CardReuseKind
import org.inkweft.data.CardReuseRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CardReuseStoreTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun freshStoreReopensOneFrozenOperationAndRejectsAnotherIntent(){
        val context=ApplicationProvider.getApplicationContext<Context>();val card=id();val first=CardReuseStore(context,card)
        val request=CardReuseRequest(id(),card,3,id(),CardReuseKind.INDEPENDENT_COPY)
        val fields=CardReuseStore.fields(request)
        try{
            assertNull(first.read());first.save(fields)
            val restarted=CardReuseStore(context,card);assertEquals(fields,restarted.read());restarted.save(fields)
            assertThrows(IllegalArgumentException::class.java){restarted.save(CardReuseStore.fields(request.copy(operationId=id())))}
            assertEquals(request.digest(),CardReuseStore.request(restarted.read()!!).digest())
            restarted.clear(fields);assertNull(CardReuseStore(context,card).read())
        }finally{first.clear(fields)}
    }
    @Test fun unreadableRecoveryRecordCannotBeOverwrittenByANewRequest(){
        val context=ApplicationProvider.getApplicationContext<Context>();val card=id();val path=File(context.filesDir,"card-reuse-$card.pending")
        val request=CardReuseRequest(id(),card,1,id(),CardReuseKind.REFERENCE)
        try{
            val corrupt=byteArrayOf(1,2,3);path.writeBytes(corrupt)
            assertThrows(Exception::class.java){CardReuseStore(context,card).save(CardReuseStore.fields(request))}
            assertArrayEquals(corrupt,path.readBytes())
        }finally{path.delete()}
    }
}
