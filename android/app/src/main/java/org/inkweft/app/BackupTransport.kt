// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

internal data class BackupIdentity(val url:String,val server:String,val issuer:String,val user:String,val device:String,val token:String){
    fun binding(library:String)=listOf(url,server,issuer,user,device,library).joinToString("|")
}
internal class BackupHttpError(val status:Int):java.io.IOException("BACKUP_HTTP_$status")

/** Separate from page-scoped CloudServicePort. No redirects, global credentials or author body access. */
internal class BackupTransport(val identity:BackupIdentity){
    fun verifyServer(){val j=JSONObject(raw(identity.url,"GET","/v1/identity").toString(Charsets.UTF_8));require(j.getString("server")==identity.server&&j.getString("issuer")==identity.issuer){"SERVER_CHANGED"}}
    fun request(method:String,library:String,path:String="",json:JSONObject?=null,bytes:ByteArray?=null):ByteArray {
        UUID.fromString(library)
        return raw(identity.url,method,"/v1/libraries/$library$path",json?.toString()?.toByteArray()?:bytes,identity.token,if(bytes==null)"application/json"else"application/octet-stream")
    }
    fun json(method:String,library:String,path:String="",body:JSONObject?=null)=JSONObject(request(method,library,path,body).toString(Charsets.UTF_8))
    fun versions(library:String)=JSONArray(request("GET",library,"/versions").toString(Charsets.UTF_8))
    fun logout(){raw(identity.url,"DELETE","/v1/sessions/current",token=identity.token)}
    companion object {
        fun normalize(url:String):String {
            val uri=URI(url.trim().trimEnd('/'))
            require(uri.host!=null&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&uri.path.isNullOrEmpty()){"SERVER_ADDRESS"}
            require(uri.scheme=="https"||(BuildConfig.DEBUG&&uri.scheme=="http"&&uri.host in setOf("127.0.0.1","localhost"))){"HTTPS_REQUIRED"}
            return uri.toString()
        }
        fun login(url:String,username:String,password:String,device:String):BackupIdentity {
            UUID.fromString(device);val base=normalize(url)
            val discovery=JSONObject(raw(base,"GET","/v1/identity").toString(Charsets.UTF_8))
            val body=JSONObject().put("username",username).put("password",password).put("device",device)
            val result=JSONObject(raw(base,"POST","/v1/sessions",body.toString().toByteArray()).toString(Charsets.UTF_8))
            require(result.getString("server")==discovery.getString("server")&&result.getString("issuer")==discovery.getString("issuer"))
            return BackupIdentity(base,result.getString("server"),result.getString("issuer"),result.getString("user"),device,result.getString("token"))
        }
        private fun raw(base:String,method:String,path:String,body:ByteArray?=null,token:String?=null,type:String="application/json"):ByteArray {
            val connection=(URI(normalize(base)+path).toURL().openConnection() as HttpURLConnection)
            try{connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=20000
                token?.let{connection.setRequestProperty("Authorization","Bearer $it")}
                if(body!=null){require(body.size<=EncryptedBackupFile.WIRE_BLOCK);connection.doOutput=true;connection.setRequestProperty("Content-Type",type);connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use{it.write(body)}}
                val status=connection.responseCode;if(status !in 200..299)throw BackupHttpError(status)
                return connection.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=EncryptedBackupFile.WIRE_BLOCK+32768){"RESPONSE_BUDGET"};out.write(buffer,0,n)};out.toByteArray()}
            }finally{connection.disconnect()}
        }
    }
}
