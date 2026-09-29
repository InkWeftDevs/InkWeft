# v45 整合测试包

版本：0.0.45-creation-shadow（45）。固定签名 workspace 包用于墨织工作台预览；构件摘要、实际测试范围和未通过项目统一见 [验证记录](VERIFICATION-V45.md)。

## 本次使用变化

- 在设置导入随附原创纸张／导图 `.iwpack`，预览后安装。
- 新建笔记→纸面→我的模板；选择后预览，确认才创建。图片等比居中，留白为白色。
- 原来的添加页面→我的模板；位置、数量和插入后是否打开继续使用原流程，取消不加页。
- 导图浮窗→新建导图→已安装模板；新图保存在当前笔记，不另外创建整本笔记。
- 模板包作者是“作者声明”。禁用后不再供新建使用；升级／卸载不删除已有作者副本。损坏包显示待修复，可重新导入原包。
- 备份遇到未完成书写时会提示等待／恢复；暂停和服务器 Retry-After 不应被自动重试绕过。

没有开启用户资料库同步。影子同步在一次性 Room 库和单独持久服务里验证正式数据格式；使用说明在 [隔离合同](../sync-lab/ANDROID-SHADOW.md)。跨页整组中断恢复本次仅交付存储与故障切片，仍未接入实际手势恢复。

## 独立测试工具

这些命令会清空**专用模拟器的测试应用**。工具只接受名为 `InkWeft-Test` 的标准 AVD，或 MuMu 管理器核对过名称与端口的同名实例，不接受物理平板。先安装本轮普通测试 APK 和对应 androidTest APK；开启 `backup-server/fixture.py --directory <新建临时目录>`，将 18751 反向映射到专用模拟器。

```sh
python ci/device_fault_probe.py --serial <专用模拟器> --output <新目录> --probe cipher
python ci/device_fault_probe.py --serial <专用模拟器> --output <另一新目录> --probe offline
python ci/device_fault_probe.py --serial <专用模拟器> --output <第三个新目录> --probe resource
```

MuMu 额外传入 `--mumu-manager <MuMuManager.exe绝对路径> --mumu-index <实例编号>`；非 PATH 中的 adb 使用 `--adb <绝对路径>`。cipher 在 5 个切点结束测试应用进程后重新验证队列，resource 在7个安装／作者实例切点重开验证；offline 真正撤去 18751 的反向映射再恢复，不能在其他任务使用同一模拟器时运行。

持续负载先另开 `fixture.py --directory <新目录> --port 18754 --upload-delay 4`，固定屏幕和字号，然后运行：

```sh
python ci/concurrent_writing.py --serial <专用模拟器> --apk <固定候选.apk> --output <新目录> --rounds 3
```

共 3 轮 × 普通墨迹／石墨／自然字体／PDF × 独立书写／打开导图／实际备份／实际安装。先核对设备 APK 的 SHA256，每次保存都必须落在后台任务活动窗口内。安装使用 256 次真实校验／幂等安装并让出前台；备份服务每个 PUT 人为延迟 4 秒。原始帧时间、保存开始／确认、任务开始／结束与失败日志均保留，不能把这些指标解释为真实笔尖延迟或无人工延迟的产品吞吐量。每轮使用新合成资料，不读取私人笔记。

TLS 另用隔离 `.tlsprobe` 测试构建，步骤见 [服务说明](../backup-server/README.md)。普通安装包不包含测试 CA；容器业务／独立卷恢复需已有获准 Docker 引擎，运行 `container_check.py`。它们不是公网部署指令。

## 实机与数据

沿用现有签名、包名和作者 schema 12／IWO9，不卸载或清空平板。覆盖前后需核对已有记录，状态以 [实机记录](DEVICE-REPORT-V45.md) 为准。15 分钟真实笔、多作者识别及首次用户观察仍待人工条件，不以模拟器替代。详细操作和证据要求见 [独立清单](DEVICE-TEST-CHECKLIST.md)。
