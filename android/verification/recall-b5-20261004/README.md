# IW-B5 持久回忆检查点（尚未整批完成）

本检查点先交core/data供同一96卡合成夹具接续，正式UI/原位投影与完整Room故障测试仍在实现，不称本批完成。

- 基于主线7f9f335与B4 Room15的5b2d718、ee2461f、2c87fc2、228f91a
- Room16追加8表：独立题义head/revisions、每questionId共享schedule、session、attempt、hint_events、corrections、receipts；保V15=34表归档兼容，旧作者表不重写
- 题型问答、固定卡片文字挖空、固定原迹区域遮挡；不把字符串offset伪装成PDF字形锚点。原页/PDF采用来源区域坐标题型；文字挖空在卡正文/原导图位置逐片揭开
- 开始固定题义/知识/来源版本；明确记录文字/独立墨迹作答、提示类型与时刻、正常封存对照、原自评及有效评分；临时模式/跳过不改变排程
- 作答期提示和查看原文使正式有效分≤2，正常锁定作答后的ANSWER_COMPARE及ORIGINAL_COMPARE不作提示惩罚
- grade+schedule+session推进+receipt同一事务；重复op查原回执，attempt结束后不再记分。撤销追加correction，拒绝覆盖仍有效的后继评分；保留原回答/评分
- 作者题序取代UUID主排序；图按作者顺序，未入图卡按标题，同卡旧题按问法/新配置按创建顺序
- 每本20,000尝试、2,000轮次、200,000命令；作答8,000字符、512,000字节墨迹、256笔/16,000点，全库作答墨迹64MB；未知或超限不清空原数据
- 历史按到期/题型/提示/本地日期过滤、100条分页，列表不载入答案墨迹或源快照；点开单条才读固定内容

验证：RecallStudyTest8与BranchReviewTest16共24 JVM通过；Room16 KSP/生成schema16/data Kotlin编译通过；host SQLite实际15→16 DDL与42表schema一致，34旧表逐行/字节不变。日志toolchain/logs/recall-b5-early.log。Android运行/人工体验/新UI编译尚未完成，均不计PASS。

算法/归属/ceil差异/测试向量来源见ALGORITHM.md；不复制官网源码，不使用无明确许可证的第三方FSRS实现。
