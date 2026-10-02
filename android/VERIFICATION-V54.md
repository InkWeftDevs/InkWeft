# V54 验证证据

UI新增2/2、V53遮挡保护7/7、受影响旧UI15/15通过；lint 0错误/0致命/132警告；最终安装APK与交付字节一致，原固定签名一致。

最终候选新增两用例复用已有原生fixture：长中文与相同前缀不同尾名、选中语义/完整名称/48dp热区、反复切换与返回取消、设置中的完整备份入口与取消零作者写；实际native标题StaticLayout均衡换行/字体、fit边缘、常规1920×1200与375dp/字号1.6以及原生浅底占位。原生作者27组canonical对照包含原文、原迹、对象、来源、历史、回执与PDF bytes。

V53原7个遮挡保护用例在V54重新验证：9种祖先入口、系统可达无障碍窗口、PDF/原迹/对象/图/摘录raw绘制门禁、提示/收起暖缓存、旋转/回源/换题、退出恢复草稿与别本、明确已知/未知CAS操作恰一次。中立占位至少70%指定浅底，仍独立拒绝fixture彩色/raw像素和系统答案可达性；没有把内部测试Compose遍历当作系统读屏。

受影响旧回归：BranchReviewUiTest7、ReadLockUiTest2、LibraryBackupUiTest3（真实合成备份生成/坏包/恢复确认）、WritingWorkspaceUiTest两项标签原迹/关闭脏草稿、StarNoteInteractionsUiTest固定列表控制与分屏1。测试导航同步当前固定底栏/手写控件及已有标签实际索引，保留数据与分屏原断言。完整名称按逐行真实字形边界、垂直高度、无省略和末行全字符检查，避免把Compose段落预留宽度误认为字形裁切；诊断见evidence/diagnostics。逐方法最终结果、执行日志与失败保留记录见evidence/instrumentation.json；通过后不为截图重复整套。

构建/runner/lint见build-app.txt/build-checks.json，签名见certificate.txt，实际安装字节见installed-artifact.json，433项V53基线保护与本批源码/文档/原件/清理见final-check.json/cleanup.json。core-domain/data-local输入与数据格式schema12/IWO9/V51知识payload未改，所以未重跑领域/Room全套、旧版三阶段APK兼容或真实资料升级；此前V53兼容证据保持原址，本次不冒充再次执行。

本批所有证据为合成模拟器结果。真人手写、物理平板/手写笔体验、原“多字变我”故障原迹、真实资料升级和人工读屏均NOT_RUN。所有运行使用本批独立Android35模拟器与合成库。未安装用户真机/真实资料库，未上传私人原迹，未提交/推送/PR/合并/发布。本批准批次完成即停止；AniMemo未启动。
