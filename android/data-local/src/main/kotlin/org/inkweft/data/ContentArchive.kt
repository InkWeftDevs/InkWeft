// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.*
import java.util.UUID

/** A prepared content copy owns its temporary file until publication or dismissal. */
class ContentFile(val title:String,val extension:String,val file:File):Closeable {
    val byteCount get()=file.length()
    fun copyTo(output:OutputStream,checkActive:()->Unit={}){file.inputStream().use{input->
        val buffer=ByteArray(64*1024)
        while(true){checkActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)}
    }}
    override fun close(){file.delete()}
}

/** Editable content transfer. Geometry and identity remapping never accumulate across a page.
 * The row codec is shared with library backup; this schema contains no history or credentials. */
class ContentArchive(private val db:NoteDatabase) {
    suspend fun export(book:String,pageId:String?=null):ContentFile {
        var returned:ContentFile?=null
        try{return withContext(Dispatchers.IO){
        val directory=File(checkNotNull(db.documentScratch),"content-export-${UUID.randomUUID()}").also{check(it.mkdirs())}
        val file=File(checkNotNull(db.documentScratch),"content-${UUID.randomUUID()}.part")
        val stage=try{Stage(directory,true)}catch(t:Throwable){directory.deleteRecursively();throw t}
        try{
            val note=checkNotNull(db.notes().note(book));require(db.workspace().get(book)?.trashedAt==null)
            val pages=if(pageId==null)db.pages().list(book)else listOf(checkNotNull(db.pages().get(pageId)))
            require(pages.size in 1..500&&pages.all{it.notebookId==book&&it.trashedAt==null})
            val world=pages.first().world;require(pages.all{it.world==world}&&(!world||pages.size==1))
            val kind=if(pageId==null&&!world)1L else 0L
            stage.insert(0,listOf(note.title,note.text,kind,if(world)1L else 0L))
            val heads=linkedMapOf<String,AuthoringSnapshot>();val counts=linkedMapOf<String,Int>()
            for((position,page) in pages.withIndex()){
                ensureActive()
                db.withTransaction{
                    val scope=AuthoringScope.page(book,page.id);val objects=PageObjectRepository(db).read(page.id).objects
                    val state=PageAuthoringRepository(db).read(scope,objects.map{LayerContent(LayerContentKind.OBJECT,it.id)})
                    heads[page.id]=state;counts[page.id]=db.ink().visibleCount(page.id)
                    val pdf=DocumentRepository(db).read(page.id)
                    stage.insert(1,listOf(page.id,position.toLong(),page.paper.toLong(),pdf?.document?.sha256,pdf?.page?.toLong()))
                    pdf?.document?.let{stage.original(it.sha256,0,it.size,it.pages)}
                    var objectPosition=0L
                    for(obj in objects){
                        require(obj.mapEmbed?.policy!=MapEmbedPolicy.LIVE){"LIVE_MAP_REQUIRES_FULL_BACKUP_OR_SNAPSHOT"}
                        val refs=obj.sourceStrokeIds.chunked(500).flatMap{batch->db.ink().visibleStrokeIds(page.id,batch)}.toSet()
                        if(obj.hidden&&refs.isEmpty())continue
                        val value=obj.copy(sourceStrokeIds=obj.sourceStrokeIds.filter{it in refs},textRuns=obj.textRuns.map{it.copy(sourceIds=it.sourceIds.filter{v->v in refs})})
                        stage.insert(3,listOf(page.id,value.id,objectPosition++,PageObjectCodec.encode(listOf(value)),state.state.layers.layer(LayerContent(LayerContentKind.OBJECT,value.id))?.id))
                        value.imageSource?.let{hash->val image=ImageSourceRepository(db).read(book,hash);stage.original(hash,1,image.size,0)}
                    }
                    stage.insert(4,listOf(page.id,PageAuthoringCodec.encodeHeader(state.state)))
                }
            }
            val reserved=object:FilterOutputStream(file.outputStream()){
                private var unchecked=Long.MAX_VALUE
                override fun write(value:Int){write(byteArrayOf(value.toByte()),0,1)}
                override fun write(bytes:ByteArray,offset:Int,length:Int){
                    if(unchecked>=1024*1024){require(directory.usableSpace>=32L*1024*1024+length){"ORIGINAL_LOW_SPACE"};unchecked=0}
                    out.write(bytes,offset,length);unchecked+=length
                }
            }
            reserved.buffered(64*1024).use{output->
                DataOutputStream(output).writeInt(MAGIC)
                val writer=LibraryArchive.Writer(output,schema,System.currentTimeMillis())
                for(table in schema.indices){
                    ensureActive()
                    val count=when(table){2->counts.values.sumOf{it.toLong()};6->stage.chunkCount();else->stage.count(table)}
                    writer.beginTable(count)
                    when(table){
                        2->for(page in pages){val head=heads.getValue(page.id)
                            InkRepository(db).visitVisible(page.id,head.inkRevision){batch->
                                val state=PageAuthoringRepository(db).read(AuthoringScope.page(book,page.id),batch.map{LayerContent(LayerContentKind.INK,it.id)})
                                check(state.revision==head.revision&&state.objectRevision==head.objectRevision&&state.inkRevision==head.inkRevision){"EXPORT_REVISION_CHANGED"}
                                batch.forEach{stroke->writer.row(listOf(page.id,stroke.id,InkStrokeCodec.encodeStored(stroke),state.state.layers.layer(LayerContent(LayerContentKind.INK,stroke.id))?.id))}
                            }
                        }
                        6->{
                            stage.visitOriginals{original->
                                val hash=original.hash
                                val source=if(original.kind==0){val row=checkNotNull(db.documents().matching(book,hash));OriginalStorage.read(db,original.size,hash,"document_chunks","documentId=?",arrayOf(row.id))}
                                    else OriginalStorage.read(db,original.size,hash,"image_chunks","notebookId=? AND digest=?",arrayOf(book,hash))
                                var position=0L;var filled=0;val buffer=ByteArray(OriginalStorage.CHUNK)
                                fun emitChunk(){if(filled>0){writer.row(listOf(hash,position++,buffer.copyOf(filled)));filled=0}}
                                source.copyTo(object:OutputStream(){
                                    override fun write(value:Int){buffer[filled++]=value.toByte();if(filled==buffer.size)emitChunk()}
                                    override fun write(bytes:ByteArray,offset:Int,length:Int){var used=0;while(used<length){val n=minOf(length-used,buffer.size-filled);bytes.copyInto(buffer,filled,offset+used,offset+used+n);used+=n;filled+=n;if(filled==buffer.size)emitChunk()}}
                                }){ensureActive()};emitChunk()
                            }
                        }
                        else->stage.visit(table){ensureActive();writer.row(it)}
                    }
                }
                for(page in pages)PageAuthoringRepository(db).checkExportHeads(page.id,heads.getValue(page.id))
                val current=if(pageId==null)db.pages().list(book)else listOf(checkNotNull(db.pages().get(pageId)))
                check(current.map{listOf(it.id,it.position,it.world,it.paper,it.trashedAt)}==pages.map{listOf(it.id,it.position,it.world,it.paper,it.trashedAt)}&&db.notes().note(book)?.revision==note.revision&&db.workspace().get(book)?.trashedAt==null){"EXPORT_REVISION_CHANGED"}
                writer.finish()
            }
            ensureActive();ContentFile(note.title,if(kind==1L)"iwbook"else"iwpage",file).also{returned=it}
        }catch(t:Throwable){file.delete();throw t}
        finally{stage.close();directory.deleteRecursively()}
        }}catch(t:Throwable){returned?.close();throw t}
    }

