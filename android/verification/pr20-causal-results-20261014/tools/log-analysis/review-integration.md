# 官方因果诊断日志 parser 独立静态审查

结论：当前冻结快照未发现剩余确定性阻断，可用于本轮官方日志的离线闭合检查。此次只读审源码、冻结 producer 和既有合成回执，未运行 parser/测试、构建、Android、网络或 CI；没有修改 controller、生产代码或 parser。

绑定本轮 run `38514139119` / job `115689021611` / attempt 1；workflow `d4a107dd84c6e309b5dbbbfb3a57d69371024408`，source `78a5babeaeb92292026423222d5e08635989198b`，readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`。具体 run/job 由调用者的可信官方元数据和 binding 输入提供，脚本不查询网络或自行证明这些文件来源。

| 最终审阅文件 | SHA-256 |
|---|---|
| analyze_logs.py | `2c96309f3f8a847f86bdb62cf87f45405461ebcaa76b3659a2cee8181758fa57` |
| test_analyzer.py | `fbab9d655dbef7387f42cff103106dc8871f4569d7c9e2caaad21a0d0066a4e5` |
| frozen_markers.py | `3514d2bca1f7b91efe7886ea42763b37eca873c8914938e8ad3dfeb03eaa1592` |
| check-03/selftest-results.json | `17526d63bc687c0090dd5255eb185db748ce59221a7309e8af6db7aff70a1317` |
| README.md | `b616563e988139715b480276b0d16de9c63cbc7fab4ab00dcb68cd0b5aad1248` |

本次发现并经作者修正三项：

1. `main` 在成功解析后的输入 stat/read 失败时曾可能保留 COMPLETE；现第226行强制降级 INCOMPLETE，并有 late I/O case 验证退出码1及输出不升级。
2. 官方元数据原先未排除额外 job 或错误事件/分支；现第75行起要求单一 job、push、固定 branch/workflow path、attempt、run/job/head SHA 与绑定一致。
3. 方法 receipt 原先缺少完整对应 step 时间窗约束；现第176行起按顺序核五个官方阶段，并把每个 CONTEXT 首 receipt 至 METHOD 末 receipt 绑定 data/raster 的真实步骤。API 秒精度的结束时刻仅加1秒容差；phase/work 截止没有延长。

闭合检查要点：

- 第33行起绑定六个冻结输入、单 source、13个原计划方法/参数、测试文件哈希、三个 APK 的安装分组、两组调用及已审 controller 哈希；固定复制的 marker validator 与执行版逐字节同 SHA。
- 第88行起仅接受带官方 UTC 时间戳的已知 envelope。每一方法（含1–7零 marker）要求 CONTEXT→RECORD*→END→METHOD PASS；原始 JSON UTF-8 长度/哈希、顺序 footer、完整 compact records 加换行哈希均与 producer 序列化一致。严格 JSON 拒绝重复键/非有限数字，冻结 schema 与数量/顺序检查不变。
- 完成必须闭合13方法、两安装组、三 APK 选择/安装哈希及 signer 一致、环境哈希、双 cleanup/ACL 精确相等声明、FINAL 的输入和各方法原日志哈希。整体失败、任何方法失败/缺失或包装不完整不能得到 COMPLETE。
- 失败的官方 run 仍会解析已有前缀，明确保存 failed receipt；部分 records 的 `whole_run_complete` 为 false。合成 method11失败场景保留前10个闭合块，不补造第11方法通过或最终成功。
- 输出只写 CLI 指定的新目录（exist_ok=False），不覆盖旧结果；数值验收恒为 NOT_EVALUATED。

已读 `check-03` 共49个离线合成案例且全部 PASS，源码/validator/checker 哈希与当前文件匹配。包含上述三项回归、真实形状的 method11失败前缀、缺尾/错序/坏 JSON、APK/环境/cleanup/预算矛盾等。这是 parser 准备证据，不是49项 Android 测试，也没有在本次审阅重跑。

证据边界：COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE 仅表示受信任输入下的官方日志闭合。compact JUnit PASS 是 controller 声明，不是原始 instrumentation/JUnit 文件重验；raw_log_sha256 只在 CONTEXT/FINAL 之间连结。APK/native/已安装字节和 ACL 文件未由 parser 重新读取；组后环境一致性也是已绑定 controller 的 PASS 声明。不得据此声称原 ZIP 完整审计、正式性能门槛通过、无诊断开销、独占锁等待或可加总因果成本。
