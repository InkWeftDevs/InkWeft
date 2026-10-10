// SPDX-License-Identifier: AGPL-3.0-or-later
import org.inkweft.core.*
import java.io.File
import java.util.UUID

/** The same public API probe runs on the exact V79 baseline and the new core. No Android timing claims. */
fun main(args:Array<String>){
    fun stroke(points:List<InkSample>)=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff112233.toInt(),4f,InkTool.STYLUS,points,points.first().world)
    val straight=stroke(listOf(InkSample(10f,20f,0,.2f),InkSample(11f,20f,10,.5f),InkSample(100f,20f,20,.9f)))
    val polished=InkSelectionEdit.beautify(listOf(straight),1f).single()
    val region=InkRegion(listOf(EraserPoint(0f,0f),EraserPoint(100f,0f),EraserPoint(100f,100f),EraserPoint(50.2f,100f),EraserPoint(50.2f,40f),EraserPoint(50.1f,40f),EraserPoint(50.1f,100f),EraserPoint(0f,100f)),false)
    val crossing=stroke(listOf(InkSample(10f,60f,0),InkSample(90f,60f,20)))
    val seam=stroke(listOf(InkSample(100f,1400f,0,.2f,0f,(2*Math.PI-.1).toFloat(),true),InkSample(140f,1428f,20,.9f,.9f,.1f,true)))
    val edge=ContinuousInk.split(seam,0,2).getValue(0).single().samples.last()
    val latest=InkPageFile("图层副本","原文",emptyList(),authoring=PageAuthoring()).encode()
    val imported=runCatching{ContentTransfer.decode(latest)}.isSuccess
    println("{\"straight_original_x\":11.0,\"straight_polished_x\":${polished.samples[1].x},\"outside_notch_stroke_selected\":${region.selects(crossing)},\"seam_orientation_radians\":${edge.orientation},\"authoring_page_imported\":$imported}")
    if(args.isNotEmpty()){
        var cases=0;var points=0
        for(file in File(args[0],"synthetic-input/starnote-packet").listFiles()!!.filter{it.extension=="csv"}.sortedBy{it.name}){
            val samples=file.readLines().filter{it.isNotBlank()&&!it.startsWith("#")}.drop(1).map{row->val v=row.split(',')
                InkSample(v[2].toFloat(),v[3].toFloat(),v[1].toLong(),v[4].toFloat(),v[5].toFloat(),v[6].toFloat(),true)}
            val original=stroke(samples);val encoded=InkStrokeCodec.encode(original)
            check(InkStrokeCodec.decode(encoded).samples==samples)
            val changed=InkSelectionEdit.beautify(listOf(original),.5f).single()
            check(changed.samples.size==samples.size)
            check(changed.samples.indices.all{i->val a=samples[i];val b=changed.samples[i];a.elapsedMs==b.elapsedMs&&a.pressure==b.pressure&&a.tilt==b.tilt&&a.orientation==b.orientation})
            check(encoded.contentEquals(InkStrokeCodec.encode(original)))
            cases++;points+=samples.size
        }
        println("{\"synthetic_matrix_codec_and_axes\":\"PASS\",\"cases\":$cases,\"points\":$points,\"physical_pen\":\"NOT_RUN\"}")
    }
}
