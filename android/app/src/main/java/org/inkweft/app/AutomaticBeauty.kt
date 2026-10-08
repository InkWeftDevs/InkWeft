// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.runtime.*
import org.inkweft.core.*
import java.util.UUID

internal enum class BeautyLanguage(val title:String){MIXED("中文（含英文）"),ENGLISH("English")}
internal data class BeautyOptions(val enabled:Boolean=false,val font:TextFont=TextFont.WENKAI,
    val size:Float=28f,val spacing:Float=1.2f,val bold:Boolean=false,val preserveLayout:Boolean=true,val snap:Float=.5f,val language:BeautyLanguage=BeautyLanguage.MIXED,val keepInk:Boolean=false,val inkStrength:Float=.5f,val formula:Boolean=false)
internal class BeautyStore(context:Context){
    private val prefs=context.getSharedPreferences("inkweft-beauty",Context.MODE_PRIVATE)
    fun read()=BeautyOptions(prefs.getBoolean("enabled",false),TextFont.entries.getOrElse(prefs.getInt("font",1)){TextFont.WENKAI},
        prefs.getFloat("size",28f).coerceIn(12f,96f),prefs.getFloat("spacing",1.2f).coerceIn(1f,2f),prefs.getBoolean("bold",false),prefs.getBoolean("preserve-layout",true),prefs.getFloat("snap",.5f).coerceIn(0f,1f),BeautyLanguage.entries.getOrElse(prefs.getInt("language",0)){BeautyLanguage.MIXED},prefs.getBoolean("keep-ink",false),prefs.getFloat("ink-strength",.5f).coerceIn(0f,1f),prefs.getBoolean("formula",false))
    fun save(value:BeautyOptions){prefs.edit().putBoolean("enabled",value.enabled).putInt("font",value.font.ordinal)
        .putFloat("size",value.size).putFloat("spacing",value.spacing).putBoolean("bold",value.bold).putBoolean("preserve-layout",value.preserveLayout).putFloat("snap",value.snap).putInt("language",value.language.ordinal).putBoolean("keep-ink",value.keepInk).putFloat("ink-strength",value.inkStrength).putBoolean("formula",value.formula).apply()}
}

@Composable internal fun AutomaticBeautyBinding(model:PageObjectViewModel,ink:InkUi,writing:Boolean,options:BeautyOptions,world:Boolean,app:InkWeftApplication){
    SideEffect{model.observeBeauty(ink,writing,options,world,app)}
}

internal fun beautyObject(strokes:List<InkStroke>,text:String,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString()):PageObject {
    val lines=HandwritingLines.split(strokes);val texts=text.lines().filter{it.isNotBlank()}
    val regions=if(lines.size==texts.size)lines.zip(texts){line,value->RecognizedLine(value,line.bounds,line.strokes.map{it.id},emptyList())}else emptyList()
    return beautyObject(strokes,RecognizedWriting(text,1f,texts.size,regions),options,world,id)
}
internal fun beautyObject(strokes:List<InkStroke>,result:RecognizedWriting,options:BeautyOptions,world:Boolean,id:String=UUID.randomUUID().toString(),revision:Long=-1):PageObject =
    NaturalText.build(strokes,result,options,world,id,revision)

/** Append settled runs without recognizing or moving previously converted characters. */
internal fun appendBeauty(previous:PageObject,next:PageObject):PageObject?=runCatching{
    require(previous.font==next.font&&previous.fontSize==next.fontSize&&previous.bold==next.bold&&previous.lineSpacing==next.lineSpacing&&previous.color==next.color)
    require(previous.textRuns.isEmpty()==next.textRuns.isEmpty())
    val oldRun=previous.textRuns.lastOrNull();val newRun=next.textRuns.firstOrNull()
    val sameLine=oldRun!=null&&newRun!=null&&kotlin.math.abs(previous.y+oldRun.baseline-next.y-newRun.baseline)<=maxOf(oldRun.size,newRun.size)*.45f&&next.x>=previous.x
    val separator=if(!sameLine)"\n" else if(previous.text.last().isLetterOrDigit()&&next.text.first().isLetterOrDigit()&&previous.text.last().code<128&&next.text.first().code<128&&next.x-(previous.x+previous.width)>oldRun!!.size*.2f)" " else ""
    val textOffset=previous.text.length+separator.length
    val x=minOf(previous.x,next.x);val y=minOf(previous.y,next.y)
    fun shift(o:PageObject,offset:Int)=TextStyles.positioned(o).map{it.copy(start=it.start+offset,end=it.end+offset,x=it.x+o.x-x,y=it.y+o.y-y)}
    previous.copy(x=x,y=y,width=maxOf(previous.x+previous.width,next.x+next.width)-x,height=maxOf(previous.y+previous.height,next.y+next.height)-y,
        text=previous.text+separator+next.text,sourceStrokeIds=previous.sourceStrokeIds+next.sourceStrokeIds,
        glyphs=shift(previous,0)+shift(next,textOffset),
        textRuns=previous.textRuns.map{it.transformed(previous.x-x,previous.y-y)}+next.textRuns.map{it.transformed(next.x-x,next.y-y,offset=textOffset).let{r->if(sameLine)r.copy(lineId=oldRun!!.lineId)else r}},
        erasures=previous.erasures.map{it.transformed(previous.x-x,previous.y-y)}+next.erasures.map{it.transformed(next.x-x,next.y-y,offset=textOffset)})
}.getOrNull()

/** Polish at pen-up, before persistence and page splitting; never re-typeset a formula. */
internal fun polishNewStroke(stroke:InkStroke,options:BeautyOptions):InkStroke {
    if(!options.enabled||!options.keepInk||stroke.pen==InkPen.HIGHLIGHTER||stroke.cuts.isNotEmpty())return stroke
    val polished=InkSelectionEdit.beautify(listOf(stroke),options.inkStrength).single()
    return InkStroke(stroke.id,stroke.pen,stroke.color,stroke.width,stroke.tool,polished.samples,stroke.world,stroke.cuts,stroke.appearance)
}
