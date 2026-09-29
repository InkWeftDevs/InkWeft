package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

class PaperTemplatesTest {
    private val bounds=CanvasBounds(0.0,0.0,1000.0,1414.0)
    @Test fun structuredGeometryStaysOnPageAndHasUsefulWritingRegions(){
        PaperStyle.entries.drop(10).forEach{s->val g=PaperTemplates.guides(s,bounds,false,1.0)
            assertTrue(s.name,g.lines.isNotEmpty());assertTrue(g.lines.size<2500)
            g.lines.forEach{assertTrue(s.name,it.x1 in 0f..1000f&&it.x2 in 0f..1000f&&it.y1 in 0f..1414f&&it.y2 in 0f..1414f)}
            g.fills.forEach{assertTrue(it.x>=0&&it.y>=0&&it.x+it.width<=1000&&it.y+it.height<=1414)}
            assertTrue(g.labels.any{it.text==PaperTemplates.title(s)})
        }
    }
    @Test fun allNewTemplatesRoundTripInEditableContentFiles(){PaperStyle.entries.drop(10).forEach{s->
        val restored=InkPageFile.decode(InkPageFile("模板","内容",emptyList(),paper=s).encode())
        assertEquals(s,restored.paper);assertEquals("内容",restored.text)}}
    @Test fun weeklyAndMonthlyAreUndatedAndHabitHasExactlyThirtyCells(){
        val habit=PaperTemplates.guides(PaperStyle.HABIT,bounds,false,1.0)
        assertEquals((1..30).map{it.toString()},habit.labels.map{it.text}.filter{it.toIntOrNull()!=null})
        val month=PaperTemplates.guides(PaperStyle.MONTHLY,bounds,false,1.0)
        assertEquals(7,month.labels.count{it.text.startsWith("周")});assertFalse(month.labels.any{it.text.toIntOrNull()!=null})
    }
    @Test fun categoriesAndDescriptionsAreSearchable(){
        assertTrue(PaperTemplates.matches(PaperStyle.MEETING,"工作","负责人"))
        assertFalse(PaperTemplates.matches(PaperStyle.MEETING,"生活",""))
        assertTrue(PaperTemplates.matches(PaperStyle.HABIT,"全部"," 打卡 "))
    }
    @Test fun structuredTemplatesNeverRepeatAcrossInfiniteCanvas(){
        val g=PaperTemplates.guides(PaperStyle.MONTHLY,CanvasBounds(-100000.0,-100000.0,100000.0,100000.0),true,.02)
        assertTrue(g.labels.isEmpty());assertTrue(g.fills.isEmpty());assertTrue(g.dots.size<=4096)
    }
    @Test fun musicHasTwelveGroupsOfFiveLines(){
        assertEquals(60,PaperTemplates.guides(PaperStyle.MUSIC,bounds,false,1.0).lines.count{it.y1>=180})
    }
}
