# Focused search screen: independent static review

结论：下列 bound-control-final 冻结快照未发现新增执行阻断；真实prep及仅workflow激活的差异仍待独立核对。仅静态读取、Git对象比较和少量哈希；未运行被审脚本、构建、ADB、设备、网络、CI、权限操作，未修改被审文件。本记录不表示原生通过或扩大授权。

## 身份与范围

真实计划提交 `03a335f3d92e3682080f3ce7e4c661434956c075` 的PLAN/source-spec/method-plan/marker-contract/original-protocol/prepare_sources/两份manifest，已与scratch冻结输入逐字核对一致。四份bound-control-final文件与template仅source-spec占位符替换，逐字一致。

- C0=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`；canonical manifest=`b5fa8f491f26db3dd4a5ab6870991b0e56c2c10b0f5c944bb3b8afae90ded66e`。
- C1=`a9ccf2cc727a7957e8fb00115f0060d80b287de1`；canonical manifest=`d04176437afb992804f3925162248bae43b30d397df64bcf36ac93114c6f4100`。
- 两端实际重建目录的共同 `KvmLibraryPairProbe.kt` SHA均为 `90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c`。没有production override，没有原B，没有跨run池化。本审查没有重新材料化整树。
- 材料化差异7路径包含筛查/缓存工具，不能称整树仅2文件改变；控制器没有启用新增缓存工具，仍为单worker、`--no-build-cache`。

10个C1 correctness selection均核对到实际Kotlin `@Test`；首项包含新empty-overlay排序/分页，第4项包含六阶段取消/真实保存/后续新鲜读取。公共8次按4101 C0/C1、4102 C1/C0、4103 C0/C1、4104 C1/C0，总18 calls/9安装组/480保存。原探针/协议/阈值不改，未启阶段诊断。

## 执行与失败边界

1. 仅两端真实data AndroidTest APK。包/签名、xmltree同一instrumentation元素、四ABI SQLite ELF及两端同native字节有检查；安装后限定单base.apk路径并与构件SHA比对。无app/MuPDF构建成功声明。
2. raw JUnit exact class/method、OK(1)、完整合法status/terminal；半截状态行会拒绝。host另外要求data包自然退出、fixture、marker、deadline。无helper例外/强停/重试。
3. missing或malformed marker在原JUnit成功时保留JUnit PASS，host标INCOMPLETE并停止，不转发任意payload。schema为7种/每方法12条，闭合字段/数组、有限非负数、固定enum或64hex字符串；拒绝重复键、unknown、数组及截断，限制字节，保留原JSON字符串与哈希。
4. `SCREEN_END`早于最终method deadline/PASS和组后环境检查。消费者须联合METHOD PASS、最终18方法/9组、双cleanup FINAL；完整数值块不能独自证明整轮完成。XML由同raw派生；日志中APK/ACL哈希仅是回执，不是独立原ZIP/二进制/ACL字节审计。
5. 首步本地clock一次固定，不是actual jobstart或旧PR-server anchor，无网络clock请求。累计30/35/50/55/60，50–55无额外任务；GitHub独立job60上限。261秒收尾caps是名义预算，不能用local anchor冒充精确jobstart证明固定39秒余量。
6. cleanup与旧算法同字节；KVM仅current UID、--no-mask及双恢复，无chmod666/group/udev/module。白名单文本<=20MiB/3天，不上传APK/DB/key。proposed before仍全零，真实prep-parent需最后核准。

## 已读检查与剩余边界

最终依据是 `offline-check-final/preflight.json`（SHA `b035265e03fd4c4c8339b949179eb5b61b2f297018728a5b980bdade5e9c041a`）的10组PASS；全部input、checker以及final-handoff所绑文件当前均MATCH。覆盖既有58 raw+13负例、8 marker块+10负例、12预算边界、3自然退出、坏marker分层、installedhash、实际首步本地脚本与13个package情形。我未重跑，均不是新原生。根发现的badging字段缺失及首步换行双转义已修，xmltree及首步脚本有离线回执。

最后仅package补充了白名单missing_files：correctness不再要求不存在的forwarding文件；每公共方法仍要求原raw/XML/records/forwarding。宣称执行完整但缺文件则降为EVIDENCE_INCOMPLETE、packaging_complete=false，不覆盖原始summary备份，不把早停缺文件升级。该分支退出码仍0以保存工件，因此GitHub绿色不能独自等于screen完整；读取FINAL/status/complete及后续原字节核验仍是必要门禁。

旧01/02保持历史，最终不使用其旧checker身份。末尾冻结checker为 `a2a3812f5923a2d9932b01edf9e11a164856e20974517790061d8c3e101240b5`，最终回执覆盖此版本。bind_control只校验plan_git格式，本审查已补核真实提交与8份readiness字节。planning-inputs旧server-clock待办措辞已提醒清理。

SCREENING_ONLY不能关闭旧20k/10秒门槛、原B observe-save回退、绘制/GC或全套正式验收。nearest-rank30使用索引14/28/29，旧emit p50索引15保留；后续使用每seed配对差，不能用四值中位差替代配对差中位。

## 审查绑定SHA-256

| 文件 | SHA-256 |
|---|---|
| `bound-control-final/run.py` | `3b88cda9aabda1eb261a4d1c860cde11ce73fd4d3b22223bac34676aa21febec` |
| `bound-control-final/package.py` | `6662193ae687c097df6d4e748c348d7a83a3e702b8c44ca1ce79b86ee27d449a` |
| `bound-control-final/cleanup.py` | `49e4337f483de46179466ae1c0fcf9ef18772e6a9388cce5cb6826a60d634576` |
| `bound-control-final/workflow.proposed.yml` | `26fa7448371fdb56b6874091f95a78f9e196f8d726382fd9ef104f8a3bd5dd91` |
| `bound-control-final/authorization.json` | `acdcce3b571a891ddbee3189d2ae241f6769bb2691403c201c7c1243b8cc2b3a` |
| `frozen-readiness/PLAN.md` | `64921517343ba2dfddf3cba3f319b15b61757501744c87f43d4217bd37d4a08a` |
| `frozen-readiness/source-spec.json` | `e57296ec73cea28ba22c5ee5871d77a0e5b22f0d0503c2bd453b987653ed6499` |
| `frozen-readiness/method-plan.json` | `7dd485f678aa16d89fc0a1ef3e69421a376b4054cc31918f2b393c8212033284` |
| `frozen-readiness/marker-contract.json` | `3f3bc2a6bc5b0a663a4e03a54be65c2193a8ed868ba31014e344a95595d6aa4d` |
| `frozen-readiness/original-protocol.json` | `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba` |
| `frozen-readiness/prepare_sources.py` | `7e42199cd01da917a0a141982a963fd6db51f6411c7516c85ea27406a0744ce5` |
| `frozen-readiness/C0-source-manifest.json` | `e0874e09a471c8157d9d3e0c72f05c9cf42dbd5ca3721679f63be94e5c5fba5b` |
| `frozen-readiness/C1-source-manifest.json` | `befb5ccec0a8863af5a1709c523470d6d5c7e9e827f2b5e804b0468f98232533` |
| `freeze_sources.py` | `e7620a4698b17cf858615c5191f4b3fa11f120388fa6a1678e5d154b9385c02f` |
| `bind_control.py` | `4a253eb9f84145267251e41080bea370cfe5dd70ff0334a89cd02eb8717fa961` |
| `check_screen.py` | `a2a3812f5923a2d9932b01edf9e11a164856e20974517790061d8c3e101240b5` |
| `offline-check-final/preflight.json` | `b035265e03fd4c4c8339b949179eb5b61b2f297018728a5b980bdade5e9c041a` |
| `final-handoff.json` | `8424181e02896d9fecb2281086637f6d4848f0e6a198f2701de89e5ef5f9b12d` |
