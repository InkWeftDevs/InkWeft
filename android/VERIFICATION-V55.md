# V55 验证证据

领域 24/24、Room 34/34、原生 UI 24/24 通过；lint 0 错误、0 致命、132 警告。 具体逐方法、原始日志哈希与最终安装身份见归档 `evidence/verification-summary.json`，不把初轮失败或历史通过计为最终结果。

|本轮组|执行/通过|覆盖|
|---|---|---|
|领域 MapPortalTest / KnowledgeTest|10/10 + 14/14|V2 分支往返；V1 精确原字节；UUID/空分支/未知版本/尾部字节拒绝；目标分支进入冻结命令摘要|
|新增 MapPortalBranchRepositoryTest|12/12|主图/命名图/结构/卡位置、改名/删除/移图/同名同卡不误指、跨图/跨本拒绝零写、CAS/Unknown重试、重复入口范围、历史归属、完整备份/历史/回执/重复恢复|
|既有 MapPortalRepositoryTest / LibraryBackupRepositoryTest|10/10 + 12/12|整图入口合同与完整备份恢复保护|
|MapPortalUiTest|11/11（新增5、既有6）|真实MainActivity/原生导图/节点菜单；明确选择与取消、保存只写关系、阅读/打开/范围/旋转/全部主题/精确返回；删除替代与移除；打开后删除和嵌套返回失效；375dp/字号1.6真实触控、长名称与48dp热区|
|ReadLockUiTest|6/6|现有作者门禁、草稿/未知切换、阅读导航与旋转跨本保护|
|RecallMaskUiTest 定向7项|7/7|9类祖先窗口、系统可达无障碍、PDF/原迹/对象/图/摘录绘制门禁、暖缓存提示/收起、旋转/回源/下题、退出恢复草稿/别本、明确手工CAS评级|

构建：既有 JDK17 / Gradle9.4.1、离线镜像、max-workers=2；`:core-domain:test --tests org.inkweft.core.MapPortalTest --tests org.inkweft.core.KnowledgeTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:data-local:assembleDebugAndroidTest`、`:app:lintDebug`。领域与存储在合格后保持输入不变，末次仅因新增视图失效保护重建应用与受影响 UI；未重跑历史领域/Room全套，也未重复旧APK降级兼容。

设备：本批新建 `InkWeft-BranchPortal-V55`、Android35 / 1920×1200 / density240、独立ADB5038 / emulator-5556；只含合成资料。最终实际安装 SHA256 `a10acbb29b3a38c51bb383e26f88b4aa1a39fab5cb032dad7c7516d73f9b4227` 与构建及交付一致；原固定证书 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。校验见 `certificate.txt`、`package.txt`、`installed-artifact.json`、最终 `build-app.txt` 与 `build-checks.json`。

失败审计：初轮入口 UI 9项中8过1失败，原因是窄屏用例的完整选中名称断言缺少“✓ ”前缀。保留 `ui-branch-portals-1.txt`；只改断言后 `ui-branch-narrow-2.txt` 单项通过。随后源码核对补上“打开后删除分支不退回整图”和嵌套返回保护，重新构建并安装最终 APK，`ui-branch-portals-final.txt` 11项与 `ui-protection-final.txt` 13项全部通过。初轮 APK 身份保留在 installed-artifact-before 记录，不冒充最终 APK。

7张截图由最终入口UI检查中的 UiAutomation 原样生成，含常规选择/预览/聚焦、预览失效、已打开后失效、窄屏大字号选择/预览。文件/原机SHA/尺寸见 `native-ui/manifest.json`；未修图、未上传，属于合成素材，不是真人体验验收。

434项输入的逐项SHA、原稿/中间稿、文档原件、最终Git状态与本次资源归档/清理见 `source-manifest.json`、`artifact.json`、`final-check.json`、`cleanup.json`。Room schema12 和 IWO9 保持。整图入口继续写原 MAP_PORTAL_V1；指定分支写 MAP_PORTAL_V2（目标图 ID + 目标位置/结构节点 UUID）。含 V2 当前记录、历史或删除记录的资料库及完整备份要求 V55，旧 V54/V47 无法读取这些记录；移除入口不消除历史中的 V2。

所有运行使用本批独立 Android35 模拟器和合成资料。真实平板安装/启动/升级/降级、真实资料备份恢复、真人手写、手写笔体验、原“多字变我”故障原迹、物理分屏与人工读屏均 NOT_RUN；不能用这些合成结果代替真人验收。未提交、推送、PR、合并或发布，未上传私人原迹。 进程终止后会话视野恢复未测；真实旧库升级及真实备份可恢复性 NOT_VERIFIED。实机项目统一维护 BP55-01～07，不用模拟器结果填真机 PASS。本批完成后停止，不新增下一阶段。跨笔记/跨标签入口、独立复制身份映射、留白产品语义、服务商用范围及其他新功能不在本批。
