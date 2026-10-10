import org.inkweft.core.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private CSV prepared by audit_physical_input.py; core-only, no Android input certification. */
fun main(args:Array<String>) {
    val groups=linkedMapOf<Int,MutableList<InkSample>>()
    var exactDuplicates=0;var backwardsIgnored=0;var stationaryAxesRetained=0
    File(args.single()).useLines{lines->lines.drop(1).forEach{line->
        val p=line.split(',');val id=p[0].toInt()
        val reading=InkSample(p[1].toFloat(),p[2].toFloat(),p[3].toLong(),p[4].toFloat(),p[5].toFloat(),checkNotNull(InkSampling.orientation(p[6].toFloat())),true)
        val points=groups.getOrPut(id){mutableListOf()}
        val prior=points.lastOrNull()
        // Match InkCanvasView.append: reject backwards readings, preserve
        // stationary axis changes, and ignore exact duplicates. Never sort or
        // invent a new timestamp to make a device reading fit the model.
        when {
            prior==reading -> exactDuplicates++
            prior!=null&&prior.elapsedMs>reading.elapsedMs -> backwardsIgnored++
            else -> {
                if(prior!=null&&prior.x==reading.x&&prior.y==reading.y&&prior.elapsedMs==reading.elapsedMs)stationaryAxesRetained++
                points.add(reading)
            }
        }
    }}
    val codecHash=MessageDigest.getInstance("SHA-256")
    var points=0;var negativePressure=0;var renderPoints=0
    for((number,samples) in groups){
        val source=InkStroke(UUID(4,number+1L).toString(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,samples,true)
        val encoded=InkStrokeCodec.encode(source);val decoded=InkStrokeCodec.decode(encoded)
        check(decoded.samples==samples);codecHash.update(encoded)
        val polished=InkSelectionEdit.beautify(listOf(source),.5f).single()
        check(polished.samples.size==samples.size)
        check(polished.samples.first()==samples.first()&&polished.samples.last()==samples.last())
        samples.zip(polished.samples).forEach{(a,b)->
            check(a.elapsedMs==b.elapsedMs&&a.pressure==b.pressure&&a.tilt==b.tilt&&a.orientation==b.orientation)
        }
        points+=samples.size;negativePressure+=samples.count{it.pressure<0}
        renderPoints+=InkSampling.forRendering(samples).size
    }
    val digest=codecHash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    println("{\"scope\":\"CORE_CODEC_AND_BEAUTIFY_AXIS_PRESERVATION_NOT_ANDROID_OR_HAND_FEEL\",\"coordinates\":\"SAMPLER_PIXELS_AS_SURROGATE_WORLD_NOT_REAL_VIEWPORT_MAPPING\",\"strokes\":${groups.size},\"author_samples\":$points,\"render_samples\":$renderPoints,\"exact_duplicates_ignored\":$exactDuplicates,\"backwards_readings_ignored\":$backwardsIgnored,\"stationary_axes_retained\":$stationaryAxesRetained,\"unknown_pressure_samples\":$negativePressure,\"codec_roundtrip\":\"PASS\",\"beautify_axis_time_endpoints\":\"PASS\",\"normalized_author_bytes_sha256\":\"$digest\"}")
}
