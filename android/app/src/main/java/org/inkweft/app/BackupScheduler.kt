// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.*

/** One OS job, one application executor. No scheduling until explicit upload consent. */
internal object BackupScheduler {
    private const val ID=44001
    fun schedule(context:Context){
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(ID)!=null)return
        scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,BackupJobService::class.java))
            .setRequiredNetworkType(if(BackupSessionStore(context).meteredAllowed)JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED).setRequiresBatteryNotLow(true)
            .setPersisted(true).setMinimumLatency(30_000)
            .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun cancel(context:Context){context.getSystemService(JobScheduler::class.java).cancel(ID)}
}
class BackupJobService:JobService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer:Job?=null
    private var transfer:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val engine=(application as InkWeftApplication).backupEngine
        if(!engine.mayResume())return false
        transfer=engine.upload(background=true)
        observer=scope.launch{transfer?.join();jobFinished(params,engine.mayResume())}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        observer?.cancel();transfer?.cancel()
        return (application as InkWeftApplication).backupEngine.mayResume()
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
/** Foreground ink gets first use of the existing memory budget. */
internal object BackgroundBudget {
    @Volatile var lastInput=0L
    suspend fun <T> memory(bytes:Long,action:suspend ()->T):T {
        val lease=Any();val owner="background-${System.identityHashCode(lease)}"
        RenderResources.admit(bytes);RenderResources.track(lease,bytes,"background-work",owner,RenderResources.Role.IN_FLIGHT)
        return try{action()}finally{RenderResources.release(lease,owner)}
    }
    suspend fun await(context:Context){
        val power=context.getSystemService(PowerManager::class.java)
        while(android.os.SystemClock.elapsedRealtime()-lastInput<750 || power.currentThermalStatus>=PowerManager.THERMAL_STATUS_MODERATE){delay(250)}
        RenderResources.admit(2L*1024*1024)
        yield()
    }
}
