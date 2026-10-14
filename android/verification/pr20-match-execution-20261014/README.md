# cb7 搜索候选：同机三端原生结果与仍开放的保存诊断

**搜索收益已在本轮固定合成夹具上实测；保存延迟和完整性能验收仍未完成。** [run38520196523](https://github.com/InkWeftDevs/InkWeft/actions/runs/38520196523) / job115707901766 已成功结束，62次具名调用的执行记录完整，不代表62项性能验收。没有重跑本任务、删除慢样本或修改公共探针与阈值。

## 来源与证据边界

| 身份 | 固定提交 |
| --- | --- |
| B | `a128bb07e34cb82f00eeafc8e62a47bf7b95b128`，加已披露的共同安全 open-helper factory |
| C0 | `d66b60d6cf9587f646acb76dd8a00910b73a6d70` |
| C1 | `cb7e14218ac83aa8cf6b9d7906051a10da1a6547` |
| 冻结准备 | `d70fcb23afecb6f80e8fc716a5145ecf851dd68e` |
| 一次性执行工作流 | `115734b0ed9aab07a1100087ce76978c0d829e0f` |

同一标准 ubuntu-24.04 runner、API35 ATD x86_64 KVM、2vCPU/2GiB、1920×1200/dpi240；四个原 seed4101–4104，24公共调用、720次有序保存、18指标的54个比较。B是披露了共同 factory 的受控基线，不是未修改的旧APK。每个seed的三端逻辑夹具hash一致。真实 writer 为1024字节页、auto_vacuum=FULL(1)、WAL、synchronous=FULL(2)。

job为06:05:35–06:49:57UTC，共44分22秒。原31正确性、24公共比较及7候选诊断调用全部完成；14安装组、9APK的compact绑定与JUnit状态一致。两次清理回执均进程停止、ACL精确恢复，无错误。除原method17的受限test-helper清理例外，其余61调用均自然退出。这些是已绑定官方日志中的回执；本次没有取得APK、原完整instrumentation日志、ACL文件或工件ZIP，不能声称对其字节作了第二次独立审计。

[官方decoded日志](official/job-decoded.log)保留BOM与原字节，1,107,962字节，SHA256=`d1634bbeb118100737e2c9d6d6111fca972f2fb05999b5604ff34f0d0e7c9f62`。365个原JSON payload的字节数、SHA、顺序与62个records文件逐项匹配；严格提取为`COMPLETE_SCOPED_MATCH_LOG_EVIDENCE`、issues=[]。没有下载、重试或绕过被拒绝的工件存储路径。

## 搜索、保存与实际读取规模

以下每格是**四个seed各自统计值的中位数**，不是合池分位数；每组30保存按nearest-rank下标14/28/29重算p50/p95/max。五次暖读先各seed取中位数。原legacy上中位字段原样保留，不用于本表。

| 指标（ms） | B | C0 | C1 |
| --- | ---: | ---: | ---: |
| 未建索引首次搜索 | 4773.525 | 2168.929 | **700.518** |
| 建完索引后暖读 | 531.339 | 606.972 | **150.039** |
| direct保存p50 | 2.634 | 3.155 | 3.303 |
| direct保存p95 | 3.738 | 8.390 | **10.475** |
| direct保存max | 4564.623 | 9.665 | 11.077 |
| observe保存p50 | 3.323 | 7.818 | **7.858** |
| observe保存p95 | 98.186 | 22.607 | 19.042 |
| observe保存max | 4538.259 | 53.793 | 50.929 |
| header读取 | 200.601 | 24.923 | 25.154 |

B→C1冷读配对中位下降85.324%；暖读配对中位下降71.488%，C0→C1暖读下降75.227%。暖读没有新增独立数值门槛。B→C1 observe p50配对增加4.406300ms/128.576%，direct p95增加6.736855ms/180.559%，仍为原规则回退信号；direct p50仍不确定。更低的max不抵消这些门槛。

B的direct场景只有首个保存与冷读重叠，C0/C1的30次全部在读期间提交；这是真实并发行为变化，也使p95暴露不同。保留完整原分布与分类，不以暴露差异豁免回退。20,000索引行完整提交约33–35秒，吞吐未呈现显著改善。

固定夹具是500本×40页=20,000可检索页，另有作者1本/1空页。500个note body各20,020 UTF-16字符；19,999页各1031字符，唯一命中在book499/page39，仅11字符。搜索收益限于这个重复正文、单命中、无草稿/空分类的夹具，不能泛化到真实长文和所有查询。“冷”指未建索引，建立和hash夹具已先执行，不是设备冷启动或冷磁盘。

header/目录实际读取正文由10,010,000 UTF-16字符降至0；页面读取61条前瞻摘要、36,025字符，返回60条/35,425字符；打开正文另按需读取并核对完整内容。目录元数据仍为O(N)，字符数不是物理磁盘I/O量。详见[检索独立复算](reviews/search-review/search-review.md)及完整原序JSON。

## 绘制与GC

36组局部/冷重建像素比较通过。1/4/8层局部payload decode由B的13/21/11降为C0/C1的1/1/1，局部均只重建1块；C1 native Stroke构造仍为13/21/11，不能写成全部native跨帧复用。公共清理记录确认捕获的Job已join、bytes=0、inFlightJobs=0。

追加逐瓦片旧循环oracle的四阶段native构造24/32/16/0→2/2/1/0，像素均一致。连续14帧像素一致；12次变换不再decode/read旧payload，但仍重建212块、构造2916个native对象。分散追加仅读/解码2个新payload、复用22个旧geometry、重建2块。初始帧和两次变换慢于自身cold的原值全部保留，不能把不同case的描述中位数当B/C速度比较。原1201笔/76802点密集用例在1362.833ms达到原30秒门槛。新增oracle/连续用例的clear+timeout-or-null计数轮询不等同公共严格Job join；dense成功路径验证有界worker/geometry恢复，也不是捕获Job join。

B→C1八层forced-full的配对中位增加39.038078ms/12.219%，按冻结规则为信号；C0→C1八层first/full不确定。公共append阶段计时不能解释full阶段的原因；当前不支持凭计数相同就归为噪声。详见[绘制完整审查](reviews/raster-review/report.md)。

本轮自然GC诊断同Room/异Room有效暴露分别**0/30、1/30**，均`INSUFFICIENT_EXPOSURE`，竞争p50/p95=null。唯一暴露保存84.061ms，覆盖5批/1280条删除；精确DELETE区间和纯锁等待未测得。60/60保存Committed、revision/内容/保留数据通过。每模式首轮+收尾根扫描共60次，30夹具总清理耗时2583.426/2622.482ms；收尾始于保存后，不算竞争耗时。历史17→1根扫描/1656.414→238.557ms同环境纯GC结果和另一场条件到达30/30仅作各自历史证据，不与本轮合池。详见[GC复算](reviews/gc-review/gc-review.md)。

## 分类规则差异与下一步保存诊断

冻结[原PLAN第114行](../pr20-kvm-paired-20261013/evidence/frozen-PLAN.md)定义为至少3/4对同向变慢，且配对中位相对及绝对增量均超带。冻结`analyze.py:40–47`实施此定义；原protocol JSON仅列数值带及quantile，未另定义跨seed规则。结果为**45带内、5不确定、4回退信号**。先前[candidate3报告](../pr20-candidate3-results-20261014/README.md)也已明确区别于“三个个体越带”。

父侧提出的“至少3/4个体同时越带”敏感性复算会得45/6/3，唯一变化是B→C1八层forced-full降为不确定；其2/4个体超带但3/4变慢、配对中位超带。两种算法都保留B→C0 observe p50、B→C1 observe p50/direct p95三个保存信号。[独立规则审计](reviews/rule-audit/review.md)从原markers重建全部54项，并保留最早预运行提交`c37ba03`的PLAN/脚本/protocol原件。这里保存原规则、原输出与替代复算，不把工程筛查信号称作统计显著的确定性能回退，也不事后修改门槛。

当前method58在保存窗口内19次IO读取消，没有page SQL/目录组装开始；主要重叠为scope/draft准备。direct窗口无后台index writer，仍可见保存尾部压力，不能一概归为index锁等待。旧360次因果诊断中的receipt→return端点全部跨线程，不能把该段当纯commit或相减thread CPU。Room END会同步完成SQLite END后请求异步失效刷新，保存不等待刷新完成；无证据支持删掉失效通知或改debounce。

[阶段复算与可证伪假设](reviews/search-review/causal-plan.md)和[最小事务边界方案](follow-up/save-boundary-instrumentation-plan.md)保留了缺失边界：现有Room串行executor入队/进入、真正SQLite BEGIN/END、Room END及caller返回，带相同线程CPU与深度关联。后续默认关闭诊断将区分排队、同步结束与跨线程返回；未测结果前不猜测生产优化。此目录记录的是**方案状态**，不声称新钩子已编译或原生执行；任何后续实现/运行另行绑定提交与证据。

## 离线复核与限制

从仓库根运行`python3 -B android/verification/pr20-match-execution-20261014/verify.py`：验证本目录全文件SHA256，使用原冻结解析器和归档执行控制文件，从官方日志重新生成62records/7CSV/统计结果；仅允许输入路径拼写变化。需Python与已有PyYAML，不联网、不运行Android、不改输入。`reviews/`中独立审查脚本为原样历史副本，保留其scratch路径；正式可移动复算入口为本目录`verify.py`。

官方日志只由受支持的只读GitHub连接器取得。所有数据均为合成夹具；未访问用户电脑、真实笔记、AniMemo，未编辑或重测Docker。原生运行成功不替代完整性能验收，剩余保存、绘制及GC竞争限制仍公开保留；不合并、不部署、不强推用户分支。
