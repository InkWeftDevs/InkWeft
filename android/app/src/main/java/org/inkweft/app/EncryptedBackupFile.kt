// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.util.Base64
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Standard AES-256-GCM, bounded chunks, independently wrapped data key. Never edits the author database. */
internal object EncryptedBackupFile {
    const val BLOCK=262144
    const val WIRE_BLOCK=1048576
    const val LIMIT=500L*WIRE_BLOCK
    private val magic="IWBK1\n".toByteArray()
    fun random(size:Int)=ByteArray(size).also{SecureRandom().nextBytes(it)}
    fun b64(value:ByteArray)=Base64.encodeToString(value,Base64.NO_WRAP)
    fun unb64(value:String)=Base64.decode(value,Base64.NO_WRAP)
    fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value)
    fun hex(value:ByteArray)=hash(value).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun int(value:Int)=java.nio.ByteBuffer.allocate(4).putInt(value).array()
    private fun cipher(mode:Int,key:ByteArray,nonce:ByteArray,aad:ByteArray,value:ByteArray):ByteArray {
        require(key.size==32&&nonce.size==12)
        return Cipher.getInstance("AES/GCM/NoPadding").run{init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD(aad);doFinal(value)}
    }
    fun encrypt(source:File,output:File,library:String,recovery:ByteArray,checkpoint:()->Unit={}){
        UUID.fromString(library);require(source.length()<=LIMIT){"BACKUP_TOO_LARGE"};require(recovery.size==32&&source.length()>0&&!output.exists())
        val key=random(32);val nonce=random(12);val prefix=random(8);val backup=UUID.randomUUID().toString();val size=source.length()
        val wrapped=cipher(Cipher.ENCRYPT_MODE,recovery,nonce,"inkweft-backup-key-v1|$library|$backup".toByteArray(),key)
        val header=JSONObject().put("format",1).put("library",library).put("backup",backup).put("plainBytes",size).put("chunkSize",BLOCK)
            .put("noncePrefix",b64(prefix)).put("wrapNonce",b64(nonce)).put("wrappedKey",b64(wrapped)).toString().toByteArray()
        var created=false
        try{check(output.createNewFile());created=true
            source.inputStream().buffered().use{src->FileOutputStream(output).use{file->val out=DataOutputStream(BufferedOutputStream(file));out.write(magic);out.writeInt(header.size);out.write(header)
                var remaining=size;var i=0
                while(remaining>0){checkpoint();val n=minOf(BLOCK.toLong(),remaining).toInt();val plain=ByteArray(n);DataInputStream(src).readFully(plain)
                    val encoded=cipher(Cipher.ENCRYPT_MODE,key,prefix+int(i),hash(header)+int(i),plain);out.writeInt(encoded.size);out.write(encoded);remaining-=n;i++}
                require(src.read()==-1){"SOURCE_CHANGED"};out.flush();file.fd.sync()
            }}
        }catch(t:Throwable){if(created)output.delete();throw t}finally{key.fill(0)}
    }
    fun decrypt(source:File,output:File,library:String,recovery:ByteArray,checkpoint:()->Unit={}){
        require(!output.exists()&&recovery.size==32);UUID.fromString(library)
        val temporary=File(output.parentFile,"decrypt-${UUID.randomUUID()}.partial")
        try{DataInputStream(source.inputStream().buffered()).use{input->
            val mark=ByteArray(magic.size);input.readFully(mark);require(mark.contentEquals(magic))
            val length=input.readInt();require(length in 1..4096);val header=ByteArray(length);input.readFully(header);val j=JSONObject(header.toString(Charsets.UTF_8))
            require(j.getInt("format")==1&&j.getString("library")==library&&j.getInt("chunkSize")==BLOCK)
            val size=j.getLong("plainBytes");require(size in 1..LIMIT);val backup=j.getString("backup");UUID.fromString(backup)
            val prefix=unb64(j.getString("noncePrefix"));require(prefix.size==8)
            val key=cipher(Cipher.DECRYPT_MODE,recovery,unb64(j.getString("wrapNonce")),"inkweft-backup-key-v1|$library|$backup".toByteArray(),unb64(j.getString("wrappedKey")))
            try{check(temporary.createNewFile());FileOutputStream(temporary).use{file->val out=BufferedOutputStream(file);var remaining=size;var i=0
                while(remaining>0){checkpoint();val n=minOf(BLOCK.toLong(),remaining).toInt();require(input.readInt()==n+16)
                    val encrypted=ByteArray(n+16);input.readFully(encrypted);out.write(cipher(Cipher.DECRYPT_MODE,key,prefix+int(i),hash(header)+int(i),encrypted));remaining-=n;i++}
                require(input.read()==-1);out.flush();file.fd.sync()
            }}finally{key.fill(0)}
        };require(!output.exists());check(temporary.renameTo(output))
        }finally{temporary.delete()}
    }
}

/** Android 12-compatible bounded stream read; does not require InputStream.readNBytes (API 33). */
internal fun InputStream.readBackupChunk(limit:Int):ByteArray {
    require(limit in 1..EncryptedBackupFile.WIRE_BLOCK)
    val buffer=ByteArray(limit);var offset=0
    while(offset<limit){val n=read(buffer,offset,limit-offset);if(n<0)break;if(n==0){val value=read();if(value<0)break;buffer[offset++]=value.toByte()}else offset+=n}
    return if(offset==limit)buffer else buffer.copyOf(offset)
}
