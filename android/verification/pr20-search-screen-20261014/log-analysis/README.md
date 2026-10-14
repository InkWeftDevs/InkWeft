# 两源码数据层日志筛查：离线分析器

仅分析授权渠道取得的**已解码官方 job 文本日志**。不访问网络、不执行CI/Android、不修改输入、生产或共同probe。仅C0=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`与C1=`a9ccf2cc727a7957e8fb00115f0060d80b287de1`，四个原seed按冻结平衡顺序比较；不合池旧B或其他run。

```sh
python3 analyze_logs.py \
  --job-log /path/to/official-decoded-job.log \
  --frozen /path/to/final-clone/android/verification/pr20-search-screen-20261014 \
  --binding /path/to/root-created-binding.json \
  --control-root /path/to/final-clone \
  --run-id ACTUAL_RUN_ID --workflow-sha ACTUAL_40_HEX_HEAD \
  --out /path/to/new-empty-output
```

多份step日志按原顺序一起传给`--job-log`。可保留官方ISO时间戳前缀；不要同时传aggregate及重复step文件。解析器拒绝重复，不能通过删样或重排修复失败日志。输出目录必须不存在。

`binding.json`由根根据官方run/job metadata与最终执行clone生成，必需键如下。run/job必须completed/success、attempt必须1；源码规格及六个输入hash必须与冻结文件完全匹配；control hash逐文件读取校验。相对路径从仓库根起算，禁止`..`。

```json
{
  "scope": "SCREENING_ONLY",
  "run_id": "ACTUAL_RUN_ID",
  "job_id": "ACTUAL_JOB_ID",
  "run_attempt": "1",
  "workflow_sha": "ACTUAL_40_HEX_HEAD",
  "run_status": "completed",
  "run_conclusion": "success",
  "job_status": "completed",
  "job_conclusion": "success",
  "input_hashes": {
    "approved_plan_sha256": "SHA256_OF_PLAN.md",
    "source_spec_sha256": "SHA256_OF_source-spec.json",
    "method_plan_sha256": "SHA256_OF_method-plan.json",
    "original_protocol_sha256": "SHA256_OF_original-protocol.json",
    "marker_contract_sha256": "SHA256_OF_marker-contract.json",
    "prepare_sources_sha256": "SHA256_OF_prepare_sources.py"
  },
  "control_hashes": {
    "ci/pr20-search-screen/run.py": "ACTUAL_SHA256",
    "ci/pr20-search-screen/package.py": "ACTUAL_SHA256",
    "ci/pr20-search-screen/cleanup.py": "ACTUAL_SHA256",
    ".github/workflows/pr20-search-screen.yml": "ACTUAL_SHA256"
  }
}
```

以上只是字段模板，不是执行证据。具体控制器目录以最终clone为准。解析器不能认证metadata获取渠道；根负责官方来源绑定，输出保存该binding和日志文件的hash。

完整状态必须同时满足：

- 一个CLOCK、18个按计划顺序且自然退出的METHOD PASS、8个完整CONTEXT→12 RECORD→END块、一个末尾FINAL，未知/重复/截断回执拒绝。
- Payload固定白名单和闭合schema（未知字段、重复JSON键、非有限数、布尔冒充数字、超深/超长拒绝），原JSON UTF-8 bytes/hash、sequence、footer有序摘要及重建records文件hash一致。原JSON字符串保留。
- CONTEXT的源码、canonical manifest、plan/probe、APK及实际安装hash、同版本重复安装APK/签名、环境、METHOD原日志摘要与FINAL相符。这里是紧凑回执链，不是假称重新验过APK字节。
- FINAL声明执行完整、9组安装/after-environment检查完成、packaging完整且`missing_files=[]`、双cleanup无错误且已停止、ACL前后摘要相等、设备前后摘要相等。不能因METHOD先PASS就忽略后续失败。
- 官方run/job成功且控制器字节与binding相符。CLOCK只检查固定clock规则，不冒充独立证明所有阶段截止时刻。

通过后状态是`COMPLETE_SCOPED_LOG_SCREENING`，**不代表指标全部通过或正式验收**。输出`analysis.json`及CSV，完整保留480条原序保存、16个nearest-rank分布、36个四区组配对值、9项原library指标分类及各warm五原值/四组描述性比较。`quantiles`与`classify`在`legacy_math.py`逐字提取旧冻结analyze.py，来源hash见`legacy-math-origin.json`；旧文件未修改。warm不增加阈值；legacy probe的p50字段不替代nearest-rank索引14/28/29。

缺失或错误日志返回`INCOMPLETE`、退出码1，不输出成功数值表。`records_sha256`从全部12个payload实际重建验证；`raw_log_sha256`只能核对CONTEXT与FINAL中的摘要绑定，因为没有转发完整instrumentation原文。原ZIP、完整原JUnit/包清理/ACL文件、APK/native/签名二进制独立审计不在本工具范围；正式原工件仍待单独核验。

本地验证：`python3 test_analyzer.py`。使用历史run38498392929的45b/d66原payload，仅用伪造的未来协议envelope验证解析；所有36配对、9分类、480保存、16分布及warm与旧冻结结果一致。它们**不是a9cc原生数据**。结果文件明确标记`SYNTHETIC_VALIDATION_ONLY_NOT_NATIVE_PERFORMANCE`，不生成可交付的新原生测量日志。故障注入涵盖missing/duplicate/hash/order/status/schema/cleanup/packaging/官方metadata错误；不启动设备或CI。
