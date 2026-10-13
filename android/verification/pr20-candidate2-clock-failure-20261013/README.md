# 第二候选首次执行：取得job时钟前停止

[run38431279425](https://github.com/InkWeftDevs/InkWeft/actions/runs/38431279425)，job115413786503，attempt1，执行提交b63e5272479478964b36c778b1af52974617cf3a，准备提交0763951bb8db1cf2e68e2069163b3a674e9ca406。2026-10-13 16:32:02–16:32:10 UTC，failure。准备分支0runs且无排队/执行中的仓库任务已在激活前记录；激活仅新增workflow、用准备SHA替换唯一before占位。

首步通过未认证GitHub公共API读取实际job开始时间时，服务器返回 **HTTP403: rate limit exceeded**。这是job元数据API的限流原文，不是MuPDF依赖错误、编译错误或Android断言失败，也没有把它猜作网络策略拒绝。失败立即停止，没有重试该API或rerun。

**源码checkout、依赖、九APK构建、模拟器/KVM/ACL及全部57方法均NOT_RUN。** 30正确性、24公共性能和3诊断没有任何新样本；候选45b4861的原生性能仍未验证，第四次既有回退和不确定性保持。

原始官方job_logs内容已取得，保存于job-decoded.log：12027字节，SHA256 a2fbb662e86bfc32d0ef37cb21d33387a3c1a54a4188c40e425f8f635cafcb69。日志包含HTTP异常及随后成功的early-setup fallback步骤。checkout尚未运行，控制器文件不存在，所以always走NOT_STARTED/NOT_TOUCHED fallback；并未执行原控制器双清理，也没有需要恢复的模拟器/ACL操作。不能将此报告称为RESTORED_EXACT回执核验。

API与job日志确认上传了1个文件的232字节ZIP，artifact11830762076，官方digest d02ea133264c15a02d51a5ca452c6b37c00ee22248985a06e0bfb1c201ca4a9b，保留3天。early-setup打包分支只写setup-failure.json；原ZIP未在本地下载或校验，不伪造本地archive验证，也无需把该占位ZIP当测量证据。

control保留精确控制器/授权/预算/preflight/proposed及活动workflow；metadata保留官方终态和零运行/触发绑定回执。控制器离线检查PASS与运行首步网络限流并不矛盾。后续仅评估不引入凭证、不扩大权限、不延长累计截止的时钟入口修复；不修改原始失败记录、公共探针、源码或阈值，不合并部署。
