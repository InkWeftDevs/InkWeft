# V68 真机验收记录（2026-10-03）

固定受测源码：`15f167ed1d1270617e51e0aacd21b5b7caa9bb18`；版本 `68 / 0.0.68-cloud-candidate`。本地分支 `codex/local/v68-acceptance-20261003`。设备为 vivo iPA2673，Android 16 / SDK 36，公开别名 `physical-iPA2673-20261003`。

本次完成了原证书核对、本地成对构建及签名、独立 Room runner 构建、真机覆盖安装和方法验收。**Room 12 个唯一方法全部通过；UI 验收被进程冻结阻塞，候选未完成验收。** 本地新增内容仅为验收记录，没有修改生产代码或既有测试。

|范围|通过|环境阻塞|未执行|
|---|---:|---:|---:|
|data-local Room|12|0|0|
|LM66 新增及旧回归|0|2|9|
|ReviewRound UI|0|0|5|
|合计（唯一方法）|12|2|14|

准确方法、各次尝试和分类见 [pending-runtime.json](pending-runtime.json)。两个环境阻塞方法的状态保留为 `FAIL`，但没有产品断言失败结论。Room 原始成功事件的公开副本见 [physical-device-report.json](physical-device-report.json) 的逐方法索引与 `room-method-logs/`；完整本机原始证据保留私有。

## 实际证据

- 主包和 app runner 从同一冻结源码、同一组参数构建，JDK 17、项目 Gradle wrapper、SDK/build-tools 36、单 worker；源码身份同时写入 `INKWEFT_HEAD_SHA` 与 `GITHUB_SHA`。453 项输入构建前后未变，Windows CRLF 差异单列。
- 原证书 SHA-256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。旧主包、旧 runner、V68 主包及两个 runner 均核对同一证书；三份安装后的 APK 摘要与本地产物一致，重启后再次核对。
- 主包与 app runner 的未签名构建耗时 8 分 3 秒；独立 Room runner 构建耗时 1 分 19 秒。Room runner 的 AGP 默认签名仍生效，实际证书为原证书，未将该包记为 unsigned。精确大小、SHA 和构建重试见 [local-build-receipt.json](local-build-receipt.json)，签后身份见 [physical-device-report.json](physical-device-report.json)。
- Room 首轮精简输出中 12 个 JUnit 摘要均为 `OK (1 test)`，采集器因缺少方法级事件误记失败。原日志保留并标为采集错误；改用 `am instrument -w -r` 后，12 个唯一方法的 class/test、成功代码 `0` 和 `OK (1 test)` 全部核对。只计 12 项通过。

## UI 阻塞及诊断边界

以下两个方法出现启动事件，但没有可核准的完成事件：

1. `LocalMotionUiTest#cardSectionsEnterOnceAndDisposeCollapsedSourceAtTheNextRealFrame`
2. `LocalMotionUiTest#answerEnterCannotRetainPreviousQuestionCluesAcrossSkipHideAndExit`

观察到测试进程的 `cgroup.freeze=1`、`cgroup.events` 中 `frozen=1`，进程记录中的不同冻结字段不一致。普通前台启动、系统 `am unfreeze --sticky`、受控的 freeze/unfreeze，以及临时允许后台耗电未解除测试等待。经授权重启、解锁并重新确认 ADB 后，第一项仍连续三次被监测为内核冻结，队列随即停止。临时后台耗电选项已恢复为原来的“智能控制后台耗电”，诊断进程均已结束。

普通冷启动可到 `MainActivity` 前台；四次短时采样均为未冻结。这只证明普通启动的限定观察，不代替 UI 功能验证。**触发 instrumentation 进程冻结的来源尚未确定**，没有通过修改断言、跳过检查、清库、换 applicationId 或混用旧包取得通过结果。解除阻塞后，应继续这 16 个 UI 方法。

## 原资料与未验事项

真机原装 V47。覆盖安装前，停稳应用并保存旧主包、旧 runner、内部及外部数据快照，核验归档可读及逐文件摘要。原库 schema 12、29 张表、2535 条记录；首次启动、诊断后及重启后的全行摘要均未发现原记录缺失或改变。没有卸载或清除数据。

此结果不等于完整旧库兼容或真实恢复通过。原笔记逐页视觉/原迹、完整学习链路、真实 PDF 渲染与导出边界、完整 OS 死亡故障切点、完整备份/空库恢复以及真人笔感仍待验，见 [DEVICE-TEST-CHECKLIST.md](../../DEVICE-TEST-CHECKLIST.md)。云端交接的 307 项核心通过未在本轮重复执行。

公开材料只含合成方法事件、构建身份和脱敏统计；设备唯一标识、真实资料、原始系统日志/截图、备份与签名材料保留本机，不进入 Git/PR。
