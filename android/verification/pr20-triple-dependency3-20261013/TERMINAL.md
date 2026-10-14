# 第三次运行终态：方法结束后的进程检查失败

[run38411448143](https://github.com/InkWeftDevs/InkWeft/actions/runs/38411448143)，job115344550391，2026-10-13 14:13:05–14:38:49 UTC，25分44秒，attempt1、failure。执行HEAD71b84885bd4ecb3fe8a4406788ffa14abc189497。不是准备预算超时。

官方 GitHub Actions job 日志通过 GitHub connector 的 decoded-job-log 动作成功读取；这是独立的 job 日志，未请求或绕行此前受拒绝的 artifact 下载通道。原返回文本保存在terminal-evidence/job-decoded.log，字节摘要见terminal-verification.json；不等同于工件ZIP完整性核验。

三个真实APK构建步骤均success：B590秒、C0381秒、C1453秒；每步包含控制器中的源码身份、签名、四ABI MuPDF及依赖核验。KVM准备31秒success。九APK及其逐项原始回执仍待工件复核，未获取APK本体，不是本地重新验签。官方POM检查包含于已成功setup阶段，详细GET回执待工件。

正确性步骤14:38:17–14:38:44失败。job日志依次记录方法1–16 PASS，覆盖3项分页、2项搜索、2项索引新鲜性与9项GC，包括同Room/独立Room实际DELETE后提交前的作者竞争正确性。方法17 `NotebookShelfUiTest#pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges`记录host FAIL，顶层错误为 `AssertionError: Owned package process cleanup unconfirmed`。

控制器在解析原JUnit之后、检查JUnit PASS之前立即对三个包执行一次pidof；因此这个错误本身不能证明UI断言失败，也不能证明原JUnit通过。可能的进程退出时序或残留原因尚待该方法的原instrumentation日志、junit_status及processes_after记录，不能先放宽检查或强停进程制造PASS。24项公共比较及3项诊断均skipped，本次没有新的性能数值；旧回退和覆盖缺口未关闭。

job日志中trap与always两份实际输出都确认emulator/command_groups为STOPPED_CONFIRMED，acl为RESTORED_EXACT，errors为空。官方步骤也显示always、打包、上传success；工件中的原清理JSON及ACL字节仍需独立核验。

原始工件 `pr20-kvm-triple-38411448143`：ID11824680369，439244字节，SHA256 `d3d3d31af26afd83eb8182cd0562b0f14277e8de03ea5cf5c566b9d8843ff2c5`，到期2026-10-16 14:38:46 UTC。沿用用户上传原ZIP后Library读取路径；尚未发起第四次运行。

运行结束后云端exec工具出现过一次transport disconnected；下一次只读检查恢复成功。GitHub读取一直可用，未因控制环境通知重复启动。待取得原ZIP后核对清单、原JUnit、进程记录与清理，再做针对性修复。
