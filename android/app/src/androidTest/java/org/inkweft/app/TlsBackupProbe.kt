package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Explicit isolated build only. Not selected by the ordinary *Test discovery. */
class TlsBackupProbe {
    private fun isolated(){require(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".tlsprobe"))}
    @Test fun trustedHttpsPreservesAuthorClosure()=runBlocking{isolated();EncryptedBackupIntegrationTest().roundTrip("https://127.0.0.1:18761")}
    @Test fun expiredCertificateIsRejected()=rejected(18762)
    @Test fun wrongHostnameIsRejected()=rejected(18763)
    @Test fun unknownIssuerIsRejected()=rejected(18764)
    private fun rejected(port:Int)=runBlocking<Unit>{
        isolated()
            if(port==18764){
                val connection=java.net.URL("https://127.0.0.1:$port/v1/identity").openConnection() as javax.net.ssl.HttpsURLConnection
                try{connection.connect();val chain=connection.serverCertificates.map{it as java.security.cert.X509Certificate}.toTypedArray()
                    println("TLS peer: ${chain.first().subjectX500Principal}; issuer: ${chain.first().issuerX500Principal}")
                    val factory=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());factory.init(null as java.security.KeyStore?)
                    val trust=factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
                    try{android.net.http.X509TrustManagerExtensions(trust).checkServerTrusted(chain,"RSA","127.0.0.1");println("Platform trust accepted unknown issuer")}
                    catch(e:java.security.cert.CertificateException){println("Platform trust rejected unknown issuer: ${e.javaClass.simpleName}")}
                }catch(_:javax.net.ssl.SSLException){println("TLS rejected unknown issuer before HTTP")}
                finally{connection.disconnect()}
            }
            try{BackupTransport.login("https://127.0.0.1:$port","synthetic-alice","synthetic-alice-password-123",UUID.randomUUID().toString());fail("Untrusted TLS accepted at $port")}
            catch(_:javax.net.ssl.SSLException){}
    }
    @Test fun redirectAndRemoteHttpCannotDowngrade()=runBlocking<Unit>{
        isolated()
        try{BackupTransport.login("https://127.0.0.1:18765","synthetic-alice","synthetic-alice-password-123",UUID.randomUUID().toString());fail("Redirect followed")}
        catch(e:BackupHttpError){assertEquals(307,e.status)}
        assertThrows(IllegalArgumentException::class.java){BackupTransport.normalize("http://backup.example.com")}
    }
}
