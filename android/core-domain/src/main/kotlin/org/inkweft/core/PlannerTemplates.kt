// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Undated, author-space stationery. Fields are prompts for ink, not application form data. */
internal object PlannerTemplates {
    fun info(s:PaperStyle):Triple<String,String,String> = when(s){
        PaperStyle.CORNELL_GRID->Triple("康奈尔 · 网格","学习","关键词、方格笔记与总结")
        PaperStyle.DAILY_PLANNER->Triple("每日计划","计划","今日重点、时间轴、待办与生活记录")
        PaperStyle.WEEKLY->Triple("一周安排","计划","周一至周日、周目标与复盘")
        PaperStyle.MONTHLY->Triple("月度日历","计划","无日期六周日历、月目标与备忘")
        PaperStyle.HABIT->Triple("30 天打卡","计划","一个习惯、30 个日期格与阶段总结")
        PaperStyle.MEETING->Triple("会议纪要","工作","议题、讨论、决策、负责人和截止日期")
        PaperStyle.PROJECT->Triple("项目推进","工作","目标、里程碑、下一步与风险")
        PaperStyle.READING->Triple("阅读摘记","学习","书目信息、原文页码、摘录与我的理解")
        PaperStyle.VOCABULARY->Triple("单词积累","学习","单词、释义、例句与复习标记")
        PaperStyle.EXPENSE->Triple("日常记账","生活","日期、项目、分类、收入与支出")
        PaperStyle.MEAL->Triple("一周餐单","生活","七日三餐、购物清单与备餐")
        PaperStyle.FITNESS->Triple("运动记录","生活","动作、组数、次数、负重与感受")
        PaperStyle.MUSIC->Triple("五线谱","基础","十二组五线谱，旋律与乐理练习")
        PaperStyle.HANDWRITING->Triple("田字练习","学习","田字格、示范字与练习笔记")
        else->error("Not a structured template: $s")
    }

