// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.util.AtomicFile
import org.inkweft.core.*
import java.io.*
import java.util.UUID

data class InkCheckpoint(val stroke:InkStroke,val layerScope:LayerWriteScope?=null)

/** A cumulative chunk replaces only the same stroke's confirmed prefix. It is not a new stroke. */
internal class InkCheckpoints(private val root:File,private val fault:(String)->Unit={}){
    private fun directory(page:String):File {UUID.fromString(page);return File(root,page)}
    private fun file(page:String,id:String):AtomicFile{UUID.fromString(id);return AtomicFile(File(directory(page),"$id.inkpart"))}
    @Synchronized fun save(page:String,sequence:Int,stroke:InkStroke,layerScope:LayerWriteScope?=null){
        require(sequence==stroke.samples.size);val dir=directory(page);check(dir.exists()||dir.mkdirs())
        val target=file(page,stroke.id)
        if(target.baseFile.exists()){
            val checkpoint=read(target);require(checkpoint.layerScope==layerScope){"CHECKPOINT_LAYER_CHANGED"};val old=checkpoint.stroke;if(sequence<=old.samples.size){require(old.samples.take(sequence)==stroke.samples);return}
            require(stroke.samples.take(old.samples.size)==old.samples){"CHECKPOINT_PREFIX_CHANGED"}
        }
        fault("before-serialize");val ink=InkStrokeCodec.encode(stroke)
        val bytes=if(layerScope==null)ink else ByteArrayOutputStream().also{buffer->DataOutputStream(buffer).use{d->d.writeInt(0x49574332);d.writeUTF(layerScope.layerId);d.writeLong(layerScope.configurationRevision);d.writeInt(ink.size);d.write(ink)}}.toByteArray();fault("after-serialize")
        val stream=target.startWrite()
        try{stream.write(bytes);fault("before-commit");target.finishWrite(stream)}catch(t:Throwable){target.failWrite(stream);throw t}
        fault("after-commit")
    }
    private fun read(file:AtomicFile)=file.openRead().use{input->val bytes=input.readBytes();require(bytes.size<=2_000_100)
        DataInputStream(ByteArrayInputStream(bytes)).use{d->if(d.readInt()!=0x49574332)InkCheckpoint(InkStrokeCodec.decode(bytes))else{
            val scope=LayerWriteScope(d.readUTF(),d.readLong());val n=d.readInt();require(n in 1..InkLimits.MAX_STROKE_BYTES&&n==d.available())
            InkCheckpoint(InkStrokeCodec.decode(ByteArray(n).also(d::readFully)),scope)
        }}
    }
    fun read(page:String):List<InkStroke> = readScoped(page).map{it.stroke}
    @Synchronized fun readScoped(page:String):List<InkCheckpoint> {
        val files=directory(page).listFiles().orEmpty().filter{it.name.endsWith(".inkpart")||it.name.endsWith(".inkpart.bak")}
        require(files.size<=32){"CHECKPOINT_BUDGET"}
        return files.map{it.name.removeSuffix(".bak").removeSuffix(".inkpart")}.distinct().map{id->read(file(page,id)).also{require(it.stroke.id==id)}}
    }
    @Synchronized fun remove(page:String,id:String){file(page,id).delete()}
}
