# PR20 剩余阻塞与最小平移诊断

本补充从已推送 `44835b42fcc23b447ee8c8ac524091b5c1720ecc` 接续。整体验收仍未完成；没有重复全套测试，没有触发 CI、修改权限、访问用户电脑/真实笔记或编辑 backup-server。

## 本轮可确定、已修复的缺口

只修改 `TileLocalRedrawTest.kt`，源码提交 **`f84ede69f064bdebbd2427d06737a309525de241`**。生产 `clear()` 保持非阻塞取消，生产代码及主 APK 都没有变化。

- 五个 `ready` 各有独立标签：`initial-cached`、`pan-cached`、`pan-cold`、`zoom-cached`、`zoom-cold`。夹具、顺序、像素/复用断言及每次 **30,000 ms** 时限不变。`tilePanDiagnostics=true` 输出阶段开始/完成；失败总会记录阶段、pending/frameReady、上次已完成 build 和资源快照。诊断异常附加到原异常，不覆盖原失败。
- 测试在 Main 每次 draw 前后保存实际 Job，包含被下一次 draw 替换的 Job。finally 在 `NonCancellable` 中取消并 `joinAll`，核验 worker 数及 geometry 所有权回到基线。只有 join 已完成才关闭数据库和回收位图；清理错误保留为原错误的 suppressed。没有吞掉清理超时后继续的方法。localAppend 采用同一清理，但本轮未重跑它。
- 新增故意中断首次等待的用例，复用原夹具验证失败清理。此测试属于清理验证，不能算原超时门槛或性能验收通过。

若 worker 本身无法退出，严格 join 会阻止该方法结束；外层 1,800 秒 runner 只能报告失败并要求进程复核，不能当作正常释放，也不得自动启动下一项。本轮没有发生该情况。

## 仅两项定向原生验证

两次独立 instrumentation 调用均 **1/1 PASS**；与历史 17 次执行分开记录，不重写旧 15/17 结果。两项前后共四份环境指纹均为 `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`：原有云端 API35 x86_64、TCG/SwiftShader、2 vCPU/2 GiB 模拟器，无 KVM，不是实体设备性能。

故意中断发生在 initial-cached 首次 draw 后：pending=true、frameReady=false、inFlightJobs=1。真实取消并 join 后 inFlightJobs=0、totalBytes=0，随后关闭资源；原中断异常被精确捕获，suppressed=0。该调用 host 总时长 36.995 秒，JUnit 方法 11.658 秒。

原 pan/zoom 方法这次完整通过，host 总时长 57.786 秒，JUnit 方法 36.737 秒：

| 等待点 | 本次 elapsed ms |
| --- | ---: |
| initial-cached | 14,386.111 |
| pan-cached | 1,648.029 |
| pan-cold | 4,045.035 |
| zoom-cached | 3,109.334 |
| zoom-cold | 3,006.977 |

平移重建 12/20 瓦片；pan/zoom 像素一致、zoom 预览不能完成 capture、同 revision 复用等原断言全部通过。finally join 后 worker、geometry 和总跟踪资源均归零。两次 runner 结束后 app/appTest 进程均不存在，未 force-stop；进程消失与测试内 join 证据分开记录。

**本次没有复现 C2 超时，不能追溯历史 C2 的具体等待点。** 新标签能定位将来重现时的阶段。本次独立诊断通过不补齐旧固定 C/B/B/C 区组，不关闭单层绘制性能回退，也不把诊断计时混入旧性能统计。

原始回执和源码绑定见 [pan-followup-source-results.json](evidence/pan-followup-source-results.json)、[中断日志](evidence/candidate-pan-cleanup-v3-interrupted-targeted.log)、[原方法日志](evidence/candidate-pan-cleanup-v3-original-targeted.log)。

## 其余阻塞判定

