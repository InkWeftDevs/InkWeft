package org.inkweft.app
import android.graphics.*
import org.inkweft.core.*

/** Original repeating motifs, shared by drawing preview, stored objects and export. */
internal object TapeArt {
    private val patterns=mutableMapOf<TapePattern,Bitmap>()
    @Synchronized private fun tile(pattern:TapePattern)=patterns.getOrPut(pattern){
        Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also{b->
            val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x99ffffff.toInt();strokeWidth=1.1f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
            when(pattern){
                TapePattern.STRIPES->for(x in -32..32 step 8)c.drawLine(x.toFloat(),32f,x+32f,0f,p)
                TapePattern.GRID->{for(x in 0..32 step 8){c.drawLine(x.toFloat(),0f,x.toFloat(),32f,p);c.drawLine(0f,x.toFloat(),32f,x.toFloat(),p)}}
                TapePattern.SPARKLES->{p.style=Paint.Style.FILL;for((x,y) in listOf(8f to 8f,24f to 24f)){c.drawPath(Path().apply{moveTo(x,y-4);quadTo(x+1,y-1,x+4,y);quadTo(x+1,y+1,x,y+4);quadTo(x-1,y+1,x-4,y);quadTo(x-1,y-1,x,y-4);close()},p)}}
                TapePattern.DOTS->{p.style=Paint.Style.FILL;for((x,y) in listOf(8f to 8f,24f to 24f))c.drawCircle(x,y,1.8f,p)}
                TapePattern.WAVES->for(y in 0..32 step 8)c.drawPath(Path().apply{moveTo(0f,y.toFloat());cubicTo(8f,y-5f,8f,y+5f,16f,y.toFloat());cubicTo(24f,y-5f,24f,y+5f,32f,y.toFloat())},p)
                TapePattern.SOLID->Unit
            }
        }
    }
    fun draw(c:Canvas,o:PageObject,path:Path=ObjectGeometry.path(o)){
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=o.color or 0xff000000.toInt()}
        if(o.revealed){p.alpha=25;c.drawPath(path,p);p.alpha=100;p.style=Paint.Style.STROKE;p.strokeWidth=1f;c.drawPath(path,p);return}
        c.drawPath(path,p)
        if(o.tapePattern!=TapePattern.SOLID){
            p.color=Color.WHITE;p.shader=BitmapShader(tile(o.tapePattern),Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply{setLocalMatrix(Matrix().apply{setTranslate(o.x,o.y)})}
            c.drawPath(path,p)
        }
    }
}
