# v35 实机记录 · 2026-09-28

设备：vivo iPA2673、Android16、2512×3840、480dpi，USB连接。生产包与[交付回执](VERIFICATION-V35.md)一致，SHA-256 `c7d4c9866ac8309a4ef63cedc4ba145bc99ff1d9eed26c5448f8d44940c42a3f`，只进行一次生产包覆盖安装。

## 资料保护与输入方式

实际旧包为v34，SHA-256 `f9cdab480181ecc70dbc3e6fc435733f5f69eee991a0293de4b6522cb1540c7f`，证书与v35相同。原23份笔记留存本机恢复副本和摘要。用户确认11:27在InkWeft-v33有两次对象编辑，发生在11:33升级前；保留这些修改，没有回退。

最终逐表核对，升级后的全部原数据库行均保留。现为原23份＋新增1份 `InkWeft-v35-M1`；测试本2页、2张卡、2张图、1个手写来源。来源稳定页ID位于第2页，原迹revision=2；共享卡有1处有效和1处已移除引用，卡片未回收。

本次是ADB合成触控／触控笔事件，不是人手握笔；模拟压力不能当真实笔感验收。

## 执行记录

|编号|状态|实际步骤与证据|
|---|---|---|
|NX-06|部分通过|竖屏／横屏切换保留未提交的Shared-Insight标题与图面板。`tablet-card-draft*.png`；真实落笔旋转和系统分屏仍待测，落笔坐标保护已在模拟器通过|
|NX-07|通过（合成）|两次落笔→摘录笔圈选→Map-A／Chapter-1→添加到这里；无需先填写摘要，原迹保留。`tablet-source-target-real.png`、`tablet-added-source.png`|
|NX-09|通过（合成）|复用到Map-B，编辑为Shared-Insight，Map-A同步；移除Map-A节点后Map-B保留。`tablet-two-positions.png`、`tablet-map-a-shared.png`、`tablet-map-b-retained.png`、`tablet-fixture-proof.json`|
|NX-10|部分通过|来源前插入空页，切到空页后回源，定位原来源第2页。`tablet-source-page-second.png`、`tablet-moved-source-return.png`；擦写／回收／显式移动组合在隔离Room通过，本机未执行这些破坏步骤|
|NX-11|部分通过|缩放平移后关闭重开，图区域像素一致；显式回源保留原迹。`tablet-viewport-before-close.png`、`tablet-viewport-reopened.png`；本机未补多层折叠全组合|
|NX-13|通过|同包同签名原位升级，安装Success，设备APK摘要匹配，原数据库行全部保留。`tablet-install.log`、`tablet-install-identity.json`、`tablet-final-comparison.json`|
|NX-01/02/03/05/08/12|NOT_RUN（本设备）|冲突／重复标记／低内存／事务故障／隔离备份由模拟器或临时Room库执行，未在用户原库注入故障或恢复覆盖|
|NX-04/14|NOT_RUN（本设备性能）|前后3轮密集样本、帧、CPU与内存来自MuMu，不替代本机性能结论|
|NX-HW-01|NOT_RUN|真实笔压、倾斜、掌拒、15分钟连续书写和热负载需人工执行|

另外关闭进程后重开验收本，两笔、两页、共享卡与来源均保留。图会话回到默认入口，不把强停视口恢复列为已实现。

一次物理设备instrumentation启动未完成，尚未进入建本步骤；人工终止后runner输出Process crashed。保留 `tablet-integrated-story.log` 与 `tablet-instrument-start.log`，不计为通过，也不据此认定正常应用崩溃。随后上述步骤由直接界面操作完成，原资料经核对未改变。

证据仅在本机 `E:/Inkweft/archives/2026-09-28/Integrated-Mindmap/`；私人数据库位于 `tablet-private/`，不上传GitHub。收尾恢复临时USB常亮及旋转设置，移除测试辅助包，保留生产应用与验收本。具体复测步骤和诊断导出方法见[测试清单](DEVICE-TEST-CHECKLIST.md)。
