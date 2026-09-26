// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DiagnosticLogTest {
    private fun log() = DiagnosticLog("012345abcdef")
    private fun failure(block: () -> Unit) { try { block(); fail("must reject invalid diagnostic data") } catch (_: IllegalArgumentException) { } }
    @Test fun boundedRingKeepsNewest200() { val l=log();repeat(900){l.add(DiagnosticCode.USER_MARK,DiagnosticResult.OBSERVED,it.toLong(),it.toLong())};assertEquals(200,l.snapshot().size);assertEquals(700L,l.snapshot().first().wallMillis) }
    @Test fun codecRoundTripsEmptyAndEvents() { val l=log();assertEquals(emptyList<DiagnosticEvent>(),DiagnosticLog.decode(DiagnosticLog.encode(l.snapshot())));l.add(DiagnosticCode.INK_UI,DiagnosticResult.UNKNOWN,12,3,9,2);assertEquals(l.snapshot(),DiagnosticLog.decode(DiagnosticLog.encode(l.snapshot()))) }
    @Test fun invalidHashRejected() { val bytes=DiagnosticLog.encode(emptyList()).clone();bytes[12]='f'.code.toByte();failure{DiagnosticLog.decode(bytes)} }
    @Test fun unknownCodeRejectedEvenWithChecksum() { val body="012345abcdef\t1\t1\tSECRET_NOTE_TEXT\tOBSERVED\t0\t0";failure{DiagnosticLog.decode(("IWDIAG1\n"+DiagnosticLog.hash(body.toByteArray())+"\n"+body).toByteArray())} }
    @Test fun arbitrarySessionCannotCarryPrivateText() { failure{DiagnosticEvent("private-note-title",1,1,DiagnosticCode.USER_MARK,DiagnosticResult.OBSERVED)} }
    @Test fun extraFieldRejected() { val body="012345abcdef\t1\t1\tUSER_MARK\tOBSERVED\t0\t0\tprivate-note";failure{DiagnosticLog.decode(("IWDIAG1\n"+DiagnosticLog.hash(body.toByteArray())+"\n"+body).toByteArray())} }
    @Test fun oversizedJournalRejected() { failure{DiagnosticLog.decode(ByteArray(DiagnosticLog.MAX_JOURNAL_BYTES+1))} }
    @Test fun invalidNumericValueRejected() { failure{DiagnosticEvent("012345abcdef",-1,1,DiagnosticCode.USER_MARK,DiagnosticResult.OBSERVED)} }
    @Test fun historyPreservesFreshProcessEvents() { val l=log();l.add(DiagnosticCode.APP_START,DiagnosticResult.OBSERVED,20,20);val old=DiagnosticEvent("abcdef012345",10,10,DiagnosticCode.INK_UI,DiagnosticResult.SAVED);l.prependHistory(listOf(old));assertEquals(old,l.snapshot().first());assertEquals(DiagnosticCode.APP_START,l.snapshot().last().code) }
    @Test fun snapshotsDoNotMutateAfterChanges() { val l=log();l.add(DiagnosticCode.APP_START,DiagnosticResult.OBSERVED,1,1);val snap=l.snapshot();l.clear();assertEquals(1,snap.size);assertEquals(0,l.snapshot().size) }
    @Test fun archiveHasFixedEntriesAndHashes() { val l=log();l.add(DiagnosticCode.USER_MARK,DiagnosticResult.OBSERVED,1,1);val files=linkedMapOf<String,ByteArray>();ZipInputStream(ByteArrayInputStream(DiagnosticArchive.build("{\"synthetic\":true}",l.snapshot()))).use{z->while(true){val e=z.nextEntry?:break;files[e.name]=z.readBytes()}};assertEquals(setOf("README.txt","report.json","events.jsonl","manifest.json"),files.keys);for(n in listOf("README.txt","report.json","events.jsonl"))assertTrue(files.getValue("manifest.json").toString(Charsets.UTF_8).contains(DiagnosticLog.hash(files.getValue(n))));assertFalse(files.keys.any{it.contains("..")||it.startsWith("/")}) }
    @Test fun oversizedReportRejected() { failure{DiagnosticArchive.build("x".repeat(DiagnosticArchive.MAX_REPORT_BYTES+1),emptyList())} }
    @Test fun exportedEventsOnlyAllowlistedValues() { val e=DiagnosticEvent("012345abcdef",10,5,DiagnosticCode.INK_UI,DiagnosticResult.UNKNOWN,3,1);assertFalse(e.json().contains("exception"));assertEquals(7,e.line().split('\t').size);assertTrue(e.json().contains("\"UNKNOWN\"")) }
    @Test fun tooManyHistoryRowsRejected() { val e=DiagnosticEvent("012345abcdef",1,1,DiagnosticCode.USER_MARK,DiagnosticResult.OBSERVED);failure{DiagnosticLog.encode(List(201){e})} }
}
