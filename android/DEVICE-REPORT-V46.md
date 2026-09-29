# v46 平板验证记录

日期2026-09-29，vivo iPA2673／API36，用户已明确解锁并授权接手。APK SHA256 `44edc049c24fb9ab580376d642cef3e4cb0fd0ec10124952a32d1af213e11fba`，应用与原测试源码74b5a3dd90f558ca345f5134c2369ca64c3d14b2。

|用例|实际操作与结果|证据（本机NextStageV46）|
|---|---|---|
|V46-01 PASS|确认旧包43，停止应用完整备份，系统确认覆盖46；逐表主键／字段比较2374旧记录，缺失0、变化0；原生复验后再比相同|device-upgrade-report.json、device-final-data-report.json；原始tar与行摘要只存private-device-*|
|V46-05 PASS|Relay合成A/B，原生纸面、来源、改卡、实时图、往返与冲突选择；来源回收保留快照。没有把主库上传|device-safe-single/relay.log；合成截图|
|V46-06 PASS|隔离实验关闭／重开、交换原会话；诊断扫描不含实验密钥和token；合成book不在主库|device-safe-single/lab-reopen-fresh-fixture.log|
|V46-07 PASS|FontQualityLayoutTest 6项，字形比例／基线、独立布局版本、字号行距、局部擦除和导出像素一致、边界需人工校对|device-safe/FontQualityLayoutTest.log|
|V46-02/03/04/08/09|物理手持笔、物理故障和网络调度等未运行，不能用模拟器补填|NOT_RUN|
|V46-10|只有独立官方模拟器TLS和profileable工程负载；真实物理性能／新增Docker挂载不具备结果|PARTIAL工程；实机NOT_RUN|

失败保留：首次Shadow类批量运行超时，前台回到StarNote；改为先启动墨织并逐方法运行后原生Relay完成。实验重开单项后又命中夹具16库上限／既有合成会话不能复用新身份；旧夹具和本轮合成会话本地留档，改用全新隔离服务复验完成，没有提高预算或清除主资料。旧失败log继续保留。

备份tar约29MB；公开报告只有数量、版本和差异结论，不含笔记名称／正文／私有逐行哈希。所用USB传输和原生MotionEvent不是手持真实笔，也不是多作者识别准确率验证。

收尾：临时充电常亮恢复0；accelerometer_rotation=1、user_rotation=0均保持原值。原包名／签名保留，未卸载或清库；原安装包、恢复备份与测试证据保留本机，真实库同步未开启。
