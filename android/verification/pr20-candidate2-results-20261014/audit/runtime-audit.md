本轮独立运行记录审计 **PASS**。run38441089594 / job115447545993，active `6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5`；**57次原JUnit与host均PASS、35个不同selection、30正确性/24公共/3诊断**。审计只读原始证据；没有构建、ADB、网络、权限或源码变更。运行通过不等于性能改善，性能结论仍以冻结分析器为准。

原ZIP SHA256 `e3218dda22503f2bc085cf2e96284ce0bd24f5b05c40dcee772c13b2ec7318aa`，769104字节。重新核对289 payload的hash/size及全部291证据文件前后不变；固定验证器回执无issues/pending。8项readiness原字节、控制器清单、source-spec/method-plan及双方共同探针均与固定Git对象一致。B=a128，C0=ebb4848，C1=45b4861；原样本、探针与阈值未改。

逐一独立解析57份instrumentation原文：每次恰有一个OK(1)、一个开始code1、其余状态为0、两组准确class/method/numtests和终态-1；均adb0，无FAILURES/INSTRUMENTATION_FAILED。所有原始JSON marker与records逐项相等；57份派生JUnit XML各1测试且0failure/0error，身份/耗时匹配。每条冻结args、seed、phase、安装组与ordinal一致，顺序不重叠，没有重试。4种子×2公共selection×3身份的夹具记录逐组相等。

方法17是唯一helper例外。原JUnit PASS/adb0之后，20次natural观察中app与data.test始终exit1/空，只有app.a0.test PID2744存活。最后natural观察在2.692380139秒，单次 `am kill --user 0 org.inkweft.app.a0.test` 请求在2.750091028秒，返回0/空；2.771304338秒开始的后置观察已三包全gone，总确认耗时2.813559500秒。请求后完整观察耗时0.063468472秒，在原≤2秒/总≤5秒边界内。原控制器natural窗口为3秒、预留0.25秒避免边缘启动三条查询，故这里不能写“整整等待3秒”。原始单调时间记录及冻结method()/processes_after()顺序共同支持先完成原JUnit、再检查app/data已gone、再单次helper后台清理；没有force-stop或fallback。没有证据需要或允许声称原PID一定是cached。

其余56次均 **NATURAL_EXIT**，无auxiliary_cleanup字段，完整三包pidof返回exit1/空；最长退出确认0.050476863秒。每条原JUnit与host状态均单独核对，没有将host失败升级成JUnit成功。

9个APK回执按B/C0/C1各3个准确路径核对，全部同一debug signer `c63ad278ee90a7845869c98b20c5348c20487a177995c985c13eaca707e246f7`；3个app的嵌入source_commit与真实基线/前候选/新候选及active build_commit一致。每端记录4个MuPDF ABI（arm64-v8a/armeabi-v7a/x86/x86_64），共12条记录中对应字节数/hash一致；三个依赖清单均879项且逐字节相同。官方fitz1.28.5完整AAR记录SHA `e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e`，21636412字节；POM同官方检查hash一致。冻结runner代码在成功构建中执行apksigner、ELF魔数/尺寸检查，并在每次安装前重hash。**本审计仅核对这些回执和对应代码；ZIP不含APK/AAR/.so，未对二进制重新验签或验ELF。** 该job的JVM/lint仍为NOT_RUN，不挪用旧报告。

14安装组严格依计划顺序，42次APK安装均Success，首组三包缺席、后13组三包各卸载一次共39次Success。28份安装前后环境加整体before/after共30份字节一致，SHA `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`。设备为同一KVM API35 AOSP ATD x86_64/SwiftShader模拟器5554，1920×1200、density240、font_scale1.0；动画和其余属性保持原记录，hw_timeout_multiplier为空。30份host边界记录包含load/memory/disk，不据此推测性能因果；这是云模拟器，不是真机。

KVM原ACL SHA `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`；grant仅增加uid1001的rw和所需mask，保留全部原行。trap和always两份回执均emulator/command_groups=STOPPED_CONFIRMED、acl=RESTORED_EXACT、errors[]，各自after ACL与before **逐字节完全相同**。没有只相信RESTORED标签。

冻结anchor17:42:19与终态metadata实际job17:44:33相差134秒；job成功耗时2458秒（40分58秒），未超过60分钟。所有actions/phases及57方法均在38/41/53/55累计截止前完成。包装只给原完整summary增加cleanup_verified，没有改写原状态或时刻。

详细逐方法三文件hash、APK/ABI记录、28环境文件绑定与helper时序见[runtime-audit.json](runtime-audit.json)。共1137项一致性核对，问题数0。原raw、失败历史、冻结协议和本轮独立性能分析均保留；本报告不宣称所有性能门槛已关闭。
