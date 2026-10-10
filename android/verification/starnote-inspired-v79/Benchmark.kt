// SPDX-License-Identifier: AGPL-3.0-or-later
import org.inkweft.core.*
import java.io.File
import java.util.UUID
import kotlin.system.measureNanoTime

fun main(args:Array<String>){
    fun id()=UUID.randomUUID().toString()
    var consumed=0L
    val rows=List(16_000){i->StoredInk(InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,
        List(20){InkSample(100f+it,100f+it,it*5L)}),i<8000,1)}
    val session=InkSession(InkPage(id(),1,rows))
    val dictionary=List(128){i->KnowledgeTextTarget(id(),1,TargetRef(TargetKind.CARD,id()),RelationKind.REFERENCE,null,"知识",
        (0..3).map{j->"知识${i*4+j}条"},true)}
    val text="这是没有对应条目的笔记内容。".repeat(1000)
    fun bench(name:String,iterations:Int,action:()->Unit){
        repeat(5){repeat(iterations){action()}}
        val times=List(9){measureNanoTime{repeat(iterations){action()}}/1e6}.sorted()
        println("$name,$iterations,${times[4]},${times.first()},${times.last()}")
    }
    println("metric,iterations,median_ms,min_ms,max_ms")
    bench("canStart",500){if(session.canStart)consumed++}
    bench("visibleDraft",25){consumed+=session.visibleDraft().size}
    bench("keywordSpans",3){consumed+=KnowledgeTextLinks.spans(text,dictionary).size}
    println("consumed=$consumed")
    if(args.isNotEmpty()){
        val file=File(args[0]);val chunk=ByteArray(1_500_000){(it%251).toByte()}
        val schema=listOf(LibraryArchive.Table("records",listOf(LibraryArchive.Column("id",'I'),LibraryArchive.Column("data",'B')),listOf("id")))
        val source=object:LibraryArchive.Rows{
            override fun count(table:Int)=96L
            override fun visit(table:Int,consume:(List<Any?>)->Unit){repeat(96){consume(listOf(it.toLong(),chunk))}}
        }
        val written=file.outputStream().buffered().use{LibraryArchive.write(it,schema,source,0)}
        var rowsRead=0
        val read=file.inputStream().buffered().use{LibraryArchive.read(it,schema,{_,row->check(row[0]==rowsRead.toLong());check((row[1] as ByteArray).contentEquals(chunk));rowsRead++})}
        check(written==read&&rowsRead==96);check(written.bytes>134_217_728L)
        println("archive_bytes=${written.bytes},rows=$rowsRead,sha256=${written.sha256}")
        file.delete()
    }
}
