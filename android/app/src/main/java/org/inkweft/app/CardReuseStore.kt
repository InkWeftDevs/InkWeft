// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.util.AtomicFile
import org.inkweft.data.CardReuseKind
import org.inkweft.data.CardReuseRequest
import java.io.*
import java.util.UUID

/** Small durable intent only. Card content is read at the frozen revision in the database transaction. */
internal class CardReuseStore(context:Context,private val cardId:String){
    private val file=AtomicFile(File(context.filesDir,"card-reuse-$cardId.pending"))
    init{UUID.fromString(cardId)}
    fun read():ArrayList<String>?=synchronized(lock){
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
        file.openRead().use{stream->
            val buffer=ByteArray(4097);var size=0
            while(size<buffer.size){val count=stream.read(buffer,size,buffer.size-size);if(count<0)break;size+=count}
            require(size<=4096);val bytes=buffer.copyOf(size)
            DataInputStream(ByteArrayInputStream(bytes)).use{input->
                require(input.readInt()==0x49574352)
                ArrayList(List(7){input.readUTF()}).also{fields->require(input.read()==-1);val command=request(fields);require(command.cardId==cardId)}
            }
        }
    }
    fun save(fields:ArrayList<String>)=synchronized(lock){
        val command=request(fields);require(command.cardId==cardId)
        read()?.let{require(request(it).digest()==command.digest()){ "另一复用操作仍待核对" };return@synchronized}
        val bytes=ByteArrayOutputStream().also{out->DataOutputStream(out).use{d->d.writeInt(0x49574352);fields.forEach(d::writeUTF)}}.toByteArray();require(bytes.size<=4096)
        var stream:FileOutputStream?=null
        try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
    fun clear(fields:ArrayList<String>)=synchronized(lock){
        val existing=read()?:return@synchronized
        require(request(existing).digest()==request(fields).digest());file.delete()
        check(!file.baseFile.exists())
    }
    companion object {
        private val lock=Any()
        fun fields(r:CardReuseRequest)=arrayListOf(r.operationId,r.cardId,r.cardRevision.toString(),r.destination,r.kind.name,r.presentationId.orEmpty(),r.presentationRevision.toString())
        fun request(f:List<String>):CardReuseRequest{require(f.size==7);return CardReuseRequest(f[0],f[1],f[2].toLong(),f[3],CardReuseKind.valueOf(f[4]),f[5].ifEmpty{null},f[6].toLong())}
    }
}
