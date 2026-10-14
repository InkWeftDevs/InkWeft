# PR20：四组同 KVM 配对结果（2026-10-13）

**执行与本轮正确性检查通过；整体性能验收未关闭，PR20 保持 Draft。** 固定四组、八轮、24 次指定 instrumentation 均有完整原始 JUnit PASS。离线按预先冻结的判据复算，索引期间保存 **p50 为重复回退信号**：四对全部越线，配对增幅中位 **+104.574% / +2.455 ms**。直接冷搜保存 p95、4 层追加、4 层强制全帧及 8 层强制全帧共四个指标为 **INCONCLUSIVE**。热搜索四对也都变慢。未删除慢样本、混池增加样本数或用其他提速抵消回退。

此次只分析用户上传的既有工件、归档报告和提供未应用的最小修复建议；没有新构建、设备执行或安全设置变更。生产候选仍是 `7949011…`。这是标准云端 Android/KVM 合成测试，不是实体设备或真实笔记验收。

**来源与执行身份**

- [成功运行 38379928087](https://github.com/InkWeftDevs/InkWeft/actions/runs/38379928087)，attempt 1；实际 workflow commit `371d461cc069f8a090ccc2f844068fae68e0770d`，工作流 SHA256 `01a50c6bf5e56c456bd70480ff7c9b1dd2fc3afb66968c51dc4368bcecbbbbb5`。
- 09:56:46–10:20:22 UTC，job **23分36秒**；准备 **978.795秒（16分18.795秒）**，低于26分钟；工作未触及55分钟上限，总上限60分钟。B构建/核验517秒，C381秒。两端同参数串行、单worker，重建六个真实 debug APK。
- B：`a128bb07e34cb82f00eeafc8e62a47bf7b95b128` **加 C 同一安全 factory 与三份共同探针**，是受控修改基线，不称原封不动远端。C：`7949011ca2b84492f6438d275a09d28faef3b472` 加相同探针，无生产覆盖。
- [源码规范](evidence/frozen-source-spec.json)、[B清单](evidence/frozen-B-source-manifest.json)、[C清单](evidence/frozen-C-source-manifest.json)已分别与不可变 Git blobs 重算948/961项逐文件匹配；factory哈希 `a049af775caa688cd0dfd938acdf1539d34ee13de272ed35c930de9ef4e3d15d`。三探针字节与冻结清单一致。执行提交的21项执行清单一致；`workflow.proposed.yml`只是历史模板，实际修正工作流身份以上述SHA为准。
- [六APK身份表](analysis/apk-identities.csv)和[原始B](evidence/apk-B.json)/[原始C](evidence/apk-C.json)记录完整大小、SHA、同一debug签名及两主包内嵌基点/执行commit/`formal=false`。两端四ABI MuPDF原生库回执逐项一致，879项依赖记录完全相同。**工件刻意不含APK/依赖二进制**；本次核验运行时验签/ELF回执，未在本地重新打开APK或验签。
- 上传ZIP 516,600字节，SHA256 `235f250c867f22328762d4b19c1ca719cdc1baa73af5976fd94896c05e435220`，与执行结束时保存的GitHub工件摘要相同。136个ZIP条目中134个payload合计2,153,408字节，逐文件原始/上传长度与哈希全部一致，零截断、零遗漏。原始日志/CSV的换行和尾空白按字节保留，证据目录禁用Git换行转换。其余两项为manifest与packaging。见[来源记录](provenance.json)、[清单](evidence/manifest.json)。
- 前次[38379138319](https://github.com/InkWeftDevs/InkWeft/actions/runs/38379138319)因我新增workflow的job级`env`非法引用`runner.temp`而在启动前失败，0 job/0方法/0工件，KVM未触及；YAML/Bash静态检查漏掉了上下文规则。经用户另行批准才修复并触发本次一次运行。失败记录保留，不把它计入四组样本。

**同环境与证据有效性**

本轮host为 **Intel Xeon Platinum 8573C / 4 CPU / 16,372,440 KiB内存 / kernel6.17.0-1022-azure / Temurin17.0.20.1**。API35 AOSP ATD x86_64 rev1，emulator37.2.12.0 build16428233，KVM强制开启，SwiftShader、2核/2048MiB，1920×1200、dpi240、font1.0、window/transition1.0、animator设置删除，`ro.hw_timeout_multiplier`为空。18份设备配置快照一致。以前单候选KVM用AMD EPYC7763；本轮不与它或TCG样本合池。

固定顺序 **C/B、B/C、B/C、C/B**，seed4101–4104；每轮完整重新安装三包，每方法独立进程/新合成库；方法顺序localAppend→library→history。40份前置fixture形成20组相同标签B/C哈希匹配，包含实际层归属。两个data方法的16份结束writer配置均与前置一致：**page_size1024、auto_vacuum FULL、WAL、synchronous2**。

逐份读取24份原始日志：各1 test、指定class/method、开始/结束状态与最终`OK (1 test)`一致，全部PASS；派生XML和records来自同份日志，不另算独立执行。960项定时保存均Committed且revision连续；library直接2→31、observe33→62，最终正文/版本断言通过；GC两段2→31、32→61，最终标题/版本断言通过。24份raster严格join回执当前资源与in-flight归零；72个方法后进程检查均为空。

KVM只临时增加UID1001的rw ACL，未放宽mask/组/永久权限。trap和always均为`STOPPED_CONFIRMED`、`RESTORED_EXACT`、errors=[]；[原ACL](evidence/kvm-before.acl)与[trap后](evidence/kvm-after-trap.acl)/[always后](evidence/kvm-after-always.acl)逐字节相同。绘制追加和隐藏后的全幅像素、活引用保留、延后GC及完整正文按需读取断言均通过。仅覆盖这些既定断言，不代表本轮重跑完整撤销、恢复、UI、备份或pan/zoom测试。

**冻结口径与完整判定**

[原方案](evidence/frozen-PLAN.md)和[协议](evidence/frozen-protocol.json)保留批准前原字节，因此里面的“待批准/未执行”是历史状态；不修改它来伪造事前协议。协议SHA256 `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba`。

每场景每轮n=30，p50/p95/max取排序第15/29/30项。library旧`saveP50Ms`取第16项，原值保留但不用于本表。四对是比较单位；不把120项保存合成120次独立试验。下表B/C中位是各端四轮统计量的中位；“配对变化”是四个C/B变化的中位，不能用两列中位数相除替代。

每对超带为`C-B > max(relative×B, absolute)`：绘制10%且5ms；保存p50 10%且1ms、p95 15%且5ms、max25%且20ms；冷搜/索引/联合完成10%且500ms；GC完成10%且100ms。至少3/4同向变慢且配对相对/绝对中位均超门槛才为REGRESSION_SIGNAL；有单对超带而未满足重复判据为INCONCLUSIVE；全四对未超带才为WITHIN_PROPOSED_BAND，仍非证明无回退。

原执行器26个指标全部独立复算一致。另在每轮读取量CSV补充“直接冷读及保存联合终点”，从既有reader/保存区间终点取max；该公式未在冻结方案中明确指定，故只作描述性补充，不套用阈值或计入原26项验收。完整逐对B/C/差值/比值见[104项配对值](analysis/paired-values.csv)。

| 指标（ms） | B四轮中位 | C四轮中位 | 配对变化中位 | 超带对数 | 数值判定 |
| --- | ---: | ---: | ---: | ---: | --- |
| history.collectionMs | 1,656.414 | 238.557 | -85.67% / -1419.542 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.live.max | 6.260 | 6.697 | +11.42% / +0.752 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.live.p50 | 2.042 | 2.063 | +2.45% / +0.049 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.live.p95 | 4.251 | 3.928 | -3.73% / -0.152 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.liveCollectionAndSavesMs | 1,682.847 | 341.967 | -79.50% / -1340.879 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.paused.max | 6.722 | 5.124 | -29.53% / -1.973 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.paused.p50 | 2.311 | 2.389 | +5.14% / +0.113 ms | 0/4 | WITHIN_PROPOSED_BAND |
| history.paused.p95 | 3.098 | 3.573 | -7.82% / -0.241 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.allIndexRowsCommittedMs | 26,639.645 | 25,919.323 | -2.96% / -791.606 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.coldMs | 4,007.940 | 1,991.383 | -50.36% / -2018.361 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.direct.max | 3,842.252 | 9.135 | -99.76% / -3833.117 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.direct.p50 | 2.006 | 2.296 | +13.73% / +0.277 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.direct.p95 | 2.996 | 7.105 | +156.48% / +4.488 ms | 2/4 | INCONCLUSIVE |
| library.observe.max | 3,845.793 | 31.025 | -99.19% / -3814.769 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.observe.p50 | 2.347 | 4.884 | +104.57% / +2.455 ms | 4/4 | REGRESSION_SIGNAL |
| library.observe.p95 | 72.965 | 13.746 | -80.88% / -60.466 ms | 0/4 | WITHIN_PROPOSED_BAND |
| library.searchIndexReadyMs | 27,175.514 | 26,508.458 | -2.68% / -731.935 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.1.appendCompleteMs | 50.508 | 34.856 | -32.13% / -16.510 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.1.firstCompleteMs | 234.106 | 245.478 | +3.88% / +9.159 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.1.forcedFullCompleteMs | 179.425 | 171.740 | +0.00% / +0.007 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.4.appendCompleteMs | 100.859 | 106.892 | +5.56% / +5.658 ms | 1/4 | INCONCLUSIVE |
| raster.4.firstCompleteMs | 207.269 | 191.999 | -7.35% / -15.453 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.4.forcedFullCompleteMs | 172.003 | 174.186 | +1.09% / +1.868 ms | 1/4 | INCONCLUSIVE |
| raster.8.appendCompleteMs | 156.826 | 127.520 | -16.64% / -30.997 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.8.firstCompleteMs | 242.808 | 243.351 | +0.34% / +0.826 ms | 0/4 | WITHIN_PROPOSED_BAND |
| raster.8.forcedFullCompleteMs | 234.900 | 240.704 | +3.73% / +8.572 ms | 2/4 | INCONCLUSIVE |

**搜索保存：逐轮分布及真实竞争**

以下每格为p50 / p95 / max，单位ms，每格30项原序样本。完整960个值、时间区间、Committed/revision见[核验后原始保存表](analysis/raw-saves-verified.csv)，各轮分位与重叠子集见[分布表](analysis/save-distributions.csv)。

| 场景 | 配对 | B p50 / p95 / max | C p50 / p95 / max |
| --- | ---: | --- | --- |
| library.direct | 1 | 2.006 / 4.486 / 3,791.085 | 2.286 / 5.647 / 6.814 |
| library.direct | 2 | 2.005 / 2.509 / 3,850.772 | 2.228 / 6.235 / 8.393 |
| library.direct | 3 | 2.032 / 3.268 / 3,833.732 | 2.306 / 8.644 / 9.877 |
| library.direct | 4 | 1.880 / 2.725 / 3,876.877 | 2.361 / 7.976 / 10.971 |
| library.observe | 1 | 2.292 / 72.172 / 3,822.138 | 4.639 / 13.341 / 39.366 |
| library.observe | 2 | 2.169 / 82.609 / 3,854.209 | 5.061 / 17.059 / 29.966 |
| library.observe | 3 | 2.508 / 71.630 / 3,855.179 | 4.803 / 14.151 / 26.509 |
| library.observe | 4 | 2.401 / 73.758 / 3,837.378 | 4.964 / 11.657 / 32.083 |
| history.paused | 1 | 2.234 / 2.798 / 3.999 | 2.488 / 4.727 / 7.172 |
| history.paused | 2 | 2.467 / 3.150 / 6.186 | 2.279 / 2.957 / 4.220 |
| history.paused | 3 | 2.388 / 5.179 / 7.257 | 2.426 / 4.189 / 5.278 |
| history.paused | 4 | 2.164 / 3.046 / 9.942 | 2.352 / 2.756 / 4.970 |
| history.live | 1 | 2.067 / 4.194 / 4.727 | 2.053 / 3.263 / 3.810 |
| history.live | 2 | 2.053 / 5.628 / 7.080 | 2.245 / 7.600 / 11.825 |
| history.live | 3 | 2.032 / 3.853 / 6.789 | 2.056 / 3.704 / 5.241 |
| history.live | 4 | 1.996 / 4.308 / 5.732 | 2.070 / 4.152 / 8.153 |

- 直接冷搜：B每轮只有**1/30**保存与reader调用重叠，这一项跨越reader结束、阻塞约3.79–3.88秒，后29项均在reader结束后开始；C每轮**30/30**重叠且全部在reader结束前完成。B那1项与C30项不是等量的持续竞争尾部分布。C全30项p50四对均上升约0.223–0.482ms，但未越1ms绝对门槛；p95四对均变慢，后两对越带，配对绝对增量中位4.488ms未越5ms，所以为INCONCLUSIVE。大幅max改善不抵消这些上升。暂停reader的独立750ms检查B四轮false、C四轮true。
- observe：八轮均30/30保存发生在subscribe至全索引提交区间，且都在结束前完成。**p50四对均超带**；p95四对下降，max从约3.82–3.86秒降至26.5–39.4ms。B每轮有6个保存窗口跨批提交，C为1个；B有1个保存窗口出现原文扫描推进，C为2–3个。计数窗口只表明有进展，不是锁等待计时，不能把全部耗时解释成FTS写锁。
- B每轮保存期间索引64→448行，C为64→128行；C保存序列也短很多。整体索引结束均为20,000行，不以保存结束时进度较少直接判吞吐回退。

“冷”仅指fixture哈希已访问数据库后的首次未建索引查询，不是冷OS缓存/磁盘或真实应用冷启动。单列header成本：四轮中位B156.533→C20.956ms；冷读计时不含前置fixture与header读取。20k原文扫描次数双方均20,000。

| 配对 | 冷读B→C ms | 20k提交B→C ms | 有效吞吐B→C 行/s | 首批后增量B→C 行/s | 首64行B→C ms | 暖5次中位B→C ms |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 3,959.223 → 2,026.695 | 26,099.613 → 26,035.044 | 766.295 → 768.195 | 766.372 → 769.465 | 86.123 → 126.129 | 512.791 → 581.920 |
| 2 | 4,013.530 → 1,962.887 | 26,661.446 → 25,573.682 | 750.147 → 782.054 | 750.173 → 784.144 | 86.235 → 149.789 | 523.782 → 559.831 |
| 3 | 4,002.349 → 2,016.271 | 26,617.843 → 26,079.682 | 751.376 → 766.881 | 751.621 → 767.977 | 93.825 → 120.559 | 508.574 → 576.836 |
| 4 | 4,040.759 → 1,966.496 | 26,848.653 → 25,803.602 | 744.916 → 775.086 | 744.884 → 777.181 | 84.725 → 151.919 | 503.044 → 565.290 |

有效吞吐为`20000/(subscribe到末批提交秒数)`，含初始查询、保存、调度；首批后增量为`19936/(末批-首64行提交时刻)`，也包含并发工作，均不称纯SQL索引速度。每轮313批累计行64→20000严格单调。20k完成配对时间中位变化−2.957%，有效吞吐配对中位约+3.057%；不掩盖首64行C每对都慢于B。

暖查询在pending=0、保存完成后每轮5次，四对中位均变慢：各端四轮中位 **510.682→571.063ms**，配对增幅中位 **+12.898%**。这是独立保留的不利观察；预先协议未给暖查询独立数值门槛，不事后新设阈值把它判绿。全部40个暖原值保留在各轮`LIBRARY_READ_EVIDENCE.warmSamplesMs`。

**资料库实际读取规模**

四组一致：header/目录正文从 **10,010,000→0 UTF-16单位**；60条展示项正文/预览从 **1,181,180→35,425**；C实际预览投影 **61行（含前瞻）/36,025单位**，≤61×600，完整正文按需读取仍匹配。B没有投影observer，因此B投影行数为null，不能编造61或称其真实分页。C目录元数据仍O(N)，非空查询可能扫描全候选正文；这里证明cursor返回量受限，**不是物理磁盘I/O或搜索内部读取所有正文的字节计数**。本次只测固定首60项；稳定排序/刷新/跨页语义沿用此前测试，未在本轮额外执行。详见[每轮读取量](analysis/library-values.csv)。

**绘制：旧版/新版比较与正确性参照分开**

| 层数 | 配对1追加B→C ms | 配对2 | 配对3 | 配对4 | 两端局部decode/重建 | 两端全帧decode/重建 | C原生Stroke构造 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 50.628 → 51.176 | 50.241 → 32.199 | 52.343 → 35.089 | 50.389 → 34.622 | 13/1 | 321/24 | 13 |
| 4 | 102.818 → 102.207 | 97.607 → 101.805 | 104.459 → 111.577 | 98.899 → 118.996 | 21/1 | 321/24 | 21 |
| 8 | 189.386 → 129.517 | 125.970 → 135.941 | 127.648 → 125.522 | 186.005 → 125.397 | 11/1 | 321/48 | 11 |

单层追加四对均未超10%/5ms带，首轮C51.176ms对B50.628ms略慢，后3对更快；本轮未重现旧TCG单层大回退，**不改写或删除旧环境结果**。单层首帧C四对都略慢，但均在预设带内。4层追加3/4变慢，第4对98.899→118.996ms（+20.32%/+20.096ms）超带；总体中位相对+5.557%未越10%，判INCONCLUSIVE。4层强制全帧第2对+18.17%/+29.310ms超带；8层强制全帧第3/4对+10.96%/+25.327ms、+12.75%/+30.159ms超带，均保留为INCONCLUSIVE。

此局部追加负载中，B/C的实际decode及重建数相同，不能用C局部13/21/11对其自身完整321声称代码优化降低了B→C解码量。B缺少nativeStrokeBuilds计数，记null，不能当0或推断原生构造减少比例。追加和隐藏后全幅像素均与各端强制全帧一致；强制全帧是正确性参照，同时其B/C时间另作保护指标，不替代旧版/新版比较。

[逐轮全部首帧/追加/全帧/内部计数](analysis/raster-values.csv)保留metadata/raster/publication原值。后者是各层lastBuild累计统计，可能跨并行/等待，不能相加解释wall time。没有从本轮局部追加推导连续pan/zoom或分散脏区的重复改善；那些旧TCG/单候选证据仍有原来的边界。

**GC总成本与保存阻塞边界**

纯GC每轮2,000历史根、4,096垃圾、保留2,001个活/最新source版本；原256条删除批次未变。根扫描B每轮17→C1。纯清理四轮中位 **1656.414→238.557ms**，配对降幅中位85.672%；自然collector+30保存+收尾清理四轮中位 **1682.847→341.967ms**，配对降幅中位79.505%。这两项四对均更快。

| 配对 | live重叠保存数B/C（各30） | 首次collector删除B/C | 收尾删除B/C | live+收尾根扫描B/C | 保存与收尾删除重叠B/C |
| --- | --- | --- | --- | --- | --- |
| 1 | 6/9 | 0/0 | 4096/4096 | 18/2 | 0/0 |
| 2 | 6/3 | 0/0 | 4096/4096 | 18/2 | 0/0 |
| 3 | 7/7 | 0/0 | 4096/4096 | 18/2 | 0/0 |
| 4 | 7/7 | 0/0 | 4096/4096 | 18/2 | 0/0 |

八轮自然live首遍都`collectionDeferred=true`、删除0；4,096条均由30保存结束后的收尾调用删除。每轮只有3–9项保存与首遍collector重叠，其中1项跨其结束；剩余21–27项在它结束后开始。**每轮重叠子集少于20，p95不作为可靠尾部估计；持续删除与保存竞争未覆盖。** 分布CSV中的子集p95只是透明算术值，不提升验收结论。

暂停根扫描的每轮30项保存均成功，证明该人为固定扫描状态下writer可进展；它不等于自然删除竞争。GC各保存数值指标全四对未越原带，但并非所有数值下降：paused p50三对上升、live p50三对上升，live max两对上升（配对2 7.080→11.825ms、配对4 5.732→8.153ms）。完整原值在前表，不能笼统说“保存更快”。GC保存的数值筛选为带内，**持续竞争验收仍INCONCLUSIVE**。纯清理与组合时间、collector区间、after-roots事件及删除数详见[每轮GC表](analysis/history-values.csv)及原始records。

**最小修复建议与尚未验证范围**

静态检查发现`LibrarySearchRepository.observe`的`runningFold(0L)`先发虚拟初值，Room随后真实初始化事件可能取消首次150ms debounce；`previous`已在delay前赋值，后一次相同query随即不再等该delay。本轮C首批提交时已经扫描1228–1663行，与该启动路径相符，但缺少启动/取消次数及writer入锁时间，**不能据此把全部p50增量归因到该问题**。

提供仅移除虚拟初值发射的[未应用补丁](proposed-startup-debounce.patch)：`runningFold(...).drop(1)`，保留全部真实invalidation、取消与sourceVersion。补丁只通过静态适用性检查，**未实施、未编译、未运行**。它只作用observe启动，暖查询直接调用read，不经过这条链，故不解决或解释暖查询劣化。未通过减少新鲜性通知、退回长写事务或改变阈值取得通过。

后续若获授权，应先验证首次query debounce、真实保存刷新、sourceVersion及分页重置；再保持相同四对/30保存与原阈值，复验p50、尾部、索引与暖查询。若仍回退，再加最少的保存事务进入时间、搜索启动/取消次数及词项预计算阶段诊断；不得把探索子集替代原指标。4层/8层不确定值需原样保留并在固定同环境重复中复核；GC若要关闭持续删除竞争，需事前另定暴露充分的工作负载，而不是本次事后强行延长后台任务。

原PLAN要求host负载和磁盘余量，但本次host.json只记录CPU/内存/kernel/JDK，**缺少负载与剩余磁盘记录**；设备设置相等不能证明host负载相等，这也是未关闭的观测缺口。四对仍是小样本，不能证明无回退；真实设备、真实笔记、长期内存/电量/热约束与未选的备份/恢复/撤销/UI流程本轮未验证。以前五方法KVM与全套JVM/lint、旧TCG、Docker证据保留各自范围，本轮不重测或修改Docker。

**复核与交付**

离线复算：`python3 android/verification/pr20-kvm-paired-20261013/analyze.py`，只读证据并生成analysis，不访问网络、不构建、不调用adb。它核验全部manifest、原始JUnit/记录、writer、六APK回执、依赖、设备及双ACL，再复算原26项时间指标和960个保存样本；[validation.json](analysis/validation.json)给出机器可读结论。另有独立只读复核重算Git输入清单与各关键分布，结论一致。

本报告提交只包含合成文本证据、离线分析、未应用补丁及报告；不含ZIP/APK/数据库/密钥。提交前核对开发分支push与PR base不匹配现有workflow过滤，不复制一次性KVM workflow、不dispatch/rerun、不合并不部署、不更改main或用户源分支。PR20继续Draft，整体性能门槛保持未关闭。
