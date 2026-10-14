# New default-off block: raw ledger with retained failure

**Fixed C/B/B/C block: 8 tests executed, 7 passed, 1 failed. Normal paired summary is INCOMPLETE.** The unchanged strict parser returned exit 2 (`PASS receipt missing` for C2); its stdout, stderr and execution receipt are retained. It wrote no completed pair summary. No retry replaces C2.

| Run | Class status | Executed / passed | Receipt seconds | Pan/zoom evidence | Local 1/4/8 evidence |
|---|---|---:|---:|---|---|
| C1 | PASS | 2 / 2 | 209.018 | present | present |
| B1 | PASS | 2 / 2 | 199.640 | present | present |
| B2 | PASS | 2 / 2 | 241.328 | present | present |
| C2 | FAIL | 2 / 1 | 242.363 | absent; 30s timeout | present, isolated after failed prelude |

C2 failed `integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture` with `TimeoutCancellationException: Timed out waiting for 30000 ms`. The shared ready helper serves initial, pan/cold and zoom/cold frames; this generic stack does not identify the specific wait or intermediate assertions reached. Final pan/zoom evidence is absent. Its local-append method then completed, but those numbers cannot be pooled as a normal warmed-up full-class sample.

## Every local raw case

Milliseconds are shown to three decimals for readability; the JSON preserves every original numeric value and raw field. C2 rows are **isolated post-failure observations**, not accepted pair samples. No aggregate or performance comparison is computed.

| Run | Layers | First | Append | Forced full | Metadata sum | Raster sum | Publication sum |
|---|---:|---:|---:|---:|---:|---:|---:|
| C1 | 1 | 7463.370 | 1591.228 | 6640.944 | 474.042 | 680.071 | 28.626 |
| C1 | 4 | 8297.231 | 4473.546 | 8122.501 | 1207.911 | 2262.694 | 2957.037 |
| C1 | 8 | 11602.076 | 8319.367 | 12016.281 | 1898.380 | 1784.697 | 17661.030 |
| B1 | 1 | 7218.785 | 1571.931 | 7238.649 | 775.465 | 554.967 | 9.564 |
| B1 | 4 | 8214.462 | 4739.357 | 8538.908 | 1889.839 | 2741.201 | 2441.382 |
| B1 | 8 | 11834.877 | 8483.347 | 11544.331 | 2964.245 | 1746.493 | 18532.630 |
| B2 | 1 | 8776.214 | 1994.672 | 7021.697 | 1158.417 | 637.634 | 16.840 |
| B2 | 4 | 8837.312 | 5738.389 | 9370.744 | 2182.450 | 2108.453 | 4164.631 |
| B2 | 8 | 14201.018 | 13727.564 | 20217.502 | 5025.752 | 3264.261 | 29038.533 |
| C2 | 1 | 8970.178 | 2047.746 | 6876.270 | 629.920 | 825.139 | 36.282 |
| C2 | 4 | 10128.511 | 5128.054 | 9367.278 | 1243.459 | 2046.764 | 4245.365 |
| C2 | 8 | 13177.205 | 10283.711 | 12119.980 | 2198.866 | 2456.479 | 23210.169 |

| Run | Layers | Decode local / full | Rebuilt local / full | Native builds | Pixels equal | Bitmap bytes after destructive update |
|---|---:|---:|---:|---:|---|---:|
| C1 | 1 | 13 / 321 | 1 / 24 | 13 | true | 14371200 |
| C1 | 4 | 21 / 321 | 1 / 24 | 21 | true | 12147408 |
| C1 | 8 | 11 / 321 | 1 / 48 | 11 | true | 24294816 |
| B1 | 1 | 13 / 321 | 1 / 24 | null (absent) | true | 14371200 |
| B1 | 4 | 21 / 321 | 1 / 24 | null (absent) | true | 12147408 |
| B1 | 8 | 11 / 321 | 1 / 48 | null (absent) | true | 24294816 |
| B2 | 1 | 13 / 321 | 1 / 24 | null (absent) | true | 14371200 |
| B2 | 4 | 21 / 321 | 1 / 24 | null (absent) | true | 12147408 |
| B2 | 8 | 11 / 321 | 1 / 48 | null (absent) | true | 24294816 |
| C2 | 1 | 13 / 321 | 1 / 24 | 13 | true | 14371200 |
| C2 | 4 | 21 / 321 | 1 / 24 | 21 | true | 12147408 |
| C2 | 8 | 11 / 321 | 1 / 48 | 11 | true | 24294816 |

Every receipt has diagnostics=false and every local evidence row omits diagnostic arrays. Eight before/after configuration receipts agree on `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`; that does not establish equal transient execution conditions or explain the failure.

## Cleanup and remaining gates

The separate process review confirms the runner ended and `pidof` found neither app nor appTest process, with no force-stop requested. This proves process absence only. It does **not** demonstrate normal in-process strict worker/geometry release or make the timeout a passing test.

The parent will continue planned FrameProbe runs in new processes. Those can add independent evidence, but the failed original pan/zoom gate stays open alongside the original dense 30-second gate. Preserve all prior v4/final and diagnostic samples separately; do not replace C2 with a favorable retry or attribute the timeout solely to software emulation.

Exact raw values, statuses, parser output/receipt, per-run environment paths, source/APK receipt references and log hashes are in `candidate-raster-hex-v1-off-pair-raw-ledger.json`. This ledger makes no unconditional performance conclusion. No source or parser change, build, device command, large hash or benchmark rerun was performed.
