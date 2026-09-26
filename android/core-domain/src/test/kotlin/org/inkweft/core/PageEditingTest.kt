// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.*

class PageEditingTest {
    private fun id()=UUID.randomUUID().toString()
    private val a=id();private val b=id();private val c=id()
    private fun command(kind:PageEditKind=PageEditKind.MOVE,where:PageInsertLocation=PageInsertLocation.END,anchor:String?=null)=
        EditPage(id(),a,b,kind,InsertPages.orderHash(listOf(a,b,c)),0,where,anchor,
            if(kind==PageEditKind.COPY)id()else null,if(kind==PageEditKind.RESTORE)42 else null,a)
    private fun rejects(block:()->Unit){try{block();fail("invalid command accepted")}catch(_:IllegalArgumentException){}}
    @Test fun moveEndRemovesSourceBeforeCalculatingIndex(){assertEquals(2,command().targetIndex(listOf(a,b,c)))}
    @Test fun moveBeforeAndAfterUseTargetIdentity(){assertEquals(1,command(where=PageInsertLocation.BEFORE,anchor=c).targetIndex(listOf(a,b,c)));assertEquals(2,command(where=PageInsertLocation.AFTER,anchor=c).targetIndex(listOf(a,b,c)))}
    @Test fun movingFirstAndLastToStartPreservesIndexMeaning(){assertEquals(0,command(where=PageInsertLocation.START).targetIndex(listOf(a,b,c)));assertEquals(0,command().copy(pageId=c,location=PageInsertLocation.BEFORE,anchorPageId=a).targetIndex(listOf(a,b,c)))}
    @Test fun copyCanBePlacedNextToItsOwnSource(){assertEquals(2,command(PageEditKind.COPY,PageInsertLocation.AFTER,b).targetIndex(listOf(a,b,c)))}
    @Test fun restoreChoosesCurrentOrderNotHistoricPageNumber(){assertEquals(2,command(PageEditKind.RESTORE).targetIndex(listOf(a,c)))}
    @Test fun frozenCommandRestoresFromSavedFields(){val cmd=command(PageEditKind.COPY);assertEquals(cmd,EditPage.fromFields(cmd.fields()));assertEquals(cmd.digest(),EditPage.fromFields(cmd.fields()).digest())}
    @Test fun differentParametersNeverShareTheSameSemanticDigest(){val cmd=command();assertNotEquals(cmd.digest(),cmd.copy(location=PageInsertLocation.START).digest());assertNotEquals(cmd.digest(),cmd.copy(expectedInkRevision=1).digest())}
    @Test fun nonCanonicalIdentityAndNegativeRevisionRejected(){rejects{command().copy(pageId="1-1-1-1-1")};rejects{command().copy(expectedInkRevision=-1)}}
    @Test fun copyRequiresIndependentNewIdentity(){rejects{command(PageEditKind.COPY).copy(newPageId=a)};rejects{command(PageEditKind.COPY).copy(newPageId=null)};rejects{command().copy(newPageId=id())}}
    @Test fun restoreRequiresExactTombstoneIdentity(){rejects{command(PageEditKind.RESTORE).copy(expectedTrashedAt=null)};rejects{command().copy(expectedTrashedAt=1)}}
    @Test fun movingToSelfOrUnknownAnchorIsRejected(){rejects{command(where=PageInsertLocation.AFTER,anchor=b)};rejects{command(where=PageInsertLocation.AFTER,anchor=id()).targetIndex(listOf(a,b,c))}}
    @Test fun trashCannotEncodeAnAmbiguousPlacement(){rejects{command(PageEditKind.TRASH,PageInsertLocation.START)}}
    @Test fun oldCompiledArchiveSchemaCanBeExplicitlyReadButNotGuessed(){
        val old=listOf(LibraryArchive.Table("rows",listOf(LibraryArchive.Column("id",'S')),listOf("id")))
        val next=listOf(old.single().copy(columns=old.single().columns+LibraryArchive.Column("trash",'I',true)))
        val out=ByteArrayOutputStream();val rows=object:LibraryArchive.Rows{override fun count(table:Int)=1L;override fun visit(table:Int,consume:(List<Any?>)->Unit){consume(listOf(a))}}
        LibraryArchive.write(out,old,rows,0)
        rejects{LibraryArchive.read(ByteArrayInputStream(out.toByteArray()),next,{_,_->})}
        var seen=false
        LibraryArchive.read(ByteArrayInputStream(out.toByteArray()),next,{i,row->assertEquals(0,i);assertEquals(listOf(a),row);seen=true},legacySchema=old)
        assertTrue(seen)
    }
    @Test fun orderHashesDistinguishDeletedAndMovedPages(){assertNotEquals(InsertPages.orderHash(listOf(a,b,c)),InsertPages.orderHash(listOf(a,c)));assertNotEquals(InsertPages.orderHash(listOf(a,b,c)),InsertPages.orderHash(listOf(a,c,b)))}
}
