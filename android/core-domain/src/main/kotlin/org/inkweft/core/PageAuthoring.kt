// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

enum class AuthoringScopeKind { PAGE, MAP }
data class AuthoringScope(val notebookId:String,val kind:AuthoringScopeKind,val id:String) {
    init { UUID.fromString(notebookId);UUID.fromString(id) }
    companion object { fun page(book:String,page:String)=AuthoringScope(book,AuthoringScopeKind.PAGE,page);fun map(ref:MapRef)=AuthoringScope(ref.notebookId,AuthoringScopeKind.MAP,ref.mapId?:ref.notebookId) }
}

/** Local author state. Deleted layer contents remain in original payloads and in this undoable projection. */
class PageAuthoring(val layers:UserLayers=UserLayers(),blanks:List<DocumentWhitespace> = emptyList(),annotations:List<BoundAnnotation> = emptyList(),regions:List<AnnotationRegion> = emptyList()) {
    val blanks:List<DocumentWhitespace> = Collections.unmodifiableList(ArrayList(blanks))
    val annotations:List<BoundAnnotation> = Collections.unmodifiableList(ArrayList(annotations))
    val regions:List<AnnotationRegion> = Collections.unmodifiableList(ArrayList(regions))
    init {
        DocumentWhitespaceLayout(blanks)
        require(regions.size<=AnnotationRegion.MAX_REGIONS&&regions.map{it.target}.distinct().size==regions.size)
        require(annotations.size<=MAX_ANNOTATIONS&&annotations.map{it.stroke.id}.distinct().size==annotations.size)
        require(annotations.sumOf{it.stroke.samples.size}<=MAX_POINTS)
        require(annotations.all{a->layers.owns(LayerContent(LayerContentKind.ANNOTATION,a.stroke.id))}){"ANNOTATION_LAYER_MISSING"}
        require(annotations.all{a->a.target.kind!=AnnotationTargetKind.WHITESPACE||blanks.any{it.id==a.target.id}}){"ANNOTATION_BLANK_MISSING"}
    }
    val legacy get()=blanks.isEmpty()&&annotations.isEmpty()&&regions.isEmpty()&&layers.deleted.isEmpty()&&layers.layers==listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"))&&layers.currentId==UserLayers.DEFAULT_ID
    fun withLayers(next:UserLayers)=PageAuthoring(next,blanks,annotations,regions)
    fun withBlanks(next:List<DocumentWhitespace>)=PageAuthoring(layers,next,annotations,regions)
    fun add(annotation:BoundAnnotation)=PageAuthoring(layers.assignNew(listOf(LayerContent(LayerContentKind.ANNOTATION,annotation.stroke.id))),blanks,annotations+annotation,regions)
    fun replaceAnnotation(annotation:BoundAnnotation):PageAuthoring {
        val ref=LayerContent(LayerContentKind.ANNOTATION,annotation.stroke.id);layers.requireEditable(listOf(ref))
        require(annotations.any{it.stroke.id==annotation.stroke.id})
        return PageAuthoring(layers,blanks,annotations.map{if(it.stroke.id==annotation.stroke.id)annotation else it},regions)
    }
    fun withRegion(region:AnnotationRegion)=PageAuthoring(layers,blanks,annotations,regions.filterNot{it.target==region.target}+region)
    fun visibleAnnotations()=annotations.filter{layers.visible(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))}
    companion object { const val MAX_ANNOTATIONS=512;const val MAX_POINTS=60_000 }
}

