# PR20 三端原生复验：预算修订后的单次控制器

依据用户既有继续授权及父侧2026-10-13指令，在核验用户上传的首次工件后准备下一次有界复验。首次run38396134768已完成，C1在26分钟准备截止以exit124终止；两份原清理回执均确认所记录进程组停止、模拟器未启动、ACL未触及、错误为空。原始ZIP/manifest哈希已与官方元数据核对，没有重试Forbidden地址。首次冻结方案与失败记录保持不变。

本次仅调整累计截止为 **32/38/52/55/60分钟**；固定B/C0/C1源码、两份公共探针、9个真实debug APK、44方法（17+24+3）、14安装组、样本、判据、测试内部时限和单标准runner全部不变。仍串行单worker、不用任务构建缓存，不共享项目输出。实际预算依据和明确的未知项见`budget-revision.json`；授权关联与该修订的SHA256见`authorization.json`。

完整build action包括APK/签名/ELF/依赖核验，不能再加旧setup余数。实测首次job到C1开始1209.486秒；假设C1≈C0 501.248秒、余下启动/记录30–90秒，setup约29–30分钟。该估计不是C1测量。正确性和新诊断完整耗时未知；各阶段提前完成即推进，超预算仍停止并保留INCOMPLETE，不减端点/样本/方法，不中途改阈值或自动补跑。

控制器小修：workflow导出、run.py常量、持久化REPORT及预算授权必须相等。package先保存`summary-before-packaging.json`，再把actions/phases/install_groups/methods的遗留RUNNING/INSTALLED标INCOMPLETE；已有PASS/FAIL/NOT_RUN和JUnit字段不变，未进入下一action即预算中止的暂态也明确终态。首轮原始summary没有被更改。

cleanup.py保持4f8版本逐字节不变。trap、成功清理、always清理和打包均外包45秒timeout（2秒强制结束宽限）；上传step2分钟。正常收尾名义上限261秒，55–60分钟间留39秒调度余量。时间限制不是恢复证明：必须同时核对trap/always原回执以及KVM ACL原字节；清理失败/缺失仍不通过，不改变现有最小UID-only权限范围。

仅新分支`codex/kvm-pr20-triple-20261013-budget2`。本文件所在准备提交不含活动workflow；`workflow.proposed.yml`全零before guard不可触发。先提交/推送准备，核对无其他运行后，再将准确准备SHA填入活动workflow单独提交/推送。仅该branch/path push、created=false、固定before、attempt=1；无dispatch/schedule/PR触发，单标准ubuntu-24.04 job60分钟，工件只含白名单文本≤20MiB/3天。

`preflight.json`仅记录本次实际静态与合成控制器检查；不是Android执行。真实运行开始后以GitHub run链接、原始instrumentation、APK身份回执、环境和双清理证据另行汇报；原候选性能回退及覆盖缺口不会因包装器检查变绿。
