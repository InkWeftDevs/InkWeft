# 最终 GC 配对：清理总成本下降，保存延迟未改善

范围为新 1024/FULL 基线与候选 v4 各一次 `HistoryCollectionBenchmarkTest` 原生执行。两端均 1/1 通过；本文未混入旧 4096/INCREMENTAL 或 v3 数字。完整四组各 30 项原序数组、重新计算的分位数、输入哈希及绑定核对保存在 `final-paired-history-review.json`。baseline 全组后环境与三个 APK 的产物连续性记录现已补齐并核对一致。最终汇总 `paired-performance-summary-new-format.json` 的 history 子项与本报告的原始记录、回执、哈希、SQLite 配置及全部变化率完全一致；本结论仍仅覆盖 GC 配对，不将其当作整体性能验收已完成。

## 配对结果

同一夹具规模：2000 个历史来源根、4096 个可清理来源、1 个应保留的最新来源。实际主 writer 在计时结束后的 Room 写事务中读取到相同 PRAGMA：`page_size=1024`、`auto_vacuum=1`（FULL）、`journal_mode=wal`、`synchronous=2`；探针没有设置这些值。

| 指标 | 固定 a128 + 共同工厂基线 | 候选 v4 | v4 相对变化 |
| --- | ---: | ---: | ---: |
| 无竞争清理根扫描次数 | 17 | 1 | −94.12% |
| 无竞争清理总耗时 ms | 131305.840304 | 11354.422681 | −91.35% |
| 暂停根扫描场景扫描次数 | 1 | 1 | 0 |
| 不暂停清理 + 30 次保存 + 收尾清理根扫描次数 | 18 | 2 | −88.89% |
| 上述组合总耗时 ms | 120266.000140 | 15765.756480 | −86.89% |

无竞争清理观察到基线/v4 耗时比 11.564；组合场景为 7.628。它们各来自一次完整执行，不是多轮耗时中位数，也不能把组合场景当作独立 GC 时间。

| 保存场景 | 指标 | 基线 ms | v4 ms | v4 相对变化 |
| --- | --- | ---: | ---: | ---: |
| 暂停根扫描 | p50 | 76.064526 | 79.642262 | **+4.70%** |
| 暂停根扫描 | p95 | 92.799203 | 160.745357 | **+73.22%** |
| 暂停根扫描 | max | 154.892666 | 237.565828 | **+53.37%** |
| 不暂停清理 | p50 | 72.554328 | 76.398991 | **+5.30%** |
| 不暂停清理 | p95 | 146.327721 | 181.048899 | **+23.73%** |
| 不暂停清理 | max | 188.410436 | 197.854903 | **+5.01%** |

四组原序数组均为 30 个有限非负数。对排序副本重算的 p50=`sorted[14]`、p95=`sorted[28]`、max=`sorted[29]` 与日志逐项完全一致。这里 p50 是较小的中间样本，既不是两项均值，也不是 Library 探针的 `sorted[15]`。六个保存统计量在本次配对中均上升，尤其暂停场景尾延迟不能仅描述为“略回退”。本轮支持降低大批垃圾清理总成本，**不支持降低保存阻塞或保存延迟**；单次配对不够建立这些延迟变化的统计显著性或唯一原因。

## 计时与断言实际覆盖

- `collectionMs` 只覆盖首次无竞争 `collectDetached()`，不包含夹具建立、observer 注册及返回后的保留行数查询。两端均通过未 defer、删除 4096 行及最终剩余 2001 行的断言；仅候选额外断言根扫描恰为 1 次。
- 暂停场景在 `after-roots` 挂起清理，30 次 rename 逐次执行；每次计时包括原 5 秒 `withTimeout` 内的 rename，不包括此前读笔记或到达闸门等待。两端均通过后续 `collectionDeferred=true`、本次删除 0 行的原断言。
- live 场景在首个 `after-roots` 后发出 30 次顺序 rename；每项计时不包含此前读笔记。此组原源码没有逐项 5 秒 `withTimeout`，不能借用暂停组的门槛。总计时从清理任务启动前开始，包括到达首个根回调、保存及间隔读笔记、等待首次清理返回、随后再做一次收尾清理。
- live 原断言验证第二次收尾未 defer、两次合计删除 4096 行、最终来源行数为 2001；没有单独输出首次调用删除数。2001 是行数断言，不是该探针对所有留存内容逐字节验证。
- 两组均调用真实 `NoteRepository.rename`；探针没有逐项记录或显式断言返回值为 `SaveResult.Committed`，因此数组的直接证据是 rename 调用完成时延，不能升级为逐次提交结果审计。
- scans 是 `after-roots` 回调计数，不是所有 SQL 次数或单独量出的占用扫描次数。`observerNotifications=33` 两端相同，统计跨首次清理、重新插入及后续场景，不能当作保存数、删除批次数或锁等待计数。

