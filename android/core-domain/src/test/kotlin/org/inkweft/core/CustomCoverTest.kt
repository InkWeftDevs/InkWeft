package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

class CustomCoverTest {
    @Test fun allLayoutsRoundTripWithoutChangingImageOrCrop(){
        CoverLayout.entries.forEach{layout->val c=CustomCover(0xff234567.toInt(),layout,"课程封面","2026 秋",false,2.4f,.1f,.9f,byteArrayOf(1,2,3))
            val actual=CustomCoverCodec.decode(CustomCoverCodec.encode(c));assertEquals(c.copy(image=actual.image),actual);assertArrayEquals(c.image,actual.image)}
    }
    @Test fun rejectsInvalidColorsCoordinatesAndOversizePayloads(){
        for(c in listOf(CustomCover(color=0),CustomCover(zoom=Float.NaN),CustomCover(focusX=-.1f),CustomCover(zoom=4f),CustomCover(title="x".repeat(81)),CustomCover(subtitle="bad\nline"),CustomCover(image=ByteArray(CustomCoverCodec.MAX_IMAGE+1)))){
            assertThrows(IllegalArgumentException::class.java){CustomCoverCodec.encode(c)}
        }
    }
    @Test fun rejectsTruncatedOrTrailingData(){
        val bytes=CustomCoverCodec.encode(CustomCover(title="封面"))
        assertThrows(Exception::class.java){CustomCoverCodec.decode(bytes.copyOf(bytes.size-1))}
        assertThrows(IllegalArgumentException::class.java){CustomCoverCodec.decode(bytes+byteArrayOf(1))}
    }
    @Test fun binaryImageIsCopiedAndBlankTitleMeansFollowNotebook(){
        val original=byteArrayOf(1,2,3);val bytes=CustomCoverCodec.encode(CustomCover(image=original));original[0]=9
        val c=CustomCoverCodec.decode(bytes);assertEquals("",c.title);assertEquals(1,c.image[0].toInt())
    }
}
