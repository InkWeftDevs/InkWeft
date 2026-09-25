// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** Future service boundary only. No network implementation, account or key is
 * provisioned by this module. Installing the app must not submit a request. */
enum class CloudCapability { BACKUP, SYNC, HANDWRITING, OCR, TRANSCRIPTION, TRANSLATION, ASSISTANT }
enum class ServiceFailure { DISABLED, CONSENT_REQUIRED, AUTHENTICATION, RATE_LIMIT, BUDGET, UNSUPPORTED, NETWORK, UNKNOWN_OUTCOME }

data class RevisionScope(val documentId:String,val pageId:String,val contentRevision:Long) {
    init { UUID.fromString(documentId);UUID.fromString(pageId);require(contentRevision>=0) }
}
data class ServiceBudget(val maxUploadBytes:Long,val maxCostMicros:Long,val deadlineEpochMs:Long) {
    init { require(maxUploadBytes in 0..32_000_000 && maxCostMicros>=0 && deadlineEpochMs>0) }
}
/** Credential is an opaque vault handle, never the API key or refresh token. */
data class ServiceRequest(val requestId:String,val providerId:String,val capability:CloudCapability,
    val scope:RevisionScope,val payloadSha256:String,val credentialHandle:String?,val consentHandle:String,
    val budget:ServiceBudget) {
    init {
        UUID.fromString(requestId);require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")));require(consentHandle.isNotBlank())
    }
}
sealed interface ServiceOutcome {
    data class Ready(val requestId:String,val scope:RevisionScope,val localResultHandle:String):ServiceOutcome
    data class Pending(val requestId:String,val remoteJobHandle:String):ServiceOutcome
    data class Failed(val reason:ServiceFailure,val retryAfterSeconds:Long?=null):ServiceOutcome
}
interface CloudServicePort {
    val providerId:String
    val capabilities:Set<CloudCapability>
    suspend fun submit(request:ServiceRequest):ServiceOutcome
    /** Query the original request after uncertainty; do not duplicate billable work. */
    suspend fun query(request:ServiceRequest):ServiceOutcome
    suspend fun cancel(request:ServiceRequest):ServiceOutcome
}
/** Default composition. No hidden fallback to an official paid provider. */
object DisabledCloudServices:CloudServicePort {
    override val providerId="disabled"
    override val capabilities=emptySet<CloudCapability>()
    override suspend fun submit(request:ServiceRequest)=ServiceOutcome.Failed(ServiceFailure.DISABLED)
    override suspend fun query(request:ServiceRequest)=ServiceOutcome.Failed(ServiceFailure.DISABLED)
    override suspend fun cancel(request:ServiceRequest)=ServiceOutcome.Failed(ServiceFailure.DISABLED)
}

/** Recognition results are proposals for a specific visible-ink revision. Erase,
 * undo, rewrite or page replacement invalidates them; manual corrections are
 * retained separately, never silently attributed to a newer ink snapshot. */
data class RecognizedRegion(val scope:RevisionScope,val text:String,val bounds:CanvasBounds,
    val engineId:String,val engineVersion:String,val complete:Boolean) {
    init { require(text.length<=100_000 && engineId.isNotBlank() && engineVersion.isNotBlank()) }
    fun isCurrent(current:RevisionScope)=scope==current
}
