# 第四次运行成功；原始工件及性能复算待完成

[run38419007471](https://github.com/InkWeftDevs/InkWeft/actions/runs/38419007471) 的官方job115371021306于2026-10-13 15:05:21–15:37:15 UTC运行，31分54秒，attempt1，结论success。执行提交 `30acef2e082eb1cd2d70471737824da32fbe9c24`。本报告依据官方API及job日志，尚未取得本轮完整原ZIP；不能把运行成功写成性能验收通过。

## 已独立读取的官方日志

[原job日志](terminal-evidence/job-decoded.log)以接口返回文本原样保存，SHA256 `5ebca4080e75487fc1f51f84e09865064d1e82cd3f6fbc2217ee29b4a5997512`。[逐项复核](terminal-evidence/job-log-verification.json)确认日志包含44个不同且连续的ordinal，与冻结方法计划的phase/identity/selection逐项相同；全部host PASS、报告JUnit PASS、adb退出码0、error为空。

方法17记录 `AUXILIARY_AM_KILL_CONFIRMED`，其余43项均为 `NATURAL_EXIT`。这表明受限辅助进程收尾路径在本次Android运行中执行并经控制器判定通过；详细观察窗口、唯一清理命令及最终三包pidof仍须用原summary独立核验。第三次的原host FAIL不改写。

两次日志清理回执均为 `emulator=STOPPED_CONFIRMED`、`command_groups=STOPPED_CONFIRMED`、`acl=RESTORED_EXACT`、errors为空。尚未取得本轮before/trap/always ACL原字节，不能声称已在本地逐字节比较。

| 步骤 | 官方步骤用时 | 结果 |
| --- | ---: | --- |
| B三个真实debug APK | 503秒 | success |
| C0三个真实debug APK | 340秒 | success |
| C1三个真实debug APK | 405秒 | success |
| 同一KVM启动 | 27秒 | success |
| C1正确性17项 | 28秒 | success |
| 四组三端公共24项 | 499秒 | success |
| C1两种自然GC及阶段诊断3项 | 45秒 | success |
| 首次/always清理 | 4/3秒 | success |
| 打包/上传 | 1/1秒 | success |

这些是工作流步骤墙钟，不是搜索、保存或绘制的性能样本。九APK的完整签名/依赖/四ABI回执、14组安装和前后环境仍待原工件检查。

## 原始工件与剩余输入

官方仅有一个artifact，元数据见[原API回执](terminal-evidence/artifacts.json)：ID `11828590610`，名称 `pr20-kvm-triple-38419007471`，ZIP `749201` 字节，SHA256 `aab2be49c45bb833c9b8304f05698a3c392d561cdd92bb997f0a9b3a69dc8485`，2026-10-16 15:37:05 UTC到期。运行已结束，没有重跑或新任务。

官方job日志通道成功；此前首轮工件存储下载被拒绝的通道未重试或绕过。本次尚未收到可用的原ZIP/Library文件标识。两次限定文件名搜索未找到本轮上传，不等于证明Library不存在该文件。第三轮已上传并核验的文件不需重传。

取得本轮原ZIP后，按固定摘要核验ZIP、白名单manifest、冻结源码/方法/控制器与APK回执，再独立检查44份原instrumentation、全部进程收尾、14安装组/环境、双cleanup和ACL原字节；最后用既有分析器复算24次公共调用、720个保存原值和18项×三种比较，单列自然GC暴露和默认关闭的阶段诊断。只有四个种子组是比较单位，不能把720次保存或warm重复当独立试验。

旧C0观察保存p50回退、四项不确定结果及持续实际DELETE竞争的比较缺口尚未关闭。当前没有本轮p50/p95/max、decode/rebuild、读取规模或GC时间的新结论；也不能由44项PASS推断无性能回退。草稿PR继续保持Draft，不合并或部署。

绘制报告仍须区分代码能力和覆盖范围：旧B/C局部decode同为13/21/11且rebuild为1，不能声称本轮新增算法减少了这组计数；C0/C1绘制实现无变化。新几何缓存、精确分散脏区过滤和单次循环跨瓦片native复用，不能由仅追加单脏瓦片的结果代替连续变换与分散修改验收。完整瓦片复用仍要求兼容投影及整数采样位移，缩放预览不是完整帧就绪。
