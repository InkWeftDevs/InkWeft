# v40 平板测试记录

日期：2026-09-28；设备 vivo iPA2673 / Android 16；输入为 USB 调试合成触笔。使用 VERIFICATION-V40.md 记录的最终 APK，只修改新建的 Beauty-v40 测试本。

|编号|实际操作与结果|证据（本地 BeautyRepair/device）|
|---|---|---|
|BF40-01|PASS：原保存状态为开启美化、保留笔形、系统衬线；覆盖安装后字体入口可见，点开有三个字体选项|font-entry-restored.png、font-list.png|
|BF40-02|PASS：选文楷自动切到字体美化；六笔合成 HI 停笔后转为文楷；再选衬线和蓝色书写，生成蓝色衬线，前一行保留|font-selected.png、live-written.png、live-converted.png、serif-selected.png、two-fonts.png|
|BF40-03|PARTIAL：最终私有备份确认字体、开启状态和字体模式持久化；2030 条原记录不变，原旋转与字号设置已恢复；实际手持笔擦除／撤销待测|after-final/shared_prefs/inkweft-beauty.xml、final-preservation.json、restored-settings.png|
|BF40-04|NOT_RUN：真实手持笔中文、英文、公式与连续书写笔感待测；不以 USB 输入替代|按 DEVICE-TEST-CHECKLIST.md 执行|

BF40-02 只证明字体模式、自动转换与颜色；第二行 HI 被识别为 H工，记录为识别精度限制。未宣称本轮解决识别模型质量问题。

升级前已做完整本地备份；最终安装文件与交付包哈希一致。没有卸载或清空平板应用数据。截图、私有备份仅在本机归档。
