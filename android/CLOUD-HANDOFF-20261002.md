# V66 云端验证与本地接续

## 当前边界

本次从完整冻结工作树迁入云端，保留了未提交及未跟踪源码，没有用旧 HEAD 覆盖。原来源分支为 `codex/android-a1-ink-20260924`，基点 `8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`。该基点包含 `main` 的历史；协作分支在其上接续，不改 `main`。

V66 仍不是设备验收完成版。版本 66 / `0.0.66-local-motion`，隔离包名 `org.inkweft.app.a0.workspace`，测试 runner 包名 `org.inkweft.app.a0.workspace.test`。签名私钥留在本地。未签名 APK 不能直接安装；不同签名不能覆盖旧安装，不能为安装方便卸载真实资料。

## 已实际完成

- 迁入源码 725 个清单文件逐一校验，含 688 个工作树文件、101 个原未跟踪文件与 449 个冻结构建输入。
- 云端 JDK17、Gradle9.4.1、AGP9.2.1、Android SDK36 / build-tools36.0.0。
- 核心领域 292 个唯一 JVM 单测通过，0失败、0跳过。
- V66 主应用 debug 及修正版 AndroidTest 源码编译通过。
- 原样基线 `lintDebug`：0 Error、0 Fatal、132 Warning；unsigned debug/runner 构建同样通过，133 Warning（多一条 Compose 插件可升级提示，未升级依赖）。
- 既有 release 配置已生成并核对无签名 APK，zipalign 通过。它的 `BuildConfig.DEBUG=false`，不替代 V66 debug UI 验收。
- 云端未生成签名或 adb 密钥，未启动模拟器或访问真机。一次联合构建 daemon 退出后，拆分串行重试成功；失败记录保留，未跳过检查。

## 成对未签名 debug 构建

新增显式 `-PinkweftUnsignedBuild=true`，默认行为不变。该选项拒绝同时设置 owner signing，并要求默认 debug 签名回落保持关闭。包名和业务代码不因这个选项改变。

```sh
./gradlew --no-daemon --max-workers=1 \
  -PinkweftDiagnosticBuild=true -PinkweftInsertionPreview=false \
  -PinkweftUnsignedBuild=true \
  -Pandroid.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes=false \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

先核对 dry-run 没有 `validateSigning`、安装或 connected 任务。实际成对产物以本次回执中的路径、大小和 SHA-256 为准；不得把旧包和新 runner 混用。禁止用 `-x validateSigning` 绕过配置检查。源码、测试、额外构建选项和 APK 必须一起绑定。

## 本地接续操作

使用交付消息给出的完整远端 commit 固定 V66；不要只依赖可移动的分支头。优先新目录，不改旧 `recon` 工作树。

```powershell
$Repo = 'https://github.com/InkWeftDevs/InkWeft.git'
$Handoff = '<交付消息中的完整远端commit>'
$Destination = 'E:\Inkweft-cloud-20261003'
if (Test-Path $Destination) { throw '目标目录已存在，请改用全新目录；不要覆盖' }
git clone --no-checkout $Repo $Destination
if ($LASTEXITCODE -ne 0) { throw 'clone失败，停止' }
git -C $Destination fetch origin
git -C $Destination switch --detach $Handoff
if ($LASTEXITCODE -ne 0) { throw '交接commit不可用，停止' }
git -C $Destination switch -c codex/local/v66-device-20261003
git -C $Destination rev-parse HEAD
git -C $Destination status --porcelain
```

确认 HEAD 等于交接 SHA 且新目录 clean，才开始本任务。若有意外脏文件或 HEAD 不符，停下核对，不能自动 reset。云端当前继续学习闭环领域/Repository/UI；本地默认只负责本轮签名验收与脱敏报告。生产代码修复要先转交具体路径所有权。

本地获授权后，用保留的原签名对该主 APK 与对应 runner 分别签名，先 zipalign 再 apksigner，核对两包证书相同、主应用包名、runner targetPackage、版本及签名前后 SHA。私钥、口令和真实资料不上传。安装/旧库升级/恢复需独立明确授权；先用指定的合成材料或可恢复副本，不自动选择已有设备。

## 明日验收与未完成项

精确运行清单见 `verification/cloud-v66-20261002/pending-ui-runs.json`：新增3项与受影响旧8项，全部仍 **NOT_RUN**。必须记录11个唯一方法的真实 instrumentation 状态，不以总退出码或重复通过计数充数。

须保留四张原始截图 `lm66-card-sections.png`、`lm66-answer.png`、`lm66-source-highlight.png`、`lm66-zero-scale-restored.png` 和真实 Recomposer 上下文 `lm66-zero-scale-context.json`。0倍率证据目前只针对来源定位与 Activity 重建，不覆盖卡片/答案；Activity 重建不等于完整 OS 进程死亡。

定向静态审查未证实旧答案可见或错误定位生产阻断，但存在待补强的测试证据：折叠后的 View detach 不代表所有旧内容/共享LRU清空；顺序定位不证明两个未完成请求的抢占；等待答案出现不证明重建首帧没有重播入场。这些不能写成已通过。

操作检查和结果模板追加在 `DEVICE-TEST-CHECKLIST.md`。真实笔感、物理分屏、人工读屏、完整进程死亡和真实备份恢复均独立待验。下一学习闭环草稿不混入本 V66 交接候选，不把后续领域测试当作V66设备验收。
