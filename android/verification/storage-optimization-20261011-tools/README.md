# LeakCanary 与 Macrobenchmark 验证记录

日期：2026-10-11。本轮构建与二进制验证通过，应用APK固定使用 `batch-04/source-r5` 的生产输入，宏基准驱动使用 `source-r6`，未安装到产品包。操作入口见[模块说明](../../macrobenchmark/README.md)，物理设备按[真机清单](../../DEVICE-TEST-CHECKLIST.md)执行。

## 已验证

- `:app:assembleLeakCheck`、`:app:assemblePerformance`、`:macrobenchmark:assemblePerformance` 通过；performance 的 `lintVital` 通过。
- 五个应用变体的实际依赖图检查通过：只有 leakCheck 包含 LeakCanary2.14；debug、tlsProbe、performance、release不包含它，所有应用变体均不包含 Macrobenchmark 驱动。独立驱动锁定 Macrobenchmark1.5.0、UI Automator2.4.0，且不含 LeakCanary。
- 实际APK DEX中 AppWatcher仅出现于leakCheck，BenchmarkFixtureProvider仅出现于目标包，MacrobenchmarkRule仅出现于驱动包。
- 目标包非debuggable且允许shell profile，fixture受signature权限保护；驱动是独立self-instrumenting测试进程。
- 三包APK签名校验通过且证书相同；ZIP原生库16KB对齐检查通过。测试驱动与内存检查包的debuggable标记符合其用途。

| APK用途 | 实际包名 | SHA-256 |
|---|---|---|
| leakcheck | `org.inkweft.app.a0.leakcheck` | `3097fe0b9d39f97ecd38bb3f77ccbb9be224a2b3344aaef3a449f17e356cca05` |
| macro-target | `org.inkweft.app.a0.macrobenchmark` | `e19a70652b7e41a6c84e172427432bcc720bb58bf903837961843b1cc02d9364` |
| macro-test | `org.inkweft.macrobenchmark` | `2d088c54d9566f15cda89cfe778f14fda4b04b227357f34b1e99a7004a18656f` |

## 运行结果

MuMu5 / Android12 / API32 的 `FixtureNavigationSmokeTest` **1/1 PASS（37.482s）**：同签名夹具初始化、24,000笔大页完整绘制、翻页和双笔记切换均完成，实际页面ID与绘制完成条件已核对。未签名shell调用fixture被权限拦截；LeakCheck启动返回Status:ok，当前进程日志出现 `LeakCanary is running`。原始runner、逐方法时间、权限拒绝、启动日志与摘要见本机归档 `verification/device-attempt01/`。

三包已卸载，MuMu5关闭且root=false/旋转设置保持，私有ADB55125停止，清理回执确认其他包未改动。实机Macrobenchmark性能测量和实机泄漏检查均为 **NOT_RUN / 待测**；当前结果确认模拟器功能与LeakCanary初始化，不作无泄漏或性能提升结论。

## 复现入口

在 `android/`：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleLeakCheck :app:assemblePerformance :macrobenchmark:assemblePerformance -PinkweftMacrobenchmark=true
./gradlew.bat --offline --no-daemon --init-script verification/storage-optimization-20261011-tools/dependency-isolation.init.gradle verifyMemoryAndBenchmarkIsolation -PinkweftMacrobenchmark=true
```

本机完整证据保存在 `E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/batch-04-tools-r3/`，含三个安装包、`verification/apk-manifest.json`、实际manifest/签名/对齐输出、依赖隔离报告、构建日志与输入摘要。初次依赖清点因AGP多artifact变体歧义失败，改为官方依赖解析结果图后检查通过；初次源码候选及原始失败日志在 `batch-04-tools/` 保留。source-r5三包构建与依赖检查全部通过（7m46）。首次导航冒烟在切本Popup中缺少resource-id，驱动改用固定夹具可见标题选择条目，并等待实际目标页ID完成绘制；Smoke和性能场景共用该操作。仅3个驱动源文件改变，source-r6驱动独立重建通过（26s）；target/leak复用r5原SHA，manifest记录原构建来源以及99个未变输入。修正后的新库初始化包含在当前应用APK内。工作区重复旧日志已按摘要归档清理，当前源文件与构建入口保留。
