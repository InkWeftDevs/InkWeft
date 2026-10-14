# Focused search native runner：独立审查

最终审查对象是 `bound-control-final/` 和 `offline-check-final/preflight.json`，不是旧 bound-control/check-02。**本范围未发现尚未关闭的代码阻断；启动身份仍待父侧绑定。** 没有启动 CI、网络、Gradle、设备或修改产品/探针。

## 已关闭的问题

1. **marker 漏检**：旧正则会跳过额外截断、空或数组 payload，使已有12条仍可通过。最终 runner 检测所有 KVM/LIBRARY/INDEX 前缀，对完整原始尾部解析、闭合 schema、计数后才输出。错误现在标 host `INCOMPLETE`，原 JUnit PASS独立保留。独立纯 parser 验证了1个合法块、17个负例，全部符合预期；最终 checker 另覆盖方法级状态。
2. **缺文件仍称完整**：最终 packager 检查固定文件集合；18套 instrumentation/records/XML必须存在，只有8个公共方法要求 forwarding 文件。完整执行声明遇缺文件改成 `EVIDENCE_INCOMPLETE`；summary、packaging、终态日志都列 `missing_files`，`packaging_complete=false`。原方法/JUnit结果不改。已只读核对实际离线 `all18_missing_raw` 回执：缺 `method-01-C1-instrumentation.log`，状态准确且18个原JUnit PASS保留。
3. **JUnit状态码尾缀**：最终代码使用完整行匹配并核对状态码前缀行数，拒绝 `0junk` 等损坏行；独立纯函数负例已验证。

## 白名单、完整性与身份

七类 marker 固定为每方法12条。每条原始JSON≤65536字节，每方法payload总量≤262144字节；对象键闭合、数组定长，拒绝重复键、NaN/Infinity、布尔冒充数值及未知字符串。固定schema本身限制合法嵌套；异常深度解析失败进入INCOMPLETE。全块先校验，再转发原始JSON substring；每条带顺序/字节数/SHA256，footer绑定有序摘要。

上下文绑定run/attempt/workflow、源码提交、source/method plan/probe/源清单、构建APK与实际安装base.apk哈希、签名、安装日志、设备、原日志和records哈希。批准PLAN和其他冻结输入摘要在授权与终态receipt中。独立重算授权6项输入摘要、两个源码清单canonical摘要和固定probe绑定均一致；最终bound只与template存在已批准source-spec hash替换。

`CONTEXT/RECORD/END`只代表单方法导出。后续消费者必须再要求18个方法/9个安装组、最终状态、完整文件集合、双cleanup、设备一致性及冻结身份全部通过，不能凭一个END或METHOD PASS接纳整轮。日志不能单独证明原始ZIP/APK字节。

## 打包、清理与预算

打包仍只取固定UTF-8文本文件，拒绝符号链接和凭据/私钥模式，APK/AAR/数据库/环境dump/全logcat不在集合；不截断。文件加manifest/packaging合计≤20MiB，workflow保留3天。超限仅留下失败回执。文件存在和摘要并不验证每份内容语义；离线“18个完整”是合成夹具，不是新原生成功。

setup30/correctness35/common50/work55为累计绝对截止，所有运行命令同时受阶段和work期限约束；job硬上限60分钟。时钟只固定一次，明确为第一runner步骤时间，并不冒称实际job开始。两次cleanup、package各45+2秒，upload120秒，总尾部261秒，距5分钟留39秒；硬超时若中断清理/上传仍须记未确认。

cleanup与已审版本字节相同：只处理记录的UID/PID/start-time进程组，验证KVM节点身份，用原ACL精确恢复并由trap/always两次核验。KVM权限只在必要时给当前UID rw、`--no-mask`。数据包只允许原3秒自然退出观察，无helper/force-stop补救。

单次执行守卫包括固定分支、非分支创建事件、唯一prep-before和attempt1；动作、安装组、方法都拒绝重试并要求前序PASS。当前proposal零guard是明确未激活状态，父侧仍需绑定真实prep SHA和 `EXECUTION_SHA256SUMS`/active workflow；本审查不伪造run/job身份。

## 离线证据与最终字节

独立重哈希handoff全部19项，最终preflight输入和checker SHA全部匹配。已有最终preflight10组PASS，含13个真实离线packager合成情形；我额外只执行了纯parser的18个marker情形和3个raw JUnit情形。旧原始日志仅作格式夹具，没有样本合池或新性能结论。

- run.py：`3b88cda9aabda1eb261a4d1c860cde11ce73fd4d3b22223bac34676aa21febec`
- package.py：`6662193ae687c097df6d4e748c348d7a83a3e702b8c44ca1ce79b86ee27d449a`
- cleanup.py：`49e4337f483de46179466ae1c0fcf9ef18772e6a9388cce5cb6826a60d634576`
- workflow.proposed.yml：`26fa7448371fdb56b6874091f95a78f9e196f8d726382fd9ef104f8a3bd5dd91`
- final preflight：`b035265e03fd4c4c8339b949179eb5b61b2f297018728a5b980bdade5e9c041a`

完整字段和边界见 [review JSON](native-runner-history-review.json)、[独立parser回执](independent-parser-check.json)、[最终输入/打包回执核验](final-input-verification.json)。本轮screening不关闭完整20k rapid-input门槛或正式验收。
