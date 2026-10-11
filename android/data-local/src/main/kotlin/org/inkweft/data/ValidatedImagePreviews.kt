// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.inkweft.core.ContentTransfer
import org.inkweft.core.PageObject
import org.inkweft.core.PageObjectKind

/** Only successful content checks are reusable; geometry and database identity are not trust proofs. */
internal class ValidatedImagePreviews {
    private val checked=object:LinkedHashMap<String,Unit>(64,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Unit>?)=size>64
    }
    // ponytail: serialize validation per database; add per-content in-flight checks only if imports contend.
    @Synchronized fun validate(objects:List<PageObject>,decode:(PageObject)->Unit) {
        for(image in objects.filter{it.kind==PageObjectKind.IMAGE}){
            val digest=ContentTransfer.hash(image.image.toByteArray(Charsets.UTF_8))
            if(checked[digest]!=null)continue
            decode(image)
            checked[digest]=Unit
        }
    }
}
