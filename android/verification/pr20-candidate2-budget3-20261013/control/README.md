# 第二候选预算3：合理重分配累计阶段窗口

源码仍45b4861，C0仍ebb，B仍原受控a128，readiness6c72的57方法/公共probe/样本/阈值均保持。上一执行9907/run38433834125在旧setup32截止触及C1 outer timeout124，B/C0步骤733/589秒成功，C1的434秒是截断窗口；57项未执行。官方日志两次清理均NOT_STARTED模拟器、STOPPED_CONFIRMED命令组、ACL NOT_TOUCHED、errors[]；报告9c9e490已保留，不要求用户为已能诊断的预算停止上传原ZIP。

按最新继续授权，仅将累计阶段预算改为38/41/53/55分钟：原冻结PLAN/hash不改，authorization.phase_budget_override和budget.phase_budget_amendment明确覆盖其旧阶段数字。工作55和单标准job60硬限不变，保留既有261秒名义清理上限加39秒步骤裕量。仍serial/单worker/--no-build-cache、9真实APK、30正确性/24公共/3诊断、原样本/内部超时/门禁、method17唯一helper例外、最小UID ACL与双清理、20MiB文本/3天；没有权限或缓存机制变化。

新预算以已测阶段为参考：第四轮17正确性28.079秒、公共499.498秒、诊断44.921秒；本轮新增13项正确性尚无原生时长上界。按上轮C1开始点重放，新setup窗给C1与设备共795秒，比旧窗多360秒，不将被截断的434秒当完成耗时。当前C0=ebb应对应第四轮C1=ebb，不能混用旧C0=794的数字。阶段提前结束立即进入下一阶段；任何超时仍记录失败/未执行，不删样本，不保证完成。

本次必要PR20更新成功响应的服务器updated_at为2026-10-13T17:42:19Z，epoch1791913339。成功响应后才冻结准备文件并推送/激活；最小回执、contract、budget、auth、workflow按无环哈希绑定同一不可后移anchor。PR head9c9是报告提交，不替代被测45b。实际job started仍未知/null，终态需另以外部metadata核对。准备与排队时间扣入预算，禁止重设起点。

本轮绝对截止UTC：setup 18:20:19、正确性 18:23:19、公共 18:35:19、诊断/工作 18:37:19。实际job硬上限仍60分钟。clock首步无网络/API/token；未来或过期锚点停止。checkout后在任何官方依赖/构建/设备操作前核验完整clock绑定。

新11组离线preflight PASS，包括19进程路径、8官方POM路径、43clock路径、12预算负例及12次package合成执行；不等于Android或性能通过。准备提交先确认0run，再独立新增仅before绑定该准备SHA的workflow，运行一次，无dispatch/rerun/并行第二runner。最终活动workflow只允许与proposed相差唯一before值。cleanup/package保持与9907逐字节相同；run.py只改两处预算数字。
