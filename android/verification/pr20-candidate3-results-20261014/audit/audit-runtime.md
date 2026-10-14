本轮独立原始运行记录审计：**PASS**。run38498392929 / job115639302352，active `d20b37a00d084ddea8a59698b926d94ce2f1a633`，readiness `445654c90a4c78893f158fdc4eca4313f3320a7f`。58次原JUnit与host均PASS，36个不同selection；31正确性/24公共/3诊断。B=a128、C0=45b、C1=d66。运行通过不等于性能门槛关闭，保留冻结分析器全部回退/不确定结果。

原ZIP772054字节，SHA256 `79c53a73e262a22205519f5a994e10e6f2c75ca396a39bbd251d5bbd6b17993c`；292 payload加manifest/packaging共294文件、3663735解压字节，逐个与原ZIP/manifest原字节核对。审计前后294文件hash不变，未修改raw、源码或控制器。所有readiness和执行hash绑定固定Git对象；没有依赖自动verifier的PASS代替逐项检查。

**方法17人工前置复核**：先读原instrumentation确认唯一OK(1)、准确class/method、终态-1、adb0，再读完整21次natural观察、单次auxiliary_cleanup及后置观察，并与固定method()/processes_after()代码顺序核对。21次natural观察中app和data.test始终exit1/空，只有helper app.a0.test PID2717持续存在。最后natural观察从2.715611455s开始；单次 `am kill --user 0 org.inkweft.app.a0.test` 请求于2.754429444s，退出0/空。后置观察从2.773004771s开始三包已全gone；完整确认结束于2.810430242s。请求后完整确认仅0.056000798s，符合≤2s且总≤5s。

原自然窗口为3s，控制器预留0.25s避免在末端启动三条查询；实际请求为2.754429444s，**不能说足等3秒**。后置≤2s是上限，全gone即可结束，不需等满2秒。仅此唯一选择/phase/C1/runner允许helper清理；其余57方法都是NATURAL_EXIT、无辅助清理字段，最长退出确认0.537928234s。没有force-stop/fallback，也没有推断PID当时必为cached。顺序证据是原raw结果、单调观察记录及哈希绑定代码共同支持，不是假造每条adb的独立墙钟时间戳。

58份raw逐一解析OK(1)、状态code一次1其余0、两组准确class/method/numtests、终态-1；58份records逐项匹配原JSON marker，58份JUnit XML各一测试且0failure/0error；所有args/ordinal/seed/identity/installgroup精确匹配冻结计划。四种子×两公共selection×三端夹具记录相等，不据此说DB字节相同。方法31新增六取消门控用例原JUnit及host均PASS；过时的workflow“30项”显示名没有改变31条实际正确性执行。

9APK回执按三端各三件核对，全部同一记录签名证书 `111e915b87ded7cc34eaceada8cac23099ba4af211e762fcc202ae6a754fdb74`；3个app的source_commit分别为实际B/C0/C1、build_commit为d20、formal=false。三端各4个MuPDF ABI记录对应字节/hash一致，879项依赖清单三端逐字节相同。完整fitz1.28.5 AAR21636412字节、SHA `e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e`；官方POM200回执与依赖POM hash一致。冻结runner确实调用apksigner、检查ELF魔数/尺寸并在安装前重hash；**这里只复核回执和代码，ZIP没有APK/AAR/.so，未独立对二进制重新验签或验ELF。** 本job JVM/lint均NOT_RUN。

14安装组顺序/身份均匹配：42次安装Success，第一组三包原本缺席，后13组39次卸载Success。28份分组前后环境加整体前后共30份字节一致，SHA `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`。API35 AOSP ATD x86_64、KVM强制on、emulator5554、SwiftShader；1920×1200/density240/font_scale1.0。动画值保持原记录（1.0/1.0/null），hw_timeout_multiplier为空；不将该环境算真机。30份host边界有load/memory/disk记录，不作无依据的性能因果归因。

KVM before ACL SHA `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`。临时grant只增加uid1001 rw与mask，保留原行。trap和always均STOPPED_CONFIRMED/RESTORED_EXACT/errors[]，两份after ACL分别与before逐字节一致；未只相信恢复标签。

独立终态metadata核对：冻结anchor01:53:21Z、实际job01:56:28Z，前置187s；02:35:30Z结束，2342s（39m02），小于60min。所有actions/phases及方法都在38/41/53/55累计截止前完成。原summary保留EXECUTION_COMPLETE_ANALYSIS_PENDING，包装只增加cleanup_verified，不改写原状态和时刻。

共1175项一致性核对，问题0；详见[audit-runtime.json](audit-runtime.json)及只读复核过程[audit-runtime-procedure.py](audit-runtime-procedure.py)。

发布建议：可以按原清单保留这些合成原instrumentation、方法XML/records、来源/依赖/签名回执、环境/清理字节与本报告，明确它们是记录审计。294个文本文件的私钥/GitHub-token/AWS-key字面模式扫描无命中；这不是一般隐私保证。不要附加APK/AAR/数据库、签名私钥、Gradle/task cache、全量logcat或环境dump；不要删旧失败/回退样本。用户授权回执含内部conversation标识，可在新增说明中省略；不要直接改动已哈希的原始回执。性能结果引用冻结分析器，不以本审计PASS替代性能验收。