    fun guides(style:PaperStyle,visible:CanvasBounds):PaperGuides {
        val lines=mutableListOf<PaperLine>();val labels=mutableListOf<PaperLabel>();val fills=mutableListOf<PaperFill>()
        fun line(x:Float,y:Float,X:Float,Y:Float,strong:Boolean=false){lines+=PaperLine(x,y,X,Y,strong)}
        fun label(text:String,x:Float,y:Float,size:Float=18f){labels+=PaperLabel(text,x,y,size)}
        fun rules(x:Float,y:Float,w:Float,h:Float,gap:Float=48f){var yy=y+gap;while(yy<y+h){line(x,yy,x+w,yy);yy+=gap}}
        fun block(text:String,x:Float,y:Float,w:Float,h:Float,ruled:Boolean=true){
            fills+=PaperFill(x,y,w,34f);label(text,x+12,y+23,17f)
            line(x,y+h,x+w,y+h,true)
            if(ruled)rules(x,y+38,w,h-38)
        }
        fun table(headers:List<String>,widths:List<Float>,y:Float,rows:Int,rowHeight:Float){
            require(headers.size==widths.size)
            val w=widths.sum();fills+=PaperFill(50f,y,w,38f)
            var x=50f
            headers.forEachIndexed{i,text->label(text,x+9,y+25,17f);line(x,y+38,x,y+38+rows*rowHeight);x+=widths[i]}
            line(x,y+38,x,y+38+rows*rowHeight)
            for(i in 0..rows)line(50f,y+38+i*rowHeight,50f+w,y+38+i*rowHeight,i==rows)
        }
        label(info(style).first,50f,85f,34f);label("日期 / 期间：",700f,80f,17f)
        line(50f,110f,950f,110f,true)
        when(style){
            PaperStyle.CORNELL_GRID->{
                block("关键词 / 问题",50f,145f,200f,960f,false);block("课堂笔记",270f,145f,680f,960f,false)
                for(y in 215..1095 step 40)line(270f,y.toFloat(),950f,y.toFloat())
                for(x in 270..950 step 40)line(x.toFloat(),183f,x.toFloat(),1105f)
                block("用自己的话总结",50f,1135f,900f,200f)
            }
            PaperStyle.DAILY_PLANNER->{
                block("今日最重要的三件事",50f,145f,900f,190f)
                block("时间安排",50f,365f,400f,760f,false)
                for(i in 0..15){val y=422f+i*43;label("${6+i}:00",61f,y,15f);line(122f,y+8,450f,y+8)}
                block("待办清单",475f,365f,475f,440f)
                block("生活记录 / 饮食 / 运动",475f,835f,475f,290f)
                block("今日复盘",50f,1155f,900f,180f)
            }
            PaperStyle.WEEKLY->{
                block("本周目标 / 日期范围",50f,145f,900f,160f)
                listOf("周一","周二","周三","周四","周五","周六","周日","本周回顾").forEachIndexed{i,name->
                    block(name,50f+(i%2)*465,335f+(i/2)*250,435f,220f)}
            }
            PaperStyle.MONTHLY->{
                block("本月重点",50f,145f,900f,140f)
                val width=900f/7
                listOf("一","二","三","四","五","六","日").forEachIndexed{i,d->label("周$d",60+i*width,329f,17f)}
                for(i in 0..7)line(50+i*width,350f,50+i*width,1130f)
                for(i in 0..6)line(50f,350+i*130f,950f,350+i*130f)
                block("备忘 / 下月准备",50f,1160f,900f,175f)
            }
            PaperStyle.HABIT->{
                block("我想坚持的习惯 / 每日最低目标",50f,145f,900f,170f)
                for(i in 0 until 30){val x=50+(i%5)*180f;val y=350+(i/5)*132f
                    label("${i+1}",x+10,y+25,20f);line(x,y,x+180,y);line(x,y,x,y+132)
                    line(x+180,y,x+180,y+132);line(x,y+132,x+180,y+132)}
                block("坚持中的发现 / 下次调整",50f,1170f,900f,165f)
            }
            PaperStyle.MEETING->{
                block("会议主题 / 参与人",50f,145f,900f,150f)
                block("议题与讨论记录",50f,325f,900f,460f)
                block("决策 / 待确认问题",50f,815f,900f,190f)
                table(listOf("行动项","负责人","截止日期"),listOf(540f,180f,180f),1035f,5,50f)
            }
            PaperStyle.PROJECT->{
                block("目标 / 完成标准",50f,145f,900f,210f)
                table(listOf("里程碑","目标日期","进度 / 结果"),listOf(440f,180f,280f),385f,5,65f)
                block("下一步行动",50f,800f,435f,300f);block("风险 / 依赖",515f,800f,435f,300f)
                block("进展回顾",50f,1130f,900f,205f)
            }
            PaperStyle.READING->{
                block("书名 / 作者 / 阅读范围",50f,145f,900f,150f)
                block("页码与原文摘录",50f,325f,435f,730f);block("我的理解与疑问",515f,325f,435f,730f)
                block("收获 / 可以尝试的行动",50f,1085f,900f,250f)
            }
            PaperStyle.VOCABULARY->{table(listOf("单词 / 发音","释义","例句 / 搭配","复习"),listOf(220f,200f,400f,80f),150f,12,90f)
                label("复习标记由你填写；可记录日期或熟悉程度。",50f,1325f,17f)}
            PaperStyle.EXPENSE->{block("本期预算 / 记录范围",50f,145f,900f,135f)
                table(listOf("日期","项目","分类","收入","支出"),listOf(120f,340f,160f,140f,140f),310f,15,54f)
                block("合计 / 结余 / 调整",50f,1190f,900f,145f)}
            PaperStyle.MEAL->{table(listOf("星期","早餐","午餐","晚餐"),listOf(120f,260f,260f,260f),150f,7,100f)
                listOf("一","二","三","四","五","六","日").forEachIndexed{i,d->label("周$d",66f,243f+i*100,18f)}
                block("购物清单",50f,925f,435f,410f);block("备餐 / 库存",515f,925f,435f,410f)}
            PaperStyle.FITNESS->{block("训练目标 / 热身",50f,145f,900f,180f)
                table(listOf("动作","组数","次数 / 时间","负重","感受"),listOf(260f,100f,190f,130f,220f),355f,9,72f)
                block("放松 / 状态 / 下次调整",50f,1090f,900f,245f)}
            PaperStyle.MUSIC->{for(group in 0 until 12)for(i in 0..4)line(50f,180f+group*96+i*12,950f,180f+group*96+i*12)}
            PaperStyle.HANDWRITING->{
                for(row in 0 until 10)for(col in 0 until 8){val x=50+col*112.5f;val y=150+row*108f
                    line(x,y,x+112.5f,y,true);line(x,y,x,y+108,true)
                    line(x+112.5f,y,x+112.5f,y+108,true);line(x,y+108,x+112.5f,y+108,true)
                    for(k in 0..9){line(x+k*11.25f,y+54,x+k*11.25f+5,y+54);line(x+56.25f,y+k*10.8f,x+56.25f,y+k*10.8f+5)}}
                label("练习笔记：",50f,1270f);line(50f,1335f,950f,1335f)
            }
            else->error("Not a structured template")
        }
        return PaperGuides(lines.filter{CanvasBounds(minOf(it.x1,it.x2).toDouble(),minOf(it.y1,it.y2).toDouble(),maxOf(it.x1,it.x2).toDouble(),maxOf(it.y1,it.y2).toDouble()).intersects(visible)},emptyList(),
            labels.filter{it.y>=visible.top&&it.y-it.size<=visible.bottom},fills.filter{it.y+it.height>=visible.top&&it.y<=visible.bottom})
    }
}
