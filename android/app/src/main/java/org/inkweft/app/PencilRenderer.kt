package org.inkweft.app

import android.graphics.*
import org.inkweft.core.*
import kotlin.math.*

/** Graphite v1. Sparse, derived tiles, never document data. Each pixel stores the
 * maximum deposition of ONE stroke; separate strokes compose with SRC_OVER.
 * Sampling grid travels with the material origin, including copies/page splits.
 * A full paper-sized live stroke stays resident (up to 2048 sparse tiles / 64 MiB).
 * The previous 96-tile limit evicted the start of a fast stroke every frame. */
internal object PencilRenderer {
    private val live=PencilTileRenderer()
    init{RenderResources.onTrim{live.clear()}}
    internal val tileBuilds get()=live.tileBuilds
    fun draw(canvas:Canvas,stroke:InkStroke)=live.draw(canvas,stroke)
    fun forget(ids:Set<String>)=live.forget(ids)
}
/** Each raster worker owns its engine; background generation never locks live input. */
internal class PencilTileRenderer(private val unit:Float=.5f,private val live:Boolean=true,private val checkpoint:()->Unit={}) {
    internal var tileBuilds=0L; private set
    companion object {private const val SIDE=64}
    private val UNIT=unit.coerceAtLeast(.5f);private val TILE=SIDE*UNIT
    private data class Key(val id:String,val appearance:StrokeAppearance,val color:Int,val width:Float,val x:Int,val y:Int)
    private val owner="pencil-"+java.util.UUID.randomUUID()
    private val bitmapAllocation=Any();private val coverageAllocation=Any();private var ownedTiles=0
    private fun account(){val role=if(live)RenderResources.Role.LIVE_INK else RenderResources.Role.IN_FLIGHT
        if(ownedTiles==0){RenderResources.release(bitmapAllocation,owner);RenderResources.release(coverageAllocation,owner)}else{
            RenderResources.track(bitmapAllocation,ownedTiles.toLong()*SIDE*SIDE*4,"pencil-tile",owner,role)
            RenderResources.track(coverageAllocation,ownedTiles.toLong()*SIDE*SIDE*4,"pencil-deposition",owner,role)
        }
    }
    private inner class Tile {
        init { RenderResources.admit(SIDE.toLong()*SIDE*8,live);tileBuilds++ }
        val coverage=FloatArray(SIDE*SIDE)
        val bitmap=Bitmap.createBitmap(SIDE,SIDE,Bitmap.Config.ARGB_8888)
        init{ownedTiles++;account()}
        var count=0
        var last:InkSample?=null
    }
    private val tiles=object:LinkedHashMap<Key,Tile>(256,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Key,Tile>?):Boolean{if(size<=2048)return false;eldest?.value?.let(::release);return true}}
    private val grain=GraphiteMaterial.alpha()
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private fun release(tile:Tile){ownedTiles--;account()}
    @Synchronized fun clear(){tiles.clear();ownedTiles=0;account()}
    @Synchronized fun forget(ids:Set<String>){val it=tiles.iterator();while(it.hasNext()){val entry=it.next();if(entry.key.id in ids){release(entry.value);it.remove()}}}
    private fun pressure(s:InkSample)=sqrt(if(s.pressure<0).5f else s.pressure)
    private fun tilt(s:InkSample,stroke:InkStroke)=if(s.tilt<0||!stroke.appearance.recipe.tiltShading)1f else 1+3*((s.tilt-(PI/6).toFloat())/(PI/6).toFloat()).coerceIn(0f,1f)
    private fun segmentRadius(p:InkSample,q:InkSample,s:InkStroke)=s.width*.5f*(.85f+.15f*max(pressure(p),pressure(q)))*max(tilt(p,s),tilt(q,s))
    @Synchronized fun draw(canvas:Canvas,stroke:InkStroke) {
        val source=stroke.renderSamples()
        val clip=canvas.clipBounds;val bounds=stroke.bounds();val a=stroke.appearance;val r=a.recipe
        val left=max(bounds.left.toFloat()-UNIT,clip.left.toFloat());val right=min(bounds.right.toFloat()+UNIT,clip.right.toFloat())
        val top=max(bounds.top.toFloat()-UNIT,clip.top.toFloat());val bottom=min(bounds.bottom.toFloat()+UNIT,clip.bottom.toFloat())
        if(left>=right||top>=bottom)return
        val firstX=floor((left-a.originX)/TILE).toInt();val lastX=floor((right-a.originX)/TILE).toInt()
        val firstY=floor((top-a.originY)/TILE).toInt();val lastY=floor((bottom-a.originY)/TILE).toInt()
        // Only allocate tiles touched by a segment, never the whole bounding box.
        val candidates=linkedMapOf<Pair<Int,Int>,MutableList<Int>>()
        source.indices.forEach{i->val p=source[if(i==0)0 else i-1];val q=source[i];val pad=segmentRadius(p,q,stroke)+UNIT
            val x0=max(firstX,floor((min(p.x,q.x)-pad-a.originX)/TILE).toInt());val x1=min(lastX,floor((max(p.x,q.x)+pad-a.originX)/TILE).toInt())
            val y0=max(firstY,floor((min(p.y,q.y)-pad-a.originY)/TILE).toInt());val y1=min(lastY,floor((max(p.y,q.y)+pad-a.originY)/TILE).toInt())
            for(y in y0..y1){
                val yStart=a.originY+y*TILE-pad;val yEnd=yStart+TILE+2*pad
                val t0=if(p.y==q.y)0f else ((yStart-p.y)/(q.y-p.y)).coerceIn(0f,1f)
                val t1=if(p.y==q.y)1f else ((yEnd-p.y)/(q.y-p.y)).coerceIn(0f,1f)
                val ax=p.x+(q.x-p.x)*t0;val bx=p.x+(q.x-p.x)*t1
                val row0=max(x0,floor((min(ax,bx)-pad-a.originX)/TILE).toInt());val row1=min(x1,floor((max(ax,bx)+pad-a.originX)/TILE).toInt())
                for(x in row0..row1)candidates.getOrPut(x to y){mutableListOf()}.add(i)
            }
        }
        for((position,segments) in candidates){
            checkpoint()
            val (tx,ty)=position;val key=Key(stroke.id,a,stroke.color,stroke.width,tx,ty)
            var tile=tiles[key]
            if(tile==null||tile.count>source.size||tile.count>0&&source[tile.count-1].copy(world=tile.last!!.world)!=tile.last){tile?.let(::release);tile=Tile();tiles[key]=tile}
            val ox=a.originX+tx*TILE;val oy=a.originY+ty*TILE
            var dirty=false
            for(i in segments){if(i<tile.count)continue;dirty=true
                val p=source[if(i==0)0 else i-1];val q=source[i];val dx=q.x-p.x;val dy=q.y-p.y;val length=dx*dx+dy*dy
                val pad=segmentRadius(p,q,stroke)+UNIT
                val sx=max(0,floor((min(p.x,q.x)-pad-ox)/UNIT).toInt());val ex=min(SIDE-1,ceil((max(p.x,q.x)+pad-ox)/UNIT).toInt())
                val sy=max(0,floor((min(p.y,q.y)-pad-oy)/UNIT).toInt());val ey=min(SIDE-1,ceil((max(p.y,q.y)+pad-oy)/UNIT).toInt())
                for(y in sy..ey)for(x in sx..ex){val px=ox+(x+.5f)*UNIT;val py=oy+(y+.5f)*UNIT
                    val t=if(length==0f)0f else ((px-p.x)*dx+(py-p.y)*dy).div(length).coerceIn(0f,1f)
                    val pEffective=sqrt(if(p.pressure<0).5f else p.pressure+(q.pressure-p.pressure)*t)
                    val tiltEffective=if(p.tilt<0||!r.tiltShading)1f else 1+3*((p.tilt+(q.tilt-p.tilt)*t-(PI/6).toFloat())/(PI/6).toFloat()).coerceIn(0f,1f)
                    val radius=stroke.width*.5f*(.85f+.15f*pEffective)*tiltEffective
                    val coverage=((radius-hypot(px-p.x-t*dx,py-p.y-t*dy))/UNIT+.5f).coerceIn(0f,1f)
                    val density=(r.hardnessFactor*r.density*(.18f+.55f*pEffective)).coerceIn(0f,1f)
                    val index=y*SIDE+x;tile.coverage[index]=max(tile.coverage[index],coverage*density)
                }
            }
            tile.count=source.size;tile.last=source.last()
            if(dirty){val pixels=IntArray(SIDE*SIDE){index->
                val x=tx*SIDE+index%SIDE;val y=ty*SIDE+index/SIDE
                val gx=(floor(x*UNIT/.5f/r.grain).toInt()+(a.grainSeed and 63).toInt()) and 63
                val gy=(floor(y*UNIT/.5f/r.grain).toInt()+((a.grainSeed ushr 6) and 63).toInt()) and 63
                val alpha=(tile.coverage[index]*(grain[gy*64+gx].toInt() and 255)*((stroke.color ushr 24)/255f)).roundToInt().coerceIn(0,255)
                (alpha shl 24) or(stroke.color and 0xffffff)
            };tile.bitmap.setPixels(pixels,0,SIDE,0,0,SIDE,SIDE)}
            canvas.drawBitmap(tile.bitmap,null,RectF(ox,oy,ox+TILE,oy+TILE),paint)
        }
    }
}
