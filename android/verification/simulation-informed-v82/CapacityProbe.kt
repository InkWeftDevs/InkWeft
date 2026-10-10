import org.inkweft.core.*
import java.nio.ByteBuffer
import java.util.UUID

/** Identity payload sizes only; not maximum annotation geometry or a Room run. */
fun main(){
    fun refs(n:Int,kind:LayerContentKind)=List(n){LayerContent(kind,UUID(kind.ordinal+1L,it+1L).toString())}
    val all=refs(InkLimits.MAX_RETAINED_STROKES,LayerContentKind.INK)+
        refs(PageObjectCodec.MAX_RECORDS,LayerContentKind.OBJECT)+refs(PageAuthoring.MAX_ANNOTATIONS,LayerContentKind.ANNOTATION)
    val payload=PageAuthoringCodec.encode(PageAuthoring(UserLayers.legacy(all)))
    check(PageAuthoringCodec.decode(payload).layers.memberships.map{it.content}.toSet()==all.toSet())
    val ink=all.take(InkLimits.MAX_RETAINED_STROKES)
    val second=UserLayer(UUID(8,2).toString(),"待删除层")
    val state=UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),second),UserLayers.DEFAULT_ID,
        ink.mapIndexed{i,c->LayerMembership(c,if(i<20_000)UserLayers.DEFAULT_ID else second.id)})
    val mixed=PageAuthoringCodec.encode(PageAuthoring(state.remove(second.id,LayerDelete.DeleteContents)))
    check(PageAuthoringCodec.decode(mixed).layers.deleted.size==20_000)
    println("{\"scope\":\"HOST_CORE_IDENTITY_PAYLOAD_ONLY_NOT_ROOM_OR_FULL_ANNOTATION_MAX\",\"membership_count\":${all.size},\"membership_bytes\":${payload.size},\"magic\":\"IWA${ByteBuffer.wrap(payload).int-0x49574130}\",\"mixed_member_count\":20000,\"mixed_deleted_count\":20000,\"mixed_bytes\":${mixed.size},\"max_bytes\":${PageAuthoringCodec.MAX_BYTES},\"roundtrip\":\"PASS\"}")
}
