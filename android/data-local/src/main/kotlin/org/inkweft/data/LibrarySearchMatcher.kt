// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

/** Prepared once per read. -1 keeps non-ASCII query starts on the original matcher. */
internal fun foldedAsciiSearchStart(query:String):Int {
    if(query.isEmpty())return -1
    val first=query[0].code
    return if(first>127)-1 else if(first in 65..90)first+32 else first
}

/** Only reject provably different ASCII starts; Unicode is still verified by String. */
internal fun containsLibraryText(text:String,query:String,foldedAsciiStart:Int):Boolean {
    if(query.isEmpty())return true
    if(query.length>text.length)return false
    if(foldedAsciiStart<0)return text.contains(query,ignoreCase=true)
    val last=text.length-query.length
    var at=0
    while(at<=last){
        val first=text[at].code
        val folded=if(first in 65..90)first+32 else first
        if((first>127||folded==foldedAsciiStart)&&query.regionMatches(0,text,at,query.length,ignoreCase=true))return true
        at++
    }
    return false
}