/** Bounded, closed payload; old page files remain valid with an empty/default state. */
object PageAuthoringCodec {
    const val MAX_BYTES=1_900_000 // Below the existing archive field ceiling; reject before author writes.
    fun encode(state:PageAuthoring):ByteArray {
        // Existing-size configurations retain their exact IWA3 representation.
        // Larger stacks use IWA4 compact identities within the same byte ceiling.
        val compact=state.layers.memberships.size>UserLayers.LEGACY_MAX_CONTENT||state.layers.deleted.size>UserLayers.LEGACY_MAX_CONTENT
        if(compact)return encode(state,true)
        return try{encode(state,false)}catch(e:IllegalArgumentException){
            if(e.message!="ANNOTATION_CAPACITY")throw e
            // A large stack can move from memberships to deleted identities.
            // Keep it readable even when neither list alone exceeds 22,000.
            encode(state,true)
        }
    }
    private fun encode(state:PageAuthoring,compact:Boolean):ByteArray {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use{d->
            d.writeInt(if(compact)0x49574134 else 0x49574133)
            d.writeInt(state.layers.layers.size)
            state.layers.layers.forEach{d.writeUTF(it.id);d.writeUTF(it.name);d.writeBoolean(it.visible);d.writeBoolean(it.locked)}
            d.writeBoolean(state.layers.currentId!=null);state.layers.currentId?.let(d::writeUTF)
            fun identity(id:String){
                if(!compact){d.writeUTF(id);return}
                val uuid=UUID.fromString(id);val canonical=uuid.toString()==id
                d.writeBoolean(canonical)
                if(canonical){d.writeLong(uuid.mostSignificantBits);d.writeLong(uuid.leastSignificantBits)}else d.writeUTF(id)
            }
            fun content(c:LayerContent){d.writeByte(c.kind.ordinal);identity(c.id)}
            d.writeInt(state.layers.memberships.size);state.layers.memberships.sortedWith(compareBy<LayerMembership>{it.content.kind.ordinal}.thenBy{it.content.id}).forEach{content(it.content);identity(it.layerId)}
            d.writeInt(state.layers.deleted.size);state.layers.deleted.sortedWith(compareBy<LayerContent>{it.kind.ordinal}.thenBy{it.id}).forEach(::content)
            d.writeInt(state.blanks.size);state.blanks.forEach{d.writeUTF(it.id);d.writeDouble(it.beforeY);d.writeDouble(it.height);d.writeBoolean(it.collapsed)}
            d.writeInt(state.annotations.size);state.annotations.forEach{a->
                d.writeByte(a.target.kind.ordinal);d.writeUTF(a.target.id);d.writeDouble(a.referenceWidth);d.writeDouble(a.localFrame.x);d.writeDouble(a.localFrame.y);d.writeDouble(a.localFrame.scale)
                val bytes=InkStrokeCodec.encode(a.stroke);require(buffer.size().toLong()+bytes.size+4<=MAX_BYTES){"ANNOTATION_CAPACITY"};d.writeInt(bytes.size);d.write(bytes)
            }
            d.writeInt(state.regions.size);state.regions.forEach{r->d.writeByte(r.target.kind.ordinal);d.writeUTF(r.target.id);d.writeDouble(r.width);d.writeDouble(r.height);d.writeBoolean(r.collapsed);d.writeDouble(r.referenceWidth)}
        }
        return buffer.toByteArray().also{require(it.size<=MAX_BYTES){"ANNOTATION_CAPACITY"}}
    }
    fun decode(bytes:ByteArray):PageAuthoring {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            val version=d.readInt();require(version in listOf(0x49574131,0x49574132,0x49574133,0x49574134)){"Unsupported authoring state; retain original bytes"}
            fun count(max:Int,min:Int=0)=d.readInt().also{require(it in min..max)}
            fun identity()=if(version<0x49574134||!d.readBoolean())d.readUTF()else UUID(d.readLong(),d.readLong()).toString()
            fun content()=LayerContent(LayerContentKind.entries.getOrNull(d.readUnsignedByte())?:error("Unknown layer content"),identity())
            val layers=List(count(UserLayers.MAX_LAYERS,1)){UserLayer(d.readUTF(),d.readUTF(),d.readBoolean(),d.readBoolean())}
            val current=if(d.readBoolean())d.readUTF()else null
            val membership=List(count(UserLayers.MAX_CONTENT)){LayerMembership(content(),identity())}
            val deleted=List(count(UserLayers.MAX_CONTENT)){content()}
            val blanks=List(count(DocumentWhitespaceLayout.MAX_BLANKS)){DocumentWhitespace(d.readUTF(),d.readDouble(),d.readDouble(),d.readBoolean())}
            var points=0
            val annotations=List(count(PageAuthoring.MAX_ANNOTATIONS)){
                val target=AnnotationTarget(AnnotationTargetKind.entries.getOrNull(d.readUnsignedByte())?:error("Unknown annotation target"),d.readUTF())
                val referenceWidth=d.readDouble()
                val frame=if(version>=0x49574132)AnnotationFrame(d.readDouble(),d.readDouble(),d.readDouble())else AnnotationFrame(0.0,0.0)
                val size=count(InkLimits.MAX_STROKE_BYTES,1);require(size<=d.available())
                val stroke=InkStrokeCodec.decode(ByteArray(size).also(d::readFully));points+=stroke.samples.size;require(points<=PageAuthoring.MAX_POINTS)
                BoundAnnotation(stroke,target,referenceWidth,frame)
            }
            val regions=if(version>=0x49574133)List(count(AnnotationRegion.MAX_REGIONS)){
                val target=AnnotationTarget(AnnotationTargetKind.entries.getOrNull(d.readUnsignedByte())?:error("Unknown annotation region"),d.readUTF())
                AnnotationRegion(target,d.readDouble(),d.readDouble(),d.readBoolean(),d.readDouble())
            }else emptyList()
            require(d.available()==0){"Trailing authoring state"}
            PageAuthoring(UserLayers(layers,current,membership,deleted),blanks,annotations,regions)
        }
    }
    fun fingerprint(state:PageAuthoring)=MessageDigest.getInstance("SHA-256").digest(encode(state)).joinToString(""){"%02x".format(it.toInt()and 255)}
}

