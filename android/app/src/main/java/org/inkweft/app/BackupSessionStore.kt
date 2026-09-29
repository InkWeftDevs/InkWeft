// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore protects session credentials only. Recovery keys are deliberately separate and exportable. */
internal class BackupSessionStore(context:Context){
    private val prefs=context.getSharedPreferences("inkweft-backup-session",Context.MODE_PRIVATE)
    private val alias=context.packageName+".backup-session"
    val device:String=getOrCreate("device")
    val localLibrary:String=getOrCreate("library")
    private fun getOrCreate(key:String)=prefs.getString(key,null)?:java.util.UUID.randomUUID().toString().also{prefs.edit().putString(key,it).commit()}
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (ks.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());generateKey()}
    }
    fun save(identity:BackupIdentity){
        val j=JSONObject().put("url",identity.url).put("server",identity.server).put("issuer",identity.issuer).put("user",identity.user).put("device",identity.device).put("token",identity.token)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        check(prefs.edit().putString("session",EncryptedBackupFile.b64(cipher.iv+cipher.doFinal(j.toString().toByteArray()))).commit())
    }
    fun read():BackupIdentity?=runCatching{
        val encoded=prefs.getString("session",null)?:return null;val bytes=EncryptedBackupFile.unb64(encoded);require(bytes.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val j=JSONObject(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8));BackupIdentity(j.getString("url"),j.getString("server"),j.getString("issuer"),j.getString("user"),j.getString("device"),j.getString("token"))
    }.getOrNull()
    fun clear(){check(prefs.edit().remove("session").commit())}
}