| 问题 | 现有证据可以确定什么 | 最小下一步，未在本轮执行 |
| --- | --- | --- |
| 20,000 页搜索、128 行 / 10 秒 | v4 失败；准备阶段约 8.3 秒，进入 writer 前后约 56 ms（含调度/事务，不能当纯锁等待）；后续输入/清空/分类断言未到达。不能判定为死锁，也不能只归因 TCG | 单独执行原搜索方法，保留已有准备/写入阶段日志与原 10 秒、后续 120 秒时限；可继续在本云诊断。要区分固定时限与 TCG 开销，宜在已有 KVM 的专属云 runner 做同源码/同夹具验证 |
| 写入中取消备份 / 15 秒 | 最后 v2 失败停在私有副本验证，未到 partial 写入或目标取消；基准该验证约 24.863 秒。列出的两份 Repository 与两份测试文件在 v2/v4/本次源码中哈希一致（不涵盖 LibraryTextIndex 等其他依赖）；不由较小隔离测试推断取消通过 | 单独运行原取消方法；必须观察 partial、发出取消，再验证删除、作者内容和重开。不能把“还没进入写入”称为取消实现已坏，也不放宽窗口 |
| 1,201 笔 / 76,802 点完整帧 / 30 秒 | hex-v1 最后超时，读到 768 行、0 发布 callback、worker 在运行；单次栈落在 Brush 构造不足以证明主因。未到完整帧/末批像素/严格释放 | 同一主 APK 的原密集方法单独执行。已有 KVM runner 有助判断 TCG 成本；若仍失败，再对已定位的 worker 阶段取样，不改规模或时限 |
| 单层局部重绘回退 / 旧 C2 区组 | 旧慢样本、C2 失败及其清理重叠不确定性仍保留。本轮只修测试隔离，主 APK 不变，没有新的性能配对 | 在独占云 runner、相同测试补丁、明确 A/B 次序和固定样本数下运行 localAppend；记录原值、decode/rebuild/native 数与像素。单次新成功不构成回退关闭 |

这四项没有新增的确定生产代码缺陷可据此安全修复。**没有证据证明必须换设备才能定位代码阶段，也没有证据保证 KVM 一定通过。** 当前软件环境可继续做有目标的诊断；稳定的加速云环境是区分固定门槛与 TCG 开销的最小改进条件。实体设备耗时结论需要另有授权的云端设备资源，不能由模拟器推断；不转到用户电脑或用户真机。

[四个文件的一致性证据](evidence/remaining-gate-source-comparison.json)仅证明列出的文件相同，不宣称整个 v2/v4 APK 相同。历史失败与性能原值仍见[前次报告](../pr20-closure-20261012/README.md)。

## CI 权限审查与替代入口

本轮只读检查，未 dispatch、未改 workflow。具体权限操作：

- `.github/workflows/android-six-batches.yml:69–79` 的“临时授权KVM”步骤在 **77 行**执行 `sudo setfacl -m "u:$(id -u):rw" /dev/kvm`；**232 行**恢复 ACL，同样是权限动作。历史步骤名称中的“用户已确认”不覆盖本轮禁止扩大权限的要求。对应 `ci/test_six_batch_workflow.py:146–150` 明确检查授予/恢复步骤。
- `.github/workflows/android-a0.yml` 的 device 启动步骤 **201 行**、TLS 启动步骤 **297 行**均执行 `sudo chmod 666 /dev/kvm`。因此不能直接 dispatch 整个工作流作为本轮替代。
- `android-runner-diagnostics.yml:13–27` 仅读取节点/身份并做 accel-check，没有授权动作；`android-source-snapshot.yml` 只归档源码，不运行 Android。它们都不能证明上述原用例通过。
- a0/source-snapshot 自动触发限定 main；六批及只读诊断 push 限定旧六批分支。当前隔离分支与 PR20 的非 main base 不匹配这些自动触发条件；之前 0 个 checks 不是 PASS。

源码及具体行号绑定于 [CI 只读审查](evidence/ci-permission-read-only-review.json)。无权限修改替代是：**已配置 SDK/依赖与合成 AVD、当前用户原本已有 KVM 读写权的专属云 runner，直接跑指定方法。** 不使用 sudo、ACL/chmod、加组、设备映射或 privileged 容器来取得权限。当前 `/dev/kvm` 不存在，因此这里只提供条件和命令，不在当前机器启动 KVM。

以下是另一个符合条件的云 runner 的只读门禁（沿用当前工具路径；不满足立即停止）：

```bash
test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm || exit 1
/workspace/.setup/tools/android-sdk/emulator/emulator -accel-check || exit 1
```

在专用 launcher 进程启动已经配置好的合成 AVD；这是 **NOT_RUN** 的替代方案，当前任务仍用原 TCG AVD：

```bash
ANDROID_USER_HOME=/workspace/.setup/android-user \
ANDROID_AVD_HOME=/workspace/.setup/android-user/avd \
/workspace/.setup/tools/android-sdk/emulator/emulator \
  -avd inkweft-perf-api35 -port 5554 -accel on -gpu swiftshader \
  -cores 2 -memory 2048 -no-window -no-audio -no-boot-anim \
  -no-snapshot -feature -Vulkan
```

保持 API35 AOSP ATD x86_64 rev1、1920×1200/dpi240、字体和动画设置，并记录 SDK/系统镜像版本、CPU/内存限额与前后环境。更换为 KVM 后必须视作新环境，不能与旧 TCG 时间直接合并。

## 准确版本与执行命令

