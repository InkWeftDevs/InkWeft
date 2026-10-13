# 离线工件验证器独立静态审阅

范围：只读审查 `verify_artifact.py`、`check_verifier.py` 和已有合成回执；没有运行验证器、合成测试、构建、Android或网络操作。真实run38433834125工件尚未交付，本记录不是该run验收结果。

初审版本：verifier SHA256 `0ca3f59032d13b05e9d7f007c7cb31292bf787a2ac3b2dd5869b7555cc1d33ce`。固定run/job、active/preparation/readiness、C0/C1及clock身份均明确。ZIP大小与官方摘要、平面白名单、重复项、路径/特殊文件、UTF-8、凭据模式、20MiB解压总量、manifest逐项字节/hash及固定Git输入检查，未发现新的确定性阻断。APK检查明确仅核对已记录签名/ELF/源码回执，不能重验工件中没有的APK二进制；派生XML不是独立执行证据。

已向父侧及脚本作者报告四处最小修正：

1. 完成状态需双向一致：原/包装summary误写`EXECUTION_COMPLETE_ANALYSIS_PENDING`而57项仍NOT_RUN时，不能报告运行记录一致。
2. 外层中断导致原RUNNING、包装INCOMPLETE时，已有partial raw但尚未持久化的digest/parser/records应标待核验，不能误判为已有字段相互矛盾；存在字段冲突仍应报问题，PASS/FAIL要求不放松。
3. `AUXILIARY_AM_KILL_CONFIRMED`必须对应唯一允许方法17及完整辅助清理回执；`NATURAL_EXIT`不能同时携带辅助清理回执。
4. 已有KVM ACL快照/授予记录时，不能凭`NOT_TOUCHED`认定恢复；需核对两次after原字节及相应状态。重现原package的声明应与独立ACL验证结果分开，ACL验证失败不能仍输出独立`both_verified=true`。

另补齐末尾边界：57个方法已PASS仍可能在最后安装组环境核验或持久化前中断。最终实现分开`all57_methods_pass`与`claims_complete`，只在顶层宣称完整执行时要求14组及最终环境完整；合法后置中断保持INCOMPLETE/pending，不强推整体完成。

最终修订已只读复核：上述五处均已收口，未发现新的确定性阻断。作者只改scratch验证器及合成检查，冻结runner和生产代码没有参与此次修改。当前绑定：

- `verify_artifact.py` SHA256：`aee9eb7734c688f6c80bb5a4b9a3c8e296eaf421a95b50cc65a891293a906c8a`。
- `check_verifier.py` SHA256：`a1e5b49cbbaad84dbbc4ff21eb845f7b9ae5a905f786248edcc3f369a8fb1e17`。
- `synthetic-check.json` SHA256：`d380cda33cfc60132ebe8aa4ebbe368323a6df38e1c6a6919e7f8703768725dd`，记录27项PASS，且绑定当前验证器SHA；本审阅没有重跑这些检查。

原17项版本保留于`review-before-fixes/`。新增末尾中断用例只执行真实审计函数的完成判断AST分支，并明确不伪造完整57项raw执行；其余新增反例覆盖假完成、部分raw未持久化、已有digest冲突、辅助清理对应关系及ACL状态/原字节。

终局仍需人工核对辅助包观察序列、时间窗、操作前app/data已消失及唯一固定命令；核验官方终态元数据与完整原工件。脚本返回0、完整性通过或数值带内均不能单独替代57项执行、身份、环境、清理及性能验收。
