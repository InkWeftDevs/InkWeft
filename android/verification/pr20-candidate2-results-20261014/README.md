# PR20 第二候选：完整原生结果与独立复算

本次原始 ZIP 已通过用户上传的 Library 取得、核验并独立复算。**57 次运行通过；性能仍未全部验收**：B→C1 的 18 项筛查为 13 项带内、4 项不确定、1 项回退信号。观察搜索时保存 p50 四组均回退；暖读四组变慢；GC 两种连接均无实际删除竞争暴露。原慢样本、此前失败及本轮限制完整保留。

本报告补齐[上一份执行报告](../pr20-candidate2-budget3-20261013/README.md)当时缺少的原始性能工件，不改写历史记录。没有重跑、挑选样本或更改阈值。后续代码修改不能借用本次通过结果。

## 运行与证据身份

[GitHub run 38441089594](https://github.com/InkWeftDevs/InkWeft/actions/runs/38441089594)，job 115447545993，attempt 1；active `6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5`。标准 runner 上 API35 AOSP ATD x86_64 KVM，SwiftShader、2 vCPU、2048 MiB、1920×1200、density240，合成 debug 负载；不是用户真机。

| 身份 | 固定源码 | 实际构建输入 canonical SHA-256 |
| --- | --- | --- |
| B（叠加固定 writer factory/共同探针） | a128bb07e34cb82f00eeafc8e62a47bf7b95b128 | bcf2b51fb04e85ced2a70bc3fc7a402e1fed551583de43831d8a4ecccce91fd8 |
| C0（前候选） | ebb48481e7e995288c1844d43011398c513b4f4e | c0e3b9a4cfa9b5a602e47422f080add43353d64acacea505cc8f3ebee4c91e63 |
| C1（本次候选） | 45b4861ae23ac19eda3246fcd34cf3d2132dc1ee | 7592c7990537bde681d0179539f58e1cd79822695bfcfd4b19882d9e1f738e90 |

原 ZIP `pr20-kvm-triple-38441089594.zip`，769104 bytes，SHA-256 `e3218dda22503f2bc085cf2e96284ce0bd24f5b05c40dcee772c13b2ec7318aa`，artifact11837544923。通过用户授权的 Library 原文件取得；未重试此前被拒绝的 artifact-storage 渠道。289 payload 的 size/hash、291 完整文本文件均验证；无遗漏、截断、问题或 pending。ZIP 二进制留云端 scratch，本目录保存完整非敏感原文。

[运行独立审计](audit/runtime-audit.md)完成1137项一致性核对：57份原 JUnit/host PASS，35个不同 selection，30正确性＋24公共＋3诊断，14安装组、9APK回执、879依赖逐字节一致。MuPDF 为官方完整四 ABI AAR；本审计核对回执，ZIP不含APK，未重新对二进制验签/验ELF。56次自然退出，只有原method17按冻结规则执行一次helper清理；没有新例外。trap/always均停止确认，ACL前后逐字节一致。

实际job 17:44:33–18:25:31 UTC，共40分58秒，低于60分钟；anchor提前134秒，累计38/41/53/55分钟边界全部满足。setup在anchor后30分50秒完成，甚至早于旧32分钟截止70秒，因此不能将成功单独归因于扩大setup预算。原生job没有执行JVM/lint，既有本地结果单列在[源码冻结报告](../pr20-candidate2-readiness-20261013/README.md)。

## 搜索及保存：四区组原值

以下数组均按seed4101/4102/4103/4104顺序，单位ms。每轮30次保存按nearest-rank索引14/28/29；24轮共720条原序保存完整保留，四个seed区组才是比较单位。不采用probe旧p50索引15字段，不合池旧run。

| 指标 | B | C0 | C1 |
| --- | --- | --- | --- |
| 未建索引首次读取 | 3604.125 / 3635.890 / 3645.937 / 3622.370 | 2127.271 / 2106.788 / 2104.801 / 2114.796 | 2140.033 / 2099.237 / 2120.942 / 2120.240 |
| direct保存p50 | 2.250 / 2.161 / 2.123 / 2.017 | 2.474 / 2.421 / 2.308 / 2.316 | 2.300 / 2.293 / 2.340 / 2.373 |
| direct保存p95 | 2.877 / 3.373 / 2.558 / 2.471 | 7.038 / 5.484 / 5.407 / 9.309 | 5.826 / 5.814 / 5.409 / 9.015 |
| direct保存max | 3477.300 / 3505.190 / 3518.473 / 3494.362 | 8.844 / 9.175 / 7.436 / 12.294 | 9.484 / 6.396 / 8.014 / 10.727 |
| observe保存p50 | 2.278 / 2.383 / 2.289 / 2.295 | 6.463 / 5.227 / 5.494 / 5.510 | 5.045 / 6.326 / 6.848 / 6.292 |
| observe保存p95 | 82.099 / 81.039 / 81.292 / 86.516 | 18.601 / 13.217 / 14.444 / 15.031 | 11.566 / 12.834 / 23.368 / 14.581 |
| observe保存max | 3437.778 / 3489.198 / 3460.106 / 3491.168 | 34.387 / 32.041 / 32.585 / 30.070 | 33.086 / 33.425 / 40.971 / 30.229 |
| 每轮五次warm中位 | 574.212 / 574.403 / 575.600 / 577.013 | 633.919 / 645.146 / 634.008 / 638.496 | 630.890 / 693.497 / 631.031 / 639.898 |

B→C1冷读配对差中位 **−1513.562799ms / −41.647707%**；“冷”只指未建索引，不是OS/file-cache冷启动。observe保存p50四组均超原带，配对中位 **+3.970117ms / +169.832555%（REGRESSION_SIGNAL）**。C0→C1为+0.940515ms/+17.609098%，绝对中位未超1ms，但两对超带，仍为INCONCLUSIVE。暖读B→C1四组均慢，配对中位+59.781075ms/+10.384392%；warm只有描述性口径，不追补验收阈值。

direct p95 B→C1为INCONCLUSIVE，第四组超带。B direct只有1/30与冷读重叠，而C0/C1为30/30；observe三端均30/30与索引大区间重叠。这不是等暴露的持续写锁争用比较，不能把max改善称为纯写锁等待改善。

公共probe固定flowOf(request)，保存更新notes；a128 observe未监听notes，C0/C1监听。因此候选承担保存触发的新读，这是真实新鲜性合同的一部分，**不豁免p50回退，也不能删除notes监听恢复旧缺口**。本次阶段诊断中16个IO内取消读均未进入metadata；read-start到page-hits-start共133.130ms，其中scope72.689ms、开库4.549ms。11个scope段内已到达后续invalidation，但未记录Job实际取消时点，不能将全部墙钟时间判为已取消CPU/锁开销。最小后续候选是在无CancellationSignal的临时表SQL边界检查取消；效果尚待新源码验证。

## 实际读取规模与绘制

目录/header正文从B的10,010,000 UTF-16降为C0/C1的0；C1每页返回60条/35,425字符，实际跨cursor的preview为61条/36,025字符（含lookahead），B返回preview为1,181,180字符。目录metadata仍O(N)，非空查询可能扫描所有候选正文；这些计数不是磁盘I/O，也不是只执行了60行SQL。

36组绘制像素比较均通过（各版本local对自身forced-full，不是跨版本hash一致），清理joined且tracked资源计数字节为0。1/4/8层local payload decode由B/C0的13/21/11降为C1四组均 **1/1/1**，local重建均 **1/1/1**。C0/C1 native stroke仍13/21/11，B该字段缺失；不能宣称native跨帧缓存。full仍解码321，瓦片24/24/48。

B→C1和C0→C1的9个绘制阶段均6项带内、3项INCONCLUSIVE：单层append、八层first、八层forcedFull。没有REGRESSION_SIGNAL，不等于全通过。seed4104单层append C0 53.369809→C1 64.348551ms，metadata15.266704→27.340796ms、raster17.467149→20.429778ms；异常保留，不用该append阶段解释缺少独立阶段时序的first/full异常。原dense 30秒及连续变换性能本轮未复验，不能用局部追加结果替代。详见[绘制审计](audit/raster-analysis.md)。

## GC安全与暴露缺口

正确性8–16共9/9 PASS，包含真实DELETE后提交前发起作者请求、并发活根保留、损坏根拒删和第二批取消回滚。人为闸门证明特定顺序安全，不是自然延迟样本。

自然诊断55/56两种连接各30/30 Committed且正文/revision核验通过，但均 **0/30实际删除暴露（INSUFFICIENT_EXPOSURE）**。每轮首轮0删除＋延期，保存完成后各删1280；每组完成轮共38400。根扫描每组首轮30＋完成轮30＝60（每次collect一次，不是全部SQL数）。同Room两轮调用总计1732.661084ms，另一Room1757.475132ms。全部保存p50/p95/max分别3.071672/4.852563/7.651819ms和2.945655/4.375943/4.966373ms，**只代表未实际删除竞争的调用，暴露子集分位数为null**。不能关闭GC保存阻塞门槛。详见[GC逐条审计](audit/gc-analysis.md)。

## 筛查结论与复现

| 比较 | WITHIN_PROPOSED_BAND | INCONCLUSIVE | REGRESSION_SIGNAL |
| --- | ---: | ---: | ---: |
| B→C0（顺序3/1旁证） | 15 | 2 | 1 |
| B→C1 | 13 | 4 | 1 |
| C0→C1 | 13 | 5 | 0 |

状态保持 **COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE**。[搜索与分页专项审计](audit/search-analysis.md)补充了完整原序分布、每次取消阶段、保存区间对齐和新鲜性比较边界。原阈值、全部54项分类与216配对原值见[独立计算审计](audit/analysis-review.md)及[JSON](audit/analysis-review.json)。不以冷读、最大保存延迟或decode改善抵消保存p50回退；不把运行PASS写作性能全部完成。

- [raw/](raw/)：完整291原始文本，289 payload与原manifest逐字节一致。
- [analysis/](analysis/)：原冻结analyze.py的完整派生输出，包含720条原序保存、各warm五值、所有栅格值。
- [archive-verification](audit/archive-verification.json)、[input-origin](audit/input-origin.json)、各专项独立审计：校验、来源与解释边界。
- 分析命令：`python3 ../pr20-candidate2-readiness-20261013/analyze.py --evidence raw --out <new-empty-output>`；不覆盖本报告派生文件。
- 原始控制器/官方job metadata位于[上一执行报告](../pr20-candidate2-budget3-20261013/README.md)，固定源码和阈值位于[readiness](../pr20-candidate2-readiness-20261013/PLAN.md)。

历史Docker改动和验证保持既有集成结果，未编辑其目录；不操作用户电脑、真实笔记或AniMemo，不合并、不部署、不强推源分支。
