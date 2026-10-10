import org.inkweft.core.*
import java.security.MessageDigest
import java.util.UUID

/** Warm host JVM. V81 overrides both UserLayers.kt and PageAuthoring.kt. */
fun main(args:Array<String>){
    val before=List(15_000){LayerContent(LayerContentKind.INK,UUID(1,it+1L).toString())}
    val deleted=List(15_000){LayerContent(LayerContentKind.OBJECT,UUID(2,it+1L).toString())}
    val additions=List(InkSelectionEdit.MAX_SELECTED){LayerContent(LayerContentKind.INK,UUID(3,it+1L).toString())}
    val constructions=mutableListOf<Double>();val assignment=mutableListOf<Double>();var digest=""
    repeat(7){run->
        val t0=System.nanoTime();val layered=UserLayers(memberships=before.map{LayerMembership(it,UserLayers.DEFAULT_ID)},deleted=deleted);val t1=System.nanoTime()
        check(layered.memberships.size==15_000&&layered.deleted.size==15_000)
        var stack=UserLayers.legacy(before);val t2=System.nanoTime()
        if(args.single()=="V81")additions.forEach{stack=stack.assignNew(listOf(it))}else stack=stack.assignNew(additions)
        val t3=System.nanoTime();check(stack.memberships.size==16_024&&additions.all{stack.layer(it)?.id==UserLayers.DEFAULT_ID})
        if(run>=2){constructions+=(t1-t0)/1e6;assignment+=(t3-t2)/1e6}
        if(run==6){digest=MessageDigest.getInstance("SHA-256").digest(PageAuthoringCodec.encode(PageAuthoring(stack))).joinToString(""){"%02x".format(it.toInt()and 255)}}
    }
    println("{\"scope\":\"WARM_HOST_JVM_NOT_ANDROID\",\"version\":\"${args.single()}\",\"warmups\":2,\"measured_runs\":5,\"construct_15000_members_and_15000_deleted_ms\":${constructions},\"assign_1024_on_15000_ms\":${assignment},\"final_authoring_sha256\":\"$digest\"}")
}
