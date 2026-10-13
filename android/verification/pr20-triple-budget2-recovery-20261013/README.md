# 第二次三端复验：官方依赖连接超时的原始核验

[运行 38403076172](https://github.com/InkWeftDevs/InkWeft/actions/runs/38403076172) 于 2026-10-13 13:12:13–13:16:30 UTC 结束，状态 failure。用户上传原始 ZIP 的 SHA-256 为 `a98f26d5836fde579c2114438a680c666b68e6a1d734e11dff6c06ae863d34ef`，194,588 字节；27 个 ZIP 条目含 25 项原始载荷和两份清单，全部字节、清单及冻结输入核验一致。原始日志和摘要按字节保留，归档完整性不代表测试成功。

B 构建完整步骤 216.569 秒，Gradle 阶段 216.442 秒，退出 1。`app:processDebugNavigationResources` 解析 `com.artifex.mupdf:fitz:1.28.5` 时，访问 `https://maven.ghostscript.com/com/artifex/mupdf/fitz/1.28.5/fitz-1.28.5.pom` 连接超时。日志没有 HTTP 403 或源码编译错误证据，也不能据此断言网络问题一定短暂。32 分钟 setup 截止没有触发。

没有完成的 APK 回执，没有启动模拟器或安装；44 方法全部 `NOT_RUN`。trap 与 always 两份原始清理回执均为进程组 `STOPPED_CONFIRMED`、模拟器 `NOT_STARTED`、ACL `NOT_TOUCHED`、错误列表空。此轮不增加原生正确性或性能结论，旧观察保存 p50 回归及四项不确定结论仍未关闭。

下一次在既有继续修复授权下使用新的隔离分支，保留三端、44 方法、样本、阈值和 32/38/52/55/60 分钟截止。构建前仅对相同官方 POM 做一次至多 25 秒的 HTTPS GET 和已验证字节哈希检查，失败即停；该检查只能提前发现依赖不可达，不能保证随后 Gradle/AAR 可用。继续真实九 APK、签名和四 ABI ELF 核验，不采用替代镜像、固定 IP、降级 TLS、占位包或下载拒绝绕行。
