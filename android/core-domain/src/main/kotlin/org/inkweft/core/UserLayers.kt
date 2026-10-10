// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.Collections
import java.util.UUID

/** Only a page/canvas or one occurrence's annotation area owns this stack. */
data class UserLayer(val id:String,val name:String,val visible:Boolean=true,val locked:Boolean=false) {
    init { UUID.fromString(id);require(name.isNotBlank()&&name.length<=60) }
    val writable get()=visible&&!locked
}
enum class LayerContentKind { INK, OBJECT, ANNOTATION }
data class LayerContent(val kind:LayerContentKind,val id:String) { init { UUID.fromString(id) } }
data class LayerMembership(val content:LayerContent,val layerId:String) { init { UUID.fromString(layerId) } }

/** Visibility here never mutates InkStrokeRow.visible or PageObject.hidden. */
class UserLayers(layers:List<UserLayer> = listOf(UserLayer(DEFAULT_ID,"基础层")),
    val currentId:String?=layers.firstOrNull{it.writable}?.id,
    memberships:List<LayerMembership> = emptyList(),deleted:List<LayerContent> = emptyList()) {
    val layers:List<UserLayer> = Collections.unmodifiableList(ArrayList(layers))
    val memberships:List<LayerMembership> = Collections.unmodifiableList(ArrayList(memberships))
    val deleted:List<LayerContent> = Collections.unmodifiableList(ArrayList(deleted))
    private val byId=this.layers.associateBy{it.id}
    private val byContent=this.memberships.associateBy{it.content}
    private val deletedSet=this.deleted.toSet()
    init {
        require(layers.size in 1..MAX_LAYERS&&layers.map{it.id}.distinct().size==layers.size)
        require(layers.any{it.writable}){"LAYER_LAST_WRITABLE"}
        require(currentId==null||layers.any{it.id==currentId&&it.writable}){"LAYER_CHOOSE_WRITABLE"}
        require(memberships.size<=MAX_CONTENT&&byContent.size==memberships.size)
        require(memberships.all{it.layerId in byId})
        require(deleted.size<=MAX_CONTENT&&deletedSet.size==deleted.size&&deleted.none{it in byContent})
    }
    fun layer(content:LayerContent)=byContent[content]?.let{byId[it.layerId]}
    fun owns(content:LayerContent)=content in byContent||content in deletedSet
    fun isDeleted(content:LayerContent)=content in deletedSet
    fun visible(content:LayerContent)=content !in deletedSet&&layer(content)?.visible==true
    fun editable(content:LayerContent)=content !in deletedSet&&layer(content)?.writable==true
    fun selected(ids:Collection<LayerContent>):LayerSelection {
        require(ids.distinct().size==ids.size&&ids.all{visible(it)}){"LAYER_SELECTION_CHANGED"}
        return LayerSelection(ids.filter{editable(it)},ids.filterNot{editable(it)})
    }
    fun requireEditable(ids:Collection<LayerContent>) { require(ids.all{editable(it)}){"LAYER_SELECTION_LOCKED"} }
    fun add(layer:UserLayer):UserLayers {
        require(layer.writable&&layers.size<MAX_LAYERS&&layers.none{it.id==layer.id}){"LAYER_CAPACITY"}
        return UserLayers(layers+layer,layer.id,memberships,deleted)
    }
    fun select(id:String)=UserLayers(layers,id,memberships,deleted)
    fun update(layer:UserLayer):UserLayers {
        require(layers.any{it.id==layer.id})
        return UserLayers(layers.map{if(it.id==layer.id)layer else it},
            if(currentId==layer.id&&!layer.writable)null else currentId,memberships,deleted)
    }
    fun move(id:String,index:Int):UserLayers {
        require(index in layers.indices&&layers.any{it.id==id})
        val ordered=layers.toMutableList();val layer=ordered.first{it.id==id};ordered.remove(layer);ordered.add(index,layer)
        return UserLayers(ordered,currentId,memberships,deleted)
    }
    /** New author data must target the explicitly selected writable layer. */
    fun assignNew(ids:Collection<LayerContent>,targetId:String?=currentId):UserLayers {
        val current=checkNotNull(targetId){"LAYER_CHOOSE_WRITABLE"}
        require(layers.any{it.id==current&&it.writable}){"LAYER_TARGET_NOT_WRITABLE"}
        require(ids.distinct().size==ids.size&&ids.none{owns(it)})
        return UserLayers(layers,currentId,memberships+ids.map{LayerMembership(it,current)},deleted)
    }
    fun transfer(ids:Collection<LayerContent>,targetId:String):UserLayers {
        require(ids.isNotEmpty());requireEditable(ids)
        require(layers.any{it.id==targetId&&it.writable}){"LAYER_TARGET_NOT_WRITABLE"}
        val selected=ids.toSet()
        return UserLayers(layers,currentId,memberships.map{if(it.content in selected)it.copy(layerId=targetId)else it},deleted)
    }
    /** A null decision is permitted only for an empty layer. Never silently picks a destination. */
    fun remove(id:String,decision:LayerDelete?=null):UserLayers {
        val removed=layers.firstOrNull{it.id==id}?:error("LAYER_MISSING")
        val owned=memberships.filter{it.layerId==id}
        require(owned.isEmpty()||decision!=null){"LAYER_DELETE_CHOOSE_CONTENT"}
        require(owned.isEmpty()||!removed.locked){"LAYER_UNLOCK_BEFORE_DELETE"}
        val remaining=layers.filterNot{it.id==id}
        require(remaining.any{it.writable}){"LAYER_LAST_WRITABLE"}
        val target=(decision as? LayerDelete.Transfer)?.targetId
        if(target!=null)require(remaining.any{it.id==target&&it.writable}){"LAYER_TARGET_NOT_WRITABLE"}
        val moved=memberships.mapNotNull{m->if(m.layerId!=id)m else target?.let{m.copy(layerId=it)}}
        return UserLayers(remaining,if(currentId==id)null else currentId,moved,
            deleted+if(target==null)owned.map{it.content}else emptyList())
    }
    companion object {
        const val DEFAULT_ID="00000000-0000-0000-0000-000000000001"
        const val MAX_LAYERS=32
        const val LEGACY_MAX_CONTENT=22_000
        const val MAX_CONTENT=InkLimits.MAX_RETAINED_STROKES+PageObjectCodec.MAX_RECORDS+PageAuthoring.MAX_ANNOTATIONS
        fun legacy(contents:Collection<LayerContent>)=UserLayers(memberships=contents.map{LayerMembership(it,DEFAULT_ID)})
    }
}
sealed interface LayerDelete { data object DeleteContents:LayerDelete;data class Transfer(val targetId:String):LayerDelete }
data class LayerSelection(val editable:List<LayerContent>,val locked:List<LayerContent>) {
    val canEditAll get()=editable.isNotEmpty()&&locked.isEmpty()
}

