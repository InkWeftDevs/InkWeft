// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.util.AtomicFile
import org.inkweft.core.*
import java.io.*
import java.util.UUID

/** Storage slice only: fixed page identities survive reordering; closed groups reject late prefixes. */
data class InkGroupPrefix(val book:String,val pages:List<String>,val revisions:List<Long>,val origin:Int,val stroke:InkStroke,val state:String="OPEN",val finished:InkStroke?=null){
    init{UUID.fromString(book);require(pages.size in 1..500&&pages.distinct().size==pages.size&&revisions.size==pages.size&&revisions.all{it>=0});pages.forEach(UUID::fromString);require(origin in pages.indices&&state in listOf("OPEN","SEALING","SEALED","CANCELLED"));require(finished==null||finished.id==stroke.id)}
    fun commands():List<CommitInk> = ContinuousInk.split(finished?:stroke,origin,pages.size).map{(index,parts)->
        CommitInk(UUID.nameUUIDFromBytes("${stroke.id}:seal:${pages[index]}".toByteArray()).toString(),pages[index],revisions[index],InkMutation.Replace(emptyList(),parts))
    }
    fun digest()=ContentTransfer.hash((listOf(book,origin.toString())+pages+revisions.map{it.toString()}).joinToString("|").toByteArray()+InkStrokeCodec.encode(stroke)+(finished?.let{InkStrokeCodec.encode(it)}?:byteArrayOf()))
}
internal class InkGroupCheckpoints(private val root:File,private val fault:(String)->Unit={}){
    private fun file(id:String):AtomicFile{UUID.fromString(id);root.mkdirs();return AtomicFile(File(root,"$id.inkgroup"))}
    @Synchronized fun read(id:String):InkGroupPrefix? {
        val f=file(id);if(!f.baseFile.exists()&&!File(f.baseFile.path+".bak").exists())return null
        return f.openRead().use{raw->require(raw.available()<=4_100_000);DataInputStream(raw).use{d->
            val format=d.readInt();require(format in listOf(0x49474731,0x49474732));val book=d.readUTF();val state=d.readUTF();val origin=d.readInt();val count=d.readInt();require(count in 1..500)
            val pages=List(count){d.readUTF()};val revisions=List(count){d.readLong()};val size=d.readInt();require(size in 1..2_000_000)
            val stroke=InkStrokeCodec.decode(ByteArray(size).also{d.readFully(it)});val finished=if(format==0x49474732&&d.readBoolean()){val n=d.readInt();require(n in 1..2_000_000);InkStrokeCodec.decode(ByteArray(n).also{d.readFully(it)})}else null;require(d.read()==-1&&stroke.id==id)
            InkGroupPrefix(book,pages,revisions,origin,stroke,if(cancelled(id))"CANCELLED"else state,finished)
        }}
    }
    @Synchronized fun save(value:InkGroupPrefix):Boolean {
        if(cancelled(value.stroke.id))return false
        val old=read(value.stroke.id)
        if(old!=null){
            require(old.book==value.book&&old.pages==value.pages&&old.revisions==value.revisions&&old.origin==value.origin)
            if(old.state in listOf("SEALED","CANCELLED"))return false
            if(old.state=="SEALING"&&value.state!="SEALED")return false
            require(value.stroke.samples.take(old.stroke.samples.size)==old.stroke.samples){"GROUP_PREFIX_CHANGED"}
        }
        val f=file(value.stroke.id);val out=ByteArrayOutputStream();DataOutputStream(out).use{d->
            d.writeInt(0x49474732);d.writeUTF(value.book);d.writeUTF(value.state);d.writeInt(value.origin);d.writeInt(value.pages.size)
            value.pages.forEach(d::writeUTF);value.revisions.forEach(d::writeLong);val ink=InkStrokeCodec.encode(value.stroke);require(ink.size<=2_000_000);d.writeInt(ink.size);d.write(ink)
            d.writeBoolean(value.finished!=null);value.finished?.let{val bytes=InkStrokeCodec.encode(it);require(bytes.size<=2_000_000);d.writeInt(bytes.size);d.write(bytes)}
        }
        fault("before-write");val stream=f.startWrite();try{stream.write(out.toByteArray());fault("before-commit");f.finishWrite(stream)}catch(t:Throwable){f.failWrite(stream);throw t};fault("after-commit");return true
    }
    private fun cancellation(id:String):AtomicFile{UUID.fromString(id);root.mkdirs();return AtomicFile(File(root,"$id.cancelled"))}
    private fun cancelled(id:String)=cancellation(id).let{it.baseFile.exists()||File(it.baseFile.path+".bak").exists()}
    @Synchronized fun cancel(id:String){
        fault("before-write");val file=cancellation(id);val stream=file.startWrite()
        try{stream.write(byteArrayOf(1));fault("before-commit");file.finishWrite(stream)}catch(t:Throwable){file.failWrite(stream);throw t};fault("after-commit")
    }
    @Synchronized fun discard(id:String){file(id).delete()}
    @Synchronized fun pending(book:String):List<InkGroupPrefix>{
        val ids=root.listFiles().orEmpty().filter{it.name.endsWith(".inkgroup")||it.name.endsWith(".inkgroup.bak")}.map{it.name.removeSuffix(".bak").removeSuffix(".inkgroup")}.distinct()
        require(ids.size<=32){"GROUP_RECOVERY_BUDGET"}
        return ids.mapNotNull{read(it)}.filter{it.book==book&&it.state!="CANCELLED"}
    }
    @Synchronized fun hasUnsealed():Boolean=root.listFiles().orEmpty().filter{it.name.endsWith(".inkgroup")||it.name.endsWith(".inkgroup.bak")}.any{
        runCatching{read(it.name.removeSuffix(".bak").removeSuffix(".inkgroup"))?.state in listOf("OPEN","SEALING")}.getOrDefault(true)
    }
}
