// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CancellationException

class LibraryArchiveTest {
    private val schema=listOf(LibraryArchive.Table("rows",listOf(
        LibraryArchive.Column("id",'S'),LibraryArchive.Column("n",'I'),LibraryArchive.Column("f",'F'),
        LibraryArchive.Column("payload",'B'),LibraryArchive.Column("maybe",'S',true)),listOf("id")))
    private val row=listOf("中文标题",9L,1.25,byteArrayOf(0,-1,2),null)
    private fun rows(values:List<List<Any?>>)=object:LibraryArchive.Rows{
        override fun count(table:Int)=values.size.toLong()
        override fun visit(table:Int,consume:(List<Any?>)->Unit){values.forEach(consume)}
    }
    private fun bytes(values:List<List<Any?>> = listOf(row)):ByteArray = ByteArrayOutputStream().also{LibraryArchive.write(it,schema,rows(values),42)}.toByteArray()
    private fun rejects(block:()->Unit){try{block();fail("invalid archive accepted")}catch(_:IllegalArgumentException){}catch(_:EOFException){}}
    @Test fun typedRecordsRoundTripIncludingNullAndExactBlob(){
        val result=mutableListOf<List<Any?>>()
        val summary=LibraryArchive.read(ByteArrayInputStream(bytes()),schema,{_,r->result+=r})
        assertEquals(42L,summary.createdAt);assertEquals(listOf(1L),summary.rows)
        assertEquals("中文标题",result.single()[0]);assertArrayEquals(row[3] as ByteArray,result.single()[3] as ByteArray)
        assertNull(result.single()[4]);assertEquals(9L,result.single()[1]);assertEquals(1.25,result.single()[2])
    }
    @Test fun emptyLibraryStreamIsValid(){assertEquals(listOf(0L),LibraryArchive.read(ByteArrayInputStream(bytes(emptyList())),schema,{_,_->fail()}).rows)}
    @Test fun footerDamageIsRejected(){val b=bytes();b[b.lastIndex]=(b.last().toInt()xor 1).toByte();rejects{LibraryArchive.read(ByteArrayInputStream(b),schema,{_,_->})}}
    @Test fun headerDamageIsRejected(){val b=bytes();b[0]=0;rejects{LibraryArchive.read(ByteArrayInputStream(b),schema,{_,_->})}}
    @Test fun truncationNeverSucceeds(){val b=bytes();for(n in listOf(0,4,16,b.size-33,b.size-1))rejects{LibraryArchive.read(ByteArrayInputStream(b.copyOf(n)),schema,{_,_->})}}
    @Test fun trailingBytesAreRejected(){rejects{LibraryArchive.read(ByteArrayInputStream(bytes()+byteArrayOf(7)),schema,{_,_->})}}
    @Test fun changedColumnOrTableSchemaIsRejected(){val other=listOf(schema.single().copy(name="other"));rejects{LibraryArchive.read(ByteArrayInputStream(bytes()),other,{_,_->})}}
    @Test fun sourceCountCannotLie(){val source=object:LibraryArchive.Rows{override fun count(table:Int)=2L;override fun visit(table:Int,consume:(List<Any?>)->Unit){consume(row)}};rejects{LibraryArchive.write(ByteArrayOutputStream(),schema,source,0)}}
    @Test fun maliciousRowCountRejectedBeforeAllocation(){val b=bytes();for(i in 52..59)b[i]=0x7f;rejects{LibraryArchive.read(ByteArrayInputStream(b),schema,{_,_->fail()})}}
    @Test fun finiteNumbersRequiredAndNullabilityEnforced(){rejects{bytes(listOf(row.toMutableList().apply{this[2]=Double.NaN}))};rejects{bytes(listOf(row.toMutableList().apply{this[0]=null}))}}
    @Test fun oversizedFieldRejected(){rejects{bytes(listOf(row.toMutableList().apply{this[3]=ByteArray(LibraryArchive.MAX_FIELD+1)}))}}
    @Test fun shortReadsAndZeroProgressWorkWithoutUsingAvailable(){val b=bytes();val input=object:ByteArrayInputStream(b){var zero=true;override fun available()=0;override fun read(b:ByteArray,o:Int,n:Int):Int{if(zero){zero=false;return 0};return super.read(b,o,minOf(n,2))}};assertEquals(listOf(1L),LibraryArchive.read(input,schema,{_,_->}).rows)}
    @Test fun cancellationPropagatesAndCallerKeepsStreamOwnership(){val input=object:ByteArrayInputStream(bytes()){var closed=false;override fun close(){closed=true}};try{LibraryArchive.read(input,schema,{_,_->}){throw CancellationException()};fail()}catch(_:CancellationException){};assertFalse(input.closed)}
    @Test fun deterministicFingerprintDoesNotUseExternalState(){val a=bytes();val b=bytes();assertArrayEquals(a,b);val first=LibraryArchive.read(ByteArrayInputStream(a),schema,{_,_->});val next=LibraryArchive.read(ByteArrayInputStream(b),schema,{_,_->});assertEquals(first.sha256,next.sha256);assertEquals(a.size.toLong(),first.bytes)}
}
