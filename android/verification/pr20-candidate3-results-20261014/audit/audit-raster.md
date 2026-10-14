# run38498392929 绘制独立审计

**计数和功能通过，绘制耗时仍未全部验收。** B→C1为7项带内、2项INCONCLUSIVE；C0→C1为5项带内、3项INCONCLUSIVE、1项REGRESSION_SIGNAL。未改阈值、重跑或丢弃慢值。

独立从12份公共instrumentation原文（ordinal32、34、…、54）解析36条绘制记录、36份夹具与36份清理记录，逐项核对records JSON、原JUnit/host回执和冻结analysis；独立复算18个比较、72个配对值全部一致。另读C1绘制正确性19–30原文，12/12 PASS。

## 身份与范围

B=`a128bb07e34cb82f00eeafc8e62a47bf7b95b128`＋既定writer factory；C0=`45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`；C1=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`。active=`d20b37a00d084ddea8a59698b926d94ce2f1a633`。本轮C0是上一轮C1，不能沿用旧C0=ebb的标签。共同probe SHA-256 `a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338`。

两个候选的冻结构建清单仅LibrarySearchRepository.kt和LibraryPagingTest.kt不同；绘制、缓存、InkRepository、SpatialIndex、共同probe及依赖声明均未变化。APK源码身份与canonical清单回执一致；本审计不重新验APK二进制。12组seed×层数的三端逻辑夹具hash/行数/SQLite参数相同。SQLite为1024-byte page、auto_vacuum1、WAL、synchronous2。

## 实际工作和像素

| 层数 | B local decode | C0/C1 local decode | C0/C1 native构造 | local rebuild | full decode / rebuild |
|---|---:|---:|---:|---:|---|
| 1 | 13 | 1 | 13 | 1 | 321 / 24 |
| 4 | 21 | 1 | 21 | 1 | 321 / 24 |
| 8 | 11 | 1 | 11 | 1 | 321 / 48 |

以上四seed全部一致。B native字段缺失/null，不补零。减少的是Kotlin payload解码；C0/C1没有新的decode/native/rebuild计数改善，native仍逐帧构造。共同probe不输出reusedStrokes、readObserver或geometryBytes，不能将推导的缓存命中或读行数当成原始测量。

36/36 pixelsEqual=true，各版本局部帧对自身独立冷重建；隐藏更新之后的方法断言也通过。不是跨版本像素hash、硬件截图或真实设备验收。36份清理均joined=true、tracked totalBytes=0、inFlightJobs=0；12方法均自然退出。tracked预算计数不代表进程/native heap为0。

## 原阈值复算

每对超带为Δ>max(左值×10%,5ms)。REGRESSION_SIGNAL要求≥3对变慢且配对绝对中位数>5ms、相对中位数>10%；否则任一对超带为INCONCLUSIVE，其余带内。信号不要求3对分别超带；本轮单层append正是4对慢、2对超带。四seed是比较单位，不是统计显著性检验。

| 指标 | B→C1 中位Δ ms / % | 状态 | C0→C1 中位Δ ms / % | 状态 |
|---|---:|---|---:|---|
| 1层 firstCompleteMs | -4.544398 / -1.641064% | WITHIN_PROPOSED_BAND | -5.297560 / -1.892635% | WITHIN_PROPOSED_BAND |
| 1层 appendCompleteMs | -8.795768 / -16.499519% | WITHIN_PROPOSED_BAND | +9.468752 / +26.372281% | REGRESSION_SIGNAL |
| 1层 forcedFullCompleteMs | +0.037392 / +0.018550% | WITHIN_PROPOSED_BAND | -0.312743 / -0.154486% | WITHIN_PROPOSED_BAND |
| 4层 firstCompleteMs | +2.397863 / +1.320805% | WITHIN_PROPOSED_BAND | -12.898539 / -5.197030% | WITHIN_PROPOSED_BAND |
| 4层 appendCompleteMs | -7.395031 / -6.928137% | WITHIN_PROPOSED_BAND | -5.485077 / -5.244686% | INCONCLUSIVE |
| 4层 forcedFullCompleteMs | +0.341487 / +0.237373% | INCONCLUSIVE | -2.215460 / -1.049775% | INCONCLUSIVE |
| 8层 firstCompleteMs | +2.973033 / +1.196328% | INCONCLUSIVE | -3.478396 / -0.834994% | INCONCLUSIVE |
| 8层 appendCompleteMs | -58.253551 / -30.815007% | WITHIN_PROPOSED_BAND | -0.396216 / -0.308825% | WITHIN_PROPOSED_BAND |
| 8层 forcedFullCompleteMs | -21.977410 / -8.371450% | WITHIN_PROPOSED_BAND | -0.302894 / -0.121618% | WITHIN_PROPOSED_BAND |

全部超带样本（其余慢值也完整保留在下表/JSON）：

| 比较 | 指标 | seed | 原值→C1 ms | Δ ms / % |
|---|---|---:|---:|---:|
| B->C1 | raster.4.forcedFullCompleteMs | 4101 | 184.793970→204.392540 | +19.598570 / +10.605633% |
| B->C1 | raster.8.firstCompleteMs | 4102 | 269.233317→313.135320 | +43.902003 / +16.306304% |
| C0->C1 | raster.1.appendCompleteMs | 4101 | 35.984269→53.101253 | +17.116984 / +47.567964% |
| C0->C1 | raster.1.appendCompleteMs | 4103 | 34.420208→52.069215 | +17.649007 / +51.275132% |
| C0->C1 | raster.4.appendCompleteMs | 4101 | 95.906129→105.833601 | +9.927472 / +10.351238% |
| C0->C1 | raster.4.forcedFullCompleteMs | 4101 | 181.459630→204.392540 | +22.932910 / +12.638023% |
| C0->C1 | raster.8.firstCompleteMs | 4102 | 255.763821→313.135320 | +57.371499 / +22.431436% |

## 所有原始时间

每格B / C0 / C1，单位ms。包括基线慢值和候选慢值，未筛样或替换。

### 1层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 278.264340 / 284.324156 / 272.396387 | 53.780680 / 35.984269 / 53.101253 | 202.062089 / 202.478931 / 202.583379 |
| 4102 | 271.685274 / 263.446714 / 262.737897 | 53.306613 / 35.168245 / 36.988764 | 202.222805 / 202.581411 / 201.502140 |
| 4103 | 279.880909 / 265.015785 / 277.565111 | 53.342902 / 34.420208 / 52.069215 | 202.054328 / 202.062934 / 201.874915 |
| 4104 | 274.497680 / 281.163139 / 271.276837 | 53.506046 / 36.204486 / 37.054168 | 201.912571 / 202.604235 / 202.166768 |

### 4层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 212.389286 / 227.428811 / 226.027522 | 106.426519 / 95.906129 / 105.833601 | 184.793970 / 181.459630 / 204.392540 |
| 4102 | 233.945584 / 249.498863 / 225.103073 | 106.872619 / 113.551286 / 100.525297 | 209.916615 / 200.744528 / 189.658864 |
| 4103 | 224.813191 / 224.010647 / 240.742498 | 106.638926 / 106.471087 / 98.196186 | 186.075303 / 202.445118 / 189.107254 |
| 4104 | 234.363043 / 242.361170 / 216.963223 | 107.557831 / 99.184953 / 96.489699 | 203.432032 / 194.428309 / 201.083054 |

### 8层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 299.568576 / 292.881215 / 243.429566 | 191.587168 / 127.818574 / 127.265180 | 268.038799 / 247.443093 / 246.472190 |
| 4102 | 269.233317 / 255.763821 / 313.135320 | 186.332677 / 131.982851 / 131.075266 | 257.429746 / 249.823539 / 235.041535 |
| 4103 | 249.167494 / 241.135689 / 255.546308 | 187.759309 / 129.421157 / 129.182119 | 314.636559 / 244.816580 / 245.181694 |
| 4104 | 258.519161 / 279.453825 / 258.086414 | 190.358593 / 126.006128 / 132.428682 | 261.950650 / 241.540629 / 242.897243 |

## 回退信号与归因边界

**不能认定为噪声，也不能认定是新增绘制算法缺陷。** C0/C1绘制源码和工作计数相同，但本轮单层append四对均慢，原规则信号必须保留。C0=45b在上一run的表现不得混入或用来稀释本次分类；host不同、APK重新构建且运行上下文不同。

单层append的阶段原值C0→C1：

| seed | append ms | metadata ms | raster ms | publication ms | 未分段wall ms |
|---:|---:|---:|---:|---:|---:|
| 4101 | 35.984269→53.101253 | 11.149244→14.933214 | 15.652024→16.314942 | 0.512477→0.449049 | 8.670524→21.404048 |
| 4102 | 35.168245→36.988764 | 11.472928→14.407678 | 14.835840→14.914547 | 0.467784→0.483382 | 8.391693→7.183157 |
| 4103 | 34.420208→52.069215 | 11.116322→17.998797 | 15.453343→15.122456 | 0.438079→0.490707 | 7.412464→18.457255 |
| 4104 | 36.204486→37.054168 | 11.244983→14.693193 | 14.642239→14.901802 | 0.465410→0.476440 | 9.851854→6.982733 |

metadata wall四对均增加，但不能称为新增SQL工作/锁等待/CPU回退。4101与4103的append增加17.116984/17.649007ms，其中metadata增加3.783970/6.882475ms；未分段wall增加12.733524/11.044791ms。后者只是单层ready总wall减已有阶段的算术余量，含未单独计时的draw/调度等，不能归因vsync、GC或某队列。没有逐帧线程CPU/GC/JIT/调度证据。多层阶段为各层累加，可交叠，不能同样相加解释端到端wall。

四层forcedFull seed4101与八层first seed4102在B/C0两比较均超带；四层append seed4101仅C0→C1超带。公共probe仅输出append阶段，不可借append的metadata/raster/publication解释first/full异常。

24份visit前后设备环境逐字节相同，API35 AOSP ATD x86_64 KVM/SwiftShader、1920×1200、density240。host为4CPU AMD EPYC7763，上一run为EPYC9V74；不混池绝对时间。seed4102 C1整次visit的host负载1.324→5.134，但该边界覆盖安装、绘制与资料库，不能定位八层first异常。其余host快照及全部环境绑定见JSON。

**没有证据足够支持直接修改生产绘制算法。** 保留已验证的跨revision Kotlin解码复用；不盲目回撤、不增跨帧native缓存，也不更改benchmark。若获授权继续定位，最小诊断应保留相同夹具/顺序/门槛，先给单层append的ready→draw→worker→publish→ready做时序，并用既有默认关闭metadata observer区分admission/preparation、query/digest、hex/result wall/同线程CPU；first/full异常需各自阶段记录，不能借append代替。

## 正确性覆盖及剩余限制

C1 ordinal19–30共12/12原JUnit/host PASS，含7项RasterReadCache、3项TileLocalRedraw、2项StreamedInkRaster。覆盖分散脏区payload筛除、预算、显式append跨revision跳跃、默认拒绝复用、payload/visibility/undo/cut刷新、同revision epoch及批间head/mask失效、闲置层、变换、取消与释放；由冻结源码断言通过证明，不伪造未输出的逐断言telemetry。

完整瓦片复用仍要求整数采样位移；缩放/分数变换需要精确重建，预览不能完成capture。分散脏区筛除没有消除bounding-union metadata扫描。该公共夹具只是同位置局部append，并非连续变换或分散脏区性能测量。原dense30秒门槛和NativeStrokeTileReuse差分本轮未重跑，历史失败不被本次覆盖；超缓存容量、持续手写尾延迟及全部层/变换组合也未验证。

原始来源：[共同probe](../raw/probe-KvmLocalAppendPairProbe.kt)、[源码规格](../raw/frozen-source-spec.json)、[原阈值](../raw/frozen-original-protocol.json)、[运行summary](../raw/summary.json)、[固定派生分析](../analysis/analysis.json)。[本审计JSON](audit-raster.json)包含全部36原值、阶段、环境、APK回执和输入hash。
