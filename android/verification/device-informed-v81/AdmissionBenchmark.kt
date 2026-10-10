import org.inkweft.core.*
import java.security.MessageDigest
import java.util.UUID

/** Warm host-JVM public-API comparison; never a tablet latency measurement. */
fun main() {
    val points=List(20){InkSample(100f+it,200f,it*10L,.3f,.4f,.5f)}
    val rows=List(16_000){i->StoredInk(InkStroke(UUID(1,i+1L).toString(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,points),i<14_000,1)}
    val page=InkPage(UUID(9,1).toString(),1,rows)
    val selected=rows.subList(5000,5000+InkSelectionEdit.MAX_SELECTED).map{it.stroke}
    val moved=selected.mapIndexed{i,s->InkStroke(UUID(2,i+1L).toString(),s.pen,s.color,s.width,s.tool,s.samples.map{it.copy(x=it.x+5,y=it.y+7)})}
    val replacement=InkMutation.Replace(selected.map{it.id},moved)
    val extra=InkStroke(UUID(3,1).toString(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,points)
    fun measure(name:String,change:InkMutation,expectedVisible:Int,expectedRetained:Int){
        val prepare=mutableListOf<Double>();val admit=mutableListOf<Double>();val commit=mutableListOf<Double>()
        var contentHash=""
        repeat(10){iteration->
            val t0=System.nanoTime();val s=InkSession(page);s.visibleDraft();val t1=System.nanoTime()
            s.enqueue(change);val t2=System.nanoTime()
            val command=checkNotNull(s.nextCommand());s.complete(command,InkCommitResult.Committed(2));val t3=System.nanoTime()
            val visible=s.visibleDraft();check(visible.size==expectedVisible&&s.page.strokes.size==expectedRetained)
            check(visible.sumOf{it.samples.size}==expectedVisible*20)
            if(iteration>=3){prepare+=(t1-t0)/1e6;admit+=(t2-t1)/1e6;commit+=(t3-t2)/1e6}
            if(iteration==9){
                val digest=MessageDigest.getInstance("SHA-256")
                visible.forEach{digest.update(InkStrokeCodec.encode(it))}
                contentHash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            }
        }
        fun values(v:List<Double>)=v.joinToString(prefix="[",postfix="]")
        println("{\"case\":\"$name\",\"warmups\":3,\"measured_runs\":7,\"prepare_ms\":${values(prepare)},\"admission_ms\":${values(admit)},\"commit_ms\":${values(commit)},\"visible_strokes\":$expectedVisible,\"retained_strokes\":$expectedRetained,\"visible_content_sha256\":\"$contentHash\"}")
    }
    measure("replace_1024_on_16000_rows",replacement,14_000,17_024)
    measure("add_on_16000_rows",InkMutation.Add(extra),14_001,16_001)
}
