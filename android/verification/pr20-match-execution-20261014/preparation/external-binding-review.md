# 外部日志绑定模板与激活差异复核

结论：本地已提交身份、readiness、控制文件和保守时钟绑定一致，未发现具体不匹配。已生成 `binding.template.json`，四个官方终态字段均为 `__PENDING_OFFICIAL_TERMINAL__`。没有分析进行中的日志，也没有假定本轮成功。

- run `38520196523`，job `115707901766`，attempt1；active `115734b0ed9aab07a1100087ce76978c0d829e0f` 的唯一父为 prep `b35d448862fe5963cdb57e0bc67042e87d22b1f6`，worktree clean。
- 激活精确3文件：新增 active workflow、proposed workflow 的40零before替换为prep、EXECUTION_SHA256SUMS对应两行更新。active/proposed workflow字节完全一致，branch固定、created=false、attempt1、ubuntu24.04、单job60分钟和contents:read保持。
- readiness `d70fcb23afecb6f80e8fc716a5145ecf851dd68e` 至active的Android树没有变化。C1 `cb7e14218ac83aa8cf6b9d7906051a10da1a6547`；B=a128+共同安全factory、C0=d66；62方法/14组/9APK、公共24/720/18/54仍由冻结合同绑定。13条EXEC清单逐项MATCH，root实际binding回执所列全部控制哈希MATCH。
- 已用冻结提取器原有 `frozen_inputs` 验证本地真实7键input哈希、源manifest/test blob、auth/budget/clock/receipt链、workflow guard以及统计/prepare脚本绑定；没有运行controller或新增checker功能。统计SHA `fdbf7b76ebbf587bccaede00910209ba2586c0982bbd0da80cb26cb94ce07740`；提取器SHA `bafc7ca19ad2fb0523dd5d859ef0b521c78d8715d888c30dee274fdf97eae800`。
- 本地官方 `initial-jobs.json` 核实唯一job于 `2026-10-14T06:05:35Z` 开始；冻结PR anchor为 `06:04:06Z`，早89秒。累计setup/correctness/common/work截止分别 `06:42:06`、`06:45:06`、`06:57:06`、`06:59:06Z`；实际job60分钟上限为 `07:05:35Z`。这只是初始身份/时钟核验，不是完成或性能证据。

终态后根节点应从官方 `run-terminal.json` 与 `jobs-terminal.json` 的实际status/conclusion复制四字段到新的 `binding.json`，保留模板；失败、取消或超时必须原样填写，不能为了提取器通过而填success。其余身份/哈希不变。将原官方decoded log与两个原JSON交冻结提取器后，才进行完整/部分证据判定；缺方法、缺FINAL、清理或官方失败仍保留INCOMPLETE。

PR服务器receipt中的head `607c891…` 是anchor来源；它与隔离readiness `d70fcb…`、被测C1 `cb7e142…` 作用不同，不要求三者相等。

模板SHA `89a5a419948389b38c48d519d3046fa01a9d30bd797b219e69237bc0fa29f37a`；机器回执 `external-binding-review.json` SHA `071030c906a2929da0fabce30c7ef54913d1ac7c6f6334ee1a233a6cecba7fa7`。未改输入文件、未触网、未启动任何新job/native。
