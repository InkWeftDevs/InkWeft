# 墨织 Android

当前为 **自定义封面测试版**（`0.0.13-custom-covers`）：支持本地图片封面、配色、文字和取景调整；在 UI-R2 基础上保留可视化模板创建、双层编辑工具、框选／稳线、共享摘要、知识关联、局部图、集合与手动回忆。当前实现与验证边界见 [UI-R2 实施记录](UI-R2-IMPLEMENTATION.md) 和 [修改日志](CHANGELOG.md)，开发顺序见 [待实现内容](TODO.md)，既有学习数据合同见 [A3.5 说明](A3.5-LEARNING.md)。

以下内容保留各早期阶段的历史记录；其中“未完成”和测试数字描述的是当时状态，不代表 UI-R2 的现状。

实机验收请使用 [真机测试清单与反馈模板](DEVICE-TEST-CHECKLIST.md)，按用例操作并记录设备、版本与结果。

## A0 — 原生基础切片（历史）

这是安卓主工程的第一笔实现，不是把网页放进 WebView，也**不是完整 A0.1 书写版**。旧 `src/`、`index.html` 和 Web 自检均保持原样，原计划37条应用验收仍为 NOT_RUN。

## 本批范围

- 三模块：纯 Kotlin 草稿/命令模型、Room 本地数据、Compose 原生资料库与文字页。
- 新建、选笔记、文字输入、显式保存、独立明文文本导出。
- 草稿按 noteId/baseRevision 保存于 ViewModel：失焦不清空，切卡不串写，合法空正文保持空。
- 内容、不可变修订和幂等回执放在同一 Room 事务；失败不显示“已保存”，结果未知保留原命令重试，陈旧版本拒绝覆盖。
- 显式丢弃草稿后的异步读取，只能替换当时的同一个草稿实例；期间编辑、再次保存或其他读取均使旧请求失效，不以结构相等绕过检查。
- 无 INTERNET、麦克风、相机、全文件权限；声明排除系统备份与设备迁移。构建依赖下载不等于运行时联网。
- 保留现有数据库：无 destructive migration；自定义 open helper 在腐败回调时拒绝默认删除，并明确关闭 allowDataLossOnRecovery。此行为仍需真实故障验证。

## 明确未完成

手写/Ink、PDF、页面缩略图、卡片轴心、脑图、标题链接、留白、FSRS、插件、云盘、完整备份/恢复、长期草稿存储和正式发行尚未接入。界面没有这些假按钮。本批只验证原生工程、UI与文字数据链路，后续继续接入 AndroidX Ink，而不是自研笔引擎。

未点击保存的草稿仅在当前 ViewModel 中；旋转保留，进程终止可能丢失。没有声明 fsync/掉电零丢失或 Pencil3 适配。`PRAGMA synchronous=FULL` 是配置，不是耐久性测试证据。单页文字导出不含历史/回执，不是完整备份；云端目标必须由用户主动选择。

`org.inkweft.app.a0` 使用独立实验库，不迁移、不删除 Web 样品内容。不要将真实笔记的唯一副本放在此版本。

## 构建

本次构建例外：最初声明API37，但实际CI（run 35875551637）在官方SDK源返回 `Failed to find package platforms;android-37`。仅此A0文字基础切片显式改用compile/target36，min31不变；不是静默退回，也不表示API37验证通过。后续原生主线按实际SDK可用性再评审升至37。SDK工具显式安装，不依赖runner预置PATH。

固定：JDK 17、Gradle 9.4.1、AGP 9.2.1、Kotlin/Compose plugin 2.3.10、compile/target 36、min 31。直接依赖版本见 `gradle/libs.versions.toml`。AGP 9 使用 built-in Kotlin；Android模块不再重复应用 kotlin-android。

**标准 wrapper 已入库**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 与 `gradle-wrapper.properties` 已提交。JAR 由官方 Gradle 9.4.1 的 `wrapper` 任务生成，本地重建后 SHA-256 与下列官方校验值逐字节一致（不是手写或用下载器伪造的 JAR）：

```powershell
Set-Location android
.\gradlew.bat :core-domain:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest
```

