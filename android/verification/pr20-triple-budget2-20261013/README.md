# PR20 三端预算修订复验：B构建提前失败

**INCOMPLETE / 44项原生测试NOT_RUN。** [run38403076172](https://github.com/InkWeftDevs/InkWeft/actions/runs/38403076172) 于2026-10-13 13:12:13–13:16:30 UTC执行，总4分17秒，GitHub结论failure。该失败发生在32分钟准备截止之前；不能继续按首次超时解释，也不能在未读原日志前推断编译、依赖、网络或内存原因。

首次run的[用户原ZIP与双清理回执已完整核验](../pr20-triple-attempt1-recovery-20261013/README.md)，其cleanup证据缺口已闭合，旧运行已结束后才启动本次，无重叠。原C1代码及所有性能缺口仍不变。

## 固定执行与预检

本次独立分支 `codex/kvm-pr20-triple-20261013-budget2`；准备提交 `2ea991e51b5fed103012d3ee2e5bd94cc1ec6f0d`，活动提交 `54a1f5cc7ac757125f8ccb9cc27f5a21b87a50c9`。准确控制器见[固定源码](https://github.com/InkWeftDevs/InkWeft/tree/54a1f5cc7ac757125f8ccb9cc27f5a21b87a50c9/ci/pr20-kvm-triple)。只有指定分支/path push、created=false、固定before、attempt1；无dispatch或自动重跑。新分支准备后确认0次运行，再绑定准确SHA激活一次。

累计截止32/38/52/55/60，单标准ubuntu-24.04 runner≤60分钟；三端固定源码、9 APK、44方法（17正确性+24公共比较+3候选诊断）、14安装组、原样本/阈值、串行单worker/禁用任务缓存不变。预算依据、未知项和敏感性见budget-revision.json。

未来超时状态记录已修：保留原始summary并归一化所有层的RUNNING/INSTALLED，尚未进入下一step的暂态也标INCOMPLETE；原PASS/FAIL/NOT_RUN/JUnit不改。清理算法与旧版逐字节相同，仅外包限时，并保留双回执核验要求。

7组本地静态/合成预检通过：24份旧原始JUnit、11种失败变体、4种新marker、实际截止/导出/auth一致及各边界、actionlint/bash/内嵌Python、12个本次package合成场景（含首次真实超时摘要副本、各状态层、中断前边界、完整44与缺失/失败清理、敏感/超大工件拒绝）；38个输入哈希前后不变。preflight.json为原结果，最终8项EXEC哈希另核验，真实before绑定和活动workflow语法复核记录于metadata/bound-guard.json。**这些是控制器检查，不是Android性能或功能通过。**

## 本次公开元数据

| 步骤 | UTC时间 | 结论 |
| --- | --- | --- |
| 源码重建与SDK | 13:12:21→13:12:49 | success |
| B构建核验包装步骤 | 13:12:49→13:16:26 | failure，3分37秒 |
| C0/C1构建、KVM、17+24+3方法 | — | 全部skipped |
| always清理 | 13:16:26→13:16:27 | success |
| 白名单打包、上传 | 13:16:27 | success |

唯一failure annotation为 `Process completed with exit code 1.`；没有原始build-B日志，无法定位根因。本次未形成已核验APK、设备、JUnit或性能结果。清理step success不替代未读取的原始trap/always回执。

官方API确认一个工件 `pr20-kvm-triple-38403076172`，ID11815973887，194,588字节，SHA256 `a98f26d5836fde579c2114438a680c666b68e6a1d734e11dff6c06ae863d34ef`，2026-10-16 13:16:27 UTC过期。

没有请求本次工件的存储下载，更没有宣称本次下载返回Forbidden。此前通道受拒绝后，后续原证据沿用用户上传原ZIP并通过Library授权读取的路径。需要本次原ZIP后才能核验manifest、完整失败日志、原始summary及全部清理回执，再决定普通修复或必要有界重试；未启动第三次运行，不盲目重跑。

此报告只新增公开元数据与控制器预检证据，不改Android生产/测试源码或既有冻结方案，不合并部署，不强推用户源分支，不访问用户电脑/真实笔记/AniMemo。原始1项REGRESSION、4项INCONCLUSIVE和GC实际删除竞争覆盖缺口仍未关闭。
