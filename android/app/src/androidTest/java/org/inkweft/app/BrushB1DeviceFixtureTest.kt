package org.inkweft.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.io.File

/** Explicit opt-in only: adds a named synthetic comparison without touching old notes. */
class BrushB1DeviceFixtureTest {
    @Test fun createComparison()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inkweftSeedBrushB1")=="true")
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>();fun id()=UUID.randomUUID().toString()
        val note=app.workspaceRepository.create("InkWeft-v25-B1",false,PaperStyle.BLANK)
        val kinds=listOf(InkPen.BALLPOINT,InkPen.PEN,InkPen.PENCIL,InkPen.PENCIL,InkPen.PENCIL,InkPen.BRUSH)
        val labels=listOf("圆珠笔","钢笔","铅笔 · 轻压","铅笔 · 重压","铅笔 · 倾斜","毛笔")
        kinds.forEachIndexed{i,kind->
            val s=InkStroke(id(),kind,0xff161616.toInt(),8f,InkTool.STYLUS,(0..100).map{n->InkSample(300+n*4f,180+i*130+24*kotlin.math.sin(n*.08f),n*8L,
                if(i==2).08f else if(i==3||i==4).85f else .05f+.8f*n/100f,if(i==4)1.1f else -1f)},appearance=StrokeAppearance(BrushRecipe(),123,300f,180+i*130f))
            app.inkRepository.save(CommitInk(id(),note.id,i.toLong(),InkMutation.Add(s)))
        }
        val objects=labels.mapIndexed{i,label->PageObject(id(),PageObjectKind.TEXT,x=60f,y=150+i*130f,width=240f,height=50f,text=label,fontSize=22f)}+
            PageObject(id(),PageObjectKind.TEXT,x=60f,y=50f,width=830f,height=50f,text="B1 合成输入对比 · 相同颜色与基础宽度",fontSize=22f)
        app.pageObjects.save(note.id,0,id(),objects)
        File(app.getExternalFilesDir(null),"brush-b1-fixture-id.txt").writeText(note.id)
    }
}
