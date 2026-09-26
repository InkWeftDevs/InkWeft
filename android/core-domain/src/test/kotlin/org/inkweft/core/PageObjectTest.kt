package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageObjectTest {
    private fun text()=PageObject(UUID.randomUUID().toString(),PageObjectKind.TEXT,text="中文文本\n第二行")
    @Test fun contentRoundTripRetainsObjectGeometryTextAndTapeState(){
        val objects=listOf(text(),PageObject(UUID.randomUUID().toString(),PageObjectKind.TAPE,revealed=true))
        val file=InkPageFile("对象页面","",emptyList(),objects=objects)
        assertEquals(objects,InkPageFile.decode(file.encode()).objects)
        assertEquals(ContentTransfer.Kind.PAGE,ContentTransfer.kind(file.encode()))
    }
    @Test fun rejectsDuplicateIdsAndTrailingBytes(){
        val item=text()
        assertThrows(IllegalArgumentException::class.java){PageObjectCodec.encode(listOf(item,item))}
        assertThrows(IllegalArgumentException::class.java){PageObjectCodec.decode(PageObjectCodec.encode(listOf(item))+byteArrayOf(0))}
    }
    @Test fun rejectsNonFiniteCoordinatesAndObjectBudget(){
        assertThrows(IllegalArgumentException::class.java){text().copy(x=Float.NaN)}
        assertThrows(IllegalArgumentException::class.java){text().copy(width=0f)}
        assertThrows(IllegalArgumentException::class.java){PageObjectCodec.encode(List(33){text()})}
    }
    @Test fun pageCopyDetectsObjectCorruption(){
        val bytes=InkPageFile("完整性","",emptyList(),objects=listOf(text())).encode()
        bytes[bytes.size-40]=(bytes[bytes.size-40].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){InkPageFile.decode(bytes)}
    }
}
