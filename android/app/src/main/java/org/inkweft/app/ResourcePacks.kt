// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.json.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class PackResource(val id:String,val title:String,val paper:PaperStyle?,val image:String?,val map:KnowledgeData.MapTemplate?)
internal data class ResourcePack(val id:String,val title:String,val author:String,val version:Int,val hash:String,val bytes:ByteArray,val files:Map<String,ByteArray>,val resources:List<PackResource>)
internal data class TemplateRef(val hash:String,val id:String)
internal data class CatalogTemplate(val ref:TemplateRef,val title:String,val source:String,val version:Int,val resource:PackResource)
internal data class InstalledPack(val pack:ResourcePack,val enabled:Boolean,val copies:List<String>,val damaged:Boolean=false)

/** Closed JSON + PNG format. No code, network, path resolution or trust claimed by package metadata. */
internal object ResourcePackCodec {
    const val COMPRESSED=8*1024*1024
    const val EXPANDED=32*1024*1024
    fun inspect(bytes:ByteArray,check:()->Unit={}):ResourcePack {
        require(bytes.size in 22..COMPRESSED){"PACK_SIZE"}
        val central=centralNames(bytes)
        val files=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(bytes.inputStream()).use{zip->while(true){check();val e=zip.nextEntry?:break
            require(!e.isDirectory&&e.name in central&&e.name !in files){"PACK_PATH"}
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){check();val n=zip.read(buffer);if(n<0)break;total+=n
                require(out.size()+n<=COMPRESSED&&total<=EXPANDED&&total<=bytes.size.toLong()*20){"PACK_EXPANSION"};out.write(buffer,0,n)}
            files[e.name]=out.toByteArray()
        }}
        require(files.keys==central){"PACK_DIRECTORY"}
        files.filterKeys{it.endsWith(".json")}.values.forEach(::jsonBudget)
        files.filterKeys{it.endsWith(".png")}.values.forEach{png->
            require(png.size>=24&&png.take(8)==listOf(137,80,78,71,13,10,26,10).map{it.toByte()}){"PACK_PNG"}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(png,0,png.size,bounds)
            require(bounds.outWidth in 1..2048&&bounds.outHeight in 1..2048&&bounds.outWidth.toLong()*bounds.outHeight*4<=16*1024*1024){"PACK_PIXELS"}
            val decoded=checkNotNull(BitmapFactory.decodeByteArray(png,0,png.size,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inScaled=false})){"PACK_PNG_DECODE"}
            decoded.recycle()
        }
        val j=JSONObject(requireNotNull(files["manifest.json"]).toString(Charsets.UTF_8))
        keys(j,setOf("format","id","title","author","version","resources","files"))
        require(j.getString("format")=="inkweft.resource-pack.v1")
        val declared=j.optJSONObject("files")?:JSONObject()
        require(declared.keys().asSequence().toSet()==files.keys-setOf("manifest.json")){"PACK_FILES"}
        declared.keys().forEach{path->val value=declared.getJSONObject(path);keys(value,setOf("bytes","sha256"));val payload=files.getValue(path)
            require(value.getInt("bytes")==payload.size&&value.getString("sha256")==ContentTransfer.hash(payload)){"PACK_HASH"}}
        val id=j.getString("id");require(id.matches(Regex("[a-z][a-z0-9.-]{2,79}"))&&!id.startsWith("org.inkweft")){"PACK_NAMESPACE"}
        val version=j.getInt("version");require(version in 1..1_000_000)
        val entries=j.getJSONArray("resources");require(entries.length() in 1..64)
        val resources=List(entries.length()){i->val r=entries.getJSONObject(i)
            keys(r,setOf("id","title","type","paper","image","layout","nodes"))
            val key=r.getString("id");require(key.matches(Regex("[a-z][a-z0-9-]{0,47}")))
            val title=text(r,"title",120)
            when(r.getString("type")){
                "paper"->{val style=PaperStyle.valueOf(r.optString("paper","BLANK"));val image=r.optString("image").takeIf{it.isNotEmpty()}
                    require(image==null||image.endsWith(".png")&&image in files)
                    PackResource(key,title,style,image,null)}
                "map"->{val a=r.getJSONArray("nodes");require(a.length()<=128)
                    val nodes=List(a.length()){k->val n=a.getJSONObject(k);keys(n,setOf("title","parent","x","y"));TemplateNode(text(n,"title",120),if(n.isNull("parent"))null else n.getInt("parent"),n.getDouble("x"),n.getDouble("y"))}
                    val map=KnowledgeData.MapTemplate(title,layout=r.optString("layout","right"),nodes=nodes);KnowledgeCodec.validate(map)
                    PackResource(key,title,null,null,map)}
                else->error("PACK_TYPE")
            }
        }
        require(resources.map{it.id}.distinct().size==resources.size)
        require(files.keys==setOf("manifest.json")+resources.mapNotNull{it.image}){"PACK_UNUSED_FILE"}
        return ResourcePack(id,text(j,"title",120),text(j,"author",120),version,ContentTransfer.hash(bytes),bytes,files,resources)
    }
    private fun text(j:JSONObject,key:String,max:Int)=j.getString(key).also{require(it.isNotBlank()&&it.length<=max)}
    private fun keys(j:JSONObject,allowed:Set<String>){require(j.keys().asSequence().all{it in allowed}){"PACK_FIELDS"}}
    private fun jsonBudget(bytes:ByteArray){
        require(bytes.size<=256*1024);var depth=0;var nodes=0;var quoted=false;var escape=false;var length=0
        for(c in bytes.toString(Charsets.UTF_8)){
            if(quoted){if(escape){escape=false}else if(c=='\\'){escape=true}else if(c=='"'){quoted=false};length++;require(length<=4096)}
            else when(c){'"'->{quoted=true;length=0};'{','['->{depth++;nodes++;require(depth<=16&&nodes<=4096)};'}',']'->{depth--;require(depth>=0)};','->{nodes++;require(nodes<=8192)}}
        };require(depth==0&&!quoted)
    }
    /** Inspect Unix file attributes from the central directory before touching local entries. */
    private fun centralNames(bytes:ByteArray):Set<String>{
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(i:Int)=b.getShort(i).toInt() and 65535
        fun u32(i:Int)=b.getInt(i).toLong() and 0xffffffffL
        val end=(bytes.size-22 downTo maxOf(0,bytes.size-65557)).firstOrNull{b.getInt(it)==0x06054b50}?:error("PACK_ZIP")
        require(u16(end+4)==0&&u16(end+6)==0&&u16(end+8)==u16(end+10)&&end+22+u16(end+20)==bytes.size)
        val count=u16(end+10);require(count in 1..256)
        val start=u32(end+16);require(start<=end&&start+u32(end+12)==end.toLong())
        var cursor=start.toInt();val names=linkedSetOf<String>()
        repeat(count){require(cursor+46<=end&&b.getInt(cursor)==0x02014b50)
            val flags=u16(cursor+8);val method=u16(cursor+10);require(flags and 1==0&&method in listOf(0,8))
            val nameLength=u16(cursor+28);val extra=u16(cursor+30);val comment=u16(cursor+32)
            require(cursor+46+nameLength+extra+comment<=end)
            val mode=(u32(cursor+38) ushr 16).toInt() and 0xf000;require(mode==0||mode==0x8000){"PACK_LINK"}
            val name=bytes.copyOfRange(cursor+46,cursor+46+nameLength).toString(Charsets.UTF_8)
            require(name.length<=160&&name.matches(Regex("[a-z0-9][a-z0-9/_.-]*"))&&name.split('/').none{it.isEmpty()||it=="."||it==".."}){"PACK_PATH"}
            require(name.endsWith(".json")||name.endsWith(".png")){"PACK_TYPE"}
            require(names.add(name)){"PACK_DUPLICATE"};cursor+=46+nameLength+extra+comment
        };require(cursor==end);return names
    }
}

