# 剩余性能成本：因果诊断准备

候选源码固定为 `78a5babeaeb92292026423222d5e08635989198b`。本目录是准备、静态审查和 host 编译证据，**没有新的原生性能结论**。原公共探针、四组种子、判定规则与历史失败保持不变；原 observe-save p50、暖读、绘制回退及 GC 自然删除曝光不足仍开放。

已撤去 a9cc 的空草稿 CTE 分支，保留空/非空覆盖测试。其四对筛查没有稳定收益；这是简化与恢复因果基线，不声称回撤会提速。未删除 notes 失效监听、快照、取消检查或索引提交时的原文重核。

## 这次要区分什么

- [搜索假设](search-hypotheses.md)：分页 SQL 占暖读约98%的墙钟，不等于 contains/JNI 占98%。独立暖读入口保留实际 SQL/EXPLAIN，统计四类 UDF 的调用数、UTF16量、转换及匹配 wall/同步线程CPU；无诊断前后读、带诊断读和内存参考明确分开。
- [保存分解审查](save-diagnostic-review.md)：6固定模式、两个正反顺序 block、每模式30保存。记录事务申请/进入/写入/返回及读、索引阶段，核实际重叠。模式 readiness 不同，差值不可相加解释成纯锁成本；FULL 若无真实 reader 重叠，不能排除 reader 影响。
- [绘制闭合范围](raster-closure.md)：解码计数与已有像素收益保留；补元数据、同步 native 准备/构造、发布和帧回调到下一 draw 的时序。既有单层回退不能凭代码相同或接近帧长认定为噪声。
- [GC 闭合范围](gc-closure.md)：原自然样本全部在并发修改后延期，未暴露 DELETE。新入口在 guard 通过后仅通知预启动作者，绝不暂停临界区；它是条件到达诊断，不能替换自然分布或把零暴露视为通过。

生产观察器默认关闭，不产生诊断时钟/事件。字节码检查确认默认 `NoteRepository.save` 尾调事务函数，额外 traced 状态机仅由启用分支调用；这不是原生性能测量。详见[源码绑定](source-check.json)、[字节码](note-repository-bytecode.txt)、[GC/绘制独立审查](gc-raster-diagnostic-review.md)。

## 本地验证

| 检查 | 结果 | 限制 |
|---|---|---|
| 首次数据编译 | FAIL，回调被推断成 `Unit?`；已显式标注 `Unit` | [原失败](host-data/result.json)保留 |
| 数据重编与定向 JVM | 12项执行通过，127.064秒；AndroidTest源码编译通过 | [记录](host-data-final/result.json)，无设备 |
| App编译与定向 JVM | 57项执行通过，256.394秒；生产与AndroidTest源码编译通过 | [记录](host-app/result.json)，无设备 |
| GC测试异常清理修正后 | AndroidTest最终字节重编通过，61.452秒 | [记录](host-data-cleanup-final/result.json)；12项JVM为UP-TO-DATE，不重复计数 |

独立审查发现并修复了默认关闭时的可变捕获盒子、保存外层挂起状态机，以及新测试日志/分配失败时的清理与异常遮蔽问题。69项实际JVM检查、编译和静态核验都不能替代 Room/Android 原生执行。

后续独立原生诊断计划只构建一个源码、三个真实APK，执行7个现成正确性方法、1个新Unicode正确性方法及5个诊断方法。保持标准runner、固定模拟器、30/45/50/55/60分钟截止、最小KVM ACL恢复、文本20MiB/3天及官方日志闭合。先依据诊断选择最小修复，稳定候选才进入原冻结完整验收。

源分支仍为 `a128bb07`，main仍为 `aa43d43b`。本轮没有编辑或重测 Docker，也没有访问用户设备、真实笔记或 AniMemo。
