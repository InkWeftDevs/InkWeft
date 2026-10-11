// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import java.io.File

/** Format checks for one transfer. Inserted chunks still verify the complete digest in the transaction. */
internal class ValidatedImportOriginals private constructor(
    private val documents:Set<Triple<String,Int,Int>>,private val images:Set<Pair<String,Int>>
) {
    fun contains(source:PdfDocumentSource)=Triple(source.sha256,source.size,source.pages) in documents
    fun contains(source:ImageSource)=(source.sha256 to source.size) in images
    companion object {
        suspend fun prepare(pages:List<InkPageFile>,directory:File):ValidatedImportOriginals=
            prepare(pages.mapNotNull{it.source?.document},pages.flatMap{it.imageSources},directory)
        suspend fun prepare(documents:List<PdfDocumentSource>,images:List<ImageSource>,directory:File):ValidatedImportOriginals=withContext(Dispatchers.IO){
            val context=currentCoroutineContext()
            val checkedDocuments=mutableSetOf<Triple<String,Int,Int>>();val checkedImages=mutableSetOf<Pair<String,Int>>()
            for(source in documents){
                context.ensureActive()
                val key=Triple(source.sha256,source.size,source.pages)
                if(key !in checkedDocuments){
                    require(directory.usableSpace>=source.size.toLong()*2+32L*1024*1024){"ORIGINAL_LOW_SPACE"}
                    DocumentRepository.validatePdf(source,directory){context.ensureActive()}
                    checkedDocuments+=key
                }
            }
            for(source in images){
                context.ensureActive()
                val key=source.sha256 to source.size
                if(key !in checkedImages){
                    require(directory.usableSpace>=source.size.toLong()*2+32L*1024*1024){"ORIGINAL_LOW_SPACE"}
                    ImageSourceRepository.validate(source,directory){context.ensureActive()}
                    checkedImages+=key
                }
            }
            ValidatedImportOriginals(checkedDocuments,checkedImages)
        }
    }
}
