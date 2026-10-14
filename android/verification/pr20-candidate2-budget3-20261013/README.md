# 第二候选预算3：执行成功，原始性能复算待工件

[run38441089594](https://github.com/InkWeftDevs/InkWeft/actions/runs/38441089594) 已 success，job115447545993，attempt1，实际时间为 2026-10-13 17:44:33–18:25:31 UTC，共 **2458 秒（40分58秒）**。官方日志逐项报告 **57/57 次执行 host PASS、JUnit PASS、adb exit0**（35个不同 class#method）；这证明本轮已执行，尚不等于独立核验完整 instrumentation 原文或通过性能容差。原始 ZIP 尚未取得，本报告不更新上一轮保存 p50 回退、绘制不确定及 GC 暴露不足的结论。

准备提交6e3438e0d03fe473979cc7e4b5f7d909ca148e0c，执行提交6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5，隔离分支codex/kvm-pr20-candidate2-budget3-20261013。被测 B 为受控a128bb07，C0=ebb48481e7e995288c1844d43011398c513b4f4e，C1=45b4861ae23ac19eda3246fcd34cf3d2132dc1ee；不能套用第四次运行 C0=7949011 的身份。业务源码、原公共探针、四组样本、阈值、57 方法和 method17 唯一辅助包退出例外均保持冻结。

## 官方记录能确认的执行结果

| 阶段 | API 步骤秒数 | 结论 |
| --- | ---: | --- |
| 源码与 SDK 准备 | 33 | success |
| B 三个真实 APK 构建及后置检查 | 625 | success |
| C0 三个真实 APK 构建及后置检查 | 542 | success |
| C1 三个真实 APK 构建及后置检查 | 473 | success |
| KVM 模拟器准备 | 33 | success |
| C1 30 项正确性 | 43 | success |
| 四组三端 24 项公共比较 | 612 | success |
| C1 三项独立诊断 | 54 | success |
| 成功路径清理／always 再核 | 19／8 | success |

以上为秒级 API 包装步骤墙钟，包含步骤内检查，不是纯 Gradle、某个测试或性能指标耗时。57 份[日志状态回执](metadata/method-status-from-joblog.json)的序号、阶段、身份和方法选择逐项匹配冻结计划。仅方法17报告 AUXILIARY_AM_KILL_CONFIRMED；其余56项报告 NATURAL_EXIT。辅助包 PID、完整观察窗口、请求前主包与数据测试包退出顺序，仍须检查 ZIP 中的原始 summary，不能从简短状态行补造。

[官方原日志](job-decoded.log)保留 UTF-8 BOM 与全部原字节：63,523 bytes，SHA-256 6717e63192b2dd43b6ca66f378f659efcdcd7b291354885e89b92466f6198b97。18:25:17.4957263Z 和18:25:25.0344407Z 两份实际清理输出均为：emulator=STOPPED_CONFIRMED、command_groups=STOPPED_CONFIRMED、acl=RESTORED_EXACT、errors=[]。这是日志回执，尚未独立比对 ZIP 中 before/trap/always ACL 原字节。[结构化核验](job-log-verification.json)保存精确边界及限制，[独立终态审阅](metadata/terminal-review.md)另列。

## 时钟与预算

基于[上一轮 setup 超时](../pr20-candidate2-clock2-20261013/README.md)的官方证据继续获授权的预算修复，无需重复索取旧 ZIP。累计阶段截止改为38/41/53/55分钟；实际 job60分钟、单标准 runner、单worker、--no-build-cache、三源九 APK、原样本与清理门禁不变。历史 PLAN 保留原字节，新的授权和预算文档明确覆盖阶段数字。[启动前预算依据](budget-rationale.md)保留全部假设与未知量。

不可后移起点为必要 PR 更新成功的服务器回执2026-10-13T17:42:19Z（epoch1791913339），实际 job 晚134秒。固定 UTC 截止为 setup18:20:19、正确性18:23:19、公共18:35:19、工作18:37:19；后续状态更新未重设起点。准备于18:13:09完成，距起点1850秒，也早于假设沿用旧32分钟截止70秒。因此本轮构建完成不能单独归因于扩大预算。旧 C1 的434秒是被截断窗口，不能与本轮完整473秒计算构建加速比。

准备提交没有运行，激活前保存的全仓 queued/in_progress 均为0。激活仅将 proposed workflow 中唯一 before 占位替换为准备 SHA，控制器与 Android 树未变；本分支只有本次 attempt1，未 dispatch、rerun 或并行启动第二 runner。精确时钟及独立绑定检查见 control/、metadata/。

## 唯一待取得的原始材料

[本轮原始工件](https://github.com/InkWeftDevs/InkWeft/actions/runs/38441089594/artifacts/11837544923)：pr20-kvm-triple-38441089594.zip，artifact11837544923，769,104 bytes，官方 SHA-256 **e3218dda22503f2bc085cf2e96284ce0bd24f5b05c40dcee772c13b2ec7318aa**，到期 **2026-10-16 18:25:25 UTC**。

打包实际输出报告289载荷、3,555,071 bytes、omitted0、truncated0；上传步骤报告291文件。上述是日志与官方元数据，尚未独立核验 ZIP/manifest。没有重试或绕过此前被拒绝的工件存储下载通道。

官方 job log 中没有 instrumentation 原始指标，无法据此恢复720个保存原值、像素／decode／rebuild计数、GC暴露区间，或审阅九 APK／依赖／14组安装环境及 ACL 原回执。这份新 ZIP 是独立复算不可替代的输入；旧失败 ZIP、第四轮 ZIP 均无需再次提供。获取现有结果不需要再运行一次基准。

固定本轮身份的[离线 ZIP 验证器](audit/README.md)已通过43项合成检查（保留原27项、新增16项预算绑定），独立只读审阅无阻断；这些检查不算原生性能证据。收到原 ZIP 后，先执行验证器，再使用[未修改的57方法分析脚本](../pr20-candidate2-readiness-20261013/analyze.py)复算全部原样本。历史准备回执中的“终态待输入”描述准备当时；本目录现在已保存官方终态元数据。

当前交付仍为 Draft PR20。四项业务优化和 Docker 整合保持，未改 Docker 目录、用户源分支或 main，未合并或部署。本次仅使用云 runner 和合成数据；物理设备性能仍未验证。
