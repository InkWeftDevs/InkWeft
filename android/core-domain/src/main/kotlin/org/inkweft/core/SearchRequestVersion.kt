// SPDX-License-Identifier: AGPL-3.0-only
// Derived from SiYuan, https://github.com/siyuan-note/siyuan
// Source: 572abc5bcc2a447791001e110181eb56edb2bd0c/app/src/search/request.ts
// Modified for InkWeft: Kotlin-only generation/lifetime guard; Android coroutines retain scheduling.
// See android/third-party/siyuan-search/README.md and LICENSE.
package org.inkweft.core

/** Port of SiYuan's version/current/lifetime checks. Access only on the owning UI thread. */
class SearchRequestVersion {
    private var version=0L
    private var connected=true
    fun current()=version
    fun invalidate():Long {check(connected);version=Math.addExact(version,1);return version}
    fun isCurrent(request:Long)=connected&&version==request
    fun close(){if(connected){version=Math.addExact(version,1);connected=false}}
}
