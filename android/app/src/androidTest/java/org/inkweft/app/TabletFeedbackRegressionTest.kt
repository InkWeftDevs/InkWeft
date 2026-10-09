// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.net.Uri
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TabletFeedbackRegressionTest {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=ins.targetContext
 private val app get()=context.applicationContext as InkWeftApplication
 private fun id()=UUID.randomUUID().toString()
 private fun line(x:Float=100f,y:Float=200f,world:Boolean=false,pen:InkPen=InkPen.PEN)=InkStroke(id(),pen,Color.BLACK,4f,InkTool.STYLUS,
  listOf(InkSample(x,y,0,.7f,world=world),InkSample(x+40,y+30,50,.7f,world=world)),world)
 private suspend fun ready(vm:PageObjectViewModel){withTimeout(10000){while(vm.ui.value.loading||vm.ui.value.busy||vm.ui.value.pending)delay(20)}}
 private fun png(color:Int):ByteArray {
  val bitmap=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
  return try{java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()}finally{bitmap.recycle()}
 }
 @Test fun largePageMoveEraseWriteUndoAndReopenRetainOriginalBytes()=runBlocking {
  val name="capacity-${id()}.db";var db=NoteDatabase.open(context,name)
  try{
   var repo=InkRepository(db)
   val originals=List(InkLimits.MAX_STROKES){line(30f+(it%15)*50,30f+(it%20)*55)}
   val note=repo.importCopy(InkPageFile("容量回归","",originals))
   var page=repo.read(note.id);val before=page.strokes.associate{it.stroke.id to InkStrokeCodec.encode(it.stroke)}
   val moving=page.strokes.take(4).map{it.stroke};val moved=InkSelectionEdit.copy(moving,10f,10f)
   suspend fun save(change:InkMutation){assertEquals(InkCommitResult.Committed(page.revision+1),repo.save(CommitInk(id(),note.id,page.revision,change)));page=repo.read(note.id)}
   save(InkMutation.Replace(moving.map{it.id},moved,moving.map{it.id}))
   assertEquals(InkLimits.MAX_STROKES+4,page.strokes.size)
   assertEquals(InkLimits.MAX_STROKES,page.strokes.count{it.visible})
   val erased=page.strokes.filter{it.visible}.take(1001).map{it.stroke.id}
   save(InkMutation.Visibility(erased,false)) // More than SQLite's conservative bind limit.
   val fresh=line();save(InkMutation.Add(fresh));assertTrue(InkSession(page).canStart)
   save(InkMutation.Visibility(listOf(fresh.id),false));save(InkMutation.Visibility(erased,true))
   save(InkMutation.Swap(moved.map{it.id},moving.map{it.id}))
   db.close();db=NoteDatabase.open(context,name);repo=InkRepository(db);page=repo.read(note.id)
   assertEquals(before.keys,page.strokes.filter{it.visible}.map{it.stroke.id}.toSet())
   page.strokes.filter{it.stroke.id in before}.forEach{assertArrayEquals(before[it.stroke.id],InkStrokeCodec.encode(it.stroke))}
   val exported=InkPageFile.decode(InkPageFile("容量回归","",InkSession(page).visibleDraft()).encode())
   assertEquals(InkLimits.MAX_STROKES,exported.strokes.size)
   val restoredName="capacity-restored-${id()}.db";val restored=NoteDatabase.open(context,restoredName)
   try{
    LibraryBackupRepository(context,db).snapshot().use{snapshot->
     val backup=LibraryBackupRepository(context,restored)
     snapshot.file.inputStream().use{backup.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,backup.restore(it))}
    }
    val copy=InkRepository(restored).read(note.id);assertEquals(page.revision,copy.revision);assertEquals(page.strokes.size,copy.strokes.size)
    page.strokes.zip(copy.strokes).forEach{(a,b)->assertEquals(a.visible,b.visible);assertArrayEquals(InkStrokeCodec.encode(a.stroke),InkStrokeCodec.encode(b.stroke))}
   }finally{restored.close();context.deleteDatabase(restoredName)}
  }finally{db.close();context.deleteDatabase(name)}
 }

 @Test fun manualBeautyKeepsBoundaryNeighbourAfterSaveAndReopen()=runBlocking {
  TextStyles.initialize(context)
  val name="beauty-neighbour-${id()}.db";var db=NoteDatabase.open(context,name)
  try{
   val note=InkRepository(db).importCopy(InkPageFile("邻近笔迹","",listOf(line(),line(158f))))
   val page=InkRepository(db).read(note.id);val ink=InkSession(page).visibleDraft();val neighbour=ink.last()
   val before=InkStrokeCodec.encode(neighbour);val region=InkRegion(listOf(EraserPoint(90f,190f),EraserPoint(165f,245f)))
   val geometry=VisibleInkGeometry();assertTrue(geometry.selects(region,neighbour));assertFalse(geometry.selects(region,neighbour,true))
   lateinit var vm:PageObjectViewModel;var accepted:PageObject?=null;var supplied=emptyList<String>()
   ins.runOnMainSync{vm=PageObjectViewModel(note.id,PageObjectRepository(db)){sources,_,_->
    supplied=sources.map{it.id};RecognizedWriting("甲",.95f,1,listOf(RecognizedLine("甲",sources.single().bounds(),supplied,listOf(RecognizedToken("甲",.5f,.95f)),.95f)))
   }}
   ready(vm)
   ins.runOnMainSync{
    vm.observeBeauty(InkUi(strokes=ink,loading=false,revision=page.revision),false,BeautyOptions(),false,app)
    vm.beautify(SelectedInk(region,page.revision,ink),BeautyOptions(),false,app)
   }
   withTimeout(10000){while(vm.beautyReview.value==null)delay(20)}
   assertEquals(listOf(ink.first().id),supplied)
   ins.runOnMainSync{vm.acceptBeauty{accepted=it}}
   ready(vm);assertNotNull(accepted);assertEquals(supplied,accepted!!.sourceStrokeIds)
   db.close();db=NoteDatabase.open(context,name)
   val reopened=PageObjectRepository(db).read(note.id);val stored=InkRepository(db).read(note.id)
   assertFalse(reopened.objects.flatMap{it.sourceStrokeIds}.contains(neighbour.id))
   assertArrayEquals(before,InkStrokeCodec.encode(stored.strokes.first{it.stroke.id==neighbour.id}.stroke))
   assertTrue(stored.strokes.first{it.stroke.id==neighbour.id}.visible)
  }finally{db.close();context.deleteDatabase(name)}
 }

 @Test fun multipleImagesCommitTogetherUndoTogetherAndRejectBadBatch()=runBlocking {
  val name="image-batch-${id()}.db";val db=NoteDatabase.open(context,name);val folder=File(context.cacheDir,"image-batch-${id()}").apply{mkdirs()}
  try{
   val repo=PageObjectRepository(db);val note=InkRepository(db).importCopy(InkPageFile("多图导入","",emptyList()))
   val files=listOf(File(folder,"one.png").apply{writeBytes(png(Color.RED))},File(folder,"two.png").apply{writeBytes(png(Color.BLUE))})
   lateinit var vm:PageObjectViewModel;var selected:String?=null
   ins.runOnMainSync{vm=PageObjectViewModel(note.id,repo)};ready(vm)
   val before=repo.read(note.id).revision
   ins.runOnMainSync{vm.importImages(context,files.map{Uri.fromFile(it)},false,CanvasViewport(500.0,707.0,1.0)){selected=it}}
   ready(vm);val saved=repo.read(note.id)
   assertEquals(before+1,saved.revision);assertEquals(2,saved.objects.size);assertEquals(saved.objects.last().id,selected)
   assertEquals(files.map{ImageSource(it.readBytes()).sha256}.toSet(),repo.originals(note.id,saved.objects).map{it.sha256}.toSet())
   assertFalse(saved.objects[0].bounds().intersects(saved.objects[1].bounds()))
   assertEquals(saved.objects,PageObjectRepository(db).read(note.id).objects)
   ins.runOnMainSync{vm.undo()};ready(vm);assertTrue(repo.read(note.id).objects.isEmpty())
   ins.runOnMainSync{vm.redo()};ready(vm);assertEquals(saved.objects,repo.read(note.id).objects)
   val revision=repo.read(note.id).revision
   ins.runOnMainSync{vm.importImages(context,listOf(Uri.fromFile(files.first()),Uri.fromFile(File(folder,"missing.png"))),false,CanvasViewport())}
   ready(vm);assertNotNull(vm.ui.value.error);assertEquals(revision,repo.read(note.id).revision);assertEquals(saved.objects,repo.read(note.id).objects)
   val picker=androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents().createIntent(context,"image/*")
   assertTrue(picker.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE,false))
  }finally{db.close();context.deleteDatabase(name);folder.listFiles()?.forEach{it.delete()};folder.delete()}
 }

 @Test fun convertedTextStaysAboveLaterImageAndHitTestingMatches(){
  ins.runOnMainSync{
   TextStyles.initialize(context)
   val text=PageObject(id(),PageObjectKind.TEXT,40f,40f,210f,90f,text="ABC",fontSize=48f,color=Color.BLUE)
   val image=PageObject(id(),PageObjectKind.IMAGE,0f,0f,300f,200f,image=java.util.Base64.getEncoder().encodeToString(png(Color.RED)))
   val painter=PageObjectPainter();val a=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888);val b=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888)
   try{
    val extent=CanvasBounds(0.0,0.0,300.0,200.0)
    painter.draw(Canvas(a),listOf(text,image),false,extent);painter.draw(Canvas(b),listOf(image,text),false,extent)
    val x=IntArray(60000);val y=IntArray(x.size);a.getPixels(x,0,300,0,0,300,200);b.getPixels(y,0,300,0,0,300,200)
    assertArrayEquals(y,x);assertTrue(x.count{Color.blue(it)>200&&Color.red(it)<100}>100)
    val view=InkCanvasView(context).apply{configure(true,PaperStyle.BLANK,CanvasViewport(150.0,100.0,1.0/context.resources.displayMetrics.density));layout(0,0,300,200);showObjects(listOf(text,image))}
    assertEquals(text.id,view.imageAt(60f,60f))
   }finally{painter.clear();a.recycle();b.recycle()}
  }
 }

 @Test fun fingerPansWhileStylusSelectsAndWholeErasePreviewsBeforeUp(){
  ins.runOnMainSync{
   for(world in listOf(false,true)){
    val view=InkCanvasView(context).apply{configure(world,PaperStyle.BLANK,CanvasViewport(300.0,300.0,1.0/context.resources.displayMetrics.density));layout(0,0,600,600)}
    val selection=SelectionOverlayView(context).apply{canvasView=view;layout(0,0,600,600);enabledInput=true}
    var regions=0;selection.onRegion={if(it!=null)regions++}
    fun event(target:android.view.View,action:Int,x:Float,y:Float,tool:Int=MotionEvent.TOOL_TYPE_FINGER){
     val e=MotionEvent.obtain(0,10L+action,action,1,arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=tool}),arrayOf(MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f}),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
     try{target.onTouchEvent(e)}finally{e.recycle()}
    }
    val original=view.snapshotViewport()
    event(selection,MotionEvent.ACTION_DOWN,100f,100f);event(selection,MotionEvent.ACTION_MOVE,40f,40f);event(selection,MotionEvent.ACTION_UP,40f,40f)
    assertEquals(0,regions);assertNotEquals(original,view.snapshotViewport())
    event(selection,MotionEvent.ACTION_DOWN,100f,100f,MotionEvent.TOOL_TYPE_STYLUS);event(selection,MotionEvent.ACTION_MOVE,160f,160f,MotionEvent.TOOL_TYPE_STYLUS);event(selection,MotionEvent.ACTION_UP,160f,160f,MotionEvent.TOOL_TYPE_STYLUS)
    assertEquals(1,regions)
    val ink=listOf(line(240f,250f,world),line(380f,400f,world));view.showStrokes(ink)
    view.allowInput=true;view.eraseMode=true;view.eraserWhole=true;view.eraserDiameterDp=12f;var committed=emptyList<String>()
    view.onErase={_,_,_,_->committed=view.wholeEraseHitIds;view.showStrokes(ink.filter{it.id !in committed})}
    val vp=view.snapshotViewport();val start=vp.worldToScreen(250.0,257.5,600.0,600.0,context.resources.displayMetrics.density.toDouble())
    val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
    try{
     event(view,MotionEvent.ACTION_DOWN,start.x.toFloat(),start.y.toFloat(),MotionEvent.TOOL_TYPE_STYLUS)
     view.draw(Canvas(bitmap));assertEquals(listOf(ink.first().id),view.wholeEraseHitIds);assertTrue(committed.isEmpty())
     val far=vp.worldToScreen(270.0,272.5,600.0,600.0,context.resources.displayMetrics.density.toDouble())
     assertTrue("The complete stroke must disappear while the eraser is held",Color.red(bitmap.getPixel(far.x.toInt(),far.y.toInt()))>210)
     event(view,MotionEvent.ACTION_UP,start.x.toFloat(),start.y.toFloat(),MotionEvent.TOOL_TYPE_STYLUS)
     assertEquals(listOf(ink.first().id),committed)
    }finally{bitmap.recycle();view.cancelGesture()}
   }
  }
 }

 @Test fun incrementalWholeEraseMatchesPaintedMaskAndRecordsCost(){
  val strokes=List(240){i->InkStroke(id(),if(i%15==0)InkPen.HIGHLIGHTER else InkPen.PEN,Color.BLACK,4f,InkTool.STYLUS,
   List(100){n->InkSample(30f+(i%12)*78+n*.35f,30f+(i/12)*60+kotlin.math.sin(n*.1).toFloat()*6,n*4L,.7f)})}
  val raw=List(300){n->InkSample(10f+n*3.2f,330f+kotlin.math.sin(n*.05).toFloat()*80,n*4L)}
  val radius=12f;val geometry=VisibleInkGeometry()
  val oldStart=System.nanoTime()
  val baseline=strokes.filter{s->
   if(!InkHitTest.hits(s,raw,radius))false else Path(geometry.path(s)).apply{op(VisibleInkGeometry.sweptPath(raw.map{EraserPoint(it.x,it.y)},radius),Path.Op.INTERSECT)}.let{!it.isEmpty}
  }.map{it.id}.toSet()
  val oldMs=(System.nanoTime()-oldStart)/1e6
  val tracker=WholeEraseTracker(strokes,radius);val start=System.nanoTime();val frameMs=mutableListOf<Double>()
  for(count in (1 until raw.size step 12).toList()+raw.size){val tick=System.nanoTime();tracker.update(raw.take(count));frameMs.add((System.nanoTime()-tick)/1e6)}
  val newMs=(System.nanoTime()-start)/1e6
  assertEquals(baseline,tracker.ids);assertTrue(tracker.ids.isNotEmpty())
  val cut=strokes.first().withCuts(listOf(InkCut(id(),100f,listOf(EraserPoint(strokes.first().samples.first().x,strokes.first().samples.first().y)))))
  assertFalse(VisibleInkGeometry().hits(cut,listOf(cut.samples.first()),radius))
  val record=org.json.JSONObject().put("strokes",strokes.size).put("pointsPerStroke",100).put("eraserPoints",raw.size)
   .put("baselineFullPathMs",oldMs).put("incrementalTotalMs",newMs).put("incrementalFrameMs",org.json.JSONArray(frameMs)).put("matchedIds",baseline.size)
  File(app.getExternalFilesDir(null),"v78-whole-erase-cost.json").writeText(record.toString(2))
  println("WHOLE_ERASE_COST=$record")
 }
}
