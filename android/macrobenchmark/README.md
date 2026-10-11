# 内存检查与 Macrobenchmark

本目录为开发验证入口。依赖锁定为 LeakCanary 2.14、AndroidX Macrobenchmark 1.5.0 和 UI Automator 2.4.0（2026-10-11 核对官方稳定版）。

## 构建与隔离

在 `android` 目录运行，使用项目现有 JDK 17、Android SDK 36 和 Gradle wrapper：

```powershell
./gradlew.bat :app:assembleLeakCheck
./gradlew.bat :app:assemblePerformance :macrobenchmark:assemblePerformance -PinkweftMacrobenchmark=true
```

`leakCheck` 基于 debug，包名追加 `.leakcheck`，只有该变体包含 LeakCanary；`debug`、`tlsProbe`、`performance`、`release` 的 `LeakTracking` 均为空实现。不要用内存检查包测性能。

基准目标包固定为 `org.inkweft.app.a0.macrobenchmark`，非 debuggable、允许 shell profile；测试 APK 在自己的进程运行。`-PinkweftMacrobenchmark=true` 不能与工作台包、插页包、TLS fixture 或 unsigned 构建参数组合。默认使用同一 debug 签名；若使用现有 `INKWEFT_SIGNING_*` 环境变量，目标和测试 APK 都使用该签名。

仅 performance 源集含 `BenchmarkFixtureProvider`。其合成数据方法同时检查固定目标包名和 signature 权限，不支持任意导入、清空或删除数据库。12 页样本使用固定 UUID 和幂等命令：第一页 24,000 笔、每笔 48 点，共 1,152,000 点；其余 11 页各 600 笔、每笔 32 点。首轮初始化按 200 笔提交。不要向此专用包存入个人资料：AndroidX 在部分旧版系统上重置编译状态时会重装目标包。

## 功能验证

安装目标与测试 APK后，单独运行功能冒烟测试。此测试可在模拟器执行，验证签名授权、数据初始化、真实大页绘制、翻页和切本，不产出性能分数：

```powershell
./gradlew.bat :macrobenchmark:connectedPerformanceAndroidTest -PinkweftMacrobenchmark=true "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.macrobenchmark.FixtureNavigationSmokeTest"
```

## 实机基准

连接一台已解锁的 Android 12 / API 31 或更新设备，关闭其他测试任务，记录型号、系统、刷新率、电量、温度、动画倍率和 APK SHA-256。保持这些条件一致后运行：

```powershell
./gradlew.bat :macrobenchmark:connectedPerformanceAndroidTest -PinkweftMacrobenchmark=true "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.macrobenchmark.ViewportBenchmark"
```

每个场景测量 5 轮，采用 `CompilationMode.Full()` 固定 AOT 编译条件；它用于同设备版本对比，不代表用户初次安装的编译状态。四个场景为：冷进程启动资料库、打开大页、在第 1/2 页间连续切换 6 次、在两本笔记间连续切换 6 次。数据准备放在测量外。页面等待通过 Binder 请求，主线程逐帧检查实际页面 ID 和 `viewportFrameReady`，60 秒仍未完整绘制即失败，不使用固定等待时间。

冷启动记录 `StartupTimingMetric`（首次显示）和 `FrameTimingMetric`；其余场景记录帧时长、帧超时和 `InkWeft.spatial.query`、`InkWeft.ink.window`、`InkWeft.ink.frame` 的 trace 累计时间/次数。Count 为 0 表示没有观测到对应 trace，不能仅凭 SumMs 为 0 认定工作没有耗时；异步区间可能重叠，累计值也不等于端到端延迟。保留 AndroidX 输出的 JSON、Perfetto trace 和设备条件记录，产物位于模块 `build/outputs`；不要只抄平均值，也不要将冒烟结果或模拟器结果标成实机性能通过。

不配置 `suppressErrors` 或 dry-run 来取得基准分数。设备配置、热状态或系统限制导致基准失败时，保留错误并修正条件后重跑。

## LeakCanary 检查

安装 `app-leakCheck.apk`，启动后确认 logcat 中出现 LeakCanary 已运行的信息。重复打开笔记、翻页、切本、打开/关闭面板，再关闭标签并退出 Activity。暂时离屏且保留撤销历史的 actor 不应被标记为已释放。应用在 `InkViewModel.onCleared` 和 Compose `AndroidView.onRelease` 后调用 `watchReleased`；LeakCanary 同时监测 Activity 等平台生命周期。

在 LeakCanary 界面检查 retained object 和泄漏路径；出现问题时保留对象描述、引用链、堆分析结果以及复现步骤。等待和一次无泄漏结果不能证明不存在泄漏。真实设备用例与结果记录由项目 `DEVICE-TEST-CHECKLIST.md` 维护。

## 本次验证

依赖隔离、APK编译、签名、包内容和ZIP 16KB对齐检查通过。最终模拟器功能冒烟 **1/1 PASS（37.482s）**，覆盖24,000笔大页绘制、翻页和切本；未签名fixture调用被拦截，LeakCanary启动与初始化日志验证通过。三包已卸载，MuMu和私有ADB已清理。

最终驱动来自source-r6，SHA-256：`2d088c54d9566f15cda89cfe778f14fda4b04b227357f34b1e99a7004a18656f`；应用生产输入仍为source-r5。完整输入、APK与运行证据见[工具验证记录](../verification/storage-optimization-20261011-tools/README.md)。实机Macrobenchmark和实机泄漏检查均为 **NOT_RUN / 待测**，当前结果不代表无泄漏或性能提升。

## 官方依据

- [LeakCanary 接入和默认生命周期监测](https://square.github.io/leakcanary/getting_started/)
- [LeakCanary 自定义对象观察](https://square.github.io/leakcanary/changelog/)
- [Macrobenchmark 稳定版与依赖](https://developer.android.com/jetpack/androidx/releases/benchmark)
- [Macrobenchmark 编写与冷启动语义](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Macrobenchmark 帧指标与同步/异步 trace 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [Android 官方独立测试进程配置示例](https://github.com/android/performance-samples/blob/main/MacrobenchmarkSample/macrobenchmark/build.gradle.kts)
- [UI Automator 稳定版](https://developer.android.com/jetpack/androidx/releases/test-uiautomator)
