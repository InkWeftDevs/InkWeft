# 墨织 Android A0 — 原生基础切片

这是安卓主工程的第一笔实现，不是把网页放进 WebView，也**不是完整 A0.1 书写版**。旧 `src/`、`index.html` 和 Web 自检均保持原样，原计划37条应用验收仍为 NOT_RUN。

## 本批范围

- 三模块：纯 Kotlin 草稿/命令模型、Room 本地数据、Compose 原生资料库与文字页。
- 新建、选笔记、文字输入、显式保存、独立明文文本导出。
- 草稿按 noteId/baseRevision 保存于 ViewModel：失焦不清空，切卡不串写，合法空正文保持空。
- 内容、不可变修订和幂等回执放在同一 Room 事务；失败不显示“已保存”，结果未知保留原命令重试，陈旧版本拒绝覆盖。
- 无 INTERNET、麦克风、相机、全文件权限；声明排除系统备份与设备迁移。构建依赖下载不等于运行时联网。
- 保留现有数据库：无 destructive migration；自定义 open helper 在腐败回调时拒绝默认删除，并明确关闭 allowDataLossOnRecovery。此行为仍需真实故障验证。

## 明确未完成

手写/Ink、PDF、页面缩略图、卡片轴心、脑图、标题链接、留白、FSRS、插件、云盘、完整备份/恢复、长期草稿存储和正式发行尚未接入。界面没有这些假按钮。本批只验证原生工程、UI与文字数据链路，后续继续接入 AndroidX Ink，而不是自研笔引擎。

未点击保存的草稿仅在当前 ViewModel 中；旋转保留，进程终止可能丢失。没有声明 fsync/掉电零丢失或 Pencil3 适配。`PRAGMA synchronous=FULL` 是配置，不是耐久性测试证据。单页文字导出不含历史/回执，不是完整备份；云端目标必须由用户主动选择。

`org.inkweft.app.a0` 使用独立实验库，不迁移、不删除 Web 样品内容。不要将真实笔记的唯一副本放在此版本。

## 构建

固定：JDK 17、Gradle 9.4.1、AGP 9.2.1、Kotlin/Compose plugin 2.3.10、compile/target 37、min 31。直接依赖版本见 `gradle/libs.versions.toml`。AGP 9 使用 built-in Kotlin；Android模块不再重复应用 kotlin-android。

首次引导需要官方 Gradle 9.4.1（CI使用固定提交的setup-gradle）。**本提交未携带wrapper JAR**：先使用一次已安装的固定Gradle生成标准wrapper，CI同时输出生成的wrapper文件供审阅；没有另写下载执行器或伪造JAR。

```powershell
Set-Location android
gradle wrapper
.\gradlew.bat :core-domain:test :app:lintDebug :app:assembleDebug :data-local:assembleDebugAndroidTest
```

标准wrapper生成任务固定官方分发SHA-256：
`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
来源：Gradle官方9.4.1 release中的gradle-9.4.1-bin.zip资产digest。

Room schema由KSP在 `data-local/schemas` 生成；发布前需取回并审阅Schema、标准wrapper与传递依赖锁/校验材料。这些缺口没有用空文件宣称完成。

## 证据

CI分别编译 Debug APK、运行纯Kotlin JUnit、lint，并编译（不执行）instrumentation test APK。实际结果以对应提交的 Actions 为准。CI输出APK SHA-256、签名指纹和构建信息。没有模拟器/设备任务就保持安装、Room instrumented tests、Compose操作、热/笔延迟为 NOT_RUN；不能将 `assembleDebugAndroidTest` 当作测试通过。

测试：
- core-domain：空文本、原编辑基线、未知结果同命令重试、冲突保留、字段边界和摘要。
- data-local/androidTest：幂等、陈旧版本、重开、旧回执固定版本。仅定义/编译不等于运行。

调试APK使用当前构建环境debug key，不是正式签名；不同环境证书不保证兼容覆盖安装，禁止脚本自动卸载清库。没有自动合并、Release或上传商店。

## 后续最短路径

1. 验证当前CI并审阅生成构件；提交标准wrapper与真实schema/依赖材料。
2. 原生Ink画布、笔盒、事务化笔迹保存、取消/长笔分段、完整导出恢复。
3. 真机安装、旋转/进程终止/低空间；再接PDF与一张共享知识卡。
4. 本批未修改网页残留问题，它们仍需独立小修，不以安卓实现宣布网页已修复。

## 官方实现依据

https://developer.android.com/build/releases/agp-9-2-0-release-notes
https://developer.android.com/build/migrate-to-built-in-kotlin
https://developer.android.com/jetpack/androidx/releases/room
https://developer.android.com/reference/kotlin/androidx/sqlite/db/SupportSQLiteOpenHelper.Configuration.Builder
https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-setup

自有新增代码：AGPL-3.0-or-later；仓库根 LICENSE 保持原文。第三方 AndroidX/Kotlin/Gradle 依其自身许可；没有复制竞品素材、字体或代码。
