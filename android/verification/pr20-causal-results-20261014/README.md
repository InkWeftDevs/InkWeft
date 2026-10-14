# 因果诊断归档：正式性能验收仍未通过

[run 38514139119](https://github.com/InkWeftDevs/InkWeft/actions/runs/38514139119) / job115689021611 / attempt1，2026-10-14，14分58秒。source=`78a5babeaeb92292026423222d5e08635989198b`，readiness=`b2e1d9f9de66a26f5a4b122ea6754d77198005f8`，执行=`d4a107dd84c6e309b5dbbbfb3a57d69371024408`。官方日志中13项原方法的JUnit/host摘要均PASS；127条转发payload与13份compact records严格闭合，状态为`COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`、issues=[]。方法1–7的空数组是预期无marker。

**这是单候选、开启诊断的云KVM记录，不是新的公共配对验收。** 保留[原d66完整比较的回退与缺口](../pr20-candidate3-results-20261014/README.md)及[后续搜索筛查](../pr20-search-screen-results-20261014/README.md)，不合池、不替换旧失败、不放宽门槛。

| 范围 | 本轮支持 | 仍开放 |
|---|---|---|
| 搜索 | 暖读body匹配约占page线程CPU91.5%，支持小范围ASCII候选过滤；12组/360次保存与3792事件完整 | 保存p50门槛与暖读不利观察仍未闭合；新matcher尚无原生收益验证，不能推断它解决p50 |
| 绘制 | 1/4/8层局部均decode1、瓦片重建1、像素与cold一致；定位metadata、绘制与通知区间 | 旧单层回退信号不能归为噪声；连续变换/稀疏更新比较和原dense30秒仍open；lazy-pencil改动发生于本次诊断之后，**尚未原生验证** |
| GC | 两连接各30/30 Committed，precommit点、DELETE窗口及请求开始暴露均30/30；保存p50/p95/max同Room11.224/15.425/16.345ms、跨Room12.791/56.666/57.277ms | 这是条件到达；旧GC自然暴露0/30、等价基线竞争比较及纯锁等待仍open，无已证实的最小产品fix |

[独立完整性审查](independent-integrity-review.md)、[搜索结论](search-analysis/recommendation.md)、[绘制结论](raster-analysis/review.md)、[GC结论](gc-analysis/gc-causal-review.md)保留计时窗口、原序数据、非可加因果和失败边界。`matcher-implementation-ready.json`、`lazy-pencil-handoff.json`及patch描述本次运行之后的候选状态，**不是它们的原生通过证据**。`candidate-review/search`中的Java/SQLite检查仅为host语义核对。

本归档没有取得本轮ZIP、原APK/ELF/安装文件、原始instrumentation/JUnit或ACL文件。其哈希/验签/安装/清理是已绑定controller的日志声明，不能称独立复验这些字节。raw_log_sha256仅链接CONTEXT/FINAL。当前可独立复算的是已归档官方decoded log、API metadata、原JSON payload、records及各项算术；详见审查的完整边界。

## 文件与体积

- `official/`：原decoded log、run/jobs终态、启动及终态binding，均保持原字节。
- `extracted/`：13份原compact records及完整提取回执；`search-analysis/`、`raster-analysis/`、`gc-analysis/`：报告、原序CSV及复算结果。
- `tools/`：精确分析器、纯validator及其冻结输入。`replay-inputs/control/`仅供哈希/配置审查，**复算不会执行controller或workflow**。
- `archive-manifest.json`：显式逐文件白名单、来源路径、大小与SHA256；只复制UTF-8文本，不含APK/AAR/db/pyc/class/zip或合成夹具。原日志的ANSI转义保持，不修改原证据。省去5,228,858字节重复`search-analysis/analysis.json`，原records、四CSV、recommendation与复算代码全部保留。
- `archive-check.json`与`SHA256SUMS`记录最终文件/总字节和检查结果。哈希用于完整性，不是数字签名或来源真实性证明。

## 离线复算

需要Python3及PyYAML。以下只进行文本解析和算术，不访问网络、不运行Android/Gradle/CI，不覆盖本归档：

```sh
sha256sum -c SHA256SUMS
python -B recompute.py --out /tmp/pr20-causal-recomputed
```

输出目录必须尚不存在。复算会重新提取13份records并逐字节核对，重建搜索四CSV并逐字节核对，再复算绘制/GC；结果写到指定scratch目录。派生JSON包含本机输入路径/回执哈希，迁移路径后不要求全文件字节相同；原records、CSV与数值应相同。保留的synthetic checker回执不算原生证据，准备期工具README中的“未运行”描述属于当时状态。原始报告绝对scratch链接保留于原文，本目录导航以本README为准。
