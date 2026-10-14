# 本轮终态日志独立完整性复核

结论：`PASS_SCOPED_OFFICIAL_LOG_INTEGRITY`，未发现身份、记录、顺序、退出、时限或终态声明矛盾。独立从官方原日志重新拆解、计算哈希，结果与冻结提取器的完整状态一致。此结论是日志证据完整性通过，不是所有性能指标过线或最终产品验收。

## 身份与真实耗时

run `38520196523` / job `115707901766` / attempt1，唯一job；官方run/job均completed/success。job `06:05:35–06:49:57Z`，**44分22秒**。不能把run.created_at `06:05:32Z`当作job起点。

执行 `115734b0ed9aab07a1100087ce76978c0d829e0f`；readiness `d70fcb23afecb6f80e8fc716a5145ecf851dd68e`。B=a128+共同安全factory，C0=`d66b60d6…`，C1=`cb7e1421…`。真实binding较预备模板仅填入四个实际官方终态字段，其余身份与哈希完全一致。控制/readiness/统计文件哈希重新核对匹配。

原日志保留BOM，1,107,962字节，SHA256 `d1634bbeb118100737e2c9d6d6111fca972f2fb05999b5604ff34f0d0e7c9f62`；与官方获取回执一致。没有尝试获取工件ZIP。

| 官方步骤 | 秒数 |
|---|---:|
| 源码与SDK | 33 |
| B构建 | 649 |
| C0构建 | 540 |
| C1构建 | 502 |
| 模拟器 | 39 |
| 31项正确性 | 54 |
| 24项公共比较 | 728 |
| 原3诊断及4项补充正确性 | 77 |

PR anchor `06:04:06Z`，早于job89秒。setup完成于`06:35:06`，距anchor31分钟，在38分钟截止前420秒；甚至早于原32分钟边界，不能把本次完成单独归因于预算扩大。correctness/common/diagnostics分别在截止前546/538/581秒完成，所有块首尾均落在对应官方步骤和原累计截止内。实际job60分钟上限没有触及。

## 62调用与原始转发记录

原日志独立计得：CLOCK/FINAL各1，CONTEXT/END/METHOD各62，RECORD **365**。清单逐序精确匹配31+24+7，每次host/JUnit compact摘要PASS、adb0；这里是**62次执行**，包含重复公共探针，不能称62个不同用例。

365条原JSON共**588,998 UTF-8字节**，每条长度/SHA、顺序footer、方法records摘要均重算一致；重组的62份records共**605,172字节**，与`extracted/`实际文件逐字节匹配。每方法源/test blob、identity、selection、args、seed、schema marker名单/数量/容量均符合冻结合同。逐方法行号、record数和SHA在`review.json`中。

公共比较独立核实24调用、720保存；4seed×2种探针的8组fixture，三端完整fixture内容相等（含逻辑hash、行数、writer参数）。诊断与新增单端用例未混入公共样本。365条中另有60条自然GC试验/2个summary、12条library stage分片、1条Unicode、4条NativeStroke oracle、14条Frame、6条dense stage。数值阈值结论由原54比较和领域审阅另行给出，不能由此处执行PASS推导。

## method17及隔离

14安装组全部声明PASS；三身份×三包共9个APK身份，selected/installed/source_apk SHA在每次调用中相等；同身份跨组APK不变、同组安装记录不变、统一签名一致。前后及全部组环境哈希声明均为 `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`。

61次自然退出，观测总耗时范围0.053307505–0.080000016秒。唯一method17（原C1 UI方法）共19次观察：最后自然观察于2.683068秒，app/data已消失，只有辅助test包PID2784仍在；2.750105秒单次执行固定 `adb -s emulator-5554 shell am kill --user 0 org.inkweft.app.a0.test`，exit0且stdout/stderr空；2.778352秒后置观察三包均exit1/空，2.836793秒完成。符合原3秒自然观察尾端保护与最多2秒后置/总5秒限制。未发现force-stop、其他方法helper清理或app/data被杀来制造PASS。不能由事后消失推断该helper此前只是cached。

## 清理与未取得原文件的边界

官方日志第1021/1043行实际打印的两条cleanup receipt均为 emulator/command_groups `STOPPED_CONFIRMED`、ACL `RESTORED_EXACT`、errors[]。第1067行FINAL重复声明两次恢复前后哈希一致：`f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`。

第1066行packager声明372文件、3,785,601字节、omitted0/truncated0；FINAL packaging_complete=true、missing_files=[]。受控packager白名单/20MiB/3天规则与绑定源码一致，官方包装/上传步骤成功。**尚未独立取得或核验372文件manifest原文、ZIP、APK/ELF/依赖、逐方法原instrumentation/XML或ACL原字节**。所以62 JUnit PASS、安装包/依赖及ACL精确恢复是哈希绑定控制器在官方日志中的声明，不应写成第二次独立原文件审计。可直接证明的是转发JSON的准确字节与哈希、官方终态和这些声明之间的一致性。

NativeStrokeTileReuse原finally仍是best-effort；其PASS不可代替原严格join用例。没有重跑任何suite、修改控制器/源码或扩大门槛。所有旧失败与旧样本仍需保留，本轮不能倒推关闭其他未列入清单的历史门槛。

机器回执 `review.json` SHA256：`8c4b82614a0da6dd067e86a54ed7c1ee16ac8b021a12b38c919bfc6afc2884d8`。
