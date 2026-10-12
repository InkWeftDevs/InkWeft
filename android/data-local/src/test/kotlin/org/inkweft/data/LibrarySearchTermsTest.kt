package org.inkweft.data

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTermsTest {
    @Test fun unicodeSubstringsProduceOnlySafeMatchingTokens(){
        val text="中文搜索 Ångström İıΣς \uD83D\uDE80 \uD801\uDC00 %_ OR \"引号\""
        val indexed=LibrarySearchTerms.document("",text).split(' ').toSet()
        for(query in listOf("中","文搜","中文搜索","ångSTRÖm","iIσΣ","\uD83D\uDE80","\uD801\uDC28","\uDC00","%_","OR \"")){
            assertTrue(text.contains(query,true))
            val terms=LibrarySearchTerms.query(query).split(' ')
            assertTrue(terms.all{it.matches(Regex("u[0-9a-f]+"))&&it in indexed})
        }
        assertFalse(LibrarySearchTerms.query("不存在").split(' ').all{it in indexed})
    }
}
