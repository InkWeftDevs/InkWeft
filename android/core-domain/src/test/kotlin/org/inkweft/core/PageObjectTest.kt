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
    @Test fun drawnTapeAndEditableShapesSurviveTransfer(){
        val tape=PageObject(UUID.randomUUID().toString(),PageObjectKind.TAPE,width=300f,height=120f,
            tapePoints=listOf(TapePoint(16f,16f),TapePoint(170f,80f),TapePoint(280f,16f)),lineWidth=32f,revealed=true)
        val shapes=ObjectShape.entries.map{PageObject(UUID.randomUUID().toString(),PageObjectKind.SHAPE,shape=it,lineWidth=3f)}
        assertEquals(listOf(tape)+shapes,PageObjectCodec.decode(PageObjectCodec.encode(listOf(tape)+shapes)))
        assertEquals(listOf(tape)+shapes,InkPageFile.decode(InkPageFile("新对象","",emptyList(),objects=listOf(tape)+shapes).encode()).objects)
        assertThrows(IllegalArgumentException::class.java){tape.copy(lineWidth=Float.NaN)}
        assertThrows(IllegalArgumentException::class.java){tape.copy(tapePoints=listOf(TapePoint(999f,0f)))}
        assertThrows(IllegalArgumentException::class.java){shapes.first().copy(tapePoints=tape.tapePoints)}
    }

    @Test fun everyTapePatternSurvivesBackupPayload(){
        val objects=TapePattern.entries.map{PageObject(UUID.randomUUID().toString(),PageObjectKind.TAPE,tapePattern=it)}
        assertEquals(objects,PageObjectCodec.decode(PageObjectCodec.encode(objects)))
        assertEquals(objects,InkPageFile.decode(InkPageFile("花纹","",emptyList(),objects=objects).encode()).objects)
    }

    @Test fun originalsRemainByteExactAcrossPageBookAndMissingClosureIsRejected(){
        val bytes=byteArrayOf(1,2,3,4,5,6)
        val original=ImageSource(bytes);bytes[0]=99
        val preview=java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,3))
        val item=PageObject(UUID.randomUUID().toString(),PageObjectKind.IMAGE,image=preview,imageSource=original.sha256)
        assertThrows(IllegalArgumentException::class.java){InkPageFile("缺原件","",emptyList(),objects=listOf(item))}
        val file=InkPageFile("原件","",emptyList(),objects=listOf(item),imageSources=listOf(original))
        val page=InkPageFile.decode(file.encode())
        assertEquals(ContentTransfer.Kind.PAGE,ContentTransfer.kind(file.encode()))
        assertEquals(item,page.objects.single());assertArrayEquals(byteArrayOf(1,2,3,4,5,6),page.imageSources.single().bytes())
        val book=NotebookFile.decode(NotebookFile("原件","",listOf(file)).encode())
        assertEquals(original.sha256,book.pages.single().imageSources.single().sha256)
        val legacy=item.copy(imageSource=null)
        assertNull(InkPageFile.decode(InkPageFile("旧预览","",emptyList(),objects=listOf(legacy)).encode()).objects.single().imageSource)
        assertThrows(IllegalArgumentException::class.java){item.copy(imageSource="not-a-hash")}
    }

}
