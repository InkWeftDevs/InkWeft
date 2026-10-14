# 私有本地任务缓存：实现与离线实测回执

状态：12/12 PASS，仅 Gradle9.4.1 合成任务夹具。Android缓存命中、APK等价性和Android构建提速尚未验证；当前原生run38498392929、冻结执行clone/workflow/探针未修改。本次无网络、Gradle插件下载、权限变动或新GitHub任务。

新增主仓文件：

- `ci/pr20_build_cache.init.gradle`，SHA256 `d3e02bb3d9d237530867e04eac9892d526093fb51fdf85fcd2928bdab715e830`。
- `ci/test_pr20_build_cache.py`，SHA256 `5523ae7e35ced537d2c22b15ee7046cbd9b641c203d4418ac6a501fe2c72f35b`。

完整结果与原命令/每次耗时/输出hash：`/workspace/scratch/pr20-build-cache/fixture-20261014-v2/results.json`，SHA256 `f286cf0119e48b5e7a1616afc65d9960b098e367b9852086b4fad7a2cfa8a92e`。逐case日志、实际独立项目、identity JSON与输出都留在同目录，不上传缓存或二进制。控制台为 `/workspace/scratch/pr20-turnaround-20261014/fixture-v2-console.log`。此前v1在解析JDK空属性行时失败，尚未调用Gradle；原错误日志及v1目录保留，修正解析后使用全新v2目录，未覆盖历史。

实际命令：

```sh
python3 ci/test_pr20_build_cache.py \
  --gradle /workspace/.setup/tools/gradle-9.4.1/bin/gradle \
  --java-home /workspace/.setup/tools/jdk-17.0.16+8 \
  --output /workspace/scratch/pr20-build-cache/fixture-20261014-v2
```

测试始终传 `--offline --no-daemon --max-workers=1 --no-configuration-cache`，独立GRADLE_USER_HOME，256MiB堆，不访问Android项目或已有Gradle项目缓存。实际JDK `17.0.16` / `Eclipse Adoptium`；Gradle9.4.1来自本地既存发行目录。输出时间是小任务的整个Gradle启动+配置+执行时间，不能解释为任务计算时长或Android节省值。

| 用例 | 实际结果 | exit | 秒 |
|---|---|---:|---:|
| 01-cold | EXECUTED | 0 | 14.161 |
| 02-independent-checkout-hit | FROM-CACHE | 0 | 10.360 |
| 03-source-change | EXECUTED | 0 | 15.671 |
| 04-dependency-content-change | EXECUTED | 0 | 16.784 |
| 05-task-tool-input-change | EXECUTED | 0 | 12.727 |
| 06-toolchain-namespace-change | EXECUTED | 0 | 9.067 |
| 07-remote-disabled-hit | FROM-CACHE | 0 | 9.341 |
| 08-identity-hash-mismatch | REJECTED | 1 | 9.869 |
| 09-outside-directory | REJECTED | 1 | 9.211 |
| 10-missing-directory | REJECTED | 1 | 12.342 |
| 11-wrong-gradle-identity | REJECTED | 1 | 13.075 |
| 12-cache-not-enabled | REJECTED | 1 | 14.444 |

首轮与第二独立目录的输出SHA均为 `dca10bf876bc0e39b0036ac08df0a69eaa9d3cefd2ce19e9d0f90b3958a773bb`。第三/四/五项分别改变实际文件内容、文件依赖内容和已声明任务工具输入，任务执行而非命中。第六项改变声明工具链fingerprint，选择新cache namespace重建；实际Gradle/JDK二进制没有更换。第七项故意在fixture settings预配置remote，init将其enabled/push关掉并从本地命中，未连接该端点。第十一项声明错误Gradle版本被拒绝，而不是伪称在另一个实际Gradle版本上验证成功。

## 最小使用合同

下一独立driver须先建立新job拥有的cache目录范围并核验冻结输入，然后显式传三个变量（不改HOME）：

- `INKWEFT_TASK_CACHE_DIR`：RUNNER_TEMP严格子目录，或 `/workspace/scratch/pr20-build-cache` 严格子目录。脚本使用canonical路径，不能指向允许根目录自身或借symlink跳出允许根。
- `INKWEFT_TASK_CACHE_IDENTITY_FILE`：已冻结身份JSON，键为schema=1、gradle_version、java_version、java_vendor，以及source_spec_sha256、probe_manifest_sha256、toolchain_sha256、dependency_manifest_sha256。
- `INKWEFT_TASK_CACHE_IDENTITY_SHA256`：上述JSON原字节SHA256，须与传入文件一致。

Gradle命令仅用 `--build-cache --init-script <ci/pr20_build_cache.init.gradle>` 替换原 `--no-build-cache`，其它任务、worker、daemon、阶段时限和验证逻辑不变。base目录由driver负责每job新建/拥有，禁止从其它job导入task cache、禁止上传；此init限制可写位置但不负责证明传入目录的整个生命周期属于当前job。相同完整计划身份下B/C0/C1可共享cache；不同身份JSON哈希使用不同子目录。source变化在允许计划内由Gradle原生任务input判定失配，不给每个task加入整端source SHA导致所有合法跨端命中失效。

helper只核验身份文件摘要/字段、实际Gradle/JVM版本并选择cache目录；**它不代替driver核验source/probe/依赖清单的实际内容**。toolchain_sha256应指向完整可信工具链清单，fixture仅用本地launcher作为小型测试输入，不是完整Android工具链凭据。正式driver仍必须把完整source-spec（含B的补丁和共同探针）、实际依赖内容、工具链/SDK/build-tools、cache init和控制身份绑定起来；无法证明时使用空cache/执行重建或停止，不能以任意填入的64位字符串放行。

cache命中不免除：三份独立源树/九APK，前后源清单，内嵌source/build commit，三APK签名与哈希，完整native/依赖回执，安装前hash，14安装组，以及当前58=31/24/3方法、四seeds、原样本/阈值、所有进程/ACL清理。APK不会直接跨identity复制。现有完整构建driver尚未接入此helper；所有历史task_build_cache=false证据原样保留，下一次计划应显式声明仅cache合同变更并通过独立review/preflight。

采用前还应进行固定Android源的受限build-only兼容性/输出检查；本次仅验证cache机制及拒绝边界，不能据此宣布AGP/KSP所有任务可安全搬移或正式三端构建已经加速。增量screen与正式比较仍分别记录，不能复用缓存JUnit报告冒充本轮测试执行。
