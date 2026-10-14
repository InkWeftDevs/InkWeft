本目录只准备 run38433834125 的离线原ZIP验证器；真实ZIP尚未取得或核验。固定 job115422592925、active9907a8、prep14b4f48、readiness6c72、C0=ebb、C1=45b、57方法/30+24+3/14安装组，详见 preparation.json。

后续由父侧拿到用户上传的原ZIP及官方本地元数据后调用：

```sh
python3 /workspace/scratch/pr20-candidate2-clock2-artifact-audit/verify_artifact.py \
  --archive /path/to/original.zip \
  --metadata /path/to/artifacts.json \
  --jobs /path/to/terminal-jobs.json \
  --out /workspace/scratch/pr20-triple-run-38433834125/evidence
```

默认只从 `/workspace/InkWeft-native-candidate2-clock2` 的固定本地 Git 对象读取期望文件；`--repo` 可指向含相同对象的另一只读本地库。没有 fetch、网络调用、权限变更、Android、Gradle或ADB操作，不执行ZIP内文件。`--jobs` 可省略，但实际job开始/终态核验会明确标记待提供；父侧报告的105秒只作为准备背景，不当作独立实证。

输出目录和 `<out>-archive-verification.json` 必须均不存在。先严格核验官方元数据的run/head/ZIP大小和SHA，ZIP重复项/路径/类型/UTF-8/凭据模式/20MiB/明确白名单，再核验manifest全覆盖和逐文件hash、冻结readiness/controller/probe/factory字节。之后提取原始证据，不改写summary或日志。源身份和流程记录审计另外报告57精确方法、原raw与records/JUnit一致性、原始状态归一化、固定截止、三源9APK回执、同一设备设置和双cleanup/ACL精确恢复。

返回0表示完整性通过且运行记录没有检测到内部矛盾，不表示57项都PASS或性能通过。正常的测试FAIL/未执行会保留并计数。返回2表示完整性通过但记录审计发现问题，原始提取和审计报告仍保留；完整性/源身份不符或输出已存在则拒绝提取。真实APK不允许上传，所以此脚本只检查APK签名/ELF/身份的原回执一致性，不能重新验签缺失的APK。性能结果应另用未改动的冻结analyze.py计算。

`synthetic-check.json` 是27个新执行的合成检查，覆盖完整本地CLI、不可覆盖、ZIP hash/run/source、路径/重复项/白名单/凭据/容量拒绝、clock不匹配、NOT_RUN不升级、raw PASS与host FAIL分离、cleanup失败不升级和合成105秒时钟绑定。所有synthetic-*目录和报告都是测试夹具，不得发布为本run证据。第一次检查摘要也单独保存为synthetic-check-v1.json。

独立审查补丁另增加10个case：虚假完成状态、合法outer-kill部分raw及已有digest矛盾、AUX/NATURAL与辅助清理回执的双向关系、ACL声明与原字节/变更状态关系。原始17case版本及脚本保存在review-before-fixes。明确原RUNNING→包装INCOMPLETE时，缺失的派生字段/records/XML列pending，保留raw重新解析的观察值而不提升已记录JUnit或host状态。cleanup.receipt_claims_verified表示package原声明规则；cleanup.both_verified还要求独立ACL恢复字节与已启动模拟器状态一致。辅助清理的逐次观察时间窗、请求前app/data确已消失等仍须在终局审阅原observations；此脚本未把该部分概括成独立完整时序证明。

最后一条合成检查仅执行当前audit_execution的完成审计AST分支：57方法已PASS，但最后安装组仍INSTALLED→INCOMPLETE、原MEASURING时，记录后置检查pending，不强推整体完成或误报内部矛盾。只有原/包装顶层确实声称EXECUTION_COMPLETE_ANALYSIS_PENDING时，才要求57PASS、9APK、14组和最终环境齐全；未声称完成的合法末尾失败原样保留。四项补丁阶段的26case快照另存review-four-fixes，仍保留最初17case版本。
