// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.net.Uri
import org.inkweft.core.CustomCoverCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object CoverImages {
    /** Decode at cover resolution, honour EXIF, flatten alpha, omit original metadata. */
    fun read(context:Context,uri:Uri):ByteArray {
        val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=20*1024*1024){"图片超过 20 MB，请先缩小。"};out.write(buffer,0,n)};out.toByteArray()
        }
        return normalize(bytes)
    }
    fun normalize(bytes:ByteArray):ByteArray{
        require(bytes.isNotEmpty()&&bytes.size<=20*1024*1024)
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,info,_->
            require(info.mimeType in listOf("image/jpeg","image/png","image/webp","image/heif","image/heic")){"请选择 JPG、PNG、WebP 或 HEIF 静态图片。"}
            val w=info.size.width;val h=info.size.height
            require(w>0&&h>0&&w<=20000&&h<=20000&&w.toLong()*h<=100_000_000){"图片尺寸过大，请先缩小。"}
            val scale=minOf(1.0,1024.0/maxOf(w,h));decoder.setTargetSize(maxOf(1,(w*scale).toInt()),maxOf(1,(h*scale).toInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try{
            val flattened=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
            try{
                Canvas(flattened).apply{drawColor(Color.WHITE);drawBitmap(bitmap,0f,0f,null)}
                for(quality in listOf(88,75,60,45,30)){
                    val out=ByteArrayOutputStream();check(flattened.compress(Bitmap.CompressFormat.JPEG,quality,out))
                    if(out.size()<=CustomCoverCodec.MAX_IMAGE)return out.toByteArray()
                }
                error("图片细节过多，请裁剪后重试。")
            }finally{flattened.recycle()}
        }finally{bitmap.recycle()}
    }
}
