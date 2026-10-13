# PR20 三端原生复验：官方依赖单次预检

第二次 run38403076172 在 B 解析官方 MuPDF 1.28.5 POM 时连接超时，退出1。用户上传 ZIP 的摘要、25项载荷和双清理已核验；44方法全部NOT_RUN，模拟器未启动，ACL未触及。根因报告在集成提交9090098。此次根据用户“允许”“不必向我申请直接往后进行即可”及父侧继续普通修复/有界重试指令，准备新的隔离单次运行，不重跑既有run，不重叠运行。

与54a1控制器相比，只在setup、任何Gradle之前加单次官方POM检查，并将检查回执/错误日志加入文本白名单。curl第一个选项--disable禁用curlrc；只准HTTPS，默认TLS校验，不跟随跳转，不重试，不改代理/身份/仓库/IP；连接10秒、请求23秒、外层进程至多25秒，仍受setup和55分钟绝对截止限制。HTTP200、662字节、SHA256 d66b443446cb2bb992c0de60115ae4988584bd44d16c877d0ba5e2d400b416ff均须通过；该摘要来自首次成功B/C0的相同官方依赖回执。POM暂存在ROOT，不注入Gradle缓存或仓库，不上传。任何失败停止构建与测量。该检查仅提前发现POM不可达，不能保证后续Gradle/AAR成功或修复服务端网络。

仍按32/38/52/55/60分钟累计截止，旧budget-revision.json字节不变。新预检最多另占25秒setup预算；旧假设的119–179秒余量因此最多降至94–154秒，所有C1/启动/正确性/诊断未知量保持未知。预算不足即INCOMPLETE，不调小样本或放宽阈值。三端冻结源码、两公共探针、9个真实debug APK、44方法（17+24+3）、14安装组、样本、判据、方法内部时限不变；串行单worker、无任务构建缓存、独立项目输出。真实Gradle解析及APK签名、同源四ABI MuPDF ELF核验全部保留。

cleanup.py与54a1逐字节相同；trap、always、package45秒与2秒kill宽限、上传120秒、标准ubuntu-24.04单job60分钟、contents只读、文本20MiB/3天均不变。原始回执缺失或清理不通过仍不能宣称完成。

仅新分支codex/kvm-pr20-triple-20261013-dependency3。准备提交没有活动workflow；模板全零before guard。准备推送且确认没有活动运行后，将准备SHA绑定到唯一活动workflow提交。固定branch/path push、created=false、before精确SHA、attempt=1，无dispatch/schedule/PR触发。

preflight.json只记录实际离线静态/合成检查，不能视为Android通过。旧观察保存p50回归与四项不确定结论保持待验证。
