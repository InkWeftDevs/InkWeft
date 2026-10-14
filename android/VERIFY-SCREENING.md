# 日常筛查与正式性能验收

日常修复先用当前云工作区的增量输出，只检查相关范围；稳定候选再执行冻结的三端、同机、四组完整验收。筛查结果统一标记 `SCREENING_ONLY`、`formalAcceptance=false`，不复用原生性能样本，不改变正式样本数、阈值、正确性或清理规则。

从仓库根目录运行：

```sh
python3 android/verify.py data --screen search
python3 android/verify.py data --screen paging
python3 android/verify.py data --screen history
python3 android/verify.py app --screen raster
```

无设备时只运行所选JVM测试并编译相关Android测试源码，不组装三端九个APK、不运行Room或原生性能。使用现有JDK17/SDK/Gradle缓存，默认离线、单worker、同工作区构建锁。可用 `--gradle /absolute/path/to/gradle` 指定已安装的固定版本，避免重新下载wrapper；本项目固定Gradle9.4.1。`--plan-only` 输出选择清单并标记 `NOT_RUN_PLAN_ONLY`。

已有独占的合成测试模拟器时，可运行对应正确性；加 `--performance` 才附加该范围现有的完整探针：

```sh
python3 android/verify.py data --screen search --serial emulator-5554
python3 android/verify.py data --screen search --serial emulator-5554 --performance
```

仅接受显式 `emulator-N` 且实际 `ro.kernel.qemu=1` 的设备，使用独立 `.verify` 包；不访问真实笔记。脚本不创建模拟器或修改KVM ACL，模拟器的拥有者仍负责生命周期、总时限与ACL恢复，不与正式采样并行。成功/失败均在构建锁内停止筛查包，核对主进程及冒号命名的辅助进程消失；清理失败使整体失败。

| 范围 | JVM与测试源码 | 设备正确性 | 可选性能探针 |
|---|---|---|---|
| search | 搜索词/观察失效测试，data AndroidTest编译 | 搜索仓库及分页/取消快照 | 2万页搜索、并发保存、目录读取规模 |
| paging | 搜索词测试，data AndroidTest编译 | SQL分页、稳定排序、刷新与快照 | 同一资料库读取探针 |
| history | 原文存储/验证测试，data AndroidTest编译 | 根引用、安全删除、并发写入 | 大量GC及两种连接的自然删除暴露 |
| raster | 视口/层头/笔迹模型测试，app与AndroidTest编译 | 局部瓦片、native形状复用、流式绘制 | 连续变换、分散脏区与冷重建像素比较 |

证据写入新的目录，拒绝覆盖。`result.json`保留命令、耗时、选中测试、APK哈希和原日志；`jvmTaskOutcome`区分本次 `EXECUTED` 与Gradle的 `UP-TO-DATE`/`FROM-CACHE` 结果复用。JVM所选suite必须完整、非空且无失败/跳过；设备筛查逐项核对所选源码的具名测试，缺失、重复、零测试或跳过均失败。编译/缓存命中不能算原生测试通过。当前新增设备筛查路径仅做离线控制测试，尚未在原生模拟器上执行。

## 下一独立构建的缓存试点

`ci/pr20_build_cache.init.gradle` 提供显式启用的私有Gradle任务缓存。它已通过真实Gradle9.4.1的离线小任务试验；尚未证明本项目Android三端构建的命中量、APK一致性或节省分钟数，因此没有改写既有冻结控制器，也没有开启新的正式任务。

调用方先验证来源清单、补丁/探针、工具链和依赖内容，再提供身份JSON及其SHA256。JSON字段：`schema=1`、`gradle_version`、实际 `java_version`/`java_vendor`，以及 `source_spec_sha256`、`probe_manifest_sha256`、`toolchain_sha256`、`dependency_manifest_sha256`。这些内容哈希必须来自实际核验；init只核对声明文件哈希和Gradle/JVM版本，不替调用方审计整个源码/依赖清单。

设置 `INKWEFT_TASK_CACHE_DIR` 为本任务新建的空目录（`RUNNER_TEMP` 严格子目录，或云端 `/workspace/scratch/pr20-build-cache/` 子目录），并设置 `INKWEFT_TASK_CACHE_IDENTITY_FILE`、`INKWEFT_TASK_CACHE_IDENTITY_SHA256`。下一受审控制计划可将单个构建开关改为：

```text
--build-cache --init-script <固定哈希的ci/pr20_build_cache.init.gradle>
```

三端共用整份冻结计划的身份分区；Gradle按各任务声明的源码、依赖、插件实现和参数决定命中或重建。身份失配拒绝运行，内容输入变化重建相关任务，不强制不可缓存任务变为可缓存。远端任务缓存禁用。正式控制器的三份独立源码树、九个APK组装、内嵌commit、签名、MuPDF四ABI、依赖字节、安装前哈希与完整原生验收仍须保留。

本轮沿用现有云环境的Gradle依赖/MuPDF下载缓存；三端在同job本来已共用下载缓存，25分46秒不能归因于重复下载。未新增远端缓存、APK上传或二进制白名单。跨job直接复用旧APK暂不采用：新build/source身份与旧APK可能不同，而且当前工件仅允许文本，不能据旧回执假定二进制仍存在且相同。

本轮耗时拆分、实际筛查/缓存试验及限制见 [周转验证记录](verification/pr20-turnaround-20261014/README.md)。最近完整原生任务的有限终态回执见 [candidate3记录](verification/pr20-candidate3-terminal-20261014/README.md)。
