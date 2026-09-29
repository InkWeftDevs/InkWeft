# v46 测试安装包

已覆盖安装到获准的vivo平板，版本 `0.0.46-trusted-continuity（46）`。

- APK：`E:/Inkweft/dist/NextStageV46/InkWeft-v46-workspace-preview.apk`
- SHA256：`44edc049c24fb9ab580376d642cef3e4cb0fd0ec10124952a32d1af213e11fba`
- 应用源码：`74b5a3dd90f558ca345f5134c2369ca64c3d14b2`；沿用原包名与签名。
- 升级：实际v43→v46，测试前后2374条原记录按主键／字段一致。没有卸载、清库或重置工作树。

连续多页中，跨页长笔现在保存已确认前缀；意外退出后恢复为一组，可一次撤销。目标页已经回收或修改时保留恢复材料并提示处理。仍未抬笔的内容不会被当成已完成备份。

隔离同步为开发实验：设置→加密备份，连接本机合成服务后进入“隔离同步实验 · 合成资料”。A/B库都新建在调试缓存，仅合成内容经回环Relay；可以查看原生纸面、图、来源和选择完整冲突版本。 **不能用来同步你的真实笔记。** 没有运行夹具时无需打开该入口。

验证结果：[VERIFICATION-V46.md](VERIFICATION-V46.md)。逐项状态：[ACCEPTANCE-V46.json](ACCEPTANCE-V46.json)。实机步骤：[DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md)，本轮结果：[DEVICE-REPORT-V46.md](DEVICE-REPORT-V46.md)。

限制：封包时新远端TLS CI未运行，本次上传后的结果单独核对；容器只读／限容挂载故障受本机引擎限制；复杂计算性能还需真实帧／trace复核；真人笔感、温升和独立作者识别待测。不会把12场工程计算样本当作全应用流畅度保证。

作者格式仍schema12／IWO9，新增整笔组回执与检查点；不支持以旧版降级打开新格式。Shadow合成实验协议更新v2，旧v1实验另建合成库并配套更新服务，不能清除主资料库解决协议错误。

## 开发者复验入口

常规Gradle：JDK17，`-PinkweftDiagnosticBuild=true -PinkweftInsertionPreview=false`，`assembleDebug assembleDebugAndroidTest lintDebug`；仅受影响UI类与Room回归，文档更新不构建Android。实际构建提交由INKWEFT_HEAD_SHA／GITHUB_SHA显式传入。

TLS：依照 [服务README](../backup-server/README.md) 运行隔离TLS夹具，构建 `assembleTlsProbe assembleTlsProbeAndroidTest`；官方模拟器上运行 `ci/run_tls_probe.py`，5项计数必须完整。禁止用用户包加入测试CA。

跨页：专用空白模拟器运行 `ContinuousCrashProbe`，参数groupProbe=dedicated-emulator、phase=prepare/verify、cut对应切点；每次先核对到达切点及真实Process crashed。同步对应 `ShadowCrashProbe`。原始可复用脚本在本机NextStageV46归档，禁止将其中的pm clear改为物理平板serial。

性能：`-PinkweftPerformanceProbe=true` 独立performance包、`ComputePerformanceProbe`，compute为baseline/png/map/encryption，各3轮；报告全部样本、构件差异和峰值口径。120秒慢流使用 `BackupDeadlineProbe`，不能与同夹具其他网络测试同时运行。

出现问题：软件内标记时间→复现→“诊断与导出”生成ZIP，再补操作步骤及截图／笔感。诊断包含应用事件与状态摘要，不等于完整系统日志。不要把恢复密钥、凭据、私人笔迹或本地审计报告上传GitHub。
