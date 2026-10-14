// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class LibrarySearchMatcherTest {
    private fun equivalent(text:String,query:String){
        val expected=text.contains(query,ignoreCase=true)
        val actual=containsLibraryText(text,query,foldedAsciiSearchStart(query))
        if(expected!=actual)fail("Mismatch for UTF-16 text=${text.toCharArray().map{it.code}} query=${query.toCharArray().map{it.code}}")
    }

    @Test fun everyAsciiCharacterKeepsLiteralCaseInsensitiveSemantics(){
        for(text in 0..127)for(query in 0..127){
            equivalent(text.toChar().toString(),query.toChar().toString())
            equivalent("##${text.toChar()}!!",query.toChar().toString())
        }
        for(query in listOf("%_","[]","{}","@`","\\\"","\u0000A","Aa"))
            for(text in listOf(query,query.uppercase(),query.lowercase(),"prefix$query","unrelated"))equivalent(text,query)
    }

    @Test fun everyBmpCandidateStillUsesOriginalUnicodeVerification(){
        // ASCII queries can match non-ASCII starts. No hand-maintained equivalence table.
        val queries=listOf("i","s","k","z")
        for(value in 0..65535)for(query in queries)equivalent(value.toChar().toString(),query)
        for((text,query) in listOf("İa" to "IA","ıA" to "ia","ſΣ" to "sσ","Kς" to "kσ",
            "prefix İa suffix" to "IA","汉字ASCII后缀" to "ascii","S\uD801\uDC00" to "s\uD801\uDC28")){
            assertTrue(text.contains(query,ignoreCase=true));equivalent(text,query)
        }
    }

    @Test fun emptyLongerNulAndSurrogateQueriesKeepTheirBoundaries(){
        val texts=listOf("","A","\u0000","A\u0000B","\uD800","\uDC00","a\uD800b","\uD83D\uDE80A","I\uD801\uDC00z")
        val queries=listOf("","a","ab","\u0000","a\u0000","\u0000b","\uD800","\uDC00","\uDE80","\uD83D\uDE80a","i\uD801\uDC28Z")
        for(text in texts)for(query in queries)equivalent(text,query)
        assertTrue(containsLibraryText("","",foldedAsciiSearchStart("")))
        assertFalse(containsLibraryText("a","aa",foldedAsciiSearchStart("aa")))
    }

    @Test fun nonAsciiQueryStartsAndMixedRandomStringsKeepOriginalResults(){
        for(query in listOf("İ","ı","ſ","K","Σ","中文","🚀","\uD801\uDC28")){
            assertEquals(-1,foldedAsciiSearchStart(query))
            for(text in listOf("ASCII","i","s","k","σς","中文🚀\uD801\uDC00",""))equivalent(text,query)
        }
        val random=Random(391731L)
        val alphabet="aAzZIİıSſKKΣσςÅé中🚀\uD801\uDC00\u0000\t%_[]{}!?".toCharArray()
        repeat(2_000){
            val text=CharArray(random.nextInt(65)){alphabet[random.nextInt(alphabet.size)]}.concatToString()
            val query=CharArray(random.nextInt(9)){alphabet[random.nextInt(alphabet.size)]}.concatToString()
            equivalent(text,query)
            if(text.isNotEmpty())equivalent(text,text.substring(random.nextInt(text.length)))
        }
    }

    @Test fun longMixedTextKeepsLastStartAndUnicodeMatches(){
        val prefix="合成前缀"+"b".repeat(20_000)
        for((text,query) in listOf(prefix+"TARGET" to "target",prefix+"İA" to "ia",
            prefix to "target", "a".repeat(100_000) to "AaAaA", "a".repeat(100_000) to "aaaab"))equivalent(text,query)
    }
}
