# Focused search screen：独立原始payload复算

结论：与冻结分析一致，9指标/36配对均无计算或分类冲突；全部为WITHIN_PROPOSED_BAND。**没有证据证明空draft CTE具有稳定收益**，亦未触发预设回退信号。当前不改代码、不启动新测试；保留或撤销是维护取舍，不能声称此次数据证明回撤会提速。

本轮C0=d66b60d6cf9587f646acb76dd8a00910b73a6d70、C1=a9ccf2cc727a7957e8fb00115f0060d80b287de1；run38508580078/job115671626209。只用本run四seed的8公共调用，不混原B或历史样本。独立实现读取官方日志原JSON payload，没有调用冻结analyzer。

已核8 CONTEXT/96原payload/8 END及其逐条hash、排序footer和重建records SHA；18 compact method receipts均host/JUnit PASS和NATURAL_EXIT，9组PASS，FINAL complete、missing=[]、双cleanup宣称精确恢复。**这些是官方日志回执，不是原ZIP/raw JUnit/APK/ACL原字节的独立核验**；无需为了复算已有payload而索取ZIP，但不能提升该证据层级。

480条保存的sample索引、原序、ns区间、耗时和连续committedRevision均保留核对；最近秩p50/p95/max分别用排序索引14/28/29，16个旧emit p50按索引15核对保留，未混用。每30条都和背景窗口重叠；重叠不等于锁等待。实际比较单位是4组，非480独立实验。

## 四组配对统计

正数代表C1较慢；相对数为各seed相对变化的中位数，绝对数为各seed差值的中位数，两者分别计算。

| 指标 | 较慢组数 | 配对差中位ms | 配对相对差中位 | 原门槛绝对ms / 相对 | 分类 |
|---|---:|---:|---:|---|---|
| library.direct.p50 | 3/4 | +0.0860285 | +7.070088% | 1 / 10% | 带内 |
| library.direct.p95 | 3/4 | +0.7288495 | +17.713801% | 5 / 15% | 带内 |
| library.direct.max | 2/4 | -0.0178795 | -0.372144% | 20 / 25% | 带内 |
| library.observe.p50 | 2/4 | -0.1444035 | -3.542564% | 1 / 10% | 带内 |
| library.observe.p95 | 2/4 | -0.0207960 | -0.628076% | 5 / 15% | 带内 |
| library.observe.max | 3/4 | +4.5201365 | +26.901666% | 20 / 25% | 带内 |
| library.coldMs | 3/4 | +14.9076765 | +1.379971% | 500 / 10% | 带内 |
| library.allIndexRowsCommittedMs | 2/4 | +278.9848765 | +1.652614% | 500 / 10% | 带内 |
| library.searchIndexReadyMs | 2/4 | +282.8366700 | +1.642947% | 500 / 10% | 带内 |

observe p50两好两差，配对−0.1444035ms/−3.542564%，方向不一致。observe max三慢一快，+4.5201365ms/+26.901666%；虽超过25%相对门槛，但未过20ms绝对门槛，因此不能判回退信号。

warm每调用5个原序值单独保留，3慢1快；每seed内部中位再配对，配对差中位+1.3959920ms、配对相对差中位+0.437940%。无预设warm门槛，只描述，不把40条warm观测当独立样本或增设阈值。

## 实际周转和边界

官方job started_at=03:53:35Z、completed_at=04:04:27Z，即652秒（10分52秒）。10分49秒对应从同开始到最后Complete job step结束04:04:24Z，不能混为API job span。阶段：源码/SDK39秒，C0构建step209秒，C1构建step147秒，KVM23秒，10正确性8秒，8公共199秒；其余为首步/checkout/收尾。更短周转来自限定两data APK和18方法的工作量，不能将其当生产CTE收益或与全套构建直接作等量比较。

同seed fixture完全一致，SQLite1024/FULL/WAL/synchronous2，headers与directory正文0、projection61行，全文按需校验true；具体字符数随seed保持原值。空CTE不改变读取规模的结论限此夹具。原B observe-save回退、全套正式门槛及原20k/10秒均未被本筛查关闭。

所有数字/原对值/原warm/文件哈希见同目录independent-analysis-review.json。identity.json中的LOCAL_ONLY状态是历史prelaunch记录；终态使用官方run/jobs和binding。
