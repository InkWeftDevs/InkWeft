# PR20 第二候选原生复验：57次调用，单runner最多60分钟

状态：源码与方法冻结，原生尚未执行。沿用用户2026-10-13 11:54:51 UTC「允许」及11:55:28「不必向我申请直接往后进行即可」的持续授权；不新增付费资源、权限范围、合并或部署。此前四次执行及原始结果保持不变。

## 精确身份

- B：a128bb07e34cb82f00eeafc8e62a47bf7b95b128，加已固定writer factory和相同公共探针。
- 本计划C0：ebb48481e7e995288c1844d43011398c513b4f4e，上一候选；不是历史第四次执行的C0=7949011。
- 本计划C1：45b4861ae23ac19eda3246fcd34cf3d2132dc1ee，延后目录组装与追加几何复用的新候选。

完整Git/tree、overlay与实际Android/ci构建输入见source-spec和三个manifest；排除verification文档。prepare_sources.py只重建固定源码，不构建、不联网、不执行设备操作。两份公共探针字节保持不变。C1新增正确性检查不混入公共性能样本。

## 改动假设与现存缺口

第四次run 38419007471原始44调用及完整工件已核验。上一候选对B的observe保存p50四对均超带，配对中位+2.242045ms/+108.585%，仍为REGRESSION_SIGNAL；5个栅格阶段为INCONCLUSIVE。目录组装移到命中页SQL之后，希望减少已取消读取的无效工作；只在严格兼容append下保留解码几何，希望减少重复payload解码。两者都需新原生数据验证，不声称已消除回退，也不将stage诊断视作CPU或锁等待归因。warm没有追加验收阈值。

## 方法与比较保持

method-plan.json共57次独立单方法调用：30正确性、24公共、3候选诊断，共14安装组。原1–17逐项不变，新增18–30，原18–44仅序号后移13。新增包括取消目录组装1项、RasterReadCache全7项、TileLocalRedraw追加/平移/中断3项、StreamedInkRaster清理/省略笔迹2项。每次核验精确class/method、单次OK(1)、terminal code、adb返回值和全部合成包退出。仅原method17允许原有一次background-safe辅助test包am kill；新方法不加入例外。

四个seed固定4101/4102/4103/4104，顺序B-C0-C1、C1-B-C0、C0-C1-B、B-C1-C0；C1相对B/C0先后各2次，B对C0为3/1旁证。24公共调用、720原序保存、18原指标、54比较结果；每次30项nearest-rank索引14/28/29。四区组才是试验单位，不把720保存合池成独立样本。

original-protocol.json逐字节沿用原阈值：raster10%/5ms；保存p50 10%/1ms、p95 15%/5ms、max25%/20ms；冷读/索引10%/500ms。至少3/4变慢且配对中位相对和绝对增量均超带为REGRESSION_SIGNAL；任一超带但不足该条件为INCONCLUSIVE；全部带内且正确性、身份、环境、清理均通过才为WITHIN_PROPOSED_BAND。不能以一项改善抵消另一项回退。warm仍只作每轮5项中位的描述性比较。保存重叠及暴露区间分列。

最后3项诊断保持：同Room和另一Room各30个新1281来源合成库的自然GC；仅覆盖before-batch-commit时间点才计明确暴露，少于20不给子集p50/p95。候选诊断不能关闭缺少B/C0对应观测的GC竞争门槛。最后单次libraryStageDiagnostics=true只作阶段解释，不混公共值、不推导跨版本stage差值。

## 环境、预算与停止条件

单标准ubuntu-24.04、单API35 AOSP ATD x86_64 KVM模拟器，1920×1200/dpi240、SwiftShader、2vCPU/2048MiB、字体1.0与原动画设置。三个隔离源码树串行单worker无build-cache各构建3个debug APK，共9个；核验source/spec、APK哈希、签名、依赖及MuPDF完整四ABI ELF。官方依赖或网络策略拒绝即停止，不使用占位包或绕路下载。

从真实job开始累计截止固定setup32、correctness38、common52、diagnostics55、job60分钟；提前完成可立即进下一阶段。每命令同时受自身时限和累计截止约束。不得缩减四区组、删方法、改fixture/阈值、重试方法或自动rerun来补结果。失败保留完整原文，未执行及超预算诚实标INCOMPLETE。新13项耗时没有实测上界；第四次setup约1324秒只作预算参照，不能保证本次完成。

KVM若当前UID已rw则不动；否则只按既有最小UID ACL授权，记录node和原ACL，不扩大mask；trap与always两次停止本job拥有进程并逐字节恢复验证。cleanup各45秒加2秒kill grace，package45秒、upload120秒，全部受60分钟硬上限；时间预算本身不证明恢复成功。不得chmod666、改组/udev/模块或碰用户设备/真实笔记/AniMemo。

## 执行与证据

另建一次性隔离执行分支；先推无活动workflow的准备提交并确认0runs，再以event.before精确绑定该提交、created=false、attempt=1、唯一branch/path push激活一次。无dispatch/schedule/PR触发，无并行执行，无旧run重试。执行中冻结源码与控制器。

工件仅白名单非敏感完整文本，最多20MiB、保留3天：构建/原始instrumentation、派生XML/records、原序样本、source/overlay/APK/依赖公开回执、环境和双清理回执、manifest。不得上传APK、数据库、密钥、AVD或全环境；不截断慢样本。最终按实际commit和slot同时标注结果，旧报告保持历史身份。编译/JVM/静态通过不等于本次Android或性能通过。
