# Focused search screen — final activation review

结论：PASS（仅静态激活审查），未发现阻断。未修改执行clone、未触网、未push、未运行构建/Android/CI或权限操作。本记录不表示已启动或原生通过。

- 隔离branch：`codex/kvm-pr20-search-screen-20261014`，所读工作树clean。
- prep：`5cd3eb9b95a1cf5816ed345c3e71b2d5b408a01e`。
- active：`02aaa0e3df3d77c6a726931cee060e2daff4bbf2`，唯一parent精确为prep，非merge。
- 两提交只差 `.github/workflows/pr20-search-screen.yml` 与 `ci/pr20-search-screen/EXECUTION_SHA256SUMS`。workflow仅将唯一40位全零before替为prep；manifest仅更新该workflow一行SHA。active Android树和全部核心controller与prep相同。
- prep workflow逐字等于已审proposed。active workflow保留push唯一隔离branch/path、created=false、attempt1、exact before=prep，单job、标准ubuntu-24.04、job60分钟、contents:read、无token/clock API新增。初次创建branch与全零guard不能执行此job；激活须以远端仍为prep为前提普通push，不重试、不强推。
- 累计setup30/correctness35/common50/work55、首步本地clock及全部既有命令/收尾上限保持。50–55无额外工作；local anchor不是actual jobstart。两data APK、10 correctness+8公共、9组、原探针/阈值及480保存保持。
- 16项执行checksum均以当前实际文件重新计算并匹配。run/package/cleanup/auth/proposed与最终bound-control-final逐字一致；final preflight及方法边界参见前述审阅。
- 其他已有workflow的push分支仅main或旧六批专用分支，不匹配本隔离branch。本审查未新建PR或触发其pull_request/dispatch事件。

独立完整审阅：`review-integration.md` SHA `37c8a9ab3d2c9a069040dca57b820d2331be04563e908b81fe597c9a35c5feae`。最终preflight SHA `b035265e03fd4c4c8339b949179eb5b61b2f297018728a5b980bdade5e9c041a`。正式验收/旧B回退/旧20k门槛仍不由此screen关闭；实际运行结果须联合raw JUnit、host、全部安装组、FINAL与双cleanup/证据完整性解读。

## 最终绑定

- active workflow SHA：`27a99bf8733eca43611bae3e263330d78244c919aa9208e63c0b4ecd9fc3faef`
- active EXECUTION_SHA256SUMS 文件SHA：`46c180878dec28ffdf3251ed37973ed517096f2b58ebafa72855acc581ad0b66`

以下16项均已独立核对MATCH：

```text
27a99bf8733eca43611bae3e263330d78244c919aa9208e63c0b4ecd9fc3faef  .github/workflows/pr20-search-screen.yml
90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c  android/verification/pr20-kvm-paired-20261013/evidence/probe-KvmLibraryPairProbe.kt
e0874e09a471c8157d9d3e0c72f05c9cf42dbd5ca3721679f63be94e5c5fba5b  android/verification/pr20-search-screen-20261014/C0-source-manifest.json
befb5ccec0a8863af5a1709c523470d6d5c7e9e827f2b5e804b0468f98232533  android/verification/pr20-search-screen-20261014/C1-source-manifest.json
64921517343ba2dfddf3cba3f319b15b61757501744c87f43d4217bd37d4a08a  android/verification/pr20-search-screen-20261014/PLAN.md
3f3bc2a6bc5b0a663a4e03a54be65c2193a8ed868ba31014e344a95595d6aa4d  android/verification/pr20-search-screen-20261014/marker-contract.json
7dd485f678aa16d89fc0a1ef3e69421a376b4054cc31918f2b393c8212033284  android/verification/pr20-search-screen-20261014/method-plan.json
1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba  android/verification/pr20-search-screen-20261014/original-protocol.json
7e42199cd01da917a0a141982a963fd6db51f6411c7516c85ea27406a0744ce5  android/verification/pr20-search-screen-20261014/prepare_sources.py
e57296ec73cea28ba22c5ee5871d77a0e5b22f0d0503c2bd453b987653ed6499  android/verification/pr20-search-screen-20261014/source-spec.json
9a943aacd0cd9d17af30fd82d6c1fb9f48aed7a37d704676da22ddb7673615e1  ci/pr20-search-screen/README.md
acdcce3b571a891ddbee3189d2ae241f6769bb2691403c201c7c1243b8cc2b3a  ci/pr20-search-screen/authorization.json
49e4337f483de46179466ae1c0fcf9ef18772e6a9388cce5cb6826a60d634576  ci/pr20-search-screen/cleanup.py
6662193ae687c097df6d4e748c348d7a83a3e702b8c44ca1ce79b86ee27d449a  ci/pr20-search-screen/package.py
3b88cda9aabda1eb261a4d1c860cde11ce73fd4d3b22223bac34676aa21febec  ci/pr20-search-screen/run.py
26fa7448371fdb56b6874091f95a78f9e196f8d726382fd9ef104f8a3bd5dd91  ci/pr20-search-screen/workflow.proposed.yml
```
