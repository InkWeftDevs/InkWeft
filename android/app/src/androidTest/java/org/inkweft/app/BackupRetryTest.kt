package org.inkweft.app

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.*

class BackupRetryTest {
    @Test fun retryAfterSecondsDatesAndOverflowAreNotRetriedEarly(){
        assertEquals(2000L,BackupTransport.retryAfter("2"));assertEquals(0L,BackupTransport.retryAfter("0"))
        assertEquals(1000L,BackupTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT",1445412479000))
        assertTrue(BackupTransport.retryAfter("999999999999999999999999")!!>30_000)
        assertNull(BackupTransport.retryAfter("invalid"));assertNull(BackupTransport.retryAfter("-1"))
    }
    @Test fun rateLimitUsesServerDelayAndRetainsRequestIdentity()=runBlocking<Unit>{
        val server=java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));val executor=Executors.newSingleThreadExecutor();val times=CopyOnWriteArrayList<Long>();val paths=CopyOnWriteArrayList<String>()
        val future=executor.submit{repeat(3){attempt->server.accept().use{socket->socket.soTimeout=10000;val reader=socket.getInputStream().bufferedReader();paths.add(reader.readLine());while(reader.readLine().isNotEmpty()){};times.add(System.nanoTime())
            val status=if(attempt<2)"429 Too Many Requests"else"200 OK";socket.getOutputStream().write("HTTP/1.1 $status\r\nRetry-After: 2\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())}}}
        try{
            val identity=BackupIdentity("http://127.0.0.1:${server.localPort}","synthetic-server","synthetic-issuer","synthetic-user",UUID.randomUUID().toString(),"synthetic-token")
            assertEquals("{}",BackupTransport(identity).request("GET",UUID.randomUUID().toString()).toString(Charsets.UTF_8));future.get(10,TimeUnit.SECONDS)
            assertEquals(1,paths.distinct().size);assertEquals(3,times.size);assertTrue(times[1]-times[0]>=1_900_000_000L);assertTrue(times[2]-times[1]>=1_900_000_000L)
        }finally{server.close();future.cancel(true);executor.shutdownNow()}
    }
}
