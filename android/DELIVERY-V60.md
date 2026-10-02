# V60 阅读浮窗选页等待交付 · 2026-10-02

**0.0.60-select-await（60）**：阅读浮窗“回原文”等待真实选页事务成功后才关闭详情、切页并聚焦。失败保留卡片详情与原迹；返回、重建或更新普通选页使旧请求失效，旧结果不再回跳。阅读锁仍允许浏览。普通选页保持即时响应，并清除之前加页/编辑留下的延迟UI目标，防止后续目录更新覆盖新选择。

新增5项实际UI/VM回归、14项受影响旧回归，共19个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/SelectAwait-V60/InkWeft-v60-workspace-preview.apk`，SHA-256 `35121d4d48d2e7046d5a3a515d4d9c7a5fe784a4ab3d0f0a154cd45bc3edb60e`。见[交付](DELIVERY-V60.md)、[验证](VERIFICATION-V60.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V60.md)。V59来源避让、真实节点可见、原窗口/相机恢复和双向分屏保留均已重跑。

Room12/IWO9、原迹/PDF字节与数据格式不变，固定签名保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。继承未提交工作与旧候选保留，未暂存/提交/推送/PR/合并/发布。只操作本轮空白合成模拟器；真实平板、真人手写/原故障、物理分屏和人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定线程最新全文仍NOT_RETRIEVED；本批依据用户后续持续完善授权。

取消不补偿已经提交的浏览元数据；选页事务后的其他连接回收仍有极窄竞争。Library来源检查与选页仍为两事务，其旧回归通过；摘录/搜索等原即时包装未在本批改为await。被详情遮挡的浮窗close/min/dock/mode取消路径仅静态核对，未声称真实手指覆盖。


- APK：`E:/Inkweft/dist/SelectAwait-V60/InkWeft-v60-workspace-preview.apk`，144272770字节。
- applicationId：`org.inkweft.app.a0.workspace`；固定证书SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。
- 完整日志、失败尝试、原始/最终稿、四张原生截图、源码摘要与清理核对：`E:/Inkweft/archives/2026-10-02/SelectAwait`。
- 分支：`codex/android-a1-ink-20260924`；HEAD：`8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。

生产仅改BookInkScreen、BookPagesViewModel、data-local/NotebookPages和版本号；新增两个测试类及共享实际Room支持文件，并修正旧导航助手对可见固定按钮强制滚动的操作。442个构建输入：434个与V59一致、5个既有输入变化、3个新增。没有依赖或schema变化。

selectAwait由现有Main调用方协程拥有，复用原selectionGeneration与Mutex。等实际I/O后再检查取消、最新请求及当前可用页；成功在同一Main执行段发布selectedId、揭示原文和按Job身份清槽，中间不增加dispatcher跳转。不会将浏览等待标记为作者busy，也不会禁止阅读锁下的回源。真失败返回false；取消和更新请求覆盖抛CancellationException，避免旧错误串入新卡片。

根窗口退出、导航不可用和editor销毁先清请求槽再取消；旧finally只清自己。成功reveal不调用取消函数。NotebookPages.select在同一Room事务中验证未回收本册、本页归属和回收状态，再保存阅读页。普通select仍即时显示，其新增清理只撤销旧completed-command的requestedSelection。

使用原离线Gradle/JDK/SDK/JKS；精确命令见helpers/build_select_await.ps1。未跑的历史全域/数据库套件不计本轮，合成测试不代表真人手写验收。
