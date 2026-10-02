# V62 阅读入口与全屏导航交付 · 2026-10-02

**0.0.62-reading-access（62）**：现有“其他设置→阅读与操作”增加默认可用的“只读浏览”开关，不用先自定义工具栏。开关复用当前原生书写页的实时门禁；成功后收起设置并关闭键盘，拒绝时保留设置、原模式和草稿并说明原因。全屏阅读“更多”增加“查找笔记”和“文档概览”，沿用原功能，不增加常驻按钮。

新增4项实际原生UI回归、15项受影响旧回归，共19个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/ReadingAccess-V62/InkWeft-v62-workspace-preview.apk`，SHA-256 `c68eea53228148e080c7ec420350aa874d91308d7315e171d1ce0cae35ae5a07`。见[交付](DELIVERY-V62.md)、[验证](VERIFICATION-V62.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V62.md)。

原笔参数、手指设置、工具栏隐藏/顺序、摘录偏好及作者记录保持。搜索关闭后保留全屏、只读、原来源页及导图上下文；概览沿用原来源退出规则。Room12/IWO9、固定签名与原数据格式保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。既有未提交工作和V56–V61候选保留，未暂存/提交/推送/PR/合并/发布。

只操作本轮空白合成模拟器。真实平板、真人手写/原故障、物理分屏和人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定对话最新全文仍NOT_RETRIEVED，本批依据用户后续持续完善授权。


- APK：`E:/Inkweft/dist/ReadingAccess-V62/InkWeft-v62-workspace-preview.apk`，144336438字节。
- applicationId：`org.inkweft.app.a0.workspace`；固定证书SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。
- 完整日志、失败尝试、原始/最终源码、四张原生截图、摘要及清理核对：`E:/Inkweft/archives/2026-10-02/ReadingAccess`。
- 分支：`codex/android-a1-ink-20260924`；HEAD：`8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。

生产变化为BookInkScreen中的设置开关及按本记忆的回调槽、InkScreen注册/解除当前页面处理器、ReadingToolbar全屏菜单两个原功能入口，以及版本62。新回调使用普通捕获lambda向rememberUpdatedState更新，避免局部函数引用的结构相等保留初始载入时的门禁闭包；changeReadOnly的原实时门禁逐字保留。卸载只解除自己的处理器，不误清下一页注册。

445个构建输入：440个与V61一致，4个既有输入变化，1个新增ReadingAccessUiTest。源码5条路径、文档7条路径。没有新依赖、schema、同步服务、备份协议、偏好存储项或作者命令。排队写入、未知回执、真实原生手势和预览草稿均沿用原阻止规则；本轮测试仍保留所有原断言。

构建使用原离线Gradle/JDK/SDK/JKS，命令见helpers/build_reading_access.ps1。APK只安装到InkWeft-ReadingAccess-V62任务空白AVD，候选和实际base.apk字节摘要一致。四張图为原生截图，未重绘或修图；合成折线不代表真人手写验收。
