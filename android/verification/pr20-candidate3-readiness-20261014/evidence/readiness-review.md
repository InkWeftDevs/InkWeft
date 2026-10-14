建议在新代码完成必要本地检查并冻结真实commit后，继续**一次新的、有界的三端原生验证**。预算3控制器可以最小复用，累计预算仍38/41/53/55分钟、实际job60分钟；不启缓存、不增加worker、runner、权限或工件范围。此次新增SQL边界取消行为和一项针对性正确性测试，有新的可检验假设，适合新的验证；不能为了获得更好分数原样反复运行旧45b候选。本文件仅评估，没有创建新控制器、workflow、源码清单或触发任务。

审阅输入为已提交预算3控制器 `/workspace/InkWeft-native-candidate2-budget3/ci/pr20-kvm-triple/`、已冻结candidate2 readiness、旧 `freeze_sources.py`，以及根节点当前两个工作文件。当前新C1 commit尚未确定，本文件不填写假SHA、不把未冻结工作副本称为候选来源。

**为什么这次需要原生验证。** 最新run38441089594的57次调用已完整通过，独立运行记录无矛盾，但冻结分析器仍给B→45b的observe保存p50为REGRESSION_SIGNAL（配对中位+3.970117ms/+169.832555%），45b相对ebb的observe p50仍INCONCLUSIVE。新增代码在临时表/索引等SQL阶段边界调用ensureActive，目的是已取消读取不要继续进入后续阶段；它不是正在执行的不可取消execSQL的抢占机制，也不自动证明SQL执行时间或写锁占用减少。Android SQLite、coroutine取消、连接/快照释放与并发保存语义必须由真实Android测试验证，编译/JVM无法替代。新六门控用例能直接检验“取消后不进入下一阶段，后续读取仍看见已提交正文/分类”；原四seed公共探针用于独立检验保存分布，不改变结果判定规则。当前回退和所有INCONCLUSIVE继续保留，不能以新正确性PASS预先宣布性能闭合。

最小编号安排：

| 区间 | 内容 | 变化 |
|---|---|---|
|1–30|旧30项正确性|所有JSON字段及顺序保持；新C1 APK上执行|
|31|新增六门控取消正确性|C1、C1-correctness、data测试runner、一次OK(1)|
|32–55|旧24公共方法|旧31–54仅ordinal+1；seed、visit、args、selection不变|
|56–58|旧3候选诊断|旧55–57仅ordinal+1；参数不变|

仍14安装组（C1-correctness、12个visit、C1-diagnostics），3个隔离源码树、9个真实APK。原57项不删、不重排，不增加单次方法重试。方法17保持原ordinal、selection、C1身份和原唯一helper例外；新增方法不能加入该例外。预计58次执行对应36个不同selection，不应写成58个不同方法。

当前新方法准确名称为：

```text
org.inkweft.data.LibraryPagingTest#cancelledSetupStopsBeforeNextStageAndNextReadSeesCommittedAuthorState
```

新增entry复制旧LibraryPagingTest的数据runner参数结构，仅换上述selection；`phase=correctness`、`identity=C1`、`install_group=C1-correctness`、`expected_junit_tests=1`、`seed=null`、`stage_diagnostics=false`，最后runner为 `org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner`。不要加pairSeed、libraryStageDiagnostics或要求KVM_PAIR_FIXTURE；六个边界是在一次测试内循环，不拆成六次调用。当前源码边界为connection-open→snapshot-open、snapshot-open→drafts-ready、drafts-ready→scope-start、scope-start→scope-end、scope-end→hits-ready、hits-ready→page-hits-start；测试保留原有超时和断言，最终名称/内容须在新commit冻结时再核实。

