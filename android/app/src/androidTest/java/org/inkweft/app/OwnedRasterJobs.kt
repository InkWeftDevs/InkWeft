// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.*
import org.junit.Assert.*

/** Test ownership only: retain replaced Jobs until their worker finally has run. */
internal class OwnedRasterJobs {
    private val field=StreamedInkRaster::class.java.getDeclaredField("job").apply{isAccessible=true}
    private val jobs=linkedSetOf<Job>()
    private val baselineJobs=RenderResources.inFlightJobs.get()
    private val baselineGeometry=RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L
    var joined=false;private set

    // Call on Main before and in finally after draw, including draws that supersede a Job.
    fun remember(raster:StreamedInkRaster){(field.get(raster) as Job?)?.let{jobs.add(it);joined=false}}

    suspend fun clearAndJoin(rasters:List<StreamedInkRaster>)=withContext(NonCancellable){
        joined=false
        withContext(Dispatchers.Main){rasters.forEach{remember(it);it.clear()};jobs.forEach{it.cancel()}}
        // A hung worker must fail the enclosing test/process deadline; it must not
        // permit database close or a later test to run against unfinished work.
        jobs.joinAll()
        joined=true
        assertTrue("All owned raster jobs must complete",jobs.all{it.isCompleted})
        assertEquals("Raster worker count after join",baselineJobs,RenderResources.inFlightJobs.get())
        assertEquals("Geometry ownership after join",baselineGeometry,RenderResources.snapshot()["category.ink-geometry-reservation"]?:0L)
        jobs.clear()
    }
}