    internal suspend fun import(command:ImportNotebook,prepared:ContentTransfer.Prepared,beforeReceipt:()->Unit):Note {
        val content=prepared.content as ContentTransfer.Content.Streamed
        val stage=Stage(content.directory,false);val active=currentCoroutineContext()
        try{
            // Stage opens read-only; the private file is rechecked before any author write.
            val proof=stage.proof(){active.ensureActive()};require(proof.sha256==prepared.sha256){"IMPORT_CONTENT_CHANGED"}
            validate(stage){active.ensureActive()}
            val meta=stage.manifest();val pages=stage.pages()
            val repository=LibraryContentRepository(db)
            val result=db.withTransaction{
                repository.lookup(command.commandId,"IMPORT",command.digest())?.let{return@withTransaction it}
                require(db.notes().note(command.destinationId)==null){"DESTINATION_EXISTS"}
                val target=repository.create(command.destinationId,meta.title,meta.text,meta.world,pages.first().paper)
                fun newId(page:String,id:String)=UUID.nameUUIDFromBytes("InkWeft.Content/1:${target.id}:$page:$id".toByteArray()).toString()
                for((index,page) in pages.withIndex()){
                    currentCoroutineContext().ensureActive()
                    val destination=if(index==0)target.id else newId(page.id,page.id)
                    if(index>0)db.pages().insert(NotebookPageRow(destination,target.id,index,meta.world,page.paper))
                    page.sourceHash?.let{hash->val original=stage.original(hash);val pdf=PdfDocumentSource.fromStream(original.content(stage.directory),original.pages)
                        DocumentRepository(db).attach(destination,PdfPageSource(pdf,checkNotNull(page.sourcePage)))}
                    db.ink().insertPage(InkPageRow(destination,0));var revision=0L
                    stage.sql.rawQuery("SELECT id,payload,layer FROM content_ink WHERE page=? ORDER BY sequence",arrayOf(page.id)).use{cursor->
                        while(cursor.moveToNext()){
                            currentCoroutineContext().ensureActive()
                            val old=InkStrokeCodec.decode(cursor.getBlob(1));val id=newId(page.id,old.id)
                            val stroke=InkStroke(id,old.pen,old.color,old.width,old.tool,old.samples,old.world,
                                old.cuts.map{InkCut(newId(page.id,it.id),it.radius,it.points,it.shape)},old.appearance)
                            db.ink().insertStroke(InkStrokeRow(id,destination,InkStrokeCodec.encodeStored(stroke),stroke.samples.size,true,++revision))
                        }
                    }
                    if(revision>0)check(db.ink().compareAndSet(destination,0,revision)==1)
                    val objects=stage.objects(page.id)
                    val refs=objects.flatMap{it.sourceStrokeIds}.distinct().associateWith{newId(page.id,it)}
                    val images=objects.mapNotNull{it.imageSource}.distinct().map{ImageSource.fromStream(stage.original(it).content(stage.directory))}
                    val objectIds=PageObjectRepository(db).import(destination,objects,refs,images)
                    val header=stage.header(page.id);val copied=header.copied(destination,emptyMap(),objectIds)
                    val layerIds=header.layers.layers.zip(copied.layers.layers).associate{it.first.id to it.second.id}
                    PageAuthoringRepository(db).storePage(destination,target.id,0,copied)
                    for((table,kind) in listOf("content_ink" to LayerContentKind.INK,"content_objects" to LayerContentKind.OBJECT)){
                        stage.sql.rawQuery("SELECT id,layer FROM $table WHERE page=? ORDER BY sequence",arrayOf(page.id)).use{cursor->
                            while(cursor.moveToNext()){
                                val old=cursor.getString(0);val id=if(kind==LayerContentKind.INK)newId(page.id,old)else objectIds.getValue(old)
                                val layer=if(cursor.isNull(1))null else layerIds.getValue(cursor.getString(1))
                                db.authoring().assign(PageLayerMembershipRow(destination,kind.name,id,layer))
                            }
                        }
                    }
                    val scope=AuthoringScope.page(target.id,destination)
                    val ownership=PageAuthoringRepository(db).read(scope,objectIds.values.map{LayerContent(LayerContentKind.OBJECT,it)}+refs.values.map{LayerContent(LayerContentKind.INK,it)})
                    PageAuthoringRepository(db).validate(scope,ownership.state)
                }
                beforeReceipt();db.libraryContent().insert(LibraryContentReceipt(command.commandId,"IMPORT",command.digest(),target.id));target
            }
            return result
        }finally{stage.close()}
    }

