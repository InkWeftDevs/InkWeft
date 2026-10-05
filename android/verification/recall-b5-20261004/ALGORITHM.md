# SM2-IW1 规则与归属

SM-2 algorithm © Piotr Wozniak / SuperMemo World. 墨织的调度政策与事务包装采用 AGPL-3.0-or-later；数值核现为 MIT SuperMemo2 v3.0.1 的最小 Kotlin 移植，保留独立许可与署名（见文末）。不含官网 Delphi 代码，不引入 Python 运行时。

- 原算法说明：https://www.super-memory.com/english/ol/sm2.htm
- 官方现行文章：https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-obtained-in-working-with-the-supermemo-method
- 官方使用政策（2017-01-23）：https://www.supermemo.com/en/blog/licensing-and-copyrighting-of-supermemo-algorithms 。其说明认可按要求附版权说明使用SM-2。此处保留算法归属及出处，不宣称后续商业算法已获许可。

SM2-IW1选择官网文字版的向上取整规则。官网Delphi示例使用round，与文字版存在差异，未混用。

- 初始EF为2.5，下限1.3；成功的前两次间隔1、6天，此后按旧EF乘上一次间隔并向上取整
- EF更新：EF + 0.1 − (5−q) × (0.08 + (5−q) × 0.02)。实现按百分位整数运算，避免浮点临界误差
- q<3时成功计数归零，间隔1天；仍保留本次更新后的EF，不另重置为初始值
- 本实现安全上限36,500天（100年）是墨织边界，不声称原算法含该参数；版本标识固定SM2-IW1
- 只在正式模式评分时更新排程。使用提示/查看原文/揭开空位或遮挡的正式回答，其有效质量上限2；原自评分和提示事实分别保存并展示
- 临时练习、跳过、未完成均不更改排程。对照完整答案本身不是额外提示；揭开局部线索和查看原文是提示事实
- 完成时刻保存UTC epoch，间隔为24小时天数；本地时区只用于显示与时间段筛选，时区变化不重排既有到期时刻

测试向量来自独立代入官方公式，不是官网公布的测试数据。恒q=4应得1、6、15、38、95天且EF2.5；恒q=5应得1、6、17、48天；失败/下限/截断与跨时区另外覆盖。

## 成熟实现复用（2026-10-04 后续）

在上述公式独立实现已通过的基础上，按用户偏好核验并最小移植 MIT SuperMemo2 v3.0.1 的数值核。固定 SHA `0aaf428cf362b976f49a2dece5e01211785caec2`，原LICENSE及具体保留/调整见 `core-domain/third_party/supermemo2/README.md`。生产 `Sm2Schedule.grade` 调用 `SuperMemo2Kernel.review`，不是只留一个未调用的参考或增加无用Python依赖。保留原SM2-IW1存储及整数百分ease、UTC epoch、提示cap2政策；上游库公式分支一致，ceil而非round。新增3组上游测试数值与1项浮点差异声明测试；最终测试结果另记，不套用之前8项PASS。
