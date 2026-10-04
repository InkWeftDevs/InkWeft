// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.math.sin

/** Fixed full-sized synthetic input. All seeding uses production repositories, never DAO inserts. */
internal class SixBatchFixture(val app:InkWeftApplication,val manifest:JSONObject){
    val root=File(app.filesDir,"six-batch-fixture-v1").apply{mkdirs()}
    val runId:String get()=manifest.getString("runId")
    val books:List<String> get()=manifest.getJSONArray("books").strings()
    val documentPages:List<String> get()=manifest.getJSONArray("documentPages").strings()
    val stressPage:String get()=documentPages.last()
    fun id(name:String)=UUID.nameUUIDFromBytes("$runId:$name".toByteArray()).toString()
    fun save(){val file=File(root,"manifest.json");val tmp=File(root,"manifest.pending");tmp.writeText(manifest.toString(2));check(tmp.renameTo(file))}
    fun memory()=JSONObject().put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
        .put("javaMaxBytes",Runtime.getRuntime().maxMemory()).put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize())
        .put("totalPssKiB",Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
    suspend fun step(name:String,work:suspend ()->Unit){
        val start=SystemClock.elapsedRealtimeNanos();val before=memory()
        try{work();manifest.getJSONArray("steps").put(JSONObject().put("name",name).put("status","PASS")
            .put("executedSourceCommit",BuildConfig.SOURCE_COMMIT).put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000).put("memoryBefore",before).put("memoryAfter",memory()));save()}
        catch(t:Throwable){manifest.put("status","FAILED").put("failureStage",name).put("failureClass",t.javaClass.simpleName);save();throw t}
    }

    suspend fun seed()=withContext(Dispatchers.IO){
        require(app.repository.observeNotes().first().isEmpty()){ "Fixture requires an empty disposable app; nothing was cleared" }
        require(!File(root,"manifest.json").exists()){ "Resume the same fixture instead of generating another" }
        save()
        val pdfBytes=pdf();val pdf=PdfDocumentSource(pdfBytes,12)
        val primary=id("book-a");val secondary=id("book-b")
        step("production-import-12-page-document"){
            val file=File(root,"synthetic-document.pdf").apply{writeBytes(pdfBytes)}
            val prepared=ContentTransfer.document("合成学习资料 · 条件概率十二讲",pdf,ContentTransfer.hash(pdfBytes))
            app.libraryContent.import(ImportNotebook(id("import-pdf"),primary,prepared.sha256),prepared)
            app.workspaceRepository.create("合成知识本 · 复用与回忆",false,PaperStyle.RULED,operationId=secondary)
            manifest.put("books",JSONArray(listOf(primary,secondary)))
            manifest.put("documentPages",JSONArray(app.pages.activePages(primary).map{it.id}))
            manifest.put("documentSha256",sha(file)).put("documentBytes",file.length())
        }
        val previews=mutableListOf<ByteArray>()
        step("two-owned-4096-by-3072-originals"){
            repeat(2){index->
                val (original,preview)=image(index);previews+=preview
                val asset=File(root,"synthetic-original-${index+1}.png").apply{writeBytes(original.bytes())}
                val page=documentPages[index]
                val value=PageObject(id("image-$index"),PageObjectKind.IMAGE,80f,320f,840f,630f,
                    image=Base64.getEncoder().encodeToString(preview),imageSource=original.sha256)
                app.pageObjects.save(page,0,id("save-image-$index"),listOf(value),originals=listOf(original))
                manifest.getJSONArray("originals").put(JSONObject().put("pageId",page).put("objectId",value.id)
                    .put("sha256",sha(asset)).put("bytes",asset.length()).put("width",4096).put("height",3072))
            }
        }
        val formula=formula()
        step("formula-original-strokes"){
            val result=app.inkRepository.save(CommitInk(id("formula-ink"),documentPages[2],0,InkMutation.Replace(emptyList(),formula)))
            check(result is InkCommitResult.Committed)
            manifest.put("formulaPage",documentPages[2]).put("formulaEquation","E = x + y").put("formulaStrokeIds",JSONArray(formula.map{it.id}))
        }
        step("full-1000-stroke-100000-point-stress-page"){
            repeat(4){batch->
                val strokes=List(250){within->stressStroke(batch*250+within)}
                check(app.inkRepository.save(CommitInk(id("stress-$batch"),stressPage,batch.toLong(),InkMutation.Replace(emptyList(),strokes))) is InkCommitResult.Committed)
            }
        }
        val cardsA=(0 until 44).map{id("card-a-$it")}.toMutableList()
        val cardsB=(0 until 48).map{id("card-b-$it")}
        step("92-original-cards-through-study-commands"){
            for((bookIndex,cardIds) in listOf(cardsA,cardsB).withIndex())for((index,card) in cardIds.withIndex()){
                val book=books[bookIndex];val node=id("base-node-$bookIndex-$index")
                val source=if(bookIndex!=0)null else when(index){
                    0->StudySourceDraft(documentPages[2],1,formula.map{it.bounds()}.reduce{a,b->a.union(b)},formula.map{it.id})
                    1,2->StudySourceDraft(documentPages[index-1],0,CanvasBounds(80.0,320.0,920.0,950.0),emptyList(),previews[index-1],1)
                    else->null
                }
                app.study.submit(StudyCommand(id("create-$bookIndex-$index"),book,StudyAction.CREATE,cardId=card,nodeId=node,
                    parentId=if(index%6==0)null else id("base-node-$bookIndex-${index-1}"),
                    title=if(index==0&&bookIndex==0)"条件概率的完整推导与边界"else"${if(bookIndex==0)"原文"else"复用"}主题 ${index+1}",
                    body=chinese(if(index==0&&bookIndex==0)12000 else 1024,"正文末尾定位标记"),
                    x=40.0+(index%6)*320.0,y=80.0+(index/6)*340.0,source=source))
                app.knowledge.submit(KnowledgeCommand(id("presentation-op-$bookIndex-$index"),book,id("presentation-$bookIndex-$index"),0,
                    KnowledgeData.CardPresentation(card,chinese(if(index==0&&bookIndex==0)4000 else 128,"注释末尾定位标记"),
                        CardTint.entries[(index% (CardTint.entries.size-1))+1],CardTint.entries[((index+2)%(CardTint.entries.size-1))+1])))
            }
        }
        step("merge-split-summary-retain-original-identities-to-96-cards"){
            val merge=app.study.transforms().preview(primary,listOf(cardsA[0],cardsA[1]))
            val merged=CardTransforms.mergeTarget(merge.cards,"多来源合并 · 公式与原图").copy(id=id("merged-card"))
            app.study.transforms().submit(merge.plan(CardTransformKind.MERGE,listOf(merged),id("merge-operation")))
            val split=app.study.transforms().preview(primary,listOf(cardsA[2]))
            val parts=CardTransforms.splitTargets(split.cards.single(),512).mapIndexed{i,target->target.copy(id=id("split-card-$i"))}
            app.study.transforms().submit(split.plan(CardTransformKind.SPLIT,parts,id("split-operation")))
            val summary=app.study.transforms().preview(primary,listOf(cardsA[0],cardsA[1]))
            val summarized=CardTransforms.mergeTarget(summary.cards,"归纳总结 · 条件与证据").copy(id=id("summary-card"),
                body="条件概率先限定样本空间，再核对交集与分母。独立性是额外约束，公式和原图是同一推导的两份证据。")
            app.study.transforms().submit(summary.plan(CardTransformKind.SUMMARY,listOf(summarized),id("summary-operation")))
            cardsA+=listOf(merged.id)+parts.map{it.id}+summarized.id
            manifest.put("cardsA",JSONArray(cardsA)).put("cardsB",JSONArray(cardsB))
                .put("mergedCard",merged.id).put("splitCards",JSONArray(parts.map{it.id})).put("summaryCard",summarized.id)
        }
        step("three-actual-120-node-six-level-maps"){
            val extra=id("map-a-extra")
            app.knowledge.submit(KnowledgeCommand(id("extra-map-op"),primary,extra,0,KnowledgeData.MapDefinition("同一内容的第二种组织")))
            for((ordinal,ref) in listOf(MapRef(primary),MapRef(primary,extra),MapRef(secondary)).withIndex()){
                val cards=if(ref.notebookId==primary)cardsA else cardsB
                var graph=app.study.readGraph(ref.notebookId,ref.mapId)
                // A SUMMARY may already have inserted its stable occurrence. Count it, never add 120 blindly.
                val nodeIds=graph.orderedNodeIds.toMutableList()
                for(index in nodeIds.indices){
                    val node=graph.nodes.first{it.id==nodeIds[index]}
                    val parent=if(index%6==0)null else nodeIds[index-1]
                    if(node.parentId!=parent){val plan=StudyOrganization.reparent(graph.state,node.id,parent);app.study.submit(plan.command(id("initial-level-$ordinal-$index")));graph=app.study.readGraph(ref.notebookId,ref.mapId)}
                }
                require(nodeIds.size<=120)
                while(nodeIds.size<120){
                    val index=nodeIds.size;val node=id("fill-node-$ordinal-$index")
                    app.study.submit(StudyCommand(id("fill-op-$ordinal-$index"),ref.notebookId,StudyAction.REUSE,mapId=ref.mapId,
                        cardId=cards[index%cards.size],nodeId=node,parentId=if(index%6==0)null else nodeIds[index-1],
                        x=40.0+(index%6)*320.0,y=80.0+(index/6)*340.0))
                    nodeIds+=node
                }
                graph=app.study.readGraph(ref.notebookId,ref.mapId)
                check(graph.nodes.count{!it.removed}==120)
                manifest.getJSONArray("maps").put(JSONObject().put("book",ref.notebookId).put("mapId",ref.mapId.orEmpty())
                    .put("nodes",JSONArray(graph.orderedNodeIds)).put("graphFingerprint",graph.graphFingerprint))
            }
        }
        step("twelve-real-cross-notebook-references"){
            repeat(6){i->
                val card=app.study.cards(secondary).first().first{it.id==cardsB[i]}
                val request=app.study.reuse.prepare(card.id,card.revision,primary,CardReuseKind.REFERENCE).copy(operationId=id("reuse-reference-$i"))
                app.study.reuse.submit(request)
            }
            repeat(6){i->
                val link=id("cross-link-$i")
                app.knowledge.submit(KnowledgeCommand(id("cross-link-op-$i"),primary,link,0,
                    KnowledgeData.Link(TargetRef(TargetKind.CARD,cardsA[i]),TargetRef(TargetKind.CARD,cardsB[i+6]),RelationKind.REFERENCE,
                        lineStyle=if(i%2==0)RelationLineStyle.DASHED else RelationLineStyle.SOLID,
                        direction=if(i%2==0)RelationDirection.FORWARD else RelationDirection.BOTH,annotation="合成跨本引用 ${i+1}：核对另一份证据")))
            }
            manifest.put("navigationSourceCard",cardsA[0]).put("navigationTargetCard",cardsB[6]).put("navigationLink",id("cross-link-0"))
        }
        step("fixed-questions-without-fabricated-review-history"){
            for((book,cardIds) in listOf(primary to cardsA,secondary to cardsB))cardIds.take(12).forEachIndexed{i,card->
                app.knowledge.submit(KnowledgeCommand(id("question-op-$book-$i"),book,id("question-$book-$i"),0,
                    KnowledgeData.Question(card,"请在不看材料时说明第${i+1}个概念的条件与反例")))
            }
        }
        seedAuthoring()
        seedRecall()
        verify()
        manifest.put("status","FULL_SYNTHETIC_DATA_READY_NATIVE_B4_B5_REQUIRED").put("authoringStatus","SEEDED_PENDING_NATIVE_UI")
            .put("recallHistoryStatus","SEEDED_PENDING_NATIVE_UI");save()
    }

    private suspend fun seedAuthoring(){
        step("three-real-layers-two-blanks-and-bound-map-ink"){
            val page=documentPages[3]
            val strokes=List(12){index->InkStroke(id("layer-ink-$index"),InkPen.PEN,0xff34496e.toInt(),3f,InkTool.STYLUS,
                List(30){j->InkSample(100f+j*7f,140f+index*70f+sin(j/4.0).toFloat()*8f,j*8L,.6f,.2f,.3f)})}
            check(app.inkRepository.save(CommitInk(id("seed-layer-ink"),page,0,InkMutation.Replace(emptyList(),strokes))) is InkCommitResult.Committed)
            val db=NoteDatabase.open(app)
            try{
                val repo=PageAuthoringRepository(db);val scope=AuthoringScope.page(books[0],page);val before=repo.read(scope)
                val hidden=UserLayer(id("hidden-layer"),"隐藏推导层",visible=false)
                val locked=UserLayer(id("locked-layer"),"锁定核对层",locked=true)
                val layers=before.state.layers.add(hidden.copy(visible=true)).add(locked.copy(locked=false))
                    .update(hidden).update(locked).select(UserLayers.DEFAULT_ID)
                val membership=strokes.mapIndexed{i,stroke->LayerMembership(LayerContent(LayerContentKind.INK,stroke.id),
                    when(i/4){0->UserLayers.DEFAULT_ID;1->hidden.id;else->locked.id})}
                val blanks=listOf(DocumentWhitespace(id("blank-expanded"),300.0,300.0),DocumentWhitespace(id("blank-collapsed"),800.0,450.0,true))
                val state=PageAuthoring(UserLayers(layers.layers,UserLayers.DEFAULT_ID,membership),blanks)
                repo.save(scope,before,id("seed-authoring"),state)
                val stressScope=AuthoringScope.page(books[0],stressPage);val stressBefore=repo.read(stressScope)
                val stressHidden=UserLayer(id("stress-hidden-layer"),"隐藏空层",visible=false)
                val stressLocked=UserLayer(id("stress-locked-layer"),"锁定压力层",locked=true)
                val stressLayers=stressBefore.state.layers.add(stressHidden.copy(visible=true)).add(stressLocked.copy(locked=false))
                    .update(stressHidden).update(stressLocked).select(UserLayers.DEFAULT_ID)
                val stressMembers=List(1000){i->LayerMembership(LayerContent(LayerContentKind.INK,id("pressure-stroke-$i")),
                    if(i<900)UserLayers.DEFAULT_ID else stressLocked.id)}
                val stressState=PageAuthoring(UserLayers(stressLayers.layers,UserLayers.DEFAULT_ID,stressMembers))
                repo.save(stressScope,stressBefore,id("seed-pressure-authoring"),stressState)
                manifest.put("stressAuthoringFingerprint",PageAuthoringCodec.fingerprint(stressState))
                val mapScope=AuthoringScope.map(MapRef(books[0]));val mapBefore=repo.read(mapScope)
                val target=app.study.readGraph(books[0]).orderedNodeIds.first()
                val annotation=BoundAnnotation(InkStroke(id("bound-map-ink"),InkPen.PEN,0xffab4c24.toInt(),3f,InkTool.STYLUS,
                    List(20){j->InkSample(j*8f,sin(j/3.0).toFloat()*12f,j*12L,.6f,.2f,.3f,world=true)},world=true),
                    AnnotationTarget(AnnotationTargetKind.MAP_OCCURRENCE,target),240.0)
                val mapState=mapBefore.state.add(annotation).withRegion(AnnotationRegion(annotation.target,300.0,240.0,referenceWidth=240.0))
                repo.save(mapScope,mapBefore,id("seed-bound-annotation"),mapState)
                manifest.put("authoring",JSONObject().put("pageId",page).put("layers",3).put("hiddenLayers",1).put("lockedLayers",1)
                    .put("inkIds",JSONArray(strokes.map{it.id})).put("blanks",2).put("collapsedBlanks",1)
                    .put("pageFingerprint",PageAuthoringCodec.fingerprint(state)).put("mapFingerprint",PageAuthoringCodec.fingerprint(mapState))
                    .put("boundNodeId",target).put("boundAnnotationId",annotation.stroke.id))
            }finally{db.close()}
        }
    }

    private suspend fun verifyAuthoring():JSONObject{
        val expected=manifest.getJSONObject("authoring");val db=NoteDatabase.open(app)
        return try{
            val repo=PageAuthoringRepository(db);val page=expected.getString("pageId")
            val state=repo.readPage(page).state;val ink=InkSession(app.inkRepository.read(page)).visibleDraft()
            check(ink.map{it.id}.toSet()==expected.getJSONArray("inkIds").strings().toSet())
            check(state.layers.layers.size==3&&state.layers.layers.count{!it.visible}==1&&state.layers.layers.count{it.locked}==1)
            check(state.blanks.size==2&&state.blanks.count{it.collapsed}==1)
            check(PageAuthoringCodec.fingerprint(state)==expected.getString("pageFingerprint"))
            check(ink.count{state.layers.visible(LayerContent(LayerContentKind.INK,it.id))}==8)
            check(ink.count{state.layers.editable(LayerContent(LayerContentKind.INK,it.id))}==4)
            val stress=repo.readPage(stressPage).state
            check(PageAuthoringCodec.fingerprint(stress)==manifest.getString("stressAuthoringFingerprint"))
            check(stress.layers.layers.size==3&&stress.layers.memberships.size==1000)
            check(stress.layers.memberships.all{stress.layers.visible(it.content)})
            check(stress.layers.memberships.count{stress.layers.editable(it.content)}==900)
            val map=repo.read(AuthoringScope.map(MapRef(books[0]))).state
            check(PageAuthoringCodec.fingerprint(map)==expected.getString("mapFingerprint"))
            check(map.annotations.single().target.id==expected.getString("boundNodeId"))
            check(map.regions.single().target==map.annotations.single().target)
            JSONObject().put("layers",3).put("hiddenLayers",1).put("lockedLayers",1).put("blanks",2).put("collapsedBlanks",1)
                .put("storedPageStrokes",12).put("visiblePageStrokes",8).put("editablePageStrokes",4).put("boundMapAnnotations",1)
                .put("pressureLayers",3).put("pressureVisibleStrokes",1000).put("pressureLockedStrokes",100).put("pressureHiddenStrokes",0)
        }finally{db.close()}
    }

    suspend fun verify():JSONObject=withContext(Dispatchers.IO){
        check(books.size==2&&documentPages.size==12)
        val cards=books.map{app.study.cards(it).first()};check(cards.map{it.size}==listOf(48,48))
        val longBodyChars=cards[0].first{it.id==manifest.getJSONArray("cardsA").getString(0)}.body.length
        check(longBodyChars==12000)
        val longPresentation=app.knowledge.observeBook(books[0]).first().mapNotNull{it.data() as? KnowledgeData.CardPresentation}
            .first{it.cardId==manifest.getJSONArray("cardsA").getString(0)};check(longPresentation.annotation.length==4000)
        val maps=manifest.getJSONArray("maps");check(maps.length()==3)
        val counts=JSONArray();val depths=JSONArray()
        repeat(maps.length()){i->val record=maps.getJSONObject(i);val graph=app.study.readGraph(record.getString("book"),record.getString("mapId").ifEmpty{null})
            val active=graph.nodes.filterNot{it.removed};check(active.size==120)
            val depth=StudyOutline.project(active.map{it.model()}).rows.maxOf{it.depth}+1;check(depth==6)
            counts.put(active.size);depths.put(depth)
        }
        val ink=InkSession(app.inkRepository.read(stressPage)).visibleDraft()
        check(ink.size==1000&&ink.sumOf{it.samples.size}==100000)
        val cross=books.flatMap{app.knowledge.observeBook(it).first()}.filter{!it.removed}.mapNotNull{it.data() as? KnowledgeData.Link}.count{link->
            app.knowledge.resolve(link.source).first.id!=app.knowledge.resolve(link.target).first.id
        };check(cross==12)
        for(i in 0 until 12){val source=checkNotNull(app.documents.read(documentPages[i]));check(source.page==i&&source.document.sha256==manifest.getString("documentSha256"))}
        repeat(2){index->val record=manifest.getJSONArray("originals").getJSONObject(index);val page=record.getString("pageId")
            val objects=app.pageObjects.read(page).objects;val original=app.pageObjects.originals(page,objects).single()
            check(original.sha256==record.getString("sha256"));val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            val bytes=original.bytes();android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts);check(opts.outWidth==4096&&opts.outHeight==3072)
        }
        check(app.study.sources(manifest.getString("mergedCard")).refs.size==2)
        check(app.study.sources(manifest.getString("summaryCard")).refs.size==2)
        JSONObject().put("books",2).put("documentPages",12).put("companionPages",app.pages.activePages(books[1]).size)
            .put("cards",cards.sumOf{it.size}).put("cardsPerBook",JSONArray(cards.map{it.size})).put("mapNodes",counts).put("mapDepths",depths)
            .put("longCardBodyChars",longBodyChars).put("longCardAnnotationChars",longPresentation.annotation.length)
            .put("recall",verifyRecall()).put("authoring",verifyAuthoring()).put("crossNotebookReferences",cross).put("stressStrokes",ink.size).put("stressPoints",ink.sumOf{it.samples.size})
            .also{manifest.put("actualCounts",it);save()}
    }

    private fun stressStroke(index:Int)=InkStroke(id("pressure-stroke-$index"),InkPen.entries[index%InkPen.entries.size],0xff263a70.toInt(),2.6f,InkTool.STYLUS,
        List(100){j->InkSample(22f+(index%20)*47f+j*.32f,24f+(index/20)*26f+sin(j/9.0).toFloat()*5f,j*4L,.25f+(j%60)/100f,.2f,.4f)})
    private fun formula():List<InkStroke>{
        val paths=listOf(listOf(110f to 200f,110f to 300f,160f to 300f),listOf(110f to 200f,160f to 200f),listOf(110f to 245f,160f to 245f),
            listOf(210f to 230f,290f to 230f),listOf(210f to 260f,290f to 260f),listOf(340f to 205f,420f to 295f),
            listOf(340f to 295f,420f to 205f),listOf(470f to 245f,610f to 245f),listOf(535f to 190f,535f to 300f),listOf(660f to 205f,705f to 245f,705f to 300f),listOf(750f to 205f,705f to 245f))
        return paths.mapIndexed{i,points->InkStroke(id("formula-stroke-$i"),InkPen.PEN,0xff173652.toInt(),3f,InkTool.STYLUS,
            points.mapIndexed{j,(x,y)->InkSample(x,y,j*40L,.6f,.2f,.3f)})}
    }
    private fun pdf():ByteArray{
        val document=PdfDocument()
        return try{
        repeat(12){index->val page=document.startPage(PdfDocument.PageInfo.Builder(1000,1414,index+1).create());val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            page.canvas.drawColor(Color.WHITE);paint.color=Color.rgb(24,51,70);paint.textSize=30f
            page.canvas.drawText("条件概率十二讲 · 第${index+1}页",70f,90f,paint);paint.textSize=19f
            repeat(44){line->page.canvas.drawText("${line+1}、"+chinese(38,"核对条件"),70f,150f+line*26f,paint)}
            paint.strokeWidth=.5f;repeat(18){line->page.canvas.drawLine(760f+line*4,1320f,760f+line*4,1380f,paint)}
            document.finishPage(page)
        };ByteArrayOutputStream().also(document::writeTo).toByteArray()
        }finally{document.close()}
    }
    private fun image(index:Int):Pair<ImageSource,ByteArray>{
        val bitmap=Bitmap.createBitmap(4096,3072,Bitmap.Config.ARGB_8888)
        return try{val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(48){row->repeat(64){col->paint.color=if((row+col+index)%2==0)Color.rgb(221,235,248)else Color.WHITE;canvas.drawRect(col*64f,row*64f,(col+1)*64f,(row+1)*64f,paint)}}
            paint.color=if(index==0)Color.rgb(18,64,128)else Color.rgb(125,39,26);paint.textSize=44f
            repeat(36){row->canvas.drawText("合成高清原图 ${index+1} · 条件、分母与独立性 · 精细标记 ${row+1}",120f,120f+row*78f,paint)}
            paint.strokeWidth=1f;repeat(120){line->canvas.drawLine(2900f+line*3,200f,2900f+line*3,2850f,paint)}
            val original=ImageSource(ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray())
            val small=Bitmap.createScaledBitmap(bitmap,512,384,true)
            val preview=try{ByteArrayOutputStream().also{small.compress(Bitmap.CompressFormat.JPEG,78,it)}.toByteArray()}finally{small.recycle()}
            check(preview.size<=240000);original to preview
        }finally{bitmap.recycle()}
    }

    companion object{
        fun chinese(length:Int,end:String):String{val line="条件概率先明确已知条件并限定样本空间再核对交集与分母独立性必须用反例检查";return line.repeat(length/line.length+1).take(length-end.length)+end}
        fun sha(file:File):String=file.inputStream().use{input->val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
        fun guard(context:Context){
            val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
            require(args.getString("sixBatchFixture")=="dedicated-empty-emulator"){"Use the controlled fixture runner"}
            val qemu=instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use{fd->java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText().trim()}
            require(qemu=="1"){"Synthetic fixture refuses non-emulator devices"}
            require(args.getString("sourceCommit")==BuildConfig.SOURCE_COMMIT&&BuildConfig.SOURCE_COMMIT.matches(Regex("[0-9a-f]{40}"))){"Installed APK source identity is missing or mismatched"}
        }
        fun create(app:InkWeftApplication):SixBatchFixture{
            guard(app)
            val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("six-batch-fixture-v1.json").use{it.readBytes()}
            val spec=JSONObject(bytes.toString(Charsets.UTF_8))
            val locked=mapOf("documentPages" to 12,"companionPages" to 1,"notebooks" to 2,"cardsPerNotebook" to 48,"totalCards" to 96,
                "maps" to 3,"activeNodesPerMap" to 120,"branchDepth" to 6,"crossNotebookReferences" to 12,"longCardBodyChars" to 12000,
                "longCardAnnotationChars" to 4000,"imageWidth" to 4096,"imageHeight" to 3072,"originalImages" to 2,"formulaExcerpts" to 1,
                "stressStrokes" to 1000,"pointsPerStressStroke" to 100,"stressPoints" to 100000)
            require(locked.all{(key,value)->spec.getInt(key)==value}){"The full fixed fixture cannot be resized"}
            val authoring=spec.getJSONObject("requiredAuthoring")
            require(authoring.getInt("layers")==3&&authoring.getInt("hiddenLayers")==1&&authoring.getInt("lockedLayers")==1&&authoring.getInt("collapsibleBlanks")==2)
            require(listOf("textAnswer","handwritingAnswer","crossDayRecords").all{spec.getJSONObject("requiredRecall").getBoolean(it)})
            val signing=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            return SixBatchFixture(app,JSONObject().put("format","inkweft.six-batch-run.v1").put("synthetic",true).put("runId",UUID.randomUUID().toString())
                .put("spec",spec).put("specSha256",ContentTransfer.hash(bytes)).put("sourceCommit",BuildConfig.SOURCE_COMMIT)
                .put("installedApkSha256",sha(File(app.applicationInfo.sourceDir)))
                .put("signerCertificateSha256",JSONArray(signing?.apkContentsSigners.orEmpty().map{ContentTransfer.hash(it.toByteArray())}))
                .put("environment",JSONObject().put("api",Build.VERSION.SDK_INT).put("model",Build.MODEL).put("brand",Build.BRAND).put("emulator",true)
                    .put("densityDpi",app.resources.displayMetrics.densityDpi).put("widthPixels",app.resources.displayMetrics.widthPixels)
                    .put("heightPixels",app.resources.displayMetrics.heightPixels).put("fontScale",app.resources.configuration.fontScale.toDouble()))
                .put("status","SEEDING").put("steps",JSONArray()).put("maps",JSONArray()).put("originals",JSONArray())
                .put("manualPen","NOT_RUN").put("manualLearning","NOT_RUN").put("manualThermal","NOT_RUN"))
        }
        fun load(app:InkWeftApplication):SixBatchFixture{guard(app);return SixBatchFixture(app,JSONObject(File(app.filesDir,"six-batch-fixture-v1/manifest.json").readText()))}
        suspend fun canonical(db:NoteDatabase):String=db.withTransaction{
            val schema=LibraryBackupRepository.SCHEMA;val sql=db.openHelper.readableDatabase
            LibraryArchive.write(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},schema,object:LibraryArchive.Rows{
                override fun count(table:Int)=sql.query("SELECT COUNT(*) FROM `${schema[table].name}`").use{it.moveToFirst();it.getLong(0)}
                override fun visit(table:Int,consume:(List<Any?>)->Unit){val t=schema[table]
                    sql.query("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{cursor->
                        while(cursor.moveToNext())consume(t.columns.mapIndexed{i,col->if(cursor.isNull(i))null else when(col.kind){'I'->cursor.getLong(i);'F'->cursor.getDouble(i);'B'->cursor.getBlob(i);else->cursor.getString(i)}})
                    }
                }
            },0).sha256
        }
    }
}
internal fun JSONArray.strings()=List(length()){getString(it)}
