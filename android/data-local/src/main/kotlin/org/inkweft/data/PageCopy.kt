// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.inkweft.core.*

/** Copies one saved page inside its caller's receipt transaction. Only bounded object references
 * and the annotation/configuration header are retained; ink ownership is read one row at a time. */
internal class PageCopy(private val db:NoteDatabase,private val inkRepository:InkRepository=InkRepository(db)) {
    suspend fun copy(source:String,target:String,validated:ValidatedImportOriginals?=null):InkRepository.CopiedInk {
        check(db.inTransaction())
        val ink=inkRepository.copyVisibleTo(source,target)
        val objects=PageObjectRepository(db).read(source).objects
        val ids=linkedMapOf<String,String>()
        // Object source sets have their own authoring bounds; unrelated page identities stay in SQL.
        for(objectRow in objects)if(objectRow.sourceStrokeIds.isNotEmpty())
            db.ink().visibleStrokeIds(source,objectRow.sourceStrokeIds).forEach{ids[it]=ink.strokeId(it)}
        val included=objects.mapNotNull{o->
            val refs=o.sourceStrokeIds.filter{it in ids}
            if(o.hidden&&refs.isEmpty())null else o.copy(sourceStrokeIds=refs,textRuns=o.textRuns.map{it.copy(sourceIds=it.sourceIds.filter{v->v in refs})})
        }
        val objectIds=PageObjectRepository(db).import(target,included,ids,ImageSourceRepository(db).forPage(source,included),validated)
        authoring(ink,objects,objectIds)
        return ink
    }

    private suspend fun authoring(ink:InkRepository.CopiedInk,objects:List<PageObject>,objectIds:Map<String,String>) {
        val sourceOwner=checkNotNull(db.pages().get(ink.source));val targetOwner=checkNotNull(db.pages().get(ink.target))
        val row=db.authoring().get("PAGE",ink.source)
        require(row==null||row.notebookId==sourceOwner.notebookId&&row.revision>=0)
        val header=if(row==null)PageAuthoring()else PageAuthoringCodec.decodeHeader(row.payload)
        val copied=header.copied(ink.target,emptyMap(),objectIds)
        val layerIds=header.layers.layers.zip(copied.layers.layers).associate{it.first.id to it.second.id}
        val repository=PageAuthoringRepository(db)
        repository.storePage(ink.target,targetOwner.notebookId,0,copied)
        val sql=db.openHelper.writableDatabase;val active=currentCoroutineContext();var writtenInk=0L
        sql.compileStatement("INSERT INTO page_layer_memberships(pageId,kind,contentId,layerId) VALUES(?,?,?,?)").use{insert->
            fun assign(kind:String,id:String,layer:String?){
                active.ensureActive();insert.clearBindings();insert.bindString(1,ink.target);insert.bindString(2,kind);insert.bindString(3,id)
                if(layer==null)insert.bindNull(4)else insert.bindString(4,layerIds.getValue(layer))
                insert.executeInsert();if(kind=="INK")writtenInk++
            }
            if(row==null){
                require(db.authoring().membershipCount(ink.source)==0L){"LAYER_HEADER_MISSING"}
                sql.query("SELECT id FROM ink_strokes WHERE noteId=? AND visible=1 ORDER BY createdRevision,id",arrayOf(ink.source)).use{c->
                    while(c.moveToNext())assign("INK",ink.strokeId(c.getString(0)),UserLayers.DEFAULT_ID)
                }
                objectIds.values.forEach{assign("OBJECT",it,UserLayers.DEFAULT_ID)}
            }else{
                val sourceObjects=objects.map{it.id}.toSet();val ownedObjects=mutableSetOf<String>();var ownedInk=0L
                sql.query("SELECT m.kind,m.contentId,m.layerId,s.noteId,s.visible FROM page_layer_memberships m LEFT JOIN ink_strokes s ON m.kind='INK' AND s.id=m.contentId WHERE m.pageId=? ORDER BY m.kind,m.contentId",arrayOf(ink.source)).use{c->
                    while(c.moveToNext()){
                        active.ensureActive()
                        val ref=PageLayerMembershipRow(ink.source,c.getString(0),c.getString(1),if(c.isNull(2))null else c.getString(2))
                        val content=ref.content();require(ref.layerId==null||ref.layerId in layerIds){"LAYER_CONTENT_UNASSIGNED"}
                        when(content.kind){
                            LayerContentKind.INK->if(!c.isNull(3)&&c.getString(3)==ink.source){
                                ownedInk++;if(c.getInt(4)==1)assign("INK",ink.strokeId(content.id),ref.layerId)
                            }
                            LayerContentKind.OBJECT->{
                                if(content.id in sourceObjects)ownedObjects.add(content.id)
                                objectIds[content.id]?.let{assign("OBJECT",it,ref.layerId)}
                            }
                            LayerContentKind.ANNOTATION->error("Annotation membership belongs to its header")
                        }
                    }
                }
                // Hidden/history rows were also owned in the former complete-state reader.
                require(ownedInk==InkTotals.read(sql,ink.source).strokeCount.toLong()&&ownedObjects==sourceObjects){"LAYER_CONTENT_UNASSIGNED"}
            }
        }
        check(writtenInk==ink.strokeCount){"LAYER_CONTENT_UNASSIGNED"}
        val targetObjects=PageObjectRepository(db).read(ink.target).objects
        val contents=targetObjects.flatMap{o->listOf(LayerContent(LayerContentKind.OBJECT,o.id))+o.sourceStrokeIds.map{LayerContent(LayerContentKind.INK,it)}}
        val scope=AuthoringScope.page(targetOwner.notebookId,ink.target)
        repository.validate(scope,repository.read(scope,contents).state)
    }
}
