import org.inkweft.core.*
import java.io.File
fun main(args:Array<String>){
    File(args[0]).readLines().forEach{row->
        val fields=row.split('\t');val source=InkPageFile.decode(File(fields[1]).readBytes())
        val lines=HandwritingLines.split(source.strokes)
        val actual=lines.map{it.strokes.map{it.id}.sorted().joinToString(",")}.sorted().joinToString(";")
        val all=lines.flatMap{it.strokes.map{it.id}}
        val conserved=all.size==source.strokes.size&&all.toSet()==source.strokes.map{it.id}.toSet()
        println(listOf(fields[0],actual==fields[2],lines.size,conserved,ContentTransfer.hash(source.strokes.flatMap{InkStrokeCodec.encode(it).toList()}.toByteArray())).joinToString("\t"))
    }
}
