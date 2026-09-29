// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

enum class LearningTargetKind { NOTE, PAGE, MAP, BRANCH, COLLECTION, CARD }
data class StableTargetRef(val kind:LearningTargetKind,val notebookId:String,val id:String?=null,val mapId:String?=null){
    init {
        UUID.fromString(notebookId);id?.let(UUID::fromString);mapId?.let(UUID::fromString)
        require(kind in setOf(LearningTargetKind.NOTE,LearningTargetKind.MAP)||id!=null)
        require(mapId==null||kind==LearningTargetKind.BRANCH)
    }
}
enum class WidgetSize(val label:String,val rows:Int){SMALL("小",2),MEDIUM("中",4),LARGE("大",8)}
data class WidgetDefinition(val namespace:String,val key:String,val version:Int,val title:String,
    val sizes:Set<WidgetSize> = WidgetSize.entries.toSet()){
    val qualifiedKey get()="$namespace/$key"
    fun accepts(instance:WidgetInstance)=instance.definition==qualifiedKey&&instance.version==version&&instance.size in sizes&&instance.targets.size<=32
}
data class WidgetInstance(val id:String,val definition:String,val version:Int=1,val visible:Boolean=true,
    val size:WidgetSize=WidgetSize.MEDIUM,val targets:List<StableTargetRef> = emptyList()){
    init{UUID.fromString(id);require(definition.matches(Regex("[a-z][a-z0-9.-]*/[a-z][a-z0-9-]*")));require(version>0&&targets.size<=32)}
}
object LearningWidgets {
    val definitions=listOf(
        WidgetDefinition("org.inkweft","continue",1,"继续学习"),
        WidgetDefinition("org.inkweft","shortcuts",1,"收藏与快捷入口"),
        WidgetDefinition("org.inkweft","maps",1,"我的思维导图"),
        WidgetDefinition("org.inkweft","inbox",1,"待整理摘录"))
    fun defaults()=definitions.map{WidgetInstance(UUID.randomUUID().toString(),it.qualifiedKey)}
    /** Shared layouts contain only allowlisted structure. Local targets and visit history never leave the device. */
    fun shareLayout(instances:List<WidgetInstance>)=instances.filter{i->definitions.any{it.accepts(i)}}
        .map{it.copy(id=UUID.randomUUID().toString(),targets=emptyList())}
    fun move(instances:List<WidgetInstance>,id:String,delta:Int):List<WidgetInstance>{
        val from=instances.indexOfFirst{it.id==id};if(from<0)return instances
        val target=(from+delta).coerceIn(0,instances.lastIndex)
        return instances.toMutableList().apply{add(target,removeAt(from))}
    }
}
