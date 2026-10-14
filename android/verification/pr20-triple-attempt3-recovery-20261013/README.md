# 第三次原始工件核验：17项JUnit通过，进程门禁失败

用户上传原ZIP 439244字节、SHA256 `d3d3d31af26afd83eb8182cd0562b0f14277e8de03ea5cf5c566b9d8843ff2c5`与官方artifact11824680369一致；101个条目包括99项载荷和2份清单，解压总1554167字节。载荷大小、全部摘要、三端冻结输入及71b8488控制器身份逐项一致；原文按字节保留。

独立逐一读取17份instrumentation原文，全部为准确指定的单方法、一次开始/成功结束、adb0、OK(1 test)、INSTRUMENTATION_CODE -1；标记与派生records一致。**原JUnit为17 PASS；控制器方法状态为16 PASS、17 FAIL，后27方法NOT_RUN。** 不把原JUnit通过改写为整个run通过，也不把host门禁失败改写成UI断言失败。

第17项 `NotebookShelfUiTest#pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges` 原JUnit耗时6.527秒，通过。随后的三次pidof：主应用org.inkweft.app.a0和data.test均exit1且无输出；只有org.inkweft.app.a0.test返回exit0、PID2707、stderr空。立即采样仍有测试APK进程触发 `Owned package process cleanup unconfirmed`。该轮只有一次采样，没有进程状态/组件信息；不能确定它后来是否自然退出、是否缓存进程或有活动组件。

[Android官方进程生命周期说明](https://developer.android.com/guide/components/activities/process-lifecycle)指出组件结束不直接决定Linux进程消失，系统可以保留缓存进程；这说明“一次立即pidof”不是测试断言成败的等价条件，不能据此确认本次PID的具体状态。下一步仅修复测试控制器的退出核验/等待并保留严格停止确认；先核实测试辅助进程契约，不改业务代码、样本或阈值。

九APK原始身份/签名回执一致，三端四ABI MuPDF字节一致、全部依赖记录一致，官方AAR/POM摘要与首轮成功记录相同。ZIP无APK，因此这是回执及其源码执行逻辑核验，不是本地重新验签。官方POM预检HTTP200、662字节及摘要匹配，耗时0.372秒。

两份清理原JSON都确认STOPPED_CONFIRMED、RESTORED_EXACT、errors空。KVM before/trap/always ACL原字节相同。最终清理成功不能倒推方法17结束时已无进程；原host FAIL永久保留。本轮无性能测量，旧回退和比较覆盖缺口未关闭。

此前首轮受拒绝的是artifact ZIP存储下载；本次官方job_logs独立接口成功读取，其原文已在d1d472b归档。没有把拒绝扩大为所有日志不可用，没有重试/绕过该受拒绝地址。此ZIP来自用户主动授权上传，没有再次索取。
