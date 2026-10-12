// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

/** Unicode folding matches Kotlin/JVM contains(ignoreCase=true), including short CJK
 * queries, punctuation and surrogate pairs. FTS syntax receives only generated ASCII tokens. */
internal object LibrarySearchTerms {
    private fun folded(text:String)=buildString{
        var offset=0
        while(offset<text.length){
            val point=text.codePointAt(offset)
            appendCodePoint(Character.toLowerCase(Character.toUpperCase(point)))
            offset+=Character.charCount(point)
        }
    }.toList()
    private fun term(chars:List<Char>,start:Int,count:Int)=buildString{
        append('u');for(i in start until start+count)append(chars[i].code.toString(16).padStart(4,'0'))
    }
    fun document(title:String,body:String,check:()->Unit={}):String {
        val terms=LinkedHashSet<Long>()
        for(text in listOf(title,body))for(chars in listOf(folded(text),text.map{it.uppercaseChar().lowercaseChar()}).distinct()){
            for(i in chars.indices){
                if(i%1024==0)check()
                var value=0L
                for(size in 1..minOf(3,chars.size-i)){
                    value=(value shl 16) or chars[i+size-1].code.toLong()
                    terms.add((size.toLong() shl 48) or value)
                }
            }
        }
        // Deduplicate numeric grams before allocating strings: long repeated text should
        // not allocate three token strings for every character during initial indexing.
        return terms.joinToString(" "){value->buildString{
            append('u');val size=(value ushr 48).toInt()
            for(index in size-1 downTo 0)append(((value ushr (index*16)) and 0xffff).toString(16).padStart(4,'0'))
        }}
    }
    fun query(text:String):String {
        val chars=folded(text);require(chars.isNotEmpty())
        val size=minOf(3,chars.size)
        return (0..chars.size-size).asSequence().map{term(chars,it,size)}.distinct().take(8).joinToString(" ")
    }
}