## `liveFirstPassDeferred` 的准确含义

两端原值确实均为 `true`；共享探针直接输出 **第一次不暂停 `collectDetached()` 返回的 `liveReport.collectionDeferred`**。这个“FirstPass”不是第一次 256 条删除批次。

基线每一轮创建独立 reader、构建根和统计，在结束读快照后、持有 Room writer 时比较该 reader 的 `PRAGMA data_version`。不等时该轮不删除并将 `changed=true` 返回；此前其他轮可能已提交删除。候选先建立一份私有候选快照，逐批短写事务检查 `(primary data_version, total_changes()+pending invalidation log rows)` 是否仍等于预期；不等时停止，此前其他批次也可能已经提交。

因此两个 true 只证明各自首次 live 调用以其版本变化分支返回。日志没有输出版本值、触发变化的写入、失败批次、首次已删除数或两次调用各自扫描数。不能声称两端都“首批零删除后延期”，不能声称使用同一种 guard，也不能把基线 18 次扫描强行分成“延期 1 次 + 收尾 17 次”。**明确验证零删除的是另外的暂停场景。** JSON 对这些未观测细节保留 null。

## 来源、源码、APK 与环境

| 执行 | JUnit 秒 | host 命令秒 | 原始日志 / 回执前缀 |
| --- | ---: | ---: | --- |
| 基线 | 359.965 | 374.735872172 | `baseline-final-performance-history-probe` |
| v4 | 116.218 | 128.967283250 | `candidate-v4-performance-history-probe` |

基线是固定提交 `a128bb07e34cb82f00eeafc8e62a47bf7b95b128` 加共同的安全初始化工厂修正及相同探针，**不是完全未修改的远端原始提交**。独立重算基线 `HistoryStorage.kt` 与该固定 Git 源文件完全一致；两端探针 / 共同工厂分别字节相同：

- `HistoryCollectionBenchmarkTest.kt` SHA-256：`2b04bd2de3bc4cd6d030b14b8c12096c3a3604ddaa39324a65703cee1de30e34`
- `PreservingOpenHelperFactory.kt` SHA-256：`a049af775caa688cd0dfd938acdf1539d34ee13de272ed35c930de9ef4e3d15d`
- 基线 HistoryStorage SHA-256：`0a1c4df6b8fbe6d8a3033a03a8314586bbd0ecf13ce5412ac9b1a2a133580230`
- v4 HistoryStorage SHA-256：`07046d5d7653723153744cff81ca63430a778112b49c31d16bd6e7535f856677`

基线 `historyScanBaseline=true` 仅影响输出标签并跳过候选独有的单次根扫描断言，不改变数据、计时、30 次保存或删除/保留断言。命令附加的 `historyBaseline` 不被本探针读取。当前基线 734/734、候选 744/744 文件仍与各自构建冻结清单一致；两端成功构建回执均记录构建期间源码未变。

两个 data-local test APK 的产物、成功安装回执及当前本地文件哈希各自一致，安装均早于对应 probe，安装与运行 serial 均为 `emulator-5554`：

- 基线 APK SHA-256：`bbd78f8daa9139acdaeaac7c01fd0dc9fe762723c3750a0f26a55f5df2e2b505`
- v4 APK SHA-256：`79a7280c9667ef4721b4606ffc17a4bd7c476ceeb661d4d015ec9c19769324a3`

v4 与 baseline 两组各自前、后四份环境记录的 comparison values 完全相同，comparison SHA-256 均为 `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`。同一模拟器 PID / 启动身份，API35 x86_64、TCG / SwiftShader、无 KVM、2 核 / 2048 MB、1920×1200 / 240 dpi、font_scale=1.0，记录的超时倍率为 10，animator 设置原样记录为字符串 `null`。相同记录不证明运行全程负载、cache 或瞬时设置相同。数据库和身份使用新 UUID，夹具规模与内容规则相同，不是数据库文件字节相同。

已补核 `baseline-final-performance-environment-after.json`（2026-10-13T01:31:00.142202Z 至 01:31:08.539736Z，记录无错误）及 `baseline-final-performance-artifact-continuity.json`（01:31:18.239164Z，三个 APK 均 MATCH）。三个产物逐个与原产物清单、成功安装回执和当前本地 APK 字节哈希吻合。GC 原始数组及结果未变，最终 parser 的 history 子项复核通过。现有源码、构建、哈希、安装和运行回执构成可追溯链；本次未重新读取设备中的已安装 APK，也未独立证明 DEX 对应源码。

本报告仅针对 GC 配对；不替代损坏、取消、跨连接活引用等专门回归，也不关闭独立搜索、备份或绘制时限。只读取已有证据，输出本 MD/JSON；未改主仓或基线源码、未运行 ADB、构建、原生测试、提交或推送。
