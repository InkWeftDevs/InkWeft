# 页面大纲层级折叠 · 云端功能切片

基点：`5b67701b614a6dd6472ca108886e0d72123e8200`（PR #8）。对应 `TODO.md` 的页面大纲折叠待办，仅组织现有“文档概览→大纲”列表。

## 交互合同

- 按当前页序及已有0–3级缩进，收起一项之后连续更深缩进的条目，遇同级／更浅条目停止。标题仍打开原稳定页面，页序与作者缩进不改写。
- 同页允许多条大纲项，因此折叠身份必须同时包含稳定页面ID和大纲记录ID，不能依赖列表位置，也不合并或去重作者记录。
- 折叠仅保存在本册界面SavedState；搜索暂时显示匹配的隐藏条目，清空恢复原折叠。搜索筛选不得参与折叠键修剪。
- 只在完整有效读取后按当前未筛选大纲修剪失效折叠键；暂未载入或读取失败不能清空恢复状态。页面删除、重排或缩进变化依据最新页序／层级重新计算。
- 折叠动作与标题选页分开，保持48dp触控区和窄侧栏可达性。

现有PageMark没有父节点ID，本批不声称新增持久父子图、拖动重排或PDF目录解析。数据库／作者数据／备份格式／版本号／签名／依赖保持。

## 验证

- 实现只改 `OverviewCollections.kt`；折叠为独立整行按钮，不挤占原有标题／页码／更多菜单的宽度。
- 新增既有 `OverviewTabsUiTest#outlineFoldsFollowStableRowsThroughSearchRestoreAndStructureChanges` 一个场景：嵌套／同级、同页多大纲项、搜索清空、原标题导航、SavedStateHandle重建和Activity重建、缩进变化、页面移动／回收／恢复，以及复用27组作者快照确认浏览不改作者数据。隐藏断言搜索整个LazyColumn，避免把未组合的屏外项误判为折叠。
- 独立静态复核后，失效键修剪也依赖当前VM及折叠集合，覆盖切本和迟到旧回调；相等结果不重复写SavedState。
- 2026-10-03 同次串行执行 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug` 全部PASS，退出0，耗时5分40秒（11任务执行，66任务复用）。JDK17／固定Gradle9.4.1／SDK36，单worker；不构建APK、不重跑未改变核心或Room全套。
- lint XML原始计数：0 Error、0 Fatal、134 Warning。包含两条相同的VisibleForTests告警，均指向基点已存在的OverviewViewModel默认SavedStateHandle构造；本批保留该旧构造，不抑制告警或作无关改写。
- 新方法、真实Android UI、大字体／窄窗操作均NOT_RUN。Activity／VM重建不等于完整OS进程死亡，静态或编译检查不等于行为验收。
