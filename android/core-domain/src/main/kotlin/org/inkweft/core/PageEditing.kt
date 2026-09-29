// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

enum class PageEditKind { MOVE, COPY, TRASH, RESTORE }

/** A frozen request. Order and source head are checked independently. Moving
 * presentation never changes author IDs, ink heads or search versions. */
data class EditPage(
    val commandId:String, val notebookId:String, val pageId:String,
    val kind:PageEditKind, val expectedOrder:String, val expectedInkRevision:Long,
    val location:PageInsertLocation=PageInsertLocation.END, val anchorPageId:String?=null,
    val newPageId:String?=null, val expectedTrashedAt:Long?=null,
    val stayOnPageId:String,
) {
    init {
        listOf(commandId,notebookId,pageId,stayOnPageId).forEach(::canonical)
        require(expectedOrder.matches(Regex("[0-9a-f]{64}")) && expectedInkRevision>=0)
        require((kind==PageEditKind.COPY)==(newPageId!=null))
        newPageId?.let{canonical(it);require(it !in listOf(pageId,notebookId,commandId,stayOnPageId))}
        require((kind==PageEditKind.RESTORE)==(expectedTrashedAt!=null))
        expectedTrashedAt?.let{require(it>=0)}
        if(location in listOf(PageInsertLocation.BEFORE,PageInsertLocation.AFTER)) {
            canonical(requireNotNull(anchorPageId))
            require(kind!=PageEditKind.MOVE || anchorPageId!=pageId)
        } else require(anchorPageId==null)
        if(kind==PageEditKind.TRASH)require(location==PageInsertLocation.END && anchorPageId==null)
    }
    fun targetIndex(activeIds:List<String>):Int {
        require(activeIds.isNotEmpty() && activeIds.distinct().size==activeIds.size)
        val rest=if(kind==PageEditKind.MOVE)activeIds.filterNot{it==pageId} else activeIds
        return when(location){
            PageInsertLocation.START->0
            PageInsertLocation.END->rest.size
            PageInsertLocation.BEFORE,PageInsertLocation.AFTER->{
                val index=rest.indexOf(anchorPageId);require(index>=0){"PAGE_ANCHOR_UNAVAILABLE"}
                index+if(location==PageInsertLocation.AFTER)1 else 0
            }
        }
    }
    fun fields():ArrayList<String> = arrayListOf(commandId,notebookId,pageId,kind.name,expectedOrder,
        expectedInkRevision.toString(),location.name,anchorPageId.orEmpty(),newPageId.orEmpty(),
        expectedTrashedAt?.toString().orEmpty(),stayOnPageId)
    fun digest():String {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{out->(listOf("InkWeft.EditPage/1")+fields()).forEach{v->
            val b=v.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b)
        }}
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    companion object {
        private fun canonical(s:String){require(UUID.fromString(s).toString()==s)}
        fun fromFields(a:List<String>):EditPage {
            require(a.size==11)
            return EditPage(a[0],a[1],a[2],PageEditKind.valueOf(a[3]),a[4],a[5].toLong(),
                PageInsertLocation.valueOf(a[6]),a[7].ifEmpty{null},a[8].ifEmpty{null},a[9].takeIf{it.isNotEmpty()}?.toLong(),a[10])
        }
    }
}
sealed interface EditPageResult {
    data class Applied(val resultPageId:String,val selectedPageId:String,val replayed:Boolean=false):EditPageResult
    data object OrderChanged:EditPageResult
    data object SourceChanged:EditPageResult
    data object LastPage:EditPageResult
    data object CapacityReached:EditPageResult
    data object Unavailable:EditPageResult
    data object CommandReused:EditPageResult
}