**三端身份必须换清楚。** B仍 `a128bb07e34cb82f00eeafc8e62a47bf7b95b128`，仅原common factory与公共探针覆盖，canonical仍 `bcf2b51fb04e85ced2a70bc3fc7a402e1fed551583de43831d8a4ecccce91fd8`。新C0为原本轮C1 `45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`，其canonical应仍 `7592c7990537bde681d0179539f58e1cd79822695bfcfd4b19882d9e1f738e90`；旧C0=ebb仍留历史，不能继续冒称本次前候选。新C1为待冻结的取消边界候选；不要向B/C0覆盖新生产代码或新正确性测试。报告每个B/C0/C1槽都同时列真实commit/版本别名。本次C0→C1是边界取消修正的直接比较；跨run槽名不能直接合池比较，前轮45b样本保留历史，不替代本次C0四seed新观测。

**冻结工具的关键陷阱。** `/workspace/scratch/pr20-candidate2-validation/freeze_sources.py` 仍固定读取最早 `android/verification/pr20-native-readiness-20261013/source-spec.json`，然后把其中旧C1复制给C0；直接原样运行会再次生成C0=ebb。应复制成新scratch版本，只改输入为已冻结candidate2 spec、输出为新的readiness目录和新58方法计划，并修正生成PLAN/别名/预算文字。先deepcopy旧spec.C1给新C0，再修改新C1的真实commit/tree；保留所有common_probes和B生产覆盖原字节。从commit archive重新计算android/ci输入（继续排除verification），断言B和新C0的files/canonical分别等于本轮B、C1；新C0 manifest顶层identity必须为C0，因此不能直接把旧C1 manifest原文件冒充C0，但其files/canonical应相同。新C1与45b的差异要对真实commit清单逐项审核；本任务预期仅search生产文件及LibraryPagingTest，若还含别的输入变化，应显式解释而非带过。

`prepare_sources.py` 按所在目录的spec/manifest重建三个源码树，没有57/30或固定C0代码，可**保持原字节**复用。仍fetch每个固定源码/overlay/probe来源ref，包括原公共探针来源；不修改官方MuPDF来源、POM检查、AAR、四ABI ELF/签名核验或APK嵌入身份。新探针不需要进入B/C0；原两公共探针及original-protocol.json保持字节及SHA不变。

建议新readiness目录单独命名，例如 `android/verification/pr20-candidate3-readiness-20261014`（这是建议，未创建）。文件与最小改动范围：

| 文件/入口 | 应做的变更 | 保持的边界 |
|---|---|---|
|新冻结工具副本|输入换为candidate2 spec，C0继承45b；新C1真实commit；新输出目录/58计划|不覆盖旧工具、旧readiness或运行证据|
|source-spec.json、B/C0/C1-source-manifest.json|冻结新三端身份/tree、文件清单、canonical、别名|B及新C0 canonical分别精确复用本轮B/C1；原overlays/probes不变|
|method-plan.json|58/31计数；新增31；旧31–57只顺延1|24公共、3诊断、14组、四seed、原args/超时不变|
|PLAN.md|明确新假设、三端commit、58/31-24-3、预算3保守PR时间锚点|不要再生成旧“真实job开始32/38/52/55”文本；不改旧PLAN|
|prepare_sources.py、original-protocol.json、两common probe|新目录复制原字节|零逻辑/阈值变化|
|run.py|新FROZEN_RELATIVE/source-spec SHA；57→58、range(1,58)→range(1,59)、phase[30,24,3]→[31,24,3]、main方法数与说明|所有计时公式38/41/53/55、构建/解析/进程/方法17/串行执行函数保持|
|package.py|仅新readiness路径/source-spec SHA|现有白名单按plan动态生成，会自动加入method31并移动余下命名；状态归一化/20MiB/3天不变|
|cleanup.py|无需改动，原字节复用|最小UID ACL、两次cleanup与逐字节恢复不变|
|新analyze.py|新plan/spec SHA；58总数与ordinal1..58、phase31/24/3、complete门禁；文案版本身份|所有数值指标、阈值、四区组统计、样本30、nearest-rank14/28/29、诊断GC30等不改|
|authorization/budget-revision|明确本次源码/新增正确性修订、58/31计数、新plan/source哈希及前次成功run身份|旧预算修订历史保留，新有效38/41/53/55/60明确；不冒称本次仅预算修复或旧57 scope未变|
|clock-contract/server-receipt、workflow.proposed.yml|新独立分支/concurrency、一次必要PR服务器回执、精确新head与fresh immutable anchor、最终prep guard|无jobsAPI clock、无新token/权限；contents:read；actual_job_started_epoch仍null直到外部核验；绝不后移旧anchor|
|完整preflight checker/原ZIP verifier|新身份/目录/hash/58/31/ordinal边界；保留原19helper、8POM、12package、clock/预算负例及43工件合成case语义|不能只全局替换30；30保存样本和GC30试验必须保留；方法17固定|
|README/preflight.json/EXECUTION_SHA256SUMS|实际新检查生成、重新hash闭环；准备/激活guard绑定新提交|不复用旧PASS回执当新执行；原log/failure保留|

