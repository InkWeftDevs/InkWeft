# PR20 causal UNBOUND 控制器：独立静态复核

结论：本次所读模板未发现剩余确定性执行阻断。两项 schema 漏项已关闭；此结论不是激活许可，也不是原生通过。当前控制器、授权与 readiness 占位仍为 UNBOUND，零 before guard 配合 created=false 不会执行；最终 source/readiness/授权绑定与 prep→active 精确变换须由根节点另行核准。

候选源码由根节点冻结为 `78a5babeaeb92292026423222d5e08635989198b`。审查只读本地源码、历史原日志及作者离线回执；没有执行控制器、离线 checker、构建、ADB、设备、GitHub、网络下载、ACL 或任何新任务。报告中的离线 PASS 是核对作者回执及当前输入哈希，不是本审阅者重新执行。

## 所报阻断及关闭

1. 保存诊断先插500×40页，再 WorkspaceRepository.create 创建作者及其一页，实际 notebook_pages=20001。早期 schema 上限20000会拒绝合法 method10。现 save schema 与 merged contract 均精确 const20001，checker 新增明确的20001正例与20000拒绝例，不再依赖 schema 自身最小值生成样本。
2. 早期所读 STAGES 白名单缺 FULL 的 observe-* 与 index-build-completed。当前从 LibrarySearchObservation/Repository 的真实常量补齐，maxLength=23；新增全部真实 FULL 阶段正例。runtime说明该修复在preflight04前已开始，不能把较早读取快照误报成最终文件仍有缺陷。

## 执行与证据边界

- 固定一源 C1，无公共探针覆盖或 production override；13个单方法、两安装组：7个既有正确性、1个Unicode正确性、5个独立诊断。当前全部具名方法确实存在，contract中的13项test SHA均对应实际源码。旧 original-protocol SHA保持1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba。
- 一次构建目标app/appTest/dataTest三个APK，--no-build-cache/max-workers1不变；验证签名一致、嵌入源/执行Git及非formal身份、MuPDF四ABI ELF、data SQLite四ABI ELF、依赖哈希。两个安装组分别只装data与app+appTest；安装后pm path限定base.apk路径并核实际SHA等于构建回执，不以安装命令成功代替身份一致。
- 原raw解析要求 exact class/method 两份状态、numtests=1、唯一OK(1)、一个启动码1、有效0码与唯一终态-1，不接受skip/fail或额外测试。rawJUnit状态与host状态分开；缺失/畸形marker不升级为通过。每方法需三固定包自然退出，合法pidof格式且stderr空，不做am kill/force-stop。任一失败/不完整停止后续，无重试。
- marker整块先验证再转发，拒绝未知前缀、重复JSON key、NaN/Infinity、额外字段、计数/字节超界及不闭合块。save固定12case、每case30save、正反模式次序与分块事件完整性；GC固定各30trial，quantile nearest-rank不变且仍NOT_EVALUATED；raster固定1/4/8三payload及5×层数frame。schema闭合不等于所有阶段因果关系已独立复算；GC曝光分母/真实DELETE重叠、save同block fixture一致及阶段解释仍需看真实原值。
- 官方log的CONTEXT绑定source/test/plan/APK/安装/environment/raw/records哈希；RECORD保留原JSON字节和SHA，END只代表payload闭合，必须继续看METHOD及FINAL。只转发通过全方法验证的块；失败/部分raw仍保存在工件，不承诺官方log有全部失败阶段明细。缺marker的JUnit PASS也不能替代host PASS。
- first-step clock无网络API或token，setup/data/raster/work累计30/45/50/55，job60；每子进程有自身上限且受当前phase/55截止，workflow另有外层timeout。save方法单独300s cap不重置阶段时钟。首步时钟明确不是actual job start；261s+39s清理余量是相对首步锚点的名义安排，最终实际job时刻仍以外部终态核验。
- standard ubuntu-24.04单job、明确branch/created=false/exact before/attempt1、contents:read、checkout不持久凭据、无task cache。官方POM仅单次健康检查，无redirect/retry或替代依赖。KVM只在需要时给当前UID --no-mask；cleanup.py与已成功search-screen逐字节相同，trap+always都限时，原PID/UID/starttime和ACL精确恢复规则保留。
- 白名单文本总量≤20MiB，3天，无APK/AAR/DB/key/full logcat；missing清单不抹掉历史FAIL/INCOMPLETE，已声明完成但缺文件改EVIDENCE_INCOMPLETE。RUNNING/INSTALLED和步骤间残留归一化并保留原summary。packager允许为保留证据而以exit0打包不完整结果，因此GitHub绿灯不能单独作为成功依据。cleanup_verified是清理回执判断；官方FINAL另列ACL哈希/字节相等声明，未经原始ACL文件审计不能说本审阅独立确认恢复。

