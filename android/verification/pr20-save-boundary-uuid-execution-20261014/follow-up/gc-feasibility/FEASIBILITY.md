# GC boundary 离线可行性：预算保证阻塞

结论：**BLOCKED_CONSERVATIVE_CAPACITY_GUARANTEE**。当前完整容量不能通过既定 20 MiB 保守证据预算审查，GC 保留 host-only / native 未验证。**保守保证不足不等于实际运行必定超限。** 本报告没有制作控制器、workflow、readiness、prep 或 active，也没有网络、原生执行、推送、拆分运行或修改代码/容量/时限。

绑定 source `4c4a2d67a27b56f351b3433d4f99deb7ce3e2591`，新 test SHA256 `98b69ea1d09e0bce21646d79f50f9b7dd9396c9e73bb1a9baea647de7cdf6b72`。输入执行提案 SHA256 `78e5df63e8810323819c6c18e9f2f842b3511c12ef700aa62dcfbc73f860451e`。相对已通过保存 hook guard 的 source `94379190b9ad0e1034f136ea9887fb0ac12e7ea7`，core-domain、data-local 生产目录及所列 Gradle/依赖路径的只读 Git diff 为空；完整路径在 capacity.json。继承的是已验证依赖前提，不是新的 GC 原生 PASS。

## 精确计数与算式

同 Room：30 trial × 1 DB；异 Room：30 trial × 2 DB。总 60 trial、90 个独立 recorder 窗口。每窗口 512 boundary，故 `90 × 512 = 46,080` events。每块 48，512 为 `10 × 48 + 32`，故 `90 × 11 = 990` EVENTS marker；另 60 TRIAL、2 SUMMARY。batch 是每 trial 64（不是每 DB），即 `60 × 64 = 3,840`；phase 为 `60 × 32 = 1,920`。

使用源码实际字段与 ASCII 枚举、JVM 整数位宽，逐个序列化 JSON，保留两个模式的实际 sample/part/database/connection 字符长度。258 字节的 boundary 容量模型见 capacity.json。它是字段形状/整数宽度模型，**不是合法成功事务序列，也未证明最大 ordinal/depth 在本夹具可达**；不能拿它当原生样本或预测实际工件大小。仅事件对象主体就有 `46,080 × 258 = 11,888,640` 字节。

| 已核算项目 | 字节 |
|---|---:|
| EVENTS 原 marker 行，含实际 wrapper | 12,054,930 |
| EVENTS records JSON，含实际数组/marker/data wrapper | 12,074,734 |
| **上述两份合计** | **24,129,664** |
| 原样 JSON 经转义后的 RECORD 封套 | 13,376,360 |
| 两方法 EVENTS 的 END ordered-payload 收执 | 159,430 |
| boundary 原文、records、forward 与 END 小计 | 37,665,454 |
| batch/phase 数组原文与 records 两份 | 3,014,640 |
| batch/phase 数组在转发字符串中的转义体积 | 1,611,240 |
| 一次 failure 原文预留 256 KiB | 262,144 |
| failure 封套上界 `6 × 262,144 + 8,192` | 1,581,056 |
| 其余文本预留 3 MiB | 3,145,728 |
| **已核算容量模型小计** | **47,280,262** |
| 既定 20 MiB 限制 | **20,971,520** |

batch 对象模型为 362 字节，phase 为 58 字节：单份数组分别为 `60 × (64 × 362 + 63 + 2) = 1,393,980`、`60 × (32 × 58 + 31 + 2) = 113,340` 字节。其转发体积按真实 JSON 引号转义逐字计算，不以三倍近似替代。

47,280,262 **不是完整最坏上界**：尚未展开 TRIAL 的其余字段/11 段/DELETE intervals、SUMMARY 分布、正常 instrumentation framing、TRIAL/SUMMARY 转发 metadata。独立容量上界同时取满也未证明可达。这个模型已不能闭合预算，因此无需伪造完整可达失败序列来宣称超限。作为敏感性核对，即使改用 147 字节、短数值的同容量字段模型，boundary 三份及 END 仍为 22,319,014 字节；该模型同样不是合法成功 trace。

源码没有对任意异常类名、suppressed 异常总文本或完整 instrumentation 原文提供完整字节上界。现有 failure-only 方案只能对 ≤256 KiB 的原文严格 UTF-8/secret/hash 校验后完整转发，超限须显式 REFUSED，不能截断、删慢样本或变 PASS。其固定预留不能被解释为所有失败原文都有 ≤256 KiB 的保证。

## 最小范围与未决项

method-plan.draft.json 仅列 5 项既有 HistoryCollectionTest 安全 guard，再列 sameRoomNaturalArrivalBoundaries、separateRoomNaturalArrivalBoundaries，共 7 调用、1 个 data test APK、1 安装组。两新方法保留每模式 30 trial、原 1281/1280 夹具、after-scan 非阻塞到达、一次 rename、5 秒保存 watchdog；180 秒仅沿用输入提案的外层方法预算建议，未执行/未冻结。既有 guard 超时保持继承，未擅选新值。原环境、60 分钟 job/55 分钟停工、精确 UID ACL 双清理、3 天纯文本保留等要求不变。

schema-draft.md 给出源码封闭字段集合与必要语义校验；因预算未闭合，它不是可执行契约。0/30 暴露仍可是完整功能结果：INSUFFICIENT_EXPOSURE、latencyAcceptance=NOT_EVALUATED，不能补样本或改 fixture。剩余阻塞是可审阅、可保证的证据容量设计；本轮不继续构建控制器、不寻找拆分绕过上限，也不从 host 编译/离线算术推导原生性能。

复现：`python3 /workspace/scratch/pr20-gc-boundary-feasibility-20261014/analyze_capacity.py`。脚本只读本地 Git 与提案，生成 capacity.json 和方法草案；所有数字均为离线序列化算术。
