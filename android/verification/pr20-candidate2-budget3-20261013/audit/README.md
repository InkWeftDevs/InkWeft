本目录只准备 run38441089594 / job115447545993 的离线原ZIP验证器，真实ZIP尚未取得或核验。固定 active6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5、prep6e3438e0d03fe473979cc7e4b5f7d909ca148e0c、budget3分支、readiness6c72、C0=ebb、C1=45b、57方法/30+24+3/14组。旧clock2验证器和已冻结控制器均未修改。

后续拿到本run原ZIP及官方本地元数据后调用：

```sh
python3 /workspace/scratch/pr20-candidate2-budget3-artifact-audit/verify_artifact.py \
  --archive /path/to/original.zip \
  --metadata /path/to/artifacts.json \
  --jobs /path/to/terminal-jobs.json \
  --out /workspace/scratch/pr20-triple-run-38441089594/evidence
```

默认只读 `/workspace/InkWeft-native-candidate2-budget3` 内固定Git对象。`--repo`可改为含相同对象的本地库。脚本不fetch、不访问网络、不运行Android/Gradle/ADB、不改变权限、不执行ZIP内容。不会获取已拒绝的存储通道。旧ZIP不是本次已授权预算普通修复的前提，也不用于本run测量结论。

当前本地jobs-initial.json已独立核对实际job17:44:33，较冻结PR服务器anchor17:42:19/1791913339晚134秒；这是初始metadata中的开始时间证据，终态与时长仍待后续官方本地metadata。`--jobs`可省略，但运行审计会明确标记终态证据pending。有效累计截止为38/41/53/55分钟，实际job60硬限；epoch不能后移，实际jobstart不能替代anchor。

先核验原ZIP官方元数据的run/head/branch/size/SHA、平面白名单、路径/重复项/类型/UTF-8/凭据模式/20MiB、manifest全覆盖和逐文件hash，再检查固定readiness/controller/probe/factory原字节。新增budget_override核验明确授权将历史32/38/52/55替换为38/41/53/55，旧PLAN/source/method hash不变、cache false、单worker、cleanup261+39、3源9APK/14组/57方法不变。只在完整性通过后提取，拒绝覆盖目标目录或已有archive-verification回执。

运行审计保留原summary/raw：FAIL、INCOMPLETE、NOT_RUN及raw PASS/host FAIL不会升格。双cleanup声明与独立ACL原字节核验分开；只在原RUNNING→包装INCOMPLETE的明确中断路径允许缺失派生字段pending，已有矛盾仍为issue。57方法PASS不自动代表最后安装组/环境已完成；只有顶层明确声称完成时才要求57PASS、9APK、14组及最终环境齐全。方法17辅助包例外仍唯一；完整观察时间窗及请求前app/data已消失的顺序留给终局原回执人工复核。

返回0仅表示ZIP完整性及记录内部一致性未发现问题，不表示原生方法或性能通过；正常FAIL/未执行仍保留。返回2表示完整性已过但运行记录发现矛盾，提取原文及审计报告仍保留。源身份/ZIP完整性错误则拒绝提取。APK不在允许工件内，所以只检查签名/ELF/身份的原回执一致性，不重新验签缺失的二进制。性能仍使用未改动冻结analyze.py计算。

[synthetic-check.json](synthetic-check.json)记录43个本轮重新执行的离线合成检查：原27个（仅运行身份/时间适配）全部保留，新增16个budget3正/负例。新增项包含旧分支/active拒绝、明确授权/旧PLAN/三源方法绑定、缓存与cleanup预算不变、clock不后移。最后的raw-clock旧预算反例具体只改clock.deadlines_minutes字段；其代码前注释“both ledger”并非同时改summary。所有synthetic-*文件都是测试夹具，不能当本run原始证据。

独立reviewer已只读核对最终diff及43项回执，无阻断；没有替代运行这些检查。精确文件哈希、初始metadata和待输入材料见[preparation.json](preparation.json)。当前无真实ZIP测量验收结论。