原完整checker对process4的AST回退比较专门归一化旧57/58/30等常量；新58/59/31必须精确适配setup/main允许差异，同时保留其余函数比对，不能为方便去掉AST门禁。新正确性插31后，证明新steps[:30]等于旧steps[:30]，新steps[31:]逐项减ordinal1后等于旧steps[30:]；新增entry恰1且内容准确。旧58是range终点，新58是方法总数，切勿盲目全局替换。新分析器同理只改方法合约计数，GC和保存中的30绝不能变31。

**预算仍可沿用，无需这次再放宽。** 本轮summary.actions同口径测得setup33.666s、B624.286s、ebb542.128s、45b473.373s、emulator32.165s、30正确性42.841s、24公共611.955s、3诊断54.633s；job实际40分58秒。按最迟阶段窗口，正确性180秒对旧42.841秒有约137.159秒参考余量，公共720−611.955≈108.045秒、诊断120−54.633≈65.367秒。实际本轮早完成带来的累计截止剩余是setup429.938秒、correctness566.986秒、common674.943秒、work740.206秒；这些是已测余量，不是下一runner性能保证。新六门控用例单次正常耗时未测，含多处等待和新鲜读取，不能从各局部withTimeout推导整个方法严格上界。新C0=45b也会改变构建内容/顺序，因此不承诺总耗时更短。全部阶段提前完成即继续，任何阶段失败/超时保持原始状态并停止，不能删样本或延长deadline救结果。

原标准ubuntu-24.04单runner、单API35 AOSP ATD/KVM、serial单worker/--no-build-cache、9APK来源/验签/4ABI、1920×1200/dpi240、原动画及字体、ACL最小变更和双恢复、20MiB完整白名单文本/3天保留、work55之后清理261+39秒、actual job60均保留。原工件约3.64MiB未压缩，新一项通常只多三份小文本；这只是容量参考，仍以实际package完整容量校验为准，不能截断原样本。

建议顺序为：新代码和六门控测试完成本地必要检查→冻结C1真实commit→重建独立三端清单并验证B/newC0连续性→固定新58计划/分析器→适配控制与完整离线负例审查→所有模板稳定后才一次必要PR更新取得新anchor→无活动workflow准备提交及精确一次激活。当前仅评估第一轮可复用范围，后续动作由根节点统一决定/执行；尚无新的原生PASS或性能改善证据。

来源：本轮 [summary.json](/workspace/scratch/pr20-triple-run-38441089594/evidence/summary.json)、[冻结分析输出](/workspace/scratch/pr20-triple-run-38441089594/analysis/analysis.json)、[独立runtime审计](/workspace/scratch/pr20-triple-run-38441089594/runtime-audit.md)、[原冻结工具](/workspace/scratch/pr20-candidate2-validation/freeze_sources.py)、[预算3控制器](/workspace/InkWeft-native-candidate2-budget3/ci/pr20-kvm-triple/run.py)。这是只读准备评估，不改变这些文件。