## 当前离线回执

`offline-check-final/preflight.json`：12组PASS；包含58条旧raw兼容+10负例、13组合成marker+故障、实际first-step shell、两组安装身份负例、13种实际packager分支以及两项本次schema回归。所有inputs与checker当前SHA逐项一致。未把合成数据当新原生样本。schema最大形状 raw+records=17,145,347B，另预留3,145,728B，合计20,291,075B，低于20,971,520B；这不保证真实构建日志总大小，实际packager仍必须拒绝超限，不能截断求通过。

回执 SHA-256：`6d4398e7f754c0a01a11d6dbe1041922e6e9ed80432fa6af0df2b583a0a61adc`。

## 所审 UNBOUND 文件 SHA-256

| 文件（ci/pr20-causal/） | SHA-256 |
|---|---|
| `run.py` | `a361a8c0457e095b1ccc7fc34f7e57d48b6e7eca8425171cc1dceecd4ecb515a` |
| `markers.py` | `3514d2bca1f7b91efe7886ea42763b37eca873c8914938e8ad3dfeb03eaa1592` |
| `package.py` | `7bbb06ca67b24bc06b78b1bdb258a9de552c33cdf6ec1dfb50f81c455d06bbd6` |
| `cleanup.py` | `49e4337f483de46179466ae1c0fcf9ef18772e6a9388cce5cb6826a60d634576` |
| `workflow.proposed.yml` | `01be6b6f2e06d1e858bc5c633ef6b3b3a82588c213dfd20a459a5cbf0222bfb3` |
| `authorization.json` | `f05be96b57aa10ce6c2252ec12197ce1f0f03fbd130009d195b204252c2df7ab` |
| `method-plan.template.json` | `bade9c4d2eecdc6e4c32f92d772dee3a478a9692aaaade5140568085917b0c42` |
| `marker-contract.template.json` | `ad83592ef4c9b805a6c6d89f5cafec328cbc16f7c494cb55ced66ff5215b797c` |
| `save-marker-schemas.json` | `6384d58153399c9c743dad8dd5c9d80927da4a70aa737eef38ab1261e41f05c8` |
| `PLAN.template.md` | `0dd59e718b72e42d74fc2027d773bf935cee33dfca5ed4c0043f7536f88aa757` |
| `freeze_readiness.py` | `46fe8a72df580399fb60051e8eaac4bc29880f487555f3825ac48fcd06a4994a` |
| `prepare_sources.py` | `12b357208be7e3405af34d1e615a94f59b522cada43a15e5060b7ec58669f516` |
| `bind_control.py` | `19629c16f2ee62b86a9420f830bdb01c781e031a933d1f414bf83bc2c8c04f97` |
| `check_offline.py` | `76e359263a6af7e7e3d68bfc8a6fdb6ff4c88570da2b4355736b13917cb3df82` |
| `size_bounds.py` | `3eca77783dcbffb707f69e2e5e41600d286157c7db57cba882a773fe0282e10a` |
| `original-protocol.json` | `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba` |

后续真实绑定会合法改变run/package中的source-spec hash、授权与readiness；不得用本UNBOUND快照冒充最终执行字节。最终激活parent/EXEC清单另核。

最终READY补充：offline-check-final与05的受检输入无变化；独立核对最终12组回执的每项inputs及checker SHA均MATCH。最终handoff SHA `3f46c7ecebb1c3206961fedc8be9d61c4b17ad6047ce0f9772ba2bce5fc6e54f`；该回执只声明UNBOUND离线准备完成，未启动原生。
