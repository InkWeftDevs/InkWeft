# V59 来源焦点避让交付 · 2026-10-02

**0.0.59-source-focus（59）**：阅读页回原文后，宽屏临时将导图停靠右侧并让出纸面；窄屏或原最小化窗口保留顶部48dp入口，纸面让出56dp。一级“恢复窗口”返回用户原浮动/停靠/最小化和窗口模式，原位置、大小、图节点、折叠与相机保持。既有左右/上下分屏按编辑半屏宽度处理；旋转和切换分屏方向后仍按当前画布尺寸聚焦源区域。拖动纸面后再次点同一来源也会重新定位。

新增UI 4/4、受影响旧UI 12/12，共16个唯一用例通过。lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/SourceFocus-V59/InkWeft-v59-workspace-preview.apk`；SHA-256 `addf7a02275a029b9adc8b4b3610f1946c78cc2c0b409a0ecb02b348732a3bf5`。见[交付](DELIVERY-V59.md)、[验证](VERIFICATION-V59.md)、[实机待测](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V59.md)。

Room12/IWO9、原笔迹/PDF和作者数据格式不变，固定签名保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。保留继承未提交工作，未暂存/提交/推送/PR/合并/发布。执行只限本轮独有空白Android35模拟器及合成资料，未读取/启动/安装/升级真实平板，未上传用户数据。真实平板、真人笔迹/原故障、物理分屏、人工读屏仍NOT_RUN，真实备份恢复NOT_VERIFIED。

指定线程最新全文仍NOT_RETRIEVED；本批依据用户后续持续本地完善授权，未把旧材料冒充指定最新讨论。阅读浮窗来源回调仍即时选择已加载页、后续I/O异步保存；本批不声称解决该await一致性。Library来源有效性检查与选页仍是原两个事务，取消不回滚已提交的浏览元数据。


- APK：`E:/Inkweft/dist/SourceFocus-V59/InkWeft-v59-workspace-preview.apk`，151753556字节。
- applicationId：`org.inkweft.app.a0.workspace`；固定证书SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。
- 完整证据：`E:/Inkweft/archives/2026-10-02/SourceFocus`，含候选/测试APK身份、构建与lint、每次测试尝试、四张原生截图、preimage/迭代、独立复核、源码清单和最终核对。
- 分支：`codex/android-a1-ink-20260924`；HEAD：`8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。保留全部继承脏路径和V56/V57/V58交付。

生产修改 BookInkScreen、FloatingStudyWindow、InkScreen、MindMapView、StudyWorkspace、NotebookApp，另改版本号，新增SourceFocusVisibilityUiTest，适配CardSourceNavigationUiTest/ReadLockUiTest的恢复步骤并保留原断言。439个构建输入：429个与V58一致，9个既有输入变化，1个新增；没有数据层、格式或依赖变化。

Book的临时源目标仅保存pageId与四个坐标；原window flags/SharedPreferences不被自动布局覆盖。右槽预留匹配frame宽度及8dp边距，原最小化顶部条同时给独立documentBar让位。source期间禁用窗口几何改写，恢复后沿用原拖动/缩放；显式模式、最小化、停靠、关闭操作退出临时源布局。

Ink聚焦由页面绑定LaunchedEffect拥有，等待native view已attach、布局完成且尺寸与当前纸面一致，再聚焦/消费；无未拥有的View.post。每次明确回源增加临时请求编号，旧消费不能清掉后来相同bounds的请求。保存的源bounds使重建或编辑半屏尺寸变化重新fit；离开原源页后以最新VM页面一致性检查退出临时布局。

使用原离线Gradle/JDK/SDK与JKS。精确命令见helpers/build_source_focus.ps1；本批仅对改变的界面链路定向回归，历史domain/Room/真人笔迹验收不计入本轮。继续优先核对阅读浮窗等待选页失败与取消的一致性，复用当前来源入口而不重做分屏。

真实分屏回归暴露了Row/Column换方向时编辑器重新建树，丢失临时回源目标的问题。NotebookApp用原生Compose可移动内容保留同一个编辑器composition，沿用已有分屏容器、分隔线和参照资料，保持当前源目标和窗口状态；重新布局后仍由当前native尺寸重新聚焦。