本次 `candidate-pan-cleanup-v3` 离线、单 worker 构建成功 118.094 秒，92 项任务 4 执行/88 UP-TO-DATE。744 个构建输入与 `f84ede69f064bdebbd2427d06737a309525de241` Git blobs 全部匹配。只构建 app/appTest；本轮 JVM、lint、data-local 原生测试为 **NOT_RUN**。v1 参数重复导致 Gradle 在任务执行前拒绝；v2 编译成功但静态复核后补强为 v3，v2 未运行原生测试；原失败和两个构建回执均保留。

| 构件 | SHA-256 |
| --- | --- |
| 当前及前次 hex-v1 主 APK（完全相同） | `d4af16103b601e7d73af673eeb89d6511003a0f0ef4c86785862d5e202714b5e` |
| 本次 f84ede6 appTest | `4c8a3952b049dd9ab5aeaf9815ec2c221e49d9df00b8bebad33166454aa13913` |
| 历史 hex-v1/C2 appTest（721b2ff 外部绑定） | `5ce3e01030cc7ce27d93ed3aed7027a4f670716785235c569c0d4e84e3528407` |
| 搜索最后 v4 dataTest（c60fba7 外部绑定） | `79a7280c9667ef4721b4606ffc17a4bd7c476ceeb661d4d015ec9c19769324a3` |
| 备份最后 v2 dataTest（743 文件冻结/重建清单，非当前整包） | `b2f46999fbccae5649c2455800d0bda355a845624c2c745bc74235a4eb841c0c` |

APK 保留在云端私有 scratch，不提交二进制。主 APK 四 ABI MuPDF ELF 与签名复核通过；`SOURCE_COMMIT=local-unknown`/`FORMAL_BUILD=false` 的调试包仍采用外部源码清单与字节哈希绑定，不是内嵌最终 SHA 的正式包。当前 appTest 另保留于 `/workspace/scratch/pr20-preserved-apks/candidate-pan-cleanup-v3/app-debug-androidTest.apk`。

本轮实际执行的两个独立命令如下（每个结果都严格核验 `OK (1 test)`，ADB exit 0 不等于 PASS；phase 不得覆盖旧证据）：

```bash
python3 /workspace/scratch/pr20-pan-observed-run.py candidate-pan-cleanup-v3-interrupted org.inkweft.app.TileLocalRedrawTest#interruptedPanWaitJoinsItsWorkerBeforeClosingResources 1
python3 /workspace/scratch/pr20-pan-observed-run.py candidate-pan-cleanup-v3-original org.inkweft.app.TileLocalRedrawTest#integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture 1
```

后续满足条件时，先在隔离 checkout 固定 `f84ede69f064bdebbd2427d06737a309525de241`。以下其余构建/安装/方法命令 **本轮未执行**；每项必须独立保存 stdout/回执与源码、APK 哈希，不串跑到失败之后：

```bash
# 当前 workspace 的单 worker 工具包装；SDK/JDK/Gradle 与允许的官方依赖缓存已备好。
# 包装需从 android/ 工作目录调用；缺依赖时停止，不绕过下载策略。
cd /workspace/InkWeft/android
bash /workspace/scratch/run-inkweft-gradle-pan --offline \
  :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest

ADB=/workspace/.setup/tools/android-sdk/platform-tools/adb
"$ADB" -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s emulator-5554 install -r -t data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk

# 搜索原方法：128 行/10秒，随后原有输入、清空和分类断言。
"$ADB" -s emulator-5554 shell am instrument -w -e class org.inkweft.data.LibrarySearchRepositoryTest#twentyThousandPageRapidInputClearAndCategoryCancelOldGenerationOffMain org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner

# 备份原方法：实际 partial 后取消，原15秒门槛。
"$ADB" -s emulator-5554 shell am instrument -w -e class org.inkweft.data.LibraryBackupRepositoryTest#cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner

# 原密集完整帧：1201笔/76802点，原30秒。
"$ADB" -s emulator-5554 shell am instrument -w -e class org.inkweft.app.StreamedInkRasterTest#completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner

# 单独定位pan；五个阶段标签开启，不改变各30秒门槛。
"$ADB" -s emulator-5554 shell am instrument -w -e tilePanDiagnostics true -e class org.inkweft.app.TileLocalRedrawTest#integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner

# localAppend方法；只有双方相同测试补丁、预先固定顺序/样本数时才形成新的性能配对。
"$ADB" -s emulator-5554 shell am instrument -w -e class org.inkweft.app.TileLocalRedrawTest#oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner
```

这些命令是各自独立入口，不是要求顺序执行整块。原密集用例的失败 finally 仍是历史 `withTimeoutOrNull`，本轮没有宣称替它补齐严格释放；每次独立 runner 失败后必须先检查目标进程并停止后续样本，不能用退出码、进程消失或小夹具代替正常释放断言。
