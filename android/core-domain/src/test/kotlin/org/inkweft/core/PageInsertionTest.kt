// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageInsertionTest {
    private fun id() = UUID.randomUUID().toString()
    private val book = id()
    private val pages = listOf(book,id(),id())
    private fun operation(where:PageInsertLocation = PageInsertLocation.AFTER, anchor:String?=pages[1],
        ids:List<String> = listOf(id()), paper:PaperStyle=PaperStyle.RULED, open:Boolean=true,
        commandId:String=id()) = InsertPages(commandId,book,InsertPages.orderHash(pages),where,anchor,paper,ids,open)
    @Test fun beforeUsesAnchorNotPageNumber(){assertEquals(1,operation(PageInsertLocation.BEFORE).insertionIndex(pages))}
    @Test fun afterUsesAnchor(){assertEquals(2,operation().insertionIndex(pages))}
    @Test fun beginningCanPrecedeOriginalFirstPage(){assertEquals(0,operation(PageInsertLocation.START,null).insertionIndex(pages))}
    @Test fun endIsAfterAllExistingPages(){assertEquals(3,operation(PageInsertLocation.END,null).insertionIndex(pages))}
    @Test fun missingAnchorRefusesInsteadOfAppending(){
        try{operation(anchor=id()).insertionIndex(pages);fail("missing anchor accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun sameFrozenCommandHasStableDigest(){val c=operation();assertEquals(c.digest(),c.digest());assertEquals(64,c.digest().length)}
    @Test fun commandDigestIncludesPositionPaperAndSelection(){
        val command=id();val created=listOf(id())
        val variants=listOf(operation(ids=created,commandId=command),operation(PageInsertLocation.BEFORE,ids=created,commandId=command),
            operation(ids=created,paper=PaperStyle.GRID,commandId=command),operation(ids=created,open=false,commandId=command))
        assertEquals(4,variants.map{it.digest()}.distinct().size)
    }
    @Test fun callerCannotChangeBatchAfterConfirmation(){
        val list=mutableListOf(id());val c=operation(ids=list);val digest=c.digest();list.add(id())
        assertEquals(1,c.pageIds.size);assertEquals(digest,c.digest())
        try{(c.pageIds as MutableList<String>).add(id());fail("mutable command")}catch(_:UnsupportedOperationException){}
    }
    @Test fun emptyOrOversizedOrDuplicateBatchRefused(){
        val same=id();for(ids in listOf(emptyList(),List(21){id()},listOf(same,same))){
            try{operation(ids=ids);fail("invalid batch")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun originalPageIdentityCannotBeUsedForNewPage(){try{operation(ids=listOf(book));fail("replaced original identity")}catch(_:IllegalArgumentException){}}
    @Test fun endAndBeginningHaveNoHiddenAnchor(){try{operation(PageInsertLocation.END);fail("unused anchor accepted")}catch(_:IllegalArgumentException){}}
    @Test fun orderFingerprintChangesOnMoveOrInsert(){
        val hash=InsertPages.orderHash(pages);assertNotEquals(hash,InsertPages.orderHash(pages.reversed()));assertNotEquals(hash,InsertPages.orderHash(pages+id()))
    }
    @Test fun templateAndNewIdsDoNotAffectOriginalIdentity(){val before=pages.toList();val c=operation(ids=List(3){id()},paper=PaperStyle.DOTS);c.insertionIndex(pages);assertEquals(before,pages);assertTrue(c.pageIds.none{it in pages})}
    @Test fun twentyPagesCanBeOneAtomicOperation(){assertEquals(20,operation(ids=List(20){id()}).pageIds.size)}
}
