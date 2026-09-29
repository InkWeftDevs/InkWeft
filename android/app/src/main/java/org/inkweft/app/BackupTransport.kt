// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

internal data class BackupIdentity(val url:String,val server:String,val issuer:String,val user:String,val device:String,val token:String,val name:String="",val expires:Long=0){
    fun binding(library:String)=listOf(url,server,issuer,user,device,library).joinToString("|")
}
internal class BackupHttpError(val status:Int,val retryAfterMillis:Long?=null):java.io.IOException("BACKUP_HTTP_$status")

/** Separate from page-scoped CloudServicePort. No redirects, global credentials or author body access. */
internal class BackupTransport(val identity:BackupIdentity){
    suspend fun verifyServer(){val j=JSONObject(raw(identity.url,"GET","/v1/identity").toString(Charsets.UTF_8));require(j.getString("server")==identity.server&&j.getString("issuer")==identity.issuer){"SERVER_CHANGED"}}
    suspend fun request(method:String,library:String,path:String="",json:JSONObject?=null,bytes:ByteArray?=null):ByteArray {
        UUID.fromString(library)
        return raw(identity.url,method,"/v1/libraries/$library$path",json?.toString()?.toByteArray()?:bytes,identity.token,if(bytes==null)"application/json"else"application/octet-stream")
    }
    suspend fun json(method:String,library:String,path:String="",body:JSONObject?=null)=JSONObject(request(method,library,path,body).toString(Charsets.UTF_8))
    suspend fun versions(library:String)=JSONArray(request("GET",library,"/versions?include_pending=true").toString(Charsets.UTF_8))
    suspend fun logout(){raw(identity.url,"DELETE","/v1/sessions/current",token=identity.token)}
    companion object {
        fun normalize(url:String):String {
            val uri=URI(url.trim().trimEnd('/'))
            require(uri.host!=null&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&uri.path.isNullOrEmpty()){"SERVER_ADDRESS"}
            require(uri.scheme=="https"||(BuildConfig.DEBUG&&uri.scheme=="http"&&uri.host in setOf("127.0.0.1","localhost"))){"HTTPS_REQUIRED"}
            return uri.toString()
        }
        suspend fun login(url:String,username:String,password:String,device:String):BackupIdentity {
            UUID.fromString(device);val base=normalize(url)
            val discovery=JSONObject(raw(base,"GET","/v1/identity").toString(Charsets.UTF_8))
            val body=JSONObject().put("username",username).put("password",password).put("device",device)
            val result=JSONObject(raw(base,"POST","/v1/sessions",body.toString().toByteArray()).toString(Charsets.UTF_8))
            require(result.getString("server")==discovery.getString("server")&&result.getString("issuer")==discovery.getString("issuer"))
            return BackupIdentity(base,result.getString("server"),result.getString("issuer"),result.getString("user"),device,result.getString("token"),username,result.getLong("expires"))
        }
        internal fun retryAfter(value:String?,now:Long=System.currentTimeMillis()):Long? {
            value?:return null
            if(value.trim().matches(Regex("[0-9]+")))return (value.trim().toLongOrNull()?:Long.MAX_VALUE).coerceAtMost(Long.MAX_VALUE/1000)*1000
            return runCatching{(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceAtLeast(0)}.getOrNull()
        }
        // Cancellation disconnects the actual socket; retries keep the caller's operation identity.
        private val network = java.util.concurrent.Executors.newFixedThreadPool(2)
        private suspend fun raw(base:String,method:String,path:String,body:ByteArray?=null,token:String?=null,type:String="application/json"):ByteArray {
            repeat(3) { attempt ->
                try { return withTimeout(30_000) { exchange(base,method,path,body,token,type) } }
                catch(c:CancellationException){throw c}
                catch(e:Exception){
                    val retry = (e is BackupHttpError && (e.status==429 || e.status in 500..504)) ||
                        (e is java.io.IOException && e !is BackupHttpError && e !is javax.net.ssl.SSLException)
                    if(!retry || attempt==2) throw e
                    val wait=(e as? BackupHttpError)?.retryAfterMillis ?: (1000L shl attempt)
                    if(wait>30_000)throw e // Preserve the server deadline; never retry earlier by clamping it.
                    delay(wait)
                }
            }
            error("Unreachable")
        }
        private suspend fun exchange(base:String,method:String,path:String,body:ByteArray?,token:String?,type:String):ByteArray = suspendCancellableCoroutine { continuation ->
            val connection=(URI(normalize(base)+path).toURL().openConnection() as HttpURLConnection)
            val future=network.submit {
                try {
                    if(!continuation.isActive)return@submit
                    connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=5000;connection.readTimeout=5000
                    token?.let{connection.setRequestProperty("Authorization","Bearer $it")}
                    if(body!=null){require(body.size<=EncryptedBackupFile.WIRE_BLOCK);connection.doOutput=true;connection.setRequestProperty("Content-Type",type);connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use{it.write(body)}}
                    val status=connection.responseCode;if(status !in 200..299)throw BackupHttpError(status,retryAfter(connection.getHeaderField("Retry-After")))
                    val bytes=connection.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true){if(!continuation.isActive)throw CancellationException();val n=input.read(buffer);if(n<0)break;require(out.size()+n<=EncryptedBackupFile.WIRE_BLOCK+32768){"RESPONSE_BUDGET"};out.write(buffer,0,n)};out.toByteArray()}
                    if(continuation.isActive)continuation.resume(bytes)
                }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                finally{connection.disconnect()}
            }
            continuation.invokeOnCancellation{connection.disconnect();future.cancel(true)}
        }
    }
}
