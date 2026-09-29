// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Portable snapshot, not a second writable store or a full backup. */
internal object KnowledgeCanvasExport {
    fun encode(book:String,cards:List<StudyCardRow>,rows:List<KnowledgeRow>):String {
        val selected=cards.filter{it.notebookId==book&&it.trashedAt==null};val ids=selected.map{it.id}.toSet()
        val active=rows.filter{it.notebookId==book&&!it.removed}
        val placements=active.mapNotNull{it.data() as? KnowledgeData.Placement}.associateBy{it.cardId}
        val nodes=JSONArray();val edges=JSONArray();val omitted=JSONArray()
        selected.forEachIndexed{i,c->val place=placements[c.id];nodes.put(JSONObject().apply{
            put("id",c.id);put("type","text");put("text",c.title+"\n\n"+c.body);put("x",place?.x?:((i%4)*300));put("y",place?.y?:((i/4)*220));put("width",260);put("height",180)
        })}
        active.forEach{r->when(val d=r.data()){
            is KnowledgeData.Link->{
                if(d.source.kind==TargetKind.CARD&&d.target.kind==TargetKind.CARD&&d.source.id in ids&&d.target.id in ids&&d.pinnedRevision==null)
                    edges.put(JSONObject().apply{put("id",r.id);put("fromNode",d.source.id);put("toNode",d.target.id);put("toEnd","arrow");put("label",d.relation.label)})
                else omitted.put(JSONObject().apply{put("id",r.id);put("source",d.source.kind.name+"/"+d.source.id);put("target",d.target.kind.name+"/"+d.target.id);put("reason",if(d.pinnedRevision!=null)"固定修订引用需关联 Markdown 或完整备份保存"else"页面、区域或当前文件之外的目标不转换为虚假卡片")})
            }
            else->Unit
        }}
        return JSONObject().apply{put("nodes",nodes);put("edges",edges);put("inkweft",JSONObject().apply{
            put("format","snapshot/1");put("scopeNotebookId",book);put("omittedLinks",omitted)
            put("omittedMaps",JSONArray(active.filter{it.data() is KnowledgeData.MapDefinition}.map{r->JSONObject().put("id",r.id).put("title",(r.data() as KnowledgeData.MapDefinition).title)}))
            put("limitations",JSONArray(listOf("同一卡片多次摆放合并为一份文本节点","独立脑图层级、白板装饰线及多视图布局保留在完整备份，此文件不还原它们","不含原始笔迹、回收历史、来源快照和复习记录","不启用外部目录双向写入；完整迁移使用资料库备份")))
        })}.toString(2)
    }
}
