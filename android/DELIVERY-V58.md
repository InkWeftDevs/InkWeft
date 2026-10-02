# V58 资料库来源导航交付 · 2026-10-02

**0.0.58-library-source-nav（58）**：资料库学习页“回原文”现在等待真实页面选择成功，随后打开同一本、同一源页和摘录区域；连续阅读切回单页但保持阅读锁。失效来源保留当前卡片和旧原迹快照，不提前关闭学习页。等待时禁用重复回源，返回原节点/关闭仍可点；详情退出同步取消当前请求，关闭学习窗口、换卡及旋转后旧请求不向当前界面发布导航或旧错误。

新增真实UI 3/3、受影响旧UI 12/12，共15个唯一用例通过；lint 0错误、0致命、132警告。候选 `E:/Inkweft/dist/LibrarySource-V58/InkWeft-v58-workspace-preview.apk`；SHA-256 `75155e873e696a4c6ee82ba08caf5d23cd548eb02f1a13bf9749a65130a4c657`。见[交付](DELIVERY-V58.md)、[验证](VERIFICATION-V58.md)、[实机待测](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V58.md)。

精确指定来源 [分支 · 分支 · 调研iPad笔记软件开发](chatgpt-conversation://6abbd418-4dd8-83ea-8e83-d60cf0b6e100) 最新全文仍 **NOT_RETRIEVED**。本批根据后续“那你看着改，一直往后推进完善这些功能”的连续本地开发授权实施；历史阶段的单批停止约束已被后续授权替代。未将旧讨论冒充最新需求。所有执行限本轮空白 Android35 模拟器与合成资料；未读取、启动、安装或升级真实平板，未提交、推送、PR、合并、发布或上传私有原迹。

Room12/IWO9与原笔迹、PDF、作者数据格式不变，固定签名保持。MAP_PORTAL_V2当前/历史/删除记录仍最低V55，旧V54/V47不能读取，不能据同签名推定真实旧库升级/恢复已通过。真实平板、真人笔迹/原故障、物理分屏、人工读屏、真实备份恢复均未验收。有效性检查与选页仍是两个原有事务，存在极窄的跨连接整本回收竞争；取消不能撤销已提交的浏览页选择。旧阅读页浮窗回调沿用已加载页面检查和异步选页，不声称改为等待I/O完成。浮动导图遮挡焦点的自动避让本批未实现。


- APK：`E:/Inkweft/dist/LibrarySource-V58/InkWeft-v58-workspace-preview.apk`，151697680字节。
- 固定证书SHA-256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`；applicationId `org.inkweft.app.a0.workspace`。
- 证据归档：`E:/Inkweft/archives/2026-10-02/LibrarySource`；测试APK、构建/lint、实际安装哈希、首轮失败、原生截图、源码preimage/迭代和独立复核一并保存。
- 当前分支 `codex/android-a1-ink-20260924`，HEAD `8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。保留继承未提交工作，未暂存/提交/推送。

生产改动仅既有 StudyWorkspace、WorkspaceViewModel、LibraryScreen 三文件，另改版本号并新增一个UI测试文件。438个构建输入中，433个与V57一致，4个既有输入修改、1个新增；BookInkScreen、数据层、格式与依赖均未改。来源范围从原StudySourceRow构造临时Anchor，不写知识记录、不伪造空strokeIds。

请求由当前详情的协程拥有；book/card/node/map key负责销毁取消，11个详情退出事件在清状态之前同步cancel。Library关闭同步cancel当前caller Job，finally通过请求身份检查清引用。成功与非取消错误发布前均检查活性，CancellationException保持取消语义。原搜索wrapper沿用签名；默认anchor=null时保留NotebookApp先设anchor再跳页的旧协议。

构建使用既有离线Gradle/JDK/SDK和原JKS，不更改密钥。普通命令 `gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，精确环境与参数见归档helpers/build_library_source.ps1。实机项仅维护待测清单，不作为本地下一批开发的前置审批。

本批交付后下一项优先解决阅读页浮动导图可能遮住原文焦点；继续复用现有移动/缩放/停靠与区域聚焦接口，先做只读几何核对。多来源学习集合和长期复习排期仍列独立后续范围，未声称完成。
