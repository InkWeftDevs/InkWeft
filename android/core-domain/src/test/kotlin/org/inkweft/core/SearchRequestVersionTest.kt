// SPDX-License-Identifier: AGPL-3.0-only
// Boundary cases adapted from SiYuan search/request.test.ts at 572abc5bcc2a447791001e110181eb56edb2bd0c.
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

class SearchRequestVersionTest {
    @Test fun laterRequestMakesAnOldResponseStaleEvenWhenQueryTextReturnsToA(){
        val state=SearchRequestVersion();val a=state.invalidate();assertTrue(state.isCurrent(a))
        state.invalidate();val secondA=state.invalidate()
        assertFalse(state.isCurrent(a));assertTrue(state.isCurrent(secondA))
    }
    @Test fun disposedPanelCannotAcceptAQueuedResponse(){
        val state=SearchRequestVersion();val pending=state.invalidate();state.close()
        assertFalse(state.isCurrent(pending));assertThrows(IllegalStateException::class.java){state.invalidate()}
    }
    @Test fun independentPanelsNeverShareRequestVersions(){
        val a=SearchRequestVersion();val b=SearchRequestVersion();val old=a.invalidate();val current=b.invalidate()
        a.invalidate();assertFalse(a.isCurrent(old));assertTrue(b.isCurrent(current));a.close();assertTrue(b.isCurrent(current))
    }
    @Test fun readingTokenDoesNotCancelCurrentWork(){val state=SearchRequestVersion();val token=state.invalidate();assertEquals(token,state.current());assertTrue(state.isCurrent(token))}
}
