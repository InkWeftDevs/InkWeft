# 学习区节点大纲原位标题 · 云端功能切片

基点：`bb0f2b15f2c02e956c0ea4256e9f64378f41de0e`。对应TODO125／176中的原位标题和同级续写部分；这是具有真实parentId的导图节点大纲，与页面目录PageMark无关。

## 交互合同

- 复用NodeTitleDraft／NodeTitleEditor及原保存事务，按节点稳定ID挂载输入；原点击标题查看内容保持不变。
- 改标题、新子主题、新同级均在确认前只持有草稿，取消零写入；共享卡正文、来源、已有节点身份与位置保持原值。
- 大纲的Enter／键盘下一项为“保存并继续同级主题”，只有当前操作成功核实后才打开一个未保存的同父级草稿；完成按钮仅保存退出。空草稿取消不产生节点。
- 同级只保证相同parentId，沿用既有根主题与独立图语义，不承诺列表紧邻或末尾位置；没有新增排序字段、Tab层级、重排撤销或布局算法。
- 组合输入期间不提交，重复／长按按键不重复提交；待核对操作仍重试原命令。真实中文输入法组合过程与硬件表现须单独设备验证。
- 结构主题仍更新原图定义；在结构主题旁新建沿用既有共享卡＋节点语义，不把结构主题复制为卡。

## 验证

- 生产改动限StudyWorkspace.kt与NodeActions.kt；无数据库／备份格式／依赖／版本号变化。
- 新增2条整合AndroidTest：`MapInteractionUiTest#outlineTitlesKeepSharedContentAndContinueAtTheSameParent`覆盖同图重复共享卡节点的精确挂载、原正文／来源／位置、重建／取消、子同级、合成Enter重复键／软件Next及独立图结构标题；`#outlineEnterUnknownReceiptRetriesOneCommandBeforeOpeningOneSiblingDraft`覆盖原命令／回执数、未知结果重建与核对、单一后续空草稿取消。
- 扩展原`SelectionStudyUiTest#outlineFoldFocusAndQuickAddShareOneGraph`和`ReadLockUiTest#summaryOutlineAndMapRejectActualAuthorIntents`，保留原断言。合成键投递给实际聚焦窗口，不冒充真人IME组合输入。
- 最终一次定向检查通过（2026-10-03 UTC，5分47秒）：`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`，单worker／串行，JDK17、SDK36、仓库diagnostic／unsigned参数；lint XML为0 Error／0 Fatal／135 Warning。未重建APK、未重跑未改变的核心／Room全套，文档／格式收尾不重复编译。
- 设备UI、实际IME、真实进程死亡和人工可用性仍NOT_RUN，不继承旧代码的设备通过结论。Activity.recreate仍保留ViewModel，不代表完整进程死亡；原紧凑窗口新ViewModel初始化会回到导图页，尚未验证该路径的续写体验。
