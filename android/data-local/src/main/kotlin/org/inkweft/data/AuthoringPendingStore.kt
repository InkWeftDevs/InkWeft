// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.util.AtomicFile
import org.inkweft.core.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Author bytes belong on disk, never in Android's saved-state Binder transaction. */
class AuthoringPending(val scope:AuthoringScope,val commandId:String,val before:AuthoringSnapshot,val after:PageAuthoring,val direction:Int=0,beforePayload:ByteArray=PageAuthoringCodec.encode(before.state),afterPayload:ByteArray=PageAuthoringCodec.encode(after),val ownershipEdit:LayerOwnershipEdit?=null,val retainedHistory:List<String> = emptyList()) {
    private val frozenBefore=beforePayload.copyOf();private val frozenAfter=afterPayload.copyOf()
    fun beforePayload()=frozenBefore.copyOf()
    fun afterPayload()=frozenAfter.copyOf()
    init { UUID.fromString(commandId);require(direction in -1..1);require(retainedHistory.size<=20&&retainedHistory.distinct().size==retainedHistory.size);retainedHistory.forEach(UUID::fromString);require(before.editorProjection||ownershipEdit==null) }
}
internal class AuthoringPendingStore(private val root:File,private val fault:(String)->Unit={}) {
    private fun file(scope:AuthoringScope)=AtomicFile(File(File(root,"authoring"),"${scope.kind.name}-${scope.id}.iwpending"))
    @Synchronized fun save(p:AuthoringPending) {
        val target=file(p.scope);check(target.baseFile.parentFile!!.exists()||target.baseFile.parentFile!!.mkdirs())
        val old=read(p.scope)
        if(old!=null){
            require(old.commandId==p.commandId&&old.direction==p.direction&&old.before.revision==p.before.revision&&old.before.inkRevision==p.before.inkRevision&&old.before.objectRevision==p.before.objectRevision&&old.before.graphFingerprint==p.before.graphFingerprint&&old.beforePayload().contentEquals(p.beforePayload())&&old.afterPayload().contentEquals(p.afterPayload())&&old.before.editorProjection==p.before.editorProjection&&old.ownershipEdit==p.ownershipEdit&&old.retainedHistory==p.retainedHistory){"AUTHORING_PENDING_EXISTS"}
            return
        }
        val bytes=ByteArrayOutputStream().also{b->DataOutputStream(b).use{d->
            d.writeInt(0x49574a32);d.writeUTF(p.scope.notebookId);d.writeUTF(p.scope.kind.name);d.writeUTF(p.scope.id);d.writeUTF(p.commandId);d.writeInt(p.direction)
            d.writeLong(p.before.revision);d.writeLong(p.before.inkRevision);d.writeLong(p.before.objectRevision);d.writeUTF(p.before.graphFingerprint.orEmpty())
            d.writeBoolean(p.before.editorProjection)
            when(val edit=p.ownershipEdit){
                null->d.writeByte(0)
                is LayerOwnershipEdit.Layer->{d.writeByte(1);d.writeUTF(edit.source);d.writeUTF(edit.target.orEmpty())}
                is LayerOwnershipEdit.Replay->{d.writeByte(2);d.writeUTF(edit.command);d.writeBoolean(edit.reverse)}
            }
            d.writeInt(p.retainedHistory.size);p.retainedHistory.forEach(d::writeUTF)
            for(payload in listOf(p.beforePayload(),p.afterPayload())){d.writeInt(payload.size);d.write(payload)}
        }}.toByteArray()
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
        fault("before-write");val stream=target.startWrite()
        try{stream.write(bytes);stream.write(digest);fault("before-commit");target.finishWrite(stream)}catch(t:Throwable){target.failWrite(stream);throw t}
        fault("after-commit")
    }
    @Synchronized fun read(scope:AuthoringScope):AuthoringPending? {
        val target=file(scope);if(!target.baseFile.exists()&&!File(target.baseFile.path+".bak").exists())return null
        val raw=target.openRead().use{input->require(input.channel.size() in 64..Int.MAX_VALUE.toLong());input.readBytes()}
        require(raw.size>=64);val bytes=raw.copyOfRange(0,raw.size-32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),raw.copyOfRange(raw.size-32,raw.size))){"AUTHORING_PENDING_CORRUPT"}
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            val version=d.readInt();require(version in listOf(0x49574a31,0x49574a32));val saved=AuthoringScope(d.readUTF(),AuthoringScopeKind.valueOf(d.readUTF()),d.readUTF());require(saved==scope)
            val command=d.readUTF();val direction=d.readInt();val revision=d.readLong();val ink=d.readLong();val objects=d.readLong();val graph=d.readUTF().ifEmpty{null}
            require(revision>=0&&ink>=0&&objects>=0&&graph?.matches(Regex("[0-9a-f]{64}"))!=false)
            val projection=version>=0x49574a32&&d.readBoolean()
            val edit=if(version==0x49574a31)null else when(d.readUnsignedByte()){
                0->null;1->LayerOwnershipEdit.Layer(d.readUTF(),d.readUTF().ifEmpty{null});2->LayerOwnershipEdit.Replay(d.readUTF(),d.readBoolean());else->error("AUTHORING_PENDING_CORRUPT")
            }
            val retained=if(version==0x49574a31)emptyList()else List(d.readInt().also{require(it in 0..20)}){d.readUTF()}
            fun payload():ByteArray {val n=d.readInt();require(n>=8&&n<=d.available());return ByteArray(n).also(d::readFully)}
            val before=payload();val after=payload();require(d.available()==0)
            AuthoringPending(scope,command,AuthoringSnapshot(revision,PageAuthoringCodec.decode(before),ink,objects,graph,projection),PageAuthoringCodec.decode(after),direction,before,after,edit,retained)
        }
    }
    @Synchronized fun remove(scope:AuthoringScope,commandId:String?=null) {
        if(commandId!=null)require(read(scope)?.commandId==commandId){"AUTHORING_PENDING_CHANGED"}
        file(scope).delete()
    }
}
