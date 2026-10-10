// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import kotlin.math.PI

/** Sensor values stay in author data; only render input may coalesce a stationary update. */
object InkSampling {
    private val turn=(2*PI).toFloat()

    fun orientation(raw:Float):Float? {
        if(!raw.isFinite())return null
        val wrapped=raw%turn
        return (if(wrapped<0)wrapped+turn else wrapped).let{if(it>=turn)0f else it}
    }

    /** Interpolate the short arc across zero, rather than turning the nib backwards. */
    fun orientationBetween(a:Float,b:Float,t:Float):Float {
        if(a<0)return -1f
        if(t==0f)return a
        if(t==1f)return b
        var delta=b-a
        if(delta>turn/2)delta-=turn else if(delta< -turn/2)delta+=turn
        return checkNotNull(orientation(a+delta*t))
    }

    fun sameReading(a:InkSample,b:InkSample)=a===b||
        a.x==b.x&&a.y==b.y&&a.elapsedMs==b.elapsedMs&&a.pressure==b.pressure&&a.tilt==b.tilt&&a.orientation==b.orientation

    /** Page commit changes the coordinate-domain flag, not the rendered readings. */
    fun renderPrefix(previous:List<InkSample>,next:List<InkSample>)=
        previous===next||previous.size<=next.size&&previous.indices.all{sameReading(previous[it],next[it])}

    fun forRendering(samples:List<InkSample>):List<InkSample> {
        var result:ArrayList<InkSample>?=null
        for(i in samples.indices){
            val p=samples[i]
            val prior=if(i==0)null else samples[i-1]
            val stationary=prior!=null&&prior.x==p.x&&prior.y==p.y&&prior.elapsedMs==p.elapsedMs
            if(stationary&&result==null)result=ArrayList(samples.subList(0,i))
            result?.let{if(stationary)it[it.lastIndex]=p else it.add(p)}
        }
        return result?:samples
    }
}
