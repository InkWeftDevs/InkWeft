package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.inkweft.core.*
import java.util.UUID

internal enum class SelectionType(val title:String){INK("手写"),HIGHLIGHTER("荧光笔"),IMAGE("图片"),TEXT("文字／符号"),SHAPE("图形"),TAPE("胶带")}
internal data class SelectionOptions(val types:Set<SelectionType> = SelectionType.entries.toSet(),val precise:Boolean=false,val freehand:Boolean=true){
    fun accepts(s:InkStroke)=(if(s.pen==InkPen.HIGHLIGHTER)SelectionType.HIGHLIGHTER else SelectionType.INK) in types
    fun accepts(o:PageObject)=when(o.kind){PageObjectKind.IMAGE,PageObjectKind.MAP->SelectionType.IMAGE;PageObjectKind.TEXT->SelectionType.TEXT;PageObjectKind.SHAPE->SelectionType.SHAPE;PageObjectKind.TAPE->SelectionType.TAPE} in types
}
internal class SelectionStore(context:Context){
    private val prefs=context.getSharedPreferences("inkweft-selection",0)
    fun read()=SelectionOptions(prefs.getStringSet("types",SelectionType.entries.map{it.name}.toSet()).orEmpty().mapNotNull{runCatching{SelectionType.valueOf(it)}.getOrNull()}.toSet(),prefs.getBoolean("precise",false),prefs.getBoolean("freehand",true))
    fun save(value:SelectionOptions){prefs.edit().putStringSet("types",value.types.map{it.name}.toSet()).putBoolean("precise",value.precise).putBoolean("freehand",value.freehand).apply()}
}
internal data class CanvasSelection(val region:InkRegion,val revision:Long,val strokes:List<InkStroke>,val objects:List<PageObject>,val snapshot:List<PageObject>,val sourceInk:List<InkStroke> = emptyList()){
    val count get()=strokes.size+objects.size
}
internal object CanvasSelectionEdit {
    fun query(region:InkRegion,revision:Long,ink:List<InkStroke>,objects:List<PageObject>,options:SelectionOptions,geometry:VisibleInkGeometry):CanvasSelection{
        val suppressed=objects.flatMap{it.sourceStrokeIds}.toSet()
        return CanvasSelection(region,revision,ink.filter{it.id !in suppressed&&options.accepts(it)&&geometry.selects(region,it,options.precise)},
            objects.filter{!it.hidden&&options.accepts(it)&&geometry.selects(region,it,options.precise)},objects,ink)
    }
    fun moved(s:CanvasSelection,dx:Float,dy:Float,copy:Boolean,world:Boolean):Pair<InkMutation?,List<PageObject>>{
        val ink=if(s.strokes.isEmpty())null else InkMutation.Replace(if(copy)emptyList()else s.strokes.map{it.id},InkSelectionEdit.copy(s.strokes,dx,dy))
        val sources=s.sourceInk.associateBy{it.id}
        val moved=s.objects.map{original->
            val o=if(copy)BeautyAppearance.restore(original,sources)else original
            val x=o.x+dx;val y=o.y+dy;require(world||x>=0&&y>=0&&x+o.width<=1000&&y+o.height<=1414)
            o.copy(id=if(copy)UUID.randomUUID().toString()else o.id,x=x,y=y,sourceStrokeIds=if(copy)emptyList()else o.sourceStrokeIds)
        }
        val ids=s.objects.map{it.id}.toSet()
        val objects=if(copy)s.snapshot+moved else s.snapshot.map{o->if(o.id in ids)moved.first{it.id==o.id}else o}
        return ink to objects
    }
    /** Transform every domain around the same centre; validate before committing anything. */
    fun scaled(s:CanvasSelection,scale:Float,world:Boolean):Pair<InkMutation?,List<PageObject>> {
        require(scale.isFinite()&&scale in .1f..10f&&s.count>0)
        val b=(s.strokes.map{it.bounds()}+s.objects.map{it.bounds()}).reduce{a,v->a.union(v)}
        val cx=((b.left+b.right)/2).toFloat();val cy=((b.top+b.bottom)/2).toFloat()
        fun x(v:Float)=cx+(v-cx)*scale
        fun y(v:Float)=cy+(v-cy)*scale
        fun point(p:EraserPoint)=EraserPoint(x(p.x),y(p.y))
        fun sample(p:InkSample)=p.copy(x=x(p.x),y=y(p.y))
        val cutIds=mutableMapOf<String,String>()
        val ink=s.strokes.takeIf{it.isNotEmpty()}?.let{strokes->
            require(strokes.size<=InkSelectionEdit.MAX_SELECTED)
            InkMutation.Replace(strokes.map{it.id},strokes.map{o->
                InkStroke(UUID.randomUUID().toString(),o.pen,o.color,o.width*scale,o.tool,o.samples.map(::sample),o.world,
                    o.cuts.map{c->InkCut(cutIds.getOrPut(c.id){UUID.randomUUID().toString()},(c.radius*scale).coerceAtLeast(.01f),c.points.map(::point),c.shape)},
                    o.appearance.copy(originX=x(o.appearance.originX),originY=y(o.appearance.originY),leading=o.appearance.leading?.let(::sample),trailing=o.appearance.trailing?.let(::sample)))
            })
        }
        val sources=s.sourceInk.associateBy{it.id}
        val replacements=s.objects.associate{original->
            val o=BeautyAppearance.restore(original,sources)
            val glyphs=o.glyphs
            val changed=o.copy(x=x(o.x),y=y(o.y),width=o.width*scale,height=o.height*scale,
                fontSize=if(o.kind==PageObjectKind.TEXT&&glyphs.isEmpty())o.fontSize*scale else o.fontSize,
                glyphs=glyphs.map{it.copy(x=it.x*scale,y=it.y*scale,width=it.width*scale,height=it.height*scale,grain=it.grain*scale)},
                erasures=o.erasures.map{cut->cut.copy(radius=(cut.radius*scale).coerceAtLeast(.01f),points=cut.points.map{TextErasePoint(it.x*scale,it.y*scale)})},
                tapePoints=o.tapePoints.map{TapePoint(it.x*scale,it.y*scale)},
                lineWidth=if(o.kind==PageObjectKind.TAPE||o.kind==PageObjectKind.SHAPE)o.lineWidth*scale else o.lineWidth)
            require(world||changed.x>=0&&changed.y>=0&&changed.x+changed.width<=InkLimits.WIDTH&&changed.y+changed.height<=InkLimits.HEIGHT)
            original.id to changed
        }
        return ink to s.snapshot.map{replacements[it.id]?:it}
    }
    fun deleted(s:CanvasSelection):Pair<InkMutation?,List<PageObject>>{
        val ids=s.objects.map{it.id}.toSet()
        return (s.strokes.takeIf{it.isNotEmpty()}?.let{InkMutation.Visibility(it.map{v->v.id},false)}) to s.snapshot.mapNotNull{if(it.id !in ids)it else if(it.sourceStrokeIds.isNotEmpty())it.copy(hidden=true)else null}
    }
}
@Composable internal fun SelectionSettings(value:SelectionOptions,freehand:Boolean,dismiss:()->Unit,all:()->Unit,change:(SelectionOptions,Boolean)->Unit){
    EditorPanel("套索","",dismiss,"selection-settings"){
        Column(Modifier.verticalScroll(rememberScrollState())){
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(freehand&&!value.precise,{change(value.copy(precise=false),true)},label={Text("自由")},modifier=Modifier.testTag("lasso-free"))
                FilterChip(!freehand&&!value.precise,{change(value.copy(precise=false),false)},label={Text("矩形")},modifier=Modifier.testTag("lasso-rectangle"))
                FilterChip(value.precise,{change(value.copy(precise=true),true)},label={Text("精确")},modifier=Modifier.testTag("lasso-precise"))
            }
            Text(if(value.precise)"完整圈住才选中"else"碰到一部分即可选中整项",style=MaterialTheme.typography.bodySmall,color=Quiet)
            HorizontalDivider(Modifier.padding(vertical=8.dp),color=Line)
            TextButton(all,modifier=Modifier.testTag("selection-all")){Text("全选本页")}
            SelectionType.entries.forEach{type->Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                Text(type.title,Modifier.weight(1f));Switch(type in value.types,{on->change(value.copy(types=if(on)value.types+type else value.types-type),freehand)},modifier=Modifier.testTag("lasso-filter-${type.name}"))
            }}
        }
    }
}
@Composable internal fun MixedSelectionActions(s:CanvasSelection,enabled:Boolean,copy:()->Unit,delete:()->Unit,edit:()->Unit,scale:(Float)->Unit,dismiss:()->Unit){
    var more by remember{mutableStateOf(false)}
    androidx.activity.compose.BackHandler{dismiss()}
    Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
        Text("${s.count} 项",Modifier.padding(horizontal=10.dp),style=MaterialTheme.typography.labelMedium)
        TextButton(copy,enabled=enabled,modifier=Modifier.testTag("mixed-copy")){Text("复制")}
        TextButton(delete,enabled=enabled,modifier=Modifier.testTag("mixed-delete")){Text("删除")}
        if(s.strokes.isEmpty()&&s.objects.size==1)TextButton(edit,enabled=enabled,modifier=Modifier.testTag("mixed-edit")){Text("编辑")}
        Box {
            TextButton({more=true},enabled=enabled,modifier=Modifier.testTag("mixed-more")){Text("更多")}
            DropdownMenu(more,{more=false}){
                DropdownMenuItem(text={Text("放大 10%")},onClick={more=false;scale(1.1f)},enabled=enabled,modifier=Modifier.testTag("mixed-enlarge"))
                DropdownMenuItem(text={Text("缩小 10%")},onClick={more=false;scale(.9f)},enabled=enabled,modifier=Modifier.testTag("mixed-shrink"))
            }
        }
        IconButton(dismiss,modifier=Modifier.testTag("mixed-dismiss").describedAs("取消选择")){Glyph("close")}
    }
}