/** Captured at pointer-down, retained by queue/checkpoint/receipt, never resolved at save time. */
data class LayerWriteScope(val layerId:String,val configurationRevision:Long) {
    init { UUID.fromString(layerId);require(configurationRevision>=0) }
}
fun UserLayers.writeScope(configurationRevision:Long)=LayerWriteScope(checkNotNull(currentId){"LAYER_CHOOSE_WRITABLE"},configurationRevision)
fun UserLayers.checkWrite(scope:LayerWriteScope?,configurationRevision:Long,affected:Collection<LayerContent> = emptyList()) {
    if(scope==null)require(layers.size==1&&layers.single().id==UserLayers.DEFAULT_ID&&currentId==UserLayers.DEFAULT_ID&&layers.single().writable){"LAYER_SCOPE_REQUIRED"}
    else require(scope.configurationRevision==configurationRevision&&scope.layerId==currentId&&layers.any{it.id==scope.layerId&&it.writable}){"LAYER_WRITE_SCOPE_CHANGED"}
    requireEditable(affected)
}

/** Beautified text and its retained author ink are one visible representation across user layers. */
fun UserLayers.requireBeautyOwnership(objects:List<PageObject>) {
    objects.filter{it.sourceStrokeIds.isNotEmpty()}.forEach{o->
        val objectRef=LayerContent(LayerContentKind.OBJECT,o.id)
        require(o.sourceStrokeIds.all{id->val source=LayerContent(LayerContentKind.INK,id)
            if(isDeleted(objectRef))isDeleted(source) else layer(objectRef)?.id!=null&&layer(objectRef)?.id==layer(source)?.id
        }){"LAYER_BEAUTY_MOVE_TOGETHER"}
    }
}
