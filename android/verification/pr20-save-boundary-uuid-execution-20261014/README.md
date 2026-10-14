# 保存边界原生诊断：90次保存完整，性能门仍开放

[run38543698114](https://github.com/InkWeftDevs/InkWeft/actions/runs/38543698114) / job115784421756 / attempt1 成功结束。10个具名方法全部JUnit/host PASS、全部自然退出；单data测试APK、单安装组，包含7个既有守卫、修正UUID后的2个边界守卫和1个三场景诊断。job为2026-10-14 09:37:25–09:43:37 UTC，共372秒。

本轮确认了诊断钩子的原生守卫和90条完整事务链，**没有关闭此前四组配对测试的保存或绘制信号，也没有执行新的GC边界或app测试**。数据来自合成夹具；未接触用户电脑、真实笔记、AniMemo或Docker。

## 固定来源与完整性

| 身份 | 提交 |
| --- | --- |
| 测试源码 | `94379190b9ad0e1034f136ea9887fb0ac12e7ea7` |
| 冻结准备 | `a69c173c16bd8a434cc1eab9ef8ff2ad56070c19` |
| 绑定控制 | `818493a1e8a4d2121e125d5ba39c4219f1977720` |
| 单次激活工作流 | `ac63ce66ed4aaec0372c56b39be97e3335966c31` |

执行分支为`codex/kvm-pr20-save-boundary-uuid-20261014`。1026个Android/CI源码文件的canonical SHA256为`cff1c9b79b4af51e4f9d70426ce4ad4524e3d7fa6827c58aeb9f9177597335d7`。本轮没有覆盖PR20代码分支；PR仍为草稿。

标准ubuntu-24.04 runner、API35 AOSP ATD x86_64 KVM、2vCPU/2GiB、1920×1200/dpi240。仅构建data-local测试APK；实际requery3.49.0四ABI库与安装身份检查有绑定回执，不代表MuPDF或app APK构建。本轮VM中的IDLE、DIRECT、OBSERVE共用固定设备；它不是此前运行的同一VM，因此不以两轮绝对耗时相减作为前后收益。

[官方decoded日志](official/official-job.log)保留BOM与原字节：1,037,054字节，SHA256 `45d6579ec88cdff12dbd94393121a04684dce7595713947e6663af0e6b45d68c`。官方终态metadata同存于`official/`。从原日志独立复核了1 CLOCK、10 CONTEXT、57 RECORD、10 END、10 METHOD、1 FINAL；所有原JSON payload、footer及导出字节一致。57条数值标记为3 RESULT、45 EVENTS、6 READS、3 END，三个场景各30次Committed，drop/invalid均为零。

冻结提取器返回`COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE`、issues=[]，详见[完整性摘要](extraction/actual-extraction-summary.json)和[独立原日志复核](reviews/extractor/independent-actual-review/actual-review.md)。trap及always回执均为STOPPED_CONFIRMED、RESTORED_EXACT、error_count=0，ACL前后SHA一致；打包complete、missing_files=[]。这些是绑定的官方日志回执，不是对APK、原完整JUnit、ACL文件或工件ZIP的第二次字节审计。未请求工件存储下载。

上一轮[38540498244失败记录](../pr20-save-boundary-execution-20261014/README.md)完整保留。两个guard的非法UUID是独立静态确认的夹具错误；本轮修正后通过，不等于恢复了上一轮未转发的异常堆栈。新失败原文机制没有触发，失败次数为零，未重试旧任务。

## 实测保存分布与边界

冻结分析器`fd1c22004bb91e7e6673a6ac59eb0f2c2413c9f8ef454a00b2950c0581bbd1b7`接受90条完整链。初始夹具hash仍为`1c69bba77781ba0c0b642309b8e869ff21c9a440fd7714894e67c572b95c9dcf`；seed4101、六表计数、revision1/1/32及writer WAL/FULL设置均匹配。独立复算90个样本、1080个区段，端点、线程、CPU与分位数一致。

| 场景 | p50 ms | p95 ms | max ms |
| --- | ---: | ---: | ---: |
| IDLE | 2.035774 | 4.737515 | 5.314565 |
| DIRECT | 2.268760 | 9.392518 | 9.446588 |
| OBSERVE | 6.324345 | 11.146675 | 23.839401 |

每行各30次，nearest-rank下标14/28/29，不合池、不插值。IDLE独立新夹具；DIRECT→原暂停读保存→OBSERVE保持公共生命周期。三种状态不可相减为因果开销。这是单候选、单seed、开启诊断的定位运行，没有observer-off开销校准，不能替代公共四组配对验收。

- OBSERVE最慢sample17排队21.556330ms，其中21.523241ms与同一writer线程上前一索引任务213重叠。其余29次没有索引writer交叠，索引队列不能解释中位数。
- OBSERVE实际中位sample20总6.324345ms，SQLite END为4.924512ms、同线程CPU仅0.119026ms；作者body为0.887475ms，排队0.099858ms。
- DIRECT实际p95 sample8总9.392518ms，SQLite END为7.674103ms、同线程CPU0.121790ms。其max另为sample25，未删换慢样本。
- OBSERVE实际p95 sample4总11.146675ms，耗时分散在body、END、Room post-END及跨线程返回；没有索引writer交叠。窗口中26个IO读取全部取消，9个page-hits-start没有完成，不能将未结束阶段补成完整查询耗时。

SQLite END包含提交/回滚及连接释放。低线程CPU只能定位到等待/调度占比高的边界，尚不能区分同步存储、checkpoint、连接等待与被调度出CPU；没有依据降低FULL、删失效通知或调整debounce。12段只覆盖save-request→save-returned，实际调用还包含探针两端小开销；各段分位数不能相加。完整原序数据、CPU及重叠见[数值报告](numeric/report.md)、[90条保存交叠CSV](numeric/save-overlaps.csv)与[36组区段分布CSV](numeric/segment-statistics.csv)。

## 仍未完成的验收

此前[62调用同机配对结果](../pr20-match-execution-20261014/README.md)保持原分类：45带内、5不确定、4回退信号。保存p50/p95信号尚无已验证生产修复；本轮只定位原因范围。B→C1八层forced-full仍为+39.038078ms/+12.219%的原规则信号，不能凭计数相同归为噪声。

新增严格owned Job清理测试仅有host编译证据，本轮data-only运行未执行它们。新增GC DELETE边界测试也仅有host编译；历史自然竞争0/30、1/30的暴露不足结论不变。[GC容量核算](follow-up/gc-feasibility/FEASIBILITY.md)的90×512条boundary模型，仅原marker文本与records两份已为24,129,664字节，超过20MiB；这是保守保证不足，不是证明真实运行必然超限或该组合必然可达。该范围没有生成控制器或启动原生任务；不通过截断、删样本、放宽预算或更改到达模型补齐取证。

本轮没有新增生产优化，也未进行最终统一代码推送、合并或部署。后续绘制成对归因、GC运行范围和保存END内等待仍需各自的具体证据，不能以本轮10项PASS代替。`follow-up/`保留尚未激活的绘制成对方案、GC范围和read-only handle复用审查：实际requery的addFunction会积累配置/旧闭包，简单外提连接不成立；还缺数据库重开/文件替换生命周期保证，且没有证据证明该方向解决END延迟，因此没有加入生产修改。

## 离线复核

`SHA256SUMS`覆盖本目录全部文件（清单自身除外）。从本目录执行`sha256sum -c SHA256SUMS`可核对归档。`extractor/`保留冻结提取器、120个合成例收执和独立审查；合成例不是原生结果。`control/`是归档的执行控制文件，放在verification目录不会激活CI。

从仓库根可离线重算数字：

```sh
python3 -B android/verification/pr20-save-boundary-uuid-execution-20261014/numeric-analyzer/analyze.py --markers android/verification/pr20-save-boundary-uuid-execution-20261014/extraction/actual-output/method-10-C1-markers.log --output /tmp/inkweft-save-boundary-recomputed.json
```

已从归档位置执行上面的重算，完整5,538,252字节输出与原`numeric/analysis.json`逐字节相同，SHA256为`05f728fae9fff2df0fc217ff6e3cb77a6cc69d0edae9bfa07853b7dc1c91f265`，见`archive-recompute.json`。该脚本不联网、不运行Android、不执行验收分类。完整提取器的Git链校验还需要上表四个真实Git对象；原调用与输入hash均在归档binding中，缺对象不得跳过身份检查。
