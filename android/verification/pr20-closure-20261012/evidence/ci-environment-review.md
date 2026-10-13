# PR #20：CI 与云环境只读核查

核查对象：[InkWeft PR #20](https://github.com/InkWeftDevs/InkWeft/pull/20)，草稿分支 `codex/android-perf-integration-20261012`；核查时已推送 HEAD 为 `a75d7457cfa3ec2e15c802c07ee93bfc07753a6c`，目标分支为 `codex/storage-optimization-20261011`。后续提交需要重新记录实际运行的 SHA。

本次仅读取 GitHub 元数据、工作流源码、测试入口与云环境文件。未触发 CI，未修改工作流、凭据、权限或配置，未运行 ADB、构建或测试，未下载镜像。本报告是验证入口及限制说明，不是新增测试通过记录。

## CI 入口与适用范围

| 工作流 | 已注册 ID | 历史运行证据 | 草稿分支可选输入 | 本次五项覆盖 |
| --- | --- | --- | --- | --- |
| `android-a0.yml`，Android 按改动验证 | `365215038` | [main 上历史成功运行 35880596671](https://github.com/InkWeftDevs/InkWeft/actions/runs/35880596671) | `mode=full` | 枚举全部原生测试，包含五项；另运行构建、静态检查、备份、Docker、TLS 等，无五项定向模式 |
| `android-six-batches.yml`，六批定向云端验证 | `374470886` | [旧隔离分支历史成功运行 37265155262](https://github.com/InkWeftDevs/InkWeft/actions/runs/37265155262) | `mode=focused` 或 `mode=full` | full 包含 `LibraryBackupRepositoryTest`，但其余四个目标测试类不在固定清单；focused 也不是本次五项复验清单 |

下面仅记录手动入口，**未执行**：

```sh
gh workflow run 365215038 --repo InkWeftDevs/InkWeft --ref codex/android-perf-integration-20261012 -f mode=full
gh workflow run 374470886 --repo InkWeftDevs/InkWeft --ref codex/android-perf-integration-20261012 -f mode=full
```

REST 对应入口为 `POST /repos/InkWeftDevs/InkWeft/actions/workflows/{workflow_id}/dispatches`，请求体为：

```json
{"ref":"codex/android-perf-integration-20261012","inputs":{"mode":"full"}}
```

这两个工作流均已有历史运行，具备可定位的 API/CLI dispatch 入口。核查发现 main 的 A0 文件没有 `workflow_dispatch`，main 的六批文件不存在；但不能单凭这两点断言 API dispatch 必然失败。GitHub 官方说明，工作流至少运行过一次后可以通过 API/CLI dispatch 到其他分支或标签。UI 按钮与 API 可用条件应区分。实际 Actions 写权限、工作流当前状态与请求是否受理没有通过触发动作验证。

来源：[GitHub workflow_dispatch 规则](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)、[REST dispatch 权限与参数](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event)。

核查时 `GET /repos/InkWeftDevs/InkWeft/actions/runs?head_sha=a75d7457cfa3ec2e15c802c07ee93bfc07753a6c&per_page=10` 返回 `total_count=0`。现有 push/PR 分支过滤不覆盖本次 head/base，不能宣称该 HEAD 已通过 GitHub CI。

## 不能忽略的权限副作用与范围差异

- [A0 设备作业](https://github.com/InkWeftDevs/InkWeft/blob/a75d7457cfa3ec2e15c802c07ee93bfc07753a6c/.github/workflows/android-a0.yml#L201)执行 `sudo chmod 666 /dev/kvm`；TLS 作业第 297 行也执行同一命令。当前“不改权限”的约束下，不应原样触发并把它描述为只读验证。
- [六批作业](https://github.com/InkWeftDevs/InkWeft/blob/a75d7457cfa3ec2e15c802c07ee93bfc07753a6c/.github/workflows/android-six-batches.yml#L69)先保存 KVM ACL 和设备身份，临时对当前 UID 执行 `setfacl`，最后恢复并核对原 ACL。虽然具有恢复步骤，仍会修改权限；源码中的“用户已确认”注释本身不能替代当前会话授权。
- A0 full 不是五项最小复验：其计划枚举全部 app/data-local 测试，并开启备份、实验和 TLS。六批 full 的固定清单无法替代五项验收；full 模式实际运行 Room，focused 只有满足完整源码哈希与清单一致时才可能复用已有 Room 证据。
- 两工作流都构建 `inkweftInsertionPreview=true` 变体。`ci/run_android_tests.py` 固定 app runner 为 `org.inkweft.app.a0.insertion.test/androidx.test.runner.AndroidJUnitRunner`。当前云配对 APK 使用 `org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner`，不能直接混用；CI 重建结果也不能冒充现有配对 APK 的运行记录。
- `android-runner-diagnostics.yml` 仅在旧六批分支的特定文件 push 时触发，没有 dispatch；`android-source-snapshot.yml` 仅归档源码，没有原生测试，也没有 dispatch。它们均不是本次草稿 ref 的现成五项验证入口。

## 当前保存云环境能力

- `/dev/kvm` 不存在。CPU cgroup 配额为 4 核，内存上限为 16 GiB。KVM 加速需要云平台提供设备节点及既有可用访问权限，仅修改权限不能补出缺失的节点。
- 唯一已安装系统镜像为 `system-images;android-35;aosp_atd;x86_64` revision 1。没有已安装的标准 AOSP 或 Google APIs 镜像。
- 既有运行环境是 API35 x86_64 TCG/SwiftShader 软件模拟器，不是物理设备性能环境。此前用于软件模拟器的 AOSP `ro.hw_timeout_multiplier=10` 是系统等待参数；测试自己的 30 秒、15 秒、10 秒等时限没有因此延长。
- 已缓存的官方 SDK 元数据列出标准 API35 `default;x86_64` revision 2：782,404,023 bytes；API34 revision 4：720,747,116 bytes。两者均引用 `android-sdk-license`。本次没有下载或启动这些镜像。
- ATD 可运行 Android Room、CPU Bitmap 和 Compose 语义交互。ATD 官方支持范围不包含依赖硬件渲染的截图；系统 UI、IME 等组件也经过裁减。换标准镜像能够补足相关验证路径，但不能自动解决无 KVM 的 CPU 成本，也不保证现有超时用例通过。

来源：[ATD 官方说明](https://developer.android.com/studio/test/managed-devices#atd)、[模拟器图形与 VM 加速的区别](https://developer.android.com/studio/run/emulator-acceleration)。SDK 元数据本地证据：`/workspace/.setup/android-user/cache/sdkbin-1_72847604-sys-img2-4_xml`；对应缓存元信息记录官方来源 `https://dl.google.com/android/repository/sys-img/android/sys-img2-4.xml`。

## 五项未通过用例的最小能力条件

现有失败尚无已证实的共同环境根因。以下条件用于定位和补足验收，不能把 KVM、标准镜像或更快 CPU 当作必然修复，也不能通过缩小原夹具或放宽原断言冒充原规模通过。

| 用例入口 | 最小能力与必须保留的验证点 | 环境判断 |
| --- | --- | --- |
| `org.inkweft.data.HistoryConcurrencyTest#largeLibraryAuditAllowsAuthorCommitAndCancellation` | Android Room、完整两万条历史夹具、并发保存与取消；定位具体 30 秒等待阶段，并保留原保存/取消时限与活引用断言 | 不依赖硬件渲染。更快且无并发构建争用的 Android 执行环境有助区分 TCG 成本和实现问题；先看阶段证据 |
| `org.inkweft.data.LibrarySearchRepositoryTest#twentyThousandPageRapidInputClearAndCategoryCancelOldGenerationOffMain` | 完整两万页、原 10 秒首批索引等待、实际进入后续代次取消和 Main 线程响应断言 | 不依赖标准图形镜像。仅证明冷搜索能完成不足以证明快速输入取消通过 |
| `org.inkweft.data.LibraryBackupRepositoryTest#cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary` | 应用私有可写缓存、完整备份夹具、可观察的真实部分写入，随后实际发出取消并检查清理和作者资料保持 | 不需要 Docker 或硬件渲染。必须证实取消发生于写入期间；更快执行并不天然保证观察到取消窗口 |
| `org.inkweft.app.StreamedInkRasterTest#completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker` | 原 1,201 笔规模、完整后台读取/解码、30 秒内完成帧、末批像素与随后工作器/资源释放断言 | 使用 `Canvas(bitmap)`，硬件窗口不是本测试前置条件；标准镜像不能单独解释或消除其超时 |
| `org.inkweft.app.NotebookShelfUiTest#shelfRenameCancelBlankAndPersistedIdentity` | Awake、前台 Activity、Compose 就绪、原首次编辑器 15 秒等待、目标菜单就绪及全部重命名行为断言 | ATD 能运行此类语义交互；真实窗口截图、IME/系统外观验收另需标准镜像的对应能力。不能把 teardown 后首页黑屏作为编辑器失败根因 |

最小现成验证入口是父侧管理的同一云模拟器上逐项串行 instrumentation；安装、Awake、清理合成 app 数据与证据命名由父侧统一执行。本报告没有运行这些入口。

## historyBaseline 参数更正

`/workspace/scratch/run-inkweft-instrumentation.py` 接收：

```text
phase label module selection expected
```

其中 `phase=baseline` 目前只自动附加 `-e historyScanBaseline true`。这是 GC collection 探针参数；`HistoryConcurrencyTest` 实际读取的是 `historyBaseline`。

因此旧审计路径的基线复验需要显式传入：

```text
-e historyBaseline true
```

不能依赖现有通用 wrapper 自动设置正确参数。该差异在成功越过前置等待、到达 `committedWhileAuditPaused` 断言时尤其重要。候选不应设置该基线豁免参数。本次未修改 wrapper、测试源码或原有证据。

## 结论

两项主要工作流已有技术上可定位的 dispatch 入口，但现成配置存在 KVM 权限修改与范围不匹配，不能在当前约束下直接当作五项无副作用验证路径。当前云环境可继续逐项诊断；如阶段证据最终指向 TCG 时间预算，最小新增能力是拥有既有可用 KVM 访问的隔离云 Android runner，而不是调整当前安全权限或放宽测试。硬件窗口截图能力属于独立验收条件；物理设备性能仍须单独验证。
