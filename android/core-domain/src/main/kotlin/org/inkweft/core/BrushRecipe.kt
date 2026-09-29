// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import kotlin.math.*

/** Author data, never a reference to the mutable pen box. Version 0 freezes v24. */
data class BrushRecipe(val version:Int=1,val sensitivity:Float=.5f,val sharpness:Float=.35f,
    val roundNib:Boolean=false,val hardness:Int=1,val density:Float=1f,val grain:Float=1f,val tiltShading:Boolean=true) {
    init { require(version in 0..1);require(sensitivity.isFinite()&&sensitivity in 0f..1f);require(sharpness.isFinite()&&sharpness in 0f..1f)
        require(hardness in 0..2);require(density.isFinite()&&density in .5f..1.3f);require(grain.isFinite()&&grain in .5f..2f) }
    val minimumWidth get()=.65f-.5f*sensitivity
    val pressureExponent get()=1.2f-.6f*sensitivity
    val hardnessFactor get()=listOf(.8f,1f,1.15f)[hardness]
    fun write(out:DataOutputStream){out.writeInt(version);out.writeFloat(sensitivity);out.writeFloat(sharpness);out.writeBoolean(roundNib);out.writeInt(hardness);out.writeFloat(density);out.writeFloat(grain);out.writeBoolean(tiltShading)}
    fun encode():ByteArray=ByteArrayOutputStream().also{b->DataOutputStream(b).use(::write)}.toByteArray()
    companion object {
        val LEGACY=BrushRecipe(version=0)
        fun read(input:DataInputStream)=BrushRecipe(input.readInt(),input.readFloat(),input.readFloat(),input.readBoolean(),input.readInt(),input.readFloat(),input.readFloat(),input.readBoolean())
        fun decode(bytes:ByteArray):BrushRecipe=DataInputStream(ByteArrayInputStream(bytes)).use{val r=read(it);require(it.available()==0);r}
    }
}

/** The seed chooses a phase in the shared, immutable graphite field. Translating a
 * stroke translates its material origin; changing its identity never changes it. */
data class StrokeAppearance(val recipe:BrushRecipe=BrushRecipe.LEGACY,val grainSeed:Long=0,
    val originX:Float=0f,val originY:Float=0f,val leading:InkSample?=null,val trailing:InkSample?=null) {
    init {require(originX.isFinite()&&originY.isFinite());require(abs(originX)<=BoardLimits.WORLD*2&&abs(originY)<=BoardLimits.WORLD*2)}
    fun translated(dx:Float,dy:Float)=copy(originX=originX+dx,originY=originY+dy,
        leading=leading?.copy(x=leading.x+dx,y=leading.y+dy,world=true),trailing=trailing?.copy(x=trailing.x+dx,y=trailing.y+dy,world=true))
    fun write(out:DataOutputStream){recipe.write(out);out.writeLong(grainSeed);out.writeFloat(originX);out.writeFloat(originY);out.writeUTF(if(recipe.version==1)GraphiteMaterial.digest else "legacy-v24")
        listOf(leading,trailing).forEach{p->out.writeBoolean(p!=null);if(p!=null){out.writeFloat(p.x);out.writeFloat(p.y);out.writeLong(p.elapsedMs);out.writeFloat(p.pressure);out.writeFloat(p.tilt);out.writeFloat(p.orientation)}}}
    companion object {
        fun read(input:DataInputStream):StrokeAppearance {
            val value=StrokeAppearance(BrushRecipe.read(input),input.readLong(),input.readFloat(),input.readFloat())
            require(input.readUTF()==if(value.recipe.version==1)GraphiteMaterial.digest else "legacy-v24"){"Unknown brush material; retain original bytes"}
            fun context()=if(input.readBoolean())InkSample(input.readFloat(),input.readFloat(),input.readLong(),input.readFloat(),input.readFloat(),input.readFloat(),true)else null
            return value.copy(leading=context(),trailing=context())
        }
    }
}

/** Resource v1: 64×64 alpha, integer-only generator, 16 KiB shared RGBA on device. */
object GraphiteMaterial {
    const val SIZE=64
    fun alpha():ByteArray {
        var state=0x4f1bbcdc
        return ByteArray(SIZE*SIZE){state=state xor(state shl 13);state=state xor(state ushr 17);state=state xor(state shl 5)
            val n=(state ushr 24) and 255;(72+(n*n*183/65025)).toByte()}
    }
    val digest:String by lazy {MessageDigest.getInstance("SHA-256").digest(alpha()).joinToString(""){"%02x".format(it.toInt() and 255)}}
}

fun InkStroke.coverageRadius():Float = width/2 * if(pen==InkPen.PENCIL&&appearance.recipe.tiltShading&&samples.first().tilt>=0)4f else 1f

/** Adjacent points preserve the direction/density at a physical page edge. They
 * are render context, clipped to this page, never additional editable content. */
fun InkStroke.renderSamples():List<InkSample> {
    if(appearance.leading==null&&appearance.trailing==null)return samples
    // The edge samples are synthetic intersections. Replace them with original
    // neighbours for rendering, otherwise caps at those intersections deposit
    // extra graphite and introduce a pressure/tilt discontinuity at the seam.
    val body=samples.drop(if(appearance.leading!=null)1 else 0).dropLast(if(appearance.trailing!=null)1 else 0)
    return (listOfNotNull(appearance.leading)+body.map{it.copy(world=true)}+listOfNotNull(appearance.trailing))
        .fold(mutableListOf()){out,p->if(out.lastOrNull()!=p)out.add(p);out}
}
