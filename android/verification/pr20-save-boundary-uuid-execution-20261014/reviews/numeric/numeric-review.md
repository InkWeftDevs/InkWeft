# Independent numeric review — run 38543698114

PASS for evidence arithmetic and scoped diagnostic interpretation. No blocking discrepancy was found. This review does **not** close the original four-pair performance signals or establish a production improvement.

The existing successful native run was independently checked locally; no new Android execution occurred. The prior whole-run review established all 10 methods PASS, 90 complete saves, source identity and terminal/cleanup closure. This review reconstructed all 90 saves and 1,080 disjoint segments directly from its verified original records, without importing the frozen analyzer or derived-arithmetic script. All wall endpoints, 900 same-thread CPU deltas, 180 required cross-thread nulls, outlier orders, duration conservation, read-phase statistics and 90 per-save overlap sets match. Raw array order remains intact. A separate signed-endpoint sweep reproduces interval unions.

| Arm | p50 / p95 / max (ms) | Actual sample indices |
|---|---|---|
| IDLE | 2.035774 / 4.737515 / 5.314565 | 18 / 20 / 0 |
| DIRECT | 2.268760 / 9.392518 / 9.446588 | 10 / 8 / 25 |
| OBSERVE | 6.324345 / 11.146675 / 23.839401 | 20 / 4 / 17 |

These are nearest ranks at zero-based positions 14/28/29 of 30, not an interpolated median. The 12 segments cover request→return; before-request and after-return timer portions are required to reconstruct the complete save. Segment quantiles are not additive.

The requested sample checks agree:

- OBSERVE maximum sample 17 takes 23.839401 ms. Task 214 waits 21.556330 ms before worker 44 starts. Its queue overlaps the preceding task 213 on that worker for 21.523241 ms; task 213 runs 21.543560 ms with 16.936590 ms same-thread CPU, and its index identity is associated through unique index-owner events within its task/thread interval. The gap from its exit to task 214 entry is 0.033089 ms. This is the only observed author queue overlapping an identified index writer. It supports a focused candidate experiment on that maximum exposure; it does not explain the median or p95.
- OBSERVE p50 sample 20 is 6.324345 ms, including SQLite END 4.924512 ms / worker CPU 0.119026 ms. Read 20's snapshot spans the complete save; index overlap is zero. Reader causation is not established.
- OBSERVE p95 sample 4 is 11.146675 ms, including body 4.862550 ms / CPU 1.126807 ms and SQLite END 2.493974 ms / CPU 0.096283 ms. Read IDs 4 and 5 overlap 10.579184 ms of its lifetime and their snapshots overlap 8.470251 ms. Index overlap is zero.
- DIRECT p95 sample 8 is 9.392518 ms, including SQLite END 7.674103 ms / worker CPU 0.121790 ms on worker 45. Its writer task is 46, selected Room transaction 47 and SQLite transaction 48. This locates the long call; wall minus CPU does not identify a particular wait, fsync, checkpoint or scheduler cause.

| Arm | Save window (ms) | Read lifecycle union (ms) | Open snapshot union (ms) | Index task union (ms) |
|---|---:|---:|---:|---:|
| IDLE | 68.360126 | 0 | 0 | 0 |
| DIRECT | 105.442867 | 105.442867 | 105.442867 | 0 |
| OBSERVE | 218.188628 | 195.717059 | 174.399279 | 21.543560 |

The unions include gaps between saves and measure exposure, not additive overhead. DIRECT's completed page-hit scan contains the whole save window. OBSERVE has 26 IO-started reads, all cancelled; 25 snapshots, 23 completed scope phases and nine started page-hit queries with no completion. The 29 scheduled IDs comprise one cancelled debounce, two requested reads cancelled before IO, and those 26 started reads. The `cancelled_before_io_start=3` aggregate includes the debounce-only ID; it is not three requested-read cancellations. Missing phase endpoints remain censored, not fabricated from cleanup. The one IO lifecycle crossing threads has null CPU.

No causal production choice follows for the overall concurrent-save median/p95. Off-CPU/native-operation correlation is still needed for long END/body intervals. This is one seed with hooks enabled, without observer-off overhead calibration; IDLE has a separate fixture/clock origin and DIRECT→paused-save→OBSERVE is continuous. No arm subtraction or prior-run pooling is justified.

Evidence binding is in `numeric-review.json`; reproducible checks are `check_numeric.py` and `check_read_overlap.py`, with full outputs `numeric-checks.json` and `read-overlap-checks.json`. `SHA256SUMS` binds this independent review. Official raw and all frozen source/controller/analyzer files were read only. No network, CI, build, push or native task was performed.

Input hashes:

- Official job log: `45d6579ec88cdff12dbd94393121a04684dce7595713947e6663af0e6b45d68c`
- Original method-10 records: `61eb1e524e001498e9a3ceaa59a85529439610f590e329052bc0b75846da716d`
- Original method-10 markers: `0ef0092547196143cb9a28e185052d29e63d5e2e25ea0ab5aaab60cffc702e22`
- Frozen analyzer: `fd1c22004bb91e7e6673a6ac59eb0f2c2413c9f8ef454a00b2950c0581bbd1b7`
- Numeric analysis: `05f728fae9fff2df0fc217ff6e3cb77a6cc69d0edae9bfa07853b7dc1c91f265`
- Derived JSON: `6e703793fcf44a5a6685dd55f8b02ea45c44ff6386c433d34bb71b6f2dabc1ce`