/** Editable copy remaps every local identity; hidden data is never a visibility-filtered export. */
fun PageAuthoring.copied(pageId:String,strokeIds:Map<String,String>,objectIds:Map<String,String>):PageAuthoring {
    val layerIds=layers.layers.associate{it.id to if(it.id==UserLayers.DEFAULT_ID)it.id else UUID.randomUUID().toString()}
    val blankIds=blanks.associate{it.id to UUID.randomUUID().toString()}
    val annotationIds=annotations.associate{it.stroke.id to UUID.randomUUID().toString()}
    fun ref(c:LayerContent):LayerContent?=(when(c.kind){LayerContentKind.INK->strokeIds[c.id];LayerContentKind.OBJECT->objectIds[c.id];LayerContentKind.ANNOTATION->annotationIds[c.id]})?.let{LayerContent(c.kind,it)}
    val copied=annotations.map{a->
        val target=when(a.target.kind){
            AnnotationTargetKind.PAGE->a.target.copy(id=pageId)
            AnnotationTargetKind.WHITESPACE->a.target.copy(id=checkNotNull(blankIds[a.target.id]))
            AnnotationTargetKind.PAGE_OBJECT->a.target.copy(id=checkNotNull(objectIds[a.target.id]){"ANNOTATION_TARGET_COPY_MISSING"})
            AnnotationTargetKind.MAP_OCCURRENCE->error("MAP_ANNOTATION_REQUIRES_FULL_BACKUP")
        }
        val s=a.stroke
        a.copy(stroke=InkStroke(checkNotNull(annotationIds[s.id]),s.pen,s.color,s.width,s.tool,s.samples,true,
            s.cuts.map{InkCut(UUID.randomUUID().toString(),it.radius,it.points,it.shape)},s.appearance),target=target)
    }
    return PageAuthoring(UserLayers(layers.layers.map{it.copy(id=layerIds.getValue(it.id))},layers.currentId?.let(layerIds::getValue),
        layers.memberships.mapNotNull{m->ref(m.content)?.let{LayerMembership(it,layerIds.getValue(m.layerId))}},layers.deleted.mapNotNull(::ref)),
        blanks.map{it.copy(id=blankIds.getValue(it.id))},copied,regions.map{r->
            require(r.target.kind==AnnotationTargetKind.PAGE_OBJECT){"MAP_ANNOTATION_REQUIRES_FULL_BACKUP"}
            r.copy(target=r.target.copy(id=checkNotNull(objectIds[r.target.id]){"ANNOTATION_TARGET_COPY_MISSING"}))
        })
}