internal class ResourcePacks(private val app:InkWeftApplication,private val freeBytes:()->Long={app.filesDir.usableSpace},private val fault:(String)->Unit={}){
    private val root=File(app.filesDir,"resource-packs").apply{mkdirs()}
    private val registry=AtomicFile(File(root,"registry.json"))
    private val mutex=Mutex()
    private val metadata=mutableMapOf<String,ResourcePack>()
    internal var validations=0;private set
    private suspend fun verified(hash:String,full:Boolean=false):ResourcePack {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        BackgroundBudget.await(app)
        return BackgroundBudget.memory(48L*1024*1024){
            val ctx=currentCoroutineContext()
            val bytes=File(root,"$hash.iwpack").inputStream().use{it.readBytesLimited(ResourcePackCodec.COMPRESSED)}
            require(ContentTransfer.hash(bytes)==hash){"PACK_ARCHIVE_CHANGED"};ctx.ensureActive()
            if(!full&&metadata.containsKey(hash))metadata.getValue(hash)
            else ResourcePackCodec.inspect(bytes){ctx.ensureActive()}.also{validations++;metadata[hash]=it.copy(bytes=byteArrayOf(),files=emptyMap())}
        }
    }
    suspend fun catalog():List<CatalogTemplate> = installed().filter{it.enabled&&!it.damaged}.flatMap{entry->entry.pack.resources.map{
        CatalogTemplate(TemplateRef(entry.pack.hash,it.id),it.title,entry.pack.title,entry.pack.version,it)
    }}
    suspend fun resource(ref:TemplateRef):PackResource=withContext(Dispatchers.IO){mutex.withLock{verified(ref.hash).resources.single{it.id==ref.id}}}
    suspend fun preview(ref:TemplateRef):ByteArray?=withContext(Dispatchers.IO){mutex.withLock{
        val p=verified(ref.hash,true);p.resources.single{it.id==ref.id}.image?.let{p.files.getValue(it)}
    }}
    private fun read()=if(registry.baseFile.exists())JSONObject(registry.openRead().bufferedReader().use{it.readText()})else JSONObject()
    private fun write(j:JSONObject){val stream=registry.startWrite();try{stream.write(j.toString().toByteArray());registry.finishWrite(stream)}catch(t:Throwable){registry.failWrite(stream);throw t}}
    suspend fun inspect(input:InputStream)=withContext(Dispatchers.IO){BackgroundBudget.await(app);BackgroundBudget.memory(48L*1024*1024){val ctx=currentCoroutineContext();input.use{ResourcePackCodec.inspect(it.readBytesLimited(ResourcePackCodec.COMPRESSED)){ctx.ensureActive()}}}}
    suspend fun installed():List<InstalledPack> = withContext(Dispatchers.IO){mutex.withLock{
        val j=read()
        for(file in root.listFiles().orEmpty()){
            if(file.name.matches(Regex("staging-[0-9a-f-]{36}")))file.delete()
            else if(file.name.matches(Regex("[0-9a-f]{64}\\.iwpack"))&&!j.has(file.nameWithoutExtension)&&app.resourceTemplates.copies(file.nameWithoutExtension).isEmpty())file.delete()
        }
        j.keys().asSequence().toList().map{hash->val entry=j.getJSONObject(hash);val copies=app.resourceTemplates.copies(hash)
            try{val pack=verified(hash);InstalledPack(pack.copy(bytes=byteArrayOf(),files=emptyMap()),entry.getBoolean("enabled"),copies)}
            catch(c:CancellationException){throw c}
            catch(_:Exception){InstalledPack(ResourcePack(entry.getString("id"),entry.getString("id"),"来源待核对",entry.getInt("version"),hash,byteArrayOf(),emptyMap(),emptyList()),false,copies,true)}
        }.toList()
    }}
    suspend fun install(pack:ResourcePack)=withContext(Dispatchers.IO){mutex.withLock{
        BackgroundBudget.await(app);require(freeBytes()>pack.bytes.size*2L+16*1024*1024){"PACK_SPACE"}
        // Validate again: only a complete immutable archive is registered. Orphans are inert.
        val ctx=currentCoroutineContext();val verified=BackgroundBudget.memory(48L*1024*1024){ResourcePackCodec.inspect(pack.bytes){ctx.ensureActive()}};require(verified.hash==pack.hash)
        val j=read();require(j.length()<32||j.has(pack.hash)){"PACK_COUNT"};require(root.listFiles().orEmpty().filter{it.extension=="iwpack"}.sumOf{it.length()}+pack.bytes.size<=64L*1024*1024){"PACK_STORAGE"};val entries=j.keys().asSequence().map{j.getJSONObject(it)}.filter{it.getString("id")==pack.id}.toList()
        require(entries.none{it.getInt("version")>pack.version}){"PACK_DOWNGRADE"}
        require(j.keys().asSequence().none{h->val e=j.getJSONObject(h);e.getString("id")==pack.id&&e.getInt("version")==pack.version&&h!=pack.hash}){"PACK_VERSION_CHANGED"}
        val target=File(root,"${pack.hash}.iwpack");val staging=File(root,"staging-${UUID.randomUUID()}")
        try{
            if(!target.exists()||runCatching{target.inputStream().use{ContentTransfer.hash(it.readBytesLimited(ResourcePackCodec.COMPRESSED))}}.getOrNull()!=pack.hash){
                val atomic=AtomicFile(target);val output=atomic.startWrite();try{output.write(pack.bytes);output.fd.sync();atomic.finishWrite(output)}catch(t:Throwable){atomic.failWrite(output);throw t}
            }
            fault("after-file")
            entries.forEach{it.put("enabled",false)}
            j.put(pack.hash,JSONObject().put("id",pack.id).put("version",pack.version).put("enabled",true));fault("before-registry");ctx.ensureActive();write(j);metadata[pack.hash]=verified.copy(bytes=byteArrayOf(),files=emptyMap());fault("after-registry")
        }finally{staging.delete()}
    }}
    suspend fun enable(hash:String,enabled:Boolean)=withContext(Dispatchers.IO){mutex.withLock{val j=read();j.getJSONObject(hash).put("enabled",enabled);write(j)}}
    suspend fun uninstall(hash:String)=withContext(Dispatchers.IO){mutex.withLock{val j=read();j.getJSONObject(hash).put("enabled",false)
        if(app.resourceTemplates.copies(hash).isEmpty()){j.remove(hash);write(j);File(root,"$hash.iwpack").delete()}else write(j)
    }}
    private suspend fun selected(ref:TemplateRef):Pair<ResourcePack,PackResource>{
        require(read().getJSONObject(ref.hash).getBoolean("enabled")){"PACK_DISABLED"}
        val pack=verified(ref.hash,true);return pack to pack.resources.single{it.id==ref.id}
    }
    private fun document(pack:ResourcePack,r:PackResource):PdfPageSource?=r.image?.let{path->
        val png=pack.files.getValue(path);val bitmap=checkNotNull(BitmapFactory.decodeByteArray(png,0,png.size))
        val pdf=PdfDocument();try{val page=pdf.startPage(PdfDocument.PageInfo.Builder(1000,1414,1).create())
            val scale=minOf(1000f/bitmap.width,1414f/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            page.canvas.drawColor(Color.WHITE);page.canvas.drawBitmap(bitmap,null,RectF((1000-w)/2,(1414-h)/2,(1000+w)/2,(1414+h)/2),Paint(Paint.FILTER_BITMAP_FLAG));pdf.finishPage(page)
            val out=ByteArrayOutputStream();pdf.writeTo(out);PdfPageSource(PdfDocumentSource(out.toByteArray(),1),0)
        }finally{pdf.close();bitmap.recycle()}
    }
    suspend fun instantiate(hash:String,resource:String,title:String?=null,operation:String=UUID.randomUUID().toString(),cover:NotebookCover=NotebookCover.AUTO,custom:ByteArray?=null):Note=withContext(Dispatchers.IO){mutex.withLock{
        app.resourceTemplates.created(operation,hash)?.let{return@withLock it}
        val(pack,r)=selected(TemplateRef(hash,resource))
        BackgroundBudget.memory(48L*1024*1024){app.resourceTemplates.instantiate(hash,title?:r.title,r.paper?:PaperStyle.BLANK,document(pack,r),r.map,operation,cover,custom)}
    }}
    suspend fun insert(ref:TemplateRef,command:InsertPages):InsertPagesResult=withContext(Dispatchers.IO){mutex.withLock{
        app.resourceTemplates.inserted(ref.hash,command)?.let{return@withLock it}
        val(pack,r)=selected(ref);require(r.map==null)
        BackgroundBudget.memory(48L*1024*1024){app.resourceTemplates.insert(ref.hash,command,document(pack,r))}
    }}
    suspend fun map(ref:TemplateRef,command:KnowledgeCommand):String=withContext(Dispatchers.IO){mutex.withLock{
        if(!app.resourceTemplates.hasReceipt(command.operationId,ref.hash)){val(_,r)=selected(ref);require(r.map!=null)}
        app.resourceTemplates.map(ref.hash,command)
    }}
}
