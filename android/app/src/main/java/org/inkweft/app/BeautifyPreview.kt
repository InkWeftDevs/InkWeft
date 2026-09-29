package org.inkweft.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.inkweft.core.*

internal data class BeautifyPreviewState(
    val strength:Float=.5f,
    val strokes:List<InkStroke>?=null,
    val error:Boolean=false,
)

/** Each dialog owns a frozen selection and a replayable result, independent of its
 * AndroidView subcomposition. A cancelled/older calculation cannot publish a result. */
internal class BeautifyPreview(
    original:List<InkStroke>,
    private val calculate:suspend (List<InkStroke>,Float)->List<InkStroke> = {strokes,strength->
        withContext(Dispatchers.Default){InkSelectionEdit.beautify(strokes,strength)}
    },
) {
    val original=original.toList()
    private val mutable=MutableStateFlow(BeautifyPreviewState())
    val state=mutable.asStateFlow()
    private var generation=0
    suspend fun compute(strength:Float){
        val current=++generation
        mutable.value=BeautifyPreviewState(strength)
        try{
            val result=withTimeout(5_000){calculate(original,strength)}
            currentCoroutineContext().ensureActive()
            if(current==generation)mutable.value=BeautifyPreviewState(strength,result)
        }catch(_:TimeoutCancellationException){
            if(current==generation)mutable.value=BeautifyPreviewState(strength,error=true)
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){if(current==generation)mutable.value=BeautifyPreviewState(strength,error=true)}
    }
}
