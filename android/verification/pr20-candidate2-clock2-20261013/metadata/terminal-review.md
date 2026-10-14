# Clock2 终态独立复核

仅本地读取已保存的官方job日志与终态元数据；未请求工件、重试下载或启动新任务。结论：run `38433834125` / job `115422592925` 以failure结束，证据支持C1阶段触及固定setup外层超时；本轮没有新的Android测试或性能结果。

身份仍为active `9907a8f96fe0d20061f0a7deba6a69d4416ced50`，隔离分支 `codex/kvm-pr20-candidate2-clock2-20261013`，push、attempt 1，唯一job。job实际时间为2026-10-13 **16:50:55–17:21:15 UTC，共1820秒（30分20秒）**；未将run创建时间16:50:52当作job启动时间。

PR服务器锚点16:49:10（epoch1791910150）比真实job启动早105秒。官方日志导出的setup截止为epoch1791912070，即17:21:10；correctness/common/work分别为17:27:10、17:41:10、17:44:10。冻结截止比按job启动加同样分钟数早105秒，没有后移锚点或扩大预算，且job在60分钟硬上限前终止。

| 阶段 | 官方步骤结论 | 起止UTC | 步骤耗时 |
| --- | --- | --- | --- |
| 源码与SDK setup | success | 16:51:04–16:51:53 | 49秒 |
| B构建及控制器后置检查 | success | 16:51:53–17:04:06 | 733秒 |
| C0构建及控制器后置检查 | success | 17:04:06–17:13:55 | 589秒 |
| C1构建阶段 | failure | 17:13:55–17:21:09 | 434秒 |

`job-decoded.log:315–333` 显示外层按`setup cutoff - 当前时间 - 2秒`设置timeout，C1随后在17:21:09.316报告exit 124，与固定截止及预留一致。上述B/C0耗时是整个工作流步骤耗时，不是单独Gradle任务耗时。Gradle输出被控制器写入工件内分离日志，当前没有该原文，因此不能据此断言C1没有潜在编译问题，也不能独立重验各APK哈希、签名、native ELF或依赖回执。

KVM/ACL步骤及30正确性、24公共比较、3诊断步骤均为skipped；按冻结57调用计划，本轮57项未执行。工件内summary尚未读到，不能声称已逐项核对其NOT_RUN ledger或归一化状态。

两次清理的实际JSON输出均为`emulator=NOT_STARTED`、`command_groups=STOPPED_CONFIRMED`、`acl=NOT_TOUCHED`、`errors=[]`：

- C1失败trap：17:21:09.310，日志第332行。
- always清理：17:21:09.746，日志第355行。

这是两条实际输出，未把打印的shell脚本/echo回显计入。成功路径cleanup步骤本身为skipped；失败trap和always实际运行。可描述为日志记录拥有的命令组已停止，模拟器未启动、ACL未触碰；不能描述为执行过ACL修改并恢复，也未核验尚未取得的独立cleanup JSON文件。

打包和上传步骤success。日志报告34个payload、1268679字节、omitted=0、truncated=0；上传动作报告共36文件（包含manifest和packaging元数据）。官方artifact `11834012592` 的记录为345179字节，ZIP SHA256 `ba2420bb9217f6ed2185bea66dc46451589f0a097e0119ebf86bb3f443577ebb`，与上传日志一致。**原ZIP仍未取得**，这些是服务端元数据和日志声明，尚不能独立复算ZIP摘要、36文件逐项manifest或工件内容。既有离线验证器没有在本run真实工件上执行。

本次读取输入SHA256：

- `jobs-terminal.json`：`81270a051be1524012443d3f09b69194bf4cbf03f624cb5b38999b61c5f95be2`
- `run-terminal.json`：`1bf6092327fde511621fdff7ef4f77321aee2940bdb25aaa61978ba44c19c861`
- `artifacts.json`：`27154c00373a2000417f4bc8a9fd26ccdf43e7aceb38ecb814bad789daeca147`
- `job-decoded.log`：`ea473725e383204daae37ac4ad4ae44c6ceeaa843d2914e857dfe8ffe80b87ad`
