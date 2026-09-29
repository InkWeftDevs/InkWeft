package org.inkweft.app
import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class MixedSelectionTest {
 private fun id()=UUID.randomUUID().toString()
 private fun stroke()=InkStroke(id(),InkPen.PEN,Color.BLUE,3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(220f,140f,30)))
 private fun tape()=PageObject(id(),PageObjectKind.TAPE,100f,90f,180f,90f,tapePoints=listOf(TapePoint(20f,20f),TapePoint(150f,60f)),lineWidth=20f)
 private fun region()=InkRegion(listOf(EraserPoint(50f,50f),EraserPoint(350f,350f)))
 @Test fun scalingUsesOneCentreAndKeepsLocalMasksAndSourceIdentity(){
  val source=stroke().withCuts(listOf(InkCut(id(),2f,listOf(EraserPoint(150f,115f)))))
  val text=PageObject(id(),PageObjectKind.TEXT,300f,300f,100f,80f,text="甲",fontSize=28f,
   sourceStrokeIds=listOf(id()),glyphs=listOf(TextGlyph(0,1,10f,10f,40f,40f,color=Color.BLUE,grain=1f)),
   erasures=listOf(TextErasePath(0,1,2f,listOf(TextErasePoint(30f,20f)))))
  val tape=tape();val untouched=PageObject(id(),PageObjectKind.SHAPE,600f,600f,100f,100f)
  val picture=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
  val bytes=java.io.ByteArrayOutputStream().also{picture.compress(Bitmap.CompressFormat.JPEG,80,it)}.toByteArray();picture.recycle()
  val image=PageObject(id(),PageObjectKind.IMAGE,420f,300f,100f,100f,image=java.util.Base64.getEncoder().encodeToString(bytes))
  val shape=PageObject(id(),PageObjectKind.SHAPE,460f,500f,100f,100f,lineWidth=4f)
  val selection=CanvasSelection(region(),1,listOf(source),listOf(text,tape,image,shape),listOf(text,tape,image,shape,untouched))
  val (mutation,objects)=CanvasSelectionEdit.scaled(selection,1.1f,false)
  val scaled=(mutation as InkMutation.Replace).added.single();val glyph=objects.first()
  assertEquals(source.width*1.1f,scaled.width,0.001f);assertEquals(source.samples.first().elapsedMs,scaled.samples.first().elapsedMs)
  assertEquals((source.cuts.single().points.single().x-source.samples.first().x)*1.1f,scaled.cuts.single().points.single().x-scaled.samples.first().x,.001f)
  assertEquals(2.2f,scaled.cuts.single().radius,.001f);assertEquals(2.2f,glyph.erasures.single().radius,.001f)
  assertEquals(33f,glyph.erasures.single().points.single().x,.001f);assertEquals(44f,glyph.glyphs.single().width,.001f)
  assertEquals(text.sourceStrokeIds,glyph.sourceStrokeIds);assertEquals(Color.BLUE,glyph.glyphs.single().color)
  assertEquals((text.x-source.samples.first().x)*1.1f,glyph.x-scaled.samples.first().x,.001f)
  assertEquals(tape.lineWidth*1.1f,objects[1].lineWidth,.001f);assertEquals(tape.tapePoints.first().x*1.1f,objects[1].tapePoints.first().x,.001f)
  assertEquals(image.image,objects[2].image);assertEquals(110f,objects[2].width,.001f);assertEquals(4.4f,objects[3].lineWidth,.001f)
  assertSame(untouched,objects.last());assertEquals(1,source.cuts.size)
  val decoded=PageObjectCodec.decode(PageObjectCodec.encode(objects));assertEquals(objects,decoded)
  val rectangle=source.withCuts(listOf(InkRegion(listOf(EraserPoint(170f,110f),EraserPoint(180f,130f))).mask()))
  val shrunk=CanvasSelectionEdit.scaled(selection.copy(strokes=listOf(rectangle)),.9f,false)
  val shrunkInk=(shrunk.first as InkMutation.Replace).added.single()
  assertEquals(InkCutShape.RECTANGLE,shrunkInk.cuts.last().shape);assertEquals(.01f,shrunkInk.cuts.last().radius,0f)
  assertEquals(9f,shrunkInk.cuts.last().points.last().x-shrunkInk.cuts.last().points.first().x,.001f)
 }
 @Test fun scalingRejectsOutOfPageAndPreservesOrdinaryTextLayout(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;TextStyles.initialize(context)
  val text=PageObject(id(),PageObjectKind.TEXT,300f,300f,150f,150f,text="文字 ABC\n第二行",fontSize=28f)
  val selection=CanvasSelection(region(),1,emptyList(),listOf(text),listOf(text))
  val changed=CanvasSelectionEdit.scaled(selection,.9f,false).second.single()
  assertTrue(changed.glyphs.isEmpty());assertEquals(25.2f,changed.fontSize,.001f)
  assertEquals(TextStyles.layout(text).lineCount,TextStyles.layout(changed).lineCount)
  val sticker=text.copy(text="⭐");assertTrue(CanvasSelectionEdit.scaled(selection.copy(objects=listOf(sticker),snapshot=listOf(sticker)),1.1f,false).second.single().glyphs.isEmpty())
  val edge=text.copy(x=0f,y=0f)
  assertTrue(runCatching{CanvasSelectionEdit.scaled(selection.copy(objects=listOf(edge),snapshot=listOf(edge)),1.1f,false)}.isFailure)
  val tiny=text.copy(width=24f,height=24f)
  assertTrue(runCatching{CanvasSelectionEdit.scaled(selection.copy(objects=listOf(tiny),snapshot=listOf(tiny)),.9f,true)}.isFailure)
  assertEquals(150f,text.width,0f)
 }
 @Test fun preciseShapeSelectionMatchesClippedPaintedBorder(){
  val shape=PageObject(id(),PageObjectKind.SHAPE,100f,100f,100f,100f,lineWidth=20f)
  val region=InkRegion(listOf(EraserPoint(100f,100f),EraserPoint(200f,200f)))
  assertTrue(VisibleInkGeometry().selects(region,shape,true))
  assertFalse(VisibleInkGeometry().selects(InkRegion(listOf(EraserPoint(80f,120f),EraserPoint(99f,180f))),shape))
 }
 @Test fun filtersAndPreciseModeSelectPaintedContent(){
  val ink=stroke();val tape=tape();val shape=PageObject(id(),PageObjectKind.SHAPE,110f,110f,140f,140f,lineWidth=4f)
  val all=CanvasSelectionEdit.query(region(),1,listOf(ink),listOf(tape,shape),SelectionOptions(),VisibleInkGeometry())
  assertEquals(3,all.count)
  val only=CanvasSelectionEdit.query(region(),1,listOf(ink),listOf(tape,shape),SelectionOptions(setOf(SelectionType.TAPE)),VisibleInkGeometry());assertEquals(listOf(tape),only.objects);assertTrue(only.strokes.isEmpty())
  val crossing=InkRegion(listOf(EraserPoint(90f,90f),EraserPoint(130f,150f)))
  val geometry=VisibleInkGeometry();assertTrue(geometry.selects(crossing,ink));assertFalse(geometry.selects(crossing,ink,true));assertTrue(geometry.selects(region(),ink,true))
  val center=InkRegion(listOf(EraserPoint(160f,160f),EraserPoint(180f,180f)));assertFalse(geometry.selects(center,shape))
 }
 @Test fun mixedMoveDeleteUndoAndRedoCommitTogether(){runBlocking{
  val ins=InstrumentationRegistry.getInstrumentation();val app=ins.targetContext.applicationContext as InkWeftApplication
  val note=app.workspaceRepository.create("V34 混合事务测试",false,PaperStyle.BLANK);val source=stroke();val tape=tape()
  app.inkRepository.save(CommitInk(id(),note.id,0,InkMutation.Add(source)));app.pageObjects.save(note.id,0,id(),listOf(tape))
  lateinit var ink:InkViewModel;lateinit var objects:PageObjectViewModel;lateinit var writer:ContinuousInkWriter
  ins.runOnMainSync{ink=InkViewModel(note.id,app.inkRepository);objects=PageObjectViewModel(note.id,app.pageObjects);objects.history=ink.history;writer=ContinuousInkWriter()}
  withTimeout(10000){while(ink.ui.value.loading||objects.ui.value.loading)delay(20)}
  val selection=CanvasSelectionEdit.query(region(),ink.ui.value.revision,ink.ui.value.strokes,objects.ui.value.objects,SelectionOptions(),VisibleInkGeometry())
  val moved=CanvasSelectionEdit.moved(selection,40f,60f,false,false)
  ins.runOnMainSync{assertTrue(writer.edit(ink,objects,selection.revision,selection.snapshot,moved.first,moved.second,app.inkRepository))}
  suspend fun settled(){withTimeout(10000){while(writer.blocked.value)delay(20)}}
  settled();assertEquals(140f,ink.ui.value.strokes.single().samples.first().x);assertEquals(140f,objects.ui.value.objects.single().x)
  ins.runOnMainSync{objects.undo()};settled();assertEquals(100f,ink.ui.value.strokes.single().samples.first().x);assertEquals(100f,objects.ui.value.objects.single().x)
  ins.runOnMainSync{ink.redo()};settled();assertEquals(140f,ink.ui.value.strokes.single().samples.first().x);assertEquals(140f,objects.ui.value.objects.single().x)
  val current=CanvasSelectionEdit.query(region(),ink.ui.value.revision,ink.ui.value.strokes,objects.ui.value.objects,SelectionOptions(),VisibleInkGeometry());val deleted=CanvasSelectionEdit.deleted(current)
  ins.runOnMainSync{assertFalse(writer.edit(ink,objects,current.revision-1,current.snapshot,deleted.first,deleted.second,app.inkRepository));assertTrue(writer.edit(ink,objects,current.revision,current.snapshot,deleted.first,deleted.second,app.inkRepository))}
  settled();assertTrue(ink.ui.value.strokes.isEmpty());assertTrue(objects.ui.value.objects.isEmpty())
  ins.runOnMainSync{objects.undo()};settled();assertEquals(1,ink.ui.value.strokes.size);assertEquals(1,objects.ui.value.objects.size)
  assertEquals(140f,InkSession(app.inkRepository.read(note.id)).visibleDraft().single().samples.first().x);assertEquals(140f,app.pageObjects.read(note.id).objects.single().x)
  val resizeSelection=CanvasSelectionEdit.query(region(),ink.ui.value.revision,ink.ui.value.strokes,objects.ui.value.objects,SelectionOptions(),VisibleInkGeometry())
  val resize=CanvasSelectionEdit.scaled(resizeSelection,1.1f,false)
  ins.runOnMainSync{assertTrue(writer.edit(ink,objects,resizeSelection.revision,resizeSelection.snapshot,resize.first,resize.second,app.inkRepository))};settled()
  assertEquals(198f,objects.ui.value.objects.single().width,.001f);assertEquals(3.3f,ink.ui.value.strokes.single().width,.001f)
  ins.runOnMainSync{objects.undo()};settled();assertEquals(180f,objects.ui.value.objects.single().width,.001f);assertEquals(3f,ink.ui.value.strokes.single().width,.001f)
  ins.runOnMainSync{ink.redo()};settled();assertEquals(198f,app.pageObjects.read(note.id).objects.single().width,.001f)
  ins.runOnMainSync{objects.undo()};settled()
  val beforeInk=ink.ui.value.strokes.single().id
  ins.runOnMainSync{writer.erase(listOf(PageEraseTarget(ink,objects,listOf(InkSample(160f,170f,0)))),12f,false,false,app.inkRepository,true)}
  settled();assertTrue(objects.ui.value.objects.isEmpty());assertEquals(beforeInk,ink.ui.value.strokes.single().id)
  ins.runOnMainSync{objects.undo()};withTimeout(10000){while(objects.ui.value.busy)delay(20)};assertEquals(1,objects.ui.value.objects.size)
 }}
 @Test fun copiesHaveIndependentIdentityAndBeautyAppearance(){
  val source=stroke();val text=beautyObject(listOf(source),"一",BeautyOptions(),false)
  val selection=CanvasSelection(region(),1,emptyList(),listOf(text),listOf(text),listOf(source))
  val copied=CanvasSelectionEdit.moved(selection,24f,24f,true,false).second.last()
  assertNotEquals(text.id,copied.id);assertTrue(copied.sourceStrokeIds.isEmpty());assertEquals(text.x+24,copied.x);assertEquals(Color.BLUE,copied.glyphs.single().color)
 }
 @Test fun tapeOnlyPreviewDoesNotEraseInkAndCancelRestoresTape(){
  val ins=InstrumentationRegistry.getInstrumentation();ins.runOnMainSync{
   val context=ins.targetContext;val v=InkCanvasView(context);v.configure(false,PaperStyle.BLANK,CanvasViewport(150.0,150.0,1.0/context.resources.displayMetrics.density));v.layout(0,0,300,300)
   val tape=PageObject(id(),PageObjectKind.TAPE,60f,80f,100f,40f,tapePattern=TapePattern.SOLID,color=Color.RED);val source=stroke()
   v.showObjects(listOf(tape));v.showStrokes(listOf(source));v.allowInput=true;v.eraseMode=true;v.eraserTapeOnly=true;v.eraserWhole=false
   fun pixels():IntArray{val b=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));return IntArray(90000).also{b.getPixels(it,0,300,0,0,300,300);b.recycle()}}
   val before=pixels();val time=SystemClock.uptimeMillis()
   fun event(action:Int,x:Float,y:Float,t:Long){val p=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val c=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=.5f};val e=MotionEvent.obtain(time,time+t,action,1,arrayOf(p),arrayOf(c),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);v.dispatchTouchEvent(e);e.recycle()}
   event(MotionEvent.ACTION_DOWN,80f,85f,0);event(MotionEvent.ACTION_MOVE,80f,110f,20);val erased=pixels();assertEquals(Color.RED,before[100*300+140]);assertEquals(Color.WHITE,erased[100*300+140]);assertEquals(before[140*300+220],erased[140*300+220])
   event(MotionEvent.ACTION_CANCEL,80f,110f,40);assertArrayEquals(before,pixels())
  }
 }
 @Test fun tapeHitTestUsesPathInsteadOfLargeBoundingBox(){
  val tape=tape();assertFalse(ObjectGeometry.intersectsTape(tape,listOf(InkSample(270f,95f,0)),1f));assertTrue(ObjectGeometry.intersectsTape(tape,listOf(InkSample(170f,126f,0)),4f))
 }
}
