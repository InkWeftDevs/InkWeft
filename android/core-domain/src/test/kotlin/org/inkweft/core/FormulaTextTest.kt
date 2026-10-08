package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FormulaTextTest {
    @Test fun formulaSourceMasksAndOriginalInkSurvivePageAndBookTransfers(){
        val source=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(100f,200f,0),InkSample(140f,240f,10)))
        val formula=PageObject(UUID.randomUUID().toString(),PageObjectKind.FORMULA,text="\\frac{1}{\\sin\\alpha}=\\csc\\alpha\n\\sin^2\\alpha+\\cos^2\\alpha=1",sourceStrokeIds=listOf(source.id),erasures=listOf(TextErasePath(0,8,3f,listOf(TextErasePoint(12f,20f)))))
        val page=InkPageFile("公式","",listOf(source),objects=listOf(formula))
        val restored=InkPageFile.decode(page.encode())
        assertEquals(formula,restored.objects.single());assertArrayEquals(InkStrokeCodec.encode(source),InkStrokeCodec.encode(restored.strokes.single()))
        assertEquals(formula,NotebookFile.decode(NotebookFile("公式","",listOf(page)).encode()).pages.single().objects.single())
        assertEquals(formula.text,formula.visibleText());assertEquals("",formula.copy(hidden=true).visibleText())
    }
    @Test fun incompleteOrExecutableTexCannotBecomeAuthorData(){
        for(text in listOf("", "x^{2", "x}", "\\includegraphics{/data/secret}","\\newcommand{\\a}{\\a}\\a","{".repeat(33)+"x"+"}".repeat(33))){
            assertThrows(text,IllegalArgumentException::class.java){PageObject(UUID.randomUUID().toString(),PageObjectKind.FORMULA,text=text)}
        }
        FormulaText.validate("\\sqrt{x_1^2}+\\frac{a}{b}\\leq\\alpha")
    }
}