标准wrapper生成任务固定官方分发SHA-256：
`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
来源：Gradle官方9.4.1 release中的gradle-9.4.1-bin.zip资产digest。
CI还在执行生成的wrapper前验证官方JAR SHA-256：
`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`
来源：https://gradle.org/release-checksums/

Room schema v1已从成功CI run35876852580的artifact10758881048取回并核对字段后提交至 `data-local/schemas`；未改数据库版本或结构。CI重建后检查已提交Schema没有漂移。传递依赖锁/校验材料仍需补齐，不能将直接版本固定等同整个供应链已完成。

## 证据

CI针对指向main的PR和main的相关push分别验证。先前只触发开发分支的配置已移除，合并之后仍检查实际main构件。

CI分别编译 Debug APK、运行纯Kotlin JUnit、lint，并编译（不执行）instrumentation test APK。实际结果以对应提交的 Actions 为准。CI输出APK SHA-256、签名指纹和构建信息。没有模拟器/设备任务就保持安装、Room instrumented tests、Compose操作、热/笔延迟为 NOT_RUN；不能将 `assembleDebugAndroidTest` 当作测试通过。

测试：
- core-domain：空文本、原编辑基线、未知结果同命令重试、冲突保留、字段边界和摘要。
- DraftReloadTest：明确读取、迟到结果、新输入、保存中、结果未知、相等但不同的草稿实例、重复结果与跨笔记读取；定义不等于已执行，以本次CI XML为准。
- data-local/androidTest：幂等、陈旧版本、重开、旧回执固定版本。仅定义/编译不等于运行。

调试APK使用当前构建环境debug key，不是正式签名；不同环境证书不保证兼容覆盖安装，禁止脚本自动卸载清库。没有自动合并、Release或上传商店。

## A3 局部橡皮 / 多页 / 封面改名（本轮实测）

本节的数字来自本地实际执行并保留原始日志与报告，不是计划值，也不是把定义中的用例算作通过。

- 编译：`:core-domain:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest` 全部成功（153 个任务）。
- JVM 单元测试：**104 项通过，0 失败 / 0 错误 / 0 跳过**（CanvasViewport20、DiagnosticLog14、DraftReload8、InkEditing12、InkModels26、NotebookAppearance12、NoteDraft12）。本地与 CI 两次独立执行数字一致。
- lint：本地 0 错误/40 警告，CI 0 错误/49 警告（规则集合差异）；未关闭规则、未用 `continue-on-error`。
- Room schema4 由真实 KSP 生成并提交，`identityHash=43aaa681e66f431ce083dc510f59cbe8`。用宿主 SQLite 独立复核：3→4 迁移后的结构与 Room 期望的 schema4 逐表逐索引一致，2→3→4 链式升级结果同样一致；原笔迹 `payload` 字节在迁移前后完全相同，第一页保留原 UUID 身份。CI 生成的 4.json 与本地生成逐字节相同。
- CI（run 36144792720，head `62e5e4e`）整体 success，实际 JUnit XML 统计：**app 仪器测试 18/18 通过，data-local 仪器测试 31/31 通过，0 失败 / 0 错误 / 0 跳过**。其中包含 `migration3To4PreservesAllOriginalInkBytesAndReferences`。
- 本地另用 MuMu Android 12（API 32，x86_64）独立复跑：data-local 31 项全部通过；app 侧 17/18 通过，两项抽屉用例需要窄于 840dp 的宽度，而该 VM 的虚拟显示被锁定为横屏，提高密度后这两项亦通过。该 VM 的限制属于本地模拟器环境，不代表用例有问题——CI 的 API35 模拟器上 18 项全部通过。
- 构件：`org.inkweft.app.a0.workspace`，versionCode 5 / `0.0.5-a3-editing`，minSdk 31 / target 36，四种 ABI。
  - CI 构件 APK SHA-256 `125e390b7b3ef4192d5fac0a6bf9d695f42c9a2e1479877d83856b36de9398ca`（35,248,119 bytes），签名 v2 通过，证书 SHA-256 `ea2d4ed2ddfda316f001195d379d129ef47b3a07df05723462611b912f4b082c`。
  - 本地同源构建 APK SHA-256 `CBDC7B6FD92E157B78559D45B39DFB39141018794AFB8994B6BAFE6403D25BA0`，证书 SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。两者都是 diagnostic 变体（applicationId `org.inkweft.app.a0.workspace`，应用名“墨织工作台预览”），字节不同只因为调试签名证书不同；版本、包名、schema 与内容一致。本轮未单独产出非 diagnostic 的 `org.inkweft.app.a0` 包。
- 仍然 NOT_RUN：真机 iQOO/Pencil3、真实掌拒与笔身按钮、光学延迟、温升/掉电、系统分享目标、16KiB 设备、自动手写识别（当前仅为人工转录索引）。上面通过的是模拟器与宿主检查，不等于这些项目已验收。

## 后续最短路径

1. 提交完整标准wrapper与传递依赖材料；开发基础版当前有明确bootstrap方式。
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
