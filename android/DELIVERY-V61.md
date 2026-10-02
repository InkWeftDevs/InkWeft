# V61 阅读工具栏交付 · 2026-10-02

**0.0.61-reading-tools（61）**：只读浏览使用紧凑阅读工具栏，直接打开已有导图和本笔记摘录，并一键返回书写。全屏、导出和计时器复用原入口；书写工具、加页入口及作者专用操作提示在阅读时隐藏。返回书写保留原笔类型、颜色、宽度、手指设置及工具栏自定义，不重放旧的展开请求。

375dp窄屏、1.6倍字体及左右分屏实际186.5dp编辑侧使用可换行布局；参考侧标题单独显示，操作及页码可换行。原生画布和参考本身份保持，阅读模式不新增作者记录。正常宽布局沿用原排列。

新增4项实际原生UI回归、18项受影响旧回归，共22个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/ReadingTools-V61/InkWeft-v61-workspace-preview.apk`，SHA-256 `478bfdd5a03c85c6ed6a01b9e3a06b688a2d7780d45bf183bc1d33f15d6e94a9`。见[交付](DELIVERY-V61.md)、[验证](VERIFICATION-V61.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V61.md)。

Room12/IWO9、原迹/PDF字节及数据格式保持，固定签名不变；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。既有未提交工作与V56–V60候选保留；未暂存/提交/推送/PR/合并/发布。仅操作本轮空白合成模拟器。真实平板、真人手写/原故障、物理分屏及人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定对话最新全文仍NOT_RETRIEVED；本批依据用户后续持续完善授权。


- APK：`E:/Inkweft/dist/ReadingTools-V61/InkWeft-v61-workspace-preview.apk`，151869996字节。
- applicationId：`org.inkweft.app.a0.workspace`；固定证书SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。
- 完整日志、失败尝试、原始/最终源码、五张原生截图、摘要与清理核对：`E:/Inkweft/archives/2026-10-02/ReadingTools`。
- 分支：`codex/android-a1-ink-20260924`；HEAD：`8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。

生产变化：InkScreen按现有readLock切换工具栏并隐藏作者专用UI；BookInkScreen隐藏只读加页并适配窄栏头部；EditorToolbar只消费新增加的更多请求；新增36行左右的ReadingToolbar叶组件；NotebookReferencePane适配窄参考头和页码；StudyWorkspace紧凑头部按可用宽度换行；版本号61。测试新增ReadingToolsUiTest，旧EditorToolsUiTest的只读橡皮断言按新界面改为不出现，其余断言保留。

444个构建输入：434个与V60一致、8个既有输入变化、2个新增；源码10条路径、文档7条路径。没有新依赖、schema、同步服务或写入格式。保留真实作者草稿和未完成操作；只关闭参数弹层，保留错误与待核对提示。全部模式切换仍通过原实时门禁。

阅读工具条与文档头复用原回调，不改搜索/摘录的选页协议。全屏目前通过退出全屏访问头部查找；默认隐藏的只读写工具入口也仍需自定义，这是下一批具体范围。未将后续计划记为已实现。

构建使用原离线Gradle/JDK/SDK/JKS，精确命令见helpers/build_reading_tools.ps1。合成数据不代表真人手写验收。