    companion object {
        private const val MAGIC=0x49575831 // IWX1, streaming content copy
        fun isArchive(header:ByteArray)=header.size>=4&&java.nio.ByteBuffer.wrap(header).int==MAGIC
        private fun table(name:String,vararg columns:Pair<String,Char>,nullable:Set<String> = emptySet())=LibraryArchive.Table(name,columns.map{LibraryArchive.Column(it.first,it.second,it.first in nullable)},emptyList())
        private val schema=listOf(
            table("content_manifest","title" to 'S',"text" to 'S',"kind" to 'I',"world" to 'I'),
            table("content_pages","id" to 'S',"position" to 'I',"paper" to 'I',"sourceHash" to 'S',"sourcePage" to 'I',nullable=setOf("sourceHash","sourcePage")),
            table("content_ink","page" to 'S',"id" to 'S',"payload" to 'B',"layer" to 'S',nullable=setOf("layer")),
            table("content_objects","page" to 'S',"id" to 'S',"position" to 'I',"payload" to 'B',"layer" to 'S',nullable=setOf("layer")),
            table("content_headers","page" to 'S',"payload" to 'B'),
            table("content_originals","hash" to 'S',"kind" to 'I',"size" to 'I',"pages" to 'I'),
            table("content_chunks","hash" to 'S',"position" to 'I',"payload" to 'B'))

        suspend fun prepare(input:InputStream,directory:File):ContentTransfer.Prepared=withContext(Dispatchers.IO){
            check(directory.isDirectory);val active=currentCoroutineContext();val stage=Stage(directory,true)
            try{
                require(DataInputStream(input).readInt()==MAGIC){"CONTENT_FORMAT_UNSUPPORTED"}
                stage.sql.beginTransaction()
                val proof=LibraryArchive.read(input,schema,{table,row->stage.insert(table,row)},checkActive={active.ensureActive()})
                stage.sql.execSQL("INSERT INTO transfer_proof VALUES(?,?)",arrayOf<Any>(proof.createdAt,proof.sha256))
                stage.sql.setTransactionSuccessful();stage.sql.endTransaction()
                validate(stage){active.ensureActive()}
                val meta=stage.manifest();val count=stage.count(1).toInt()
                ContentTransfer.staged(if(meta.kind==1)ContentTransfer.Kind.BOOK else ContentTransfer.Kind.PAGE,meta.title,count,directory,proof.sha256,proof.bytes+4){directory.deleteRecursively()}
            }catch(t:Throwable){stage.close();directory.deleteRecursively();throw t}finally{stage.close()}
        }

        private data class Manifest(val title:String,val text:String,val kind:Int,val world:Boolean)
        private data class Page(val id:String,val position:Int,val paper:Int,val sourceHash:String?,val sourcePage:Int?)
        private data class Original(val hash:String,val kind:Int,val size:Int,val pages:Int){
            fun content(directory:File)=OriginalBytes.stream(size,hash){val file=File(directory,"$hash.original");require(file.length()==size.toLong());file.inputStream()}
        }
        private class Stage(val directory:File,create:Boolean):Closeable {
            val sql=SQLiteDatabase.openDatabase(File(directory,"content.db").absolutePath,null,if(create)SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READONLY)
            init{if(create){
                schema.forEach{table->sql.execSQL("CREATE TABLE ${table.name} (sequence INTEGER PRIMARY KEY AUTOINCREMENT,"+table.columns.joinToString(","){column->"${column.name} ${when(column.kind){'I'->"INTEGER";'B'->"BLOB";else->"TEXT"}}${if(column.nullable)""else" NOT NULL"}"}+if(table.name=="content_chunks")",byteCount INTEGER NOT NULL)"else")")}
                for((table,columns)in listOf("content_pages" to "id","content_pages" to "position","content_ink" to "id","content_objects" to "page,id","content_objects" to "page,position","content_headers" to "page","content_originals" to "hash","content_chunks" to "hash,position"))sql.execSQL("CREATE UNIQUE INDEX ${table}_${columns.replace(',','_')} ON $table($columns)")
                sql.execSQL("CREATE INDEX content_ink_page ON content_ink(page)")
                sql.execSQL("CREATE TABLE transfer_proof (createdAt INTEGER NOT NULL,sha256 TEXT NOT NULL)")
            }}
            override fun close(){if(sql.isOpen)sql.close()}
            fun count(table:Int)=sql.rawQuery("SELECT COUNT(*) FROM ${schema[table].name}",null).use{check(it.moveToFirst());it.getLong(0)}
            fun insert(table:Int,row:List<Any?>){
                val definition=schema[table]
                require(directory.usableSpace>=32L*1024*1024+row.sumOf{if(it is ByteArray)it.size.toLong()else 0L}){"ORIGINAL_LOW_SPACE"}
                var values=row
                if(table==6){
                    val hash=row[0] as String;require(ImageSource.validHash(hash));val position=row[1] as Long;val bytes=row[2] as ByteArray
                    val original=original(hash);require(position>=0&&position<((original.size.toLong()+OriginalStorage.CHUNK-1)/OriginalStorage.CHUNK))
                    require(bytes.size==minOf(OriginalStorage.CHUNK.toLong(),original.size.toLong()-position*OriginalStorage.CHUNK).toInt())
                    val file=File(directory,"$hash.original");require(file.length()==position*OriginalStorage.CHUNK)
                    FileOutputStream(file,true).use{it.write(bytes)}
                    values=listOf(hash,position,byteArrayOf(),bytes.size.toLong())
                }
                val columns=definition.columns.map{it.name}+if(table==6)listOf("byteCount")else emptyList()
                sql.execSQL("INSERT INTO ${definition.name} (${columns.joinToString(",")}) VALUES (${columns.joinToString(","){"?"}})",values.toTypedArray())
            }
            fun visit(table:Int,consumer:(List<Any?>)->Unit){
                val definition=schema[table]
                sql.rawQuery("SELECT ${definition.columns.joinToString(","){it.name}}${if(table==6)",byteCount"else""} FROM ${definition.name} ORDER BY sequence",null).use{cursor->
                    while(cursor.moveToNext()){
                        val row=definition.columns.mapIndexed{index,column->if(cursor.isNull(index))null else when(column.kind){'I'->cursor.getLong(index);'B'->cursor.getBlob(index);else->cursor.getString(index)}}.toMutableList()
                        if(table==6){val bytes=ByteArray(cursor.getInt(3));RandomAccessFile(File(directory,"${row[0]}.original"),"r").use{file->file.seek((row[1] as Long)*OriginalStorage.CHUNK);file.readFully(bytes)};row[2]=bytes}
                        consumer(row)
                    }
                }
            }
            fun original(hash:String)=sql.rawQuery("SELECT hash,kind,size,pages FROM content_originals WHERE hash=?",arrayOf(hash)).use{c->check(c.moveToFirst());Original(c.getString(0),integer(c,1),integer(c,2),integer(c,3))}
            fun original(hash:String,kind:Int,size:Int,pages:Int){
                require(ImageSource.validHash(hash))
                val exists=sql.rawQuery("SELECT 1 FROM content_originals WHERE hash=?",arrayOf(hash)).use{it.moveToFirst()}
                if(exists)check(original(hash)==Original(hash,kind,size,pages))else insert(5,listOf(hash,kind.toLong(),size.toLong(),pages.toLong()))
            }
            suspend fun visitOriginals(consume:suspend(Original)->Unit){sql.rawQuery("SELECT hash,kind,size,pages FROM content_originals ORDER BY sequence",null).use{c->while(c.moveToNext())consume(Original(c.getString(0),integer(c,1),integer(c,2),integer(c,3)))}}
            private fun integer(cursor:android.database.Cursor,index:Int):Int {val value=cursor.getLong(index);require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong());return value.toInt()}
            fun chunkCount()=sql.rawQuery("SELECT COALESCE(SUM((size+${OriginalStorage.CHUNK-1})/${OriginalStorage.CHUNK}),0) FROM content_originals",null).use{check(it.moveToFirst());it.getLong(0)}
            fun manifest():Manifest {require(count(0)==1L);return sql.rawQuery("SELECT title,text,kind,world FROM content_manifest",null).use{check(it.moveToFirst());require(it.getLong(2) in 0L..1L&&it.getLong(3) in 0L..1L);Manifest(it.getString(0),it.getString(1),it.getInt(2),it.getInt(3)==1)}}
            fun pages()=sql.rawQuery("SELECT id,position,paper,sourceHash,sourcePage FROM content_pages ORDER BY position",null).use{c->buildList{while(c.moveToNext())add(Page(c.getString(0),integer(c,1),integer(c,2),if(c.isNull(3))null else c.getString(3),if(c.isNull(4))null else integer(c,4)))}}
            fun objects(page:String):List<PageObject>{
                sql.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(payload)),0) FROM content_objects WHERE page=?",arrayOf(page)).use{check(it.moveToFirst());require(it.getLong(0)<=PageObjectCodec.MAX_OBJECTS&&it.getLong(1)<=PageObjectCodec.MAX_BYTES+8L*PageObjectCodec.MAX_OBJECTS)}
                return sql.rawQuery("SELECT payload FROM content_objects WHERE page=? ORDER BY position",arrayOf(page)).use{c->buildList{while(c.moveToNext())add(PageObjectCodec.decode(c.getBlob(0)).single())}}
            }
            fun header(page:String)=sql.rawQuery("SELECT payload FROM content_headers WHERE page=?",arrayOf(page)).use{check(it.moveToFirst());PageAuthoringCodec.decodeHeader(it.getBlob(0))}
            fun proof(active:()->Unit):LibraryArchive.Summary {
                val at=sql.rawQuery("SELECT createdAt FROM transfer_proof",null).use{require(it.moveToFirst());it.getLong(0)}
                return LibraryArchive.write(OriginalStorage.discard,schema,object:LibraryArchive.Rows{override fun count(table:Int)=this@Stage.count(table);override fun visit(table:Int,consume:(List<Any?>)->Unit)=this@Stage.visit(table,consume)},at,checkActive=active)
            }
        }

        private suspend fun validate(stage:Stage,active:()->Unit){
            val meta=stage.manifest();require(RenameNote.validTitle(meta.title)&&meta.text.length<=100_000)
            require(stage.count(1) in 1..500);val pages=stage.pages();require((meta.kind==1&&!meta.world)||(meta.kind==0&&pages.size==1))
            require(pages.withIndex().all{it.value.position==it.index&&it.value.paper in PaperStyle.entries.indices})
            val ids=pages.map{it.id}.toSet();ids.forEach{UUID.fromString(it)}
            require(stage.count(4)==pages.size.toLong())
            stage.visitOriginals{original->
                active();require(ImageSource.validHash(original.hash)&&original.kind in 0..1)
                require(original.size in 1..if(original.kind==0)PdfDocumentSource.MAX_BYTES else ImageSource.MAX_BYTES)
                require(if(original.kind==0)original.pages in 1..500 else original.pages==0)
                original.content(stage.directory).copyTo(OriginalStorage.discard,active)
                val file=File(stage.directory,"${original.hash}.original")
                if(original.kind==0)DocumentRepository.validatePdfFile(file,original.pages,active)else ImageSourceRepository.validateFile(file,active)
            }
            for(page in pages){
                active();val header=stage.header(page.id);val layers=header.layers.layers.map{it.id}.toSet()
                if(page.sourceHash==null)require(page.sourcePage==null)else{val original=stage.original(page.sourceHash);require(!meta.world&&original.kind==0&&page.sourcePage!=null&&page.sourcePage in 0 until original.pages)}
                stage.sql.rawQuery("SELECT id,payload,layer FROM content_ink WHERE page=? ORDER BY sequence",arrayOf(page.id)).use{c->while(c.moveToNext()){
                    active();val stroke=InkStrokeCodec.decode(c.getBlob(1));require(stroke.id==c.getString(0)&&stroke.world==meta.world);require(c.isNull(2)||c.getString(2) in layers)
                }}
                val objects=stage.objects(page.id);PageObjectCodec.encode(objects);PageObjectRepository.validateBounds(objects,meta.world);PageObjectRepository.validateImages(objects)
                val objectIds=objects.map{it.id}.toSet();val refs=objects.flatMap{it.sourceStrokeIds};require(refs.distinct().size==refs.size)
                for(batch in refs.chunked(500)){
                    val count=stage.sql.rawQuery("SELECT COUNT(*) FROM content_ink WHERE page=? AND id IN (${batch.joinToString(","){"?"}})",(listOf(page.id)+batch).toTypedArray()).use{check(it.moveToFirst());it.getInt(0)};require(count==batch.size)
                }
                objects.forEach{require(it.mapEmbed?.policy!=MapEmbedPolicy.LIVE){"LIVE_MAP_REQUIRES_FULL_BACKUP_OR_SNAPSHOT"};it.imageSource?.let{hash->require(stage.original(hash).kind==1)}}
                stage.sql.rawQuery("SELECT id,position,layer FROM content_objects WHERE page=? ORDER BY position",arrayOf(page.id)).use{c->var position=0;while(c.moveToNext()){require(c.getString(0)==objects[position].id&&c.getLong(1)==position.toLong()&&(c.isNull(2)||c.getString(2) in layers));position++}}
                (header.annotations.map{it.target}+header.regions.map{it.target}).forEach{target->when(target.kind){AnnotationTargetKind.PAGE->require(target.id==page.id);AnnotationTargetKind.PAGE_OBJECT->require(target.id in objectIds);AnnotationTargetKind.WHITESPACE->require(header.blanks.any{it.id==target.id});else->error("MAP_ANNOTATION_REQUIRES_FULL_BACKUP")}}
            }
            for(table in listOf("content_ink","content_objects","content_headers"))require(!stage.sql.rawQuery("SELECT 1 FROM $table c WHERE NOT EXISTS(SELECT 1 FROM content_pages p WHERE p.id=c.page) LIMIT 1",null).use{it.moveToFirst()})
        }
    }
}
