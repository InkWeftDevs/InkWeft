# Search causal evidence analysis

Status: RELATIONS_CHECKED_CAUSAL_ONLY. Native acceptance thresholds are unchanged.

## Input binding

- warm: `/workspace/scratch/pr20-causal-execution-20261014/extracted/method-09-C1-records.json`, SHA-256 `fb5aaf476a6d114aacbf3c97d36a69339c05ef3ef2f4c3e1487d22ed31b1ae89`, 1 records.
- saves: `/workspace/scratch/pr20-causal-execution-20261014/extracted/method-10-C1-records.json`, SHA-256 `1bb57986e65d56c4e27ccd65466b0793f7a364e638c93b17b1b00b40bb0e8bd6`, 60 records.
- unicode: `/workspace/scratch/pr20-causal-execution-20261014/extracted/method-08-C1-records.json`, SHA-256 `98349570bba15821033dcbf9bdb223584d90afe2141a6a38ac5aefd7ae483c82`, 1 records.
- Scoped extraction: `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`; complete selected bindings: True.
- Expected source/run binding: `{"job_id": "115689021611", "readiness_git": "b2e1d9f9de66a26f5a4b122ea6754d77198005f8", "run_id": "38514139119", "source_commit": "78a5babeaeb92292026423222d5e08635989198b", "workflow_sha": "d4a107dd84c6e309b5dbbbfb3a57d69371024408"}`.

## Warm UDF attribution

SQL equality after diagnostic function renaming: **True**. Actual plan equality: **True**.

| Read | Field | Calls | UTF-16 | getString wall ms | getString CPU ms | contains wall ms | contains CPU ms |
|---|---|---:|---:|---:|---:|---:|---:|
| 0 | title | 501 | 4017 | 0.970798 | 0.667859 | 1.796961 | 1.611924 |
| 0 | body | 501 | 10010025 | 29.164671 | 26.223482 | 577.897724 | 572.012532 |
| 0 | tags | 501 | 0 | 1.228503 | 0.971108 | 1.625940 | 1.442922 |
| 0 | source | 1 | 11 | 0.005179 | 0.003818 | 0.007714 | 0.007323 |
| 1 | title | 501 | 4017 | 0.835835 | 0.624598 | 1.721428 | 1.541495 |
| 1 | body | 501 | 10010025 | 28.178742 | 25.801384 | 569.953005 | 564.928443 |
| 1 | tags | 501 | 0 | 1.094158 | 0.862456 | 1.634453 | 1.458159 |
| 1 | source | 1 | 11 | 0.004749 | 0.003446 | 0.006713 | 0.006322 |
| 2 | title | 501 | 4017 | 1.120275 | 0.661503 | 1.831456 | 1.634416 |
| 2 | body | 501 | 10010025 | 28.342869 | 25.737401 | 579.330776 | 572.703112 |
| 2 | tags | 501 | 0 | 1.128768 | 0.901160 | 1.661917 | 1.480393 |
| 2 | source | 1 | 11 | 0.005360 | 0.003577 | 0.007895 | 0.007464 |

| Read | Whole read ms | Page wall ms | Page CPU ms | Page wall residual ms | Page CPU residual ms | Whole read minus all callbacks ms |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 646.913660 | 632.945776 | 625.246933 | 20.261179 | 22.317106 | 34.216170 |
| 1 | 637.942320 | 623.475524 | 616.854709 | 20.057903 | 21.638174 | 34.513237 |
| 2 | 647.942506 | 634.558028 | 625.768858 | 21.141967 | 22.650873 | 34.513190 |

UDF-off planned read wall times (including EXPLAIN): before 665.972616 ms; after 644.821963 ms. Their SQL and all actual plans remain in analysis.json.

Reference preload wall: 107.750533 ms. Each reference match interval follows preload and excludes SQLite/FTS/sorting.

- Reference 0: wall 566.085160 ms; CPU 563.612656 ms; matches 1.
- Reference 1: wall 588.817497 ms; CPU 576.886476 ms; matches 1.
- Reference 2: wall 569.420390 ms; CPU 566.782013 ms; matches 1.

## All 12 save cases

| Block | Mode | Readiness | Saves | p50 ms | p95 ms | max ms | Index rows after saves | Source checks after saves |
|---:|---|---|---:|---:|---:|---:|---:|---:|
| 0 | IDLE | fixture-ready | 30 | 2.550575 | 5.190518 | 5.382075 | 0 | 0 |
| 0 | TRACKER | tracker-registered | 30 | 2.602983 | 3.086596 | 4.078590 | 0 | 0 |
| 0 | INDEX_ONLY | first-index-commit | 30 | 2.758493 | 9.267573 | 11.525792 | 64 | 0 |
| 0 | INDEX_TRACKER | first-index-commit | 30 | 3.269979 | 10.979550 | 21.044074 | 128 | 0 |
| 0 | READS_ONLY | first-source-check | 30 | 3.822479 | 7.099947 | 7.482048 | 0 | 68 |
| 0 | FULL | first-index-commit | 30 | 5.926872 | 15.977829 | 31.924389 | 128 | 191 |
| 1 | FULL | first-index-commit | 30 | 5.606635 | 15.387647 | 41.530293 | 128 | 275 |
| 1 | READS_ONLY | first-source-check | 30 | 2.855565 | 9.483978 | 10.379310 | 0 | 145 |
| 1 | INDEX_TRACKER | first-index-commit | 30 | 2.666992 | 7.250645 | 10.564366 | 64 | 0 |
| 1 | INDEX_ONLY | first-index-commit | 30 | 2.553644 | 7.144086 | 11.612343 | 64 | 0 |
| 1 | TRACKER | tracker-registered | 30 | 2.096276 | 3.049226 | 5.359753 | 0 | 0 |
| 1 | IDLE | fixture-ready | 30 | 1.751062 | 2.207735 | 2.673494 | 0 | 0 |

## Phase and overlap evidence

Every save phase, endpoint thread ID, valid same-thread CPU increment, individual read/index interval, cancellation stage and original event is retained in analysis.json and the CSVs. Temporal sums below use unions, so parallel intervals are not double-counted.

### Block 0 IDLE

Request-to-entry p50/p95/max: 0.387664/0.780595/1.079985 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.149738 | 2.626406 | 3.722013 | 30 |
| enteredThroughReturn | 30 | 2.090595 | 3.881774 | 4.673581 | 0 |
| entryToHeadRead | 30 | 0.802389 | 2.002721 | 2.931937 | 30 |
| headReadToWrite | 30 | 0.159538 | 0.540510 | 0.882950 | 30 |
| headWriteToRevision | 30 | 0.083917 | 0.144751 | 0.180247 | 30 |
| receiptToReturn | 30 | 0.888609 | 1.181204 | 1.255368 | 0 |
| requestToEntry | 30 | 0.387664 | 0.780595 | 1.079985 | 0 |
| revisionToReceipt | 30 | 0.063589 | 0.151092 | 0.187070 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 0 TRACKER

Request-to-entry p50/p95/max: 0.397483/0.681683/0.697534 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.116787 | 1.509741 | 1.572508 | 30 |
| enteredThroughReturn | 30 | 1.994507 | 2.525058 | 3.599314 | 0 |
| entryToHeadRead | 30 | 0.747216 | 1.063566 | 1.075188 | 30 |
| headReadToWrite | 30 | 0.171761 | 0.272258 | 0.311532 | 30 |
| headWriteToRevision | 30 | 0.078828 | 0.135533 | 0.175498 | 30 |
| receiptToReturn | 30 | 0.903799 | 1.083334 | 2.585842 | 0 |
| requestToEntry | 30 | 0.397483 | 0.681683 | 0.697534 | 0 |
| revisionToReceipt | 30 | 0.062357 | 0.154148 | 0.207117 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 0 INDEX_ONLY

Request-to-entry p50/p95/max: 0.389337/0.684159/0.762144 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.105225 | 3.271542 | 3.697537 | 30 |
| enteredThroughReturn | 30 | 2.305217 | 8.874949 | 11.028504 | 0 |
| entryToHeadRead | 30 | 0.738019 | 1.152363 | 1.395307 | 30 |
| headReadToWrite | 30 | 0.190817 | 2.407026 | 2.570271 | 30 |
| headWriteToRevision | 30 | 0.079799 | 0.144520 | 0.158395 | 30 |
| receiptToReturn | 30 | 1.087622 | 7.827122 | 9.201040 | 0 |
| requestToEntry | 30 | 0.389337 | 0.684159 | 0.762144 | 0 |
| revisionToReceipt | 30 | 0.062246 | 0.182851 | 0.188562 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 0.0, "index.enteredBody": 0.0, "index.enteredThroughReturn": 0.0, "index.prepare": 0.0, "index.requestToEntry": 0.0}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 0 INDEX_TRACKER

Request-to-entry p50/p95/max: 0.418302/1.261216/18.947117 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=97.573304, indexRequestToEntry=1.243663, indexEnteredBody=19.092428, indexEnteredThroughReturn=21.008518.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.146742 | 3.868285 | 4.876921 | 30 |
| enteredThroughReturn | 30 | 2.682511 | 8.475754 | 10.293648 | 0 |
| entryToHeadRead | 30 | 0.749932 | 0.997192 | 3.278875 | 30 |
| headReadToWrite | 30 | 0.198571 | 0.560928 | 2.999732 | 30 |
| headWriteToRevision | 30 | 0.083817 | 0.517136 | 3.438063 | 30 |
| receiptToReturn | 30 | 1.317882 | 6.692192 | 7.354599 | 0 |
| requestToEntry | 30 | 0.418302 | 1.261216 | 18.947117 | 0 |
| revisionToReceipt | 30 | 0.063368 | 0.131977 | 0.169577 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 1.91609, "index.enteredBody": 19.092428, "index.enteredThroughReturn": 21.008518, "index.prepare": 97.573304, "index.requestToEntry": 1.243663}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 0 READS_ONLY

Request-to-entry p50/p95/max: 0.457295/1.587827/2.647959 ms. Readers started/completed/cancelled/no terminal: 21/0/21/7.
Save-window overlap ms: readerIO=109.206531, readerSnapshot=89.754353, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{"connection-open": 2, "drafts-ready": 6, "page-hits-start": 1, "scope-end": 11, "scope-start": 1}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.287415 | 3.883575 | 4.302708 | 30 |
| enteredThroughReturn | 30 | 3.299484 | 5.323796 | 7.106636 | 0 |
| entryToHeadRead | 30 | 0.746966 | 2.455820 | 3.987920 | 30 |
| headReadToWrite | 30 | 0.188021 | 0.547383 | 2.231239 | 30 |
| headWriteToRevision | 30 | 0.084358 | 0.587477 | 0.870746 | 30 |
| receiptToReturn | 30 | 1.660791 | 3.223061 | 4.087225 | 0 |
| requestToEntry | 30 | 0.457295 | 1.587827 | 2.647959 | 0 |
| revisionToReceipt | 30 | 0.065862 | 0.834679 | 2.922589 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"reader.dispatch": 3.92162, "reader.drafts": 22.084163, "reader.hitTables": 0.0, "reader.ioRead": 109.206531, "reader.open": 6.443827, "reader.pageHits": 0.0, "reader.registerAndBegin": 2.439568, "reader.scope": 46.202946, "reader.snapshot": 89.754353}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 0 FULL

Request-to-entry p50/p95/max: 0.475846/2.109000/30.175421 ms. Readers started/completed/cancelled/no terminal: 23/0/23/7.
Save-window overlap ms: readerIO=183.303480, readerSnapshot=161.211122, indexPrepare=110.243971, indexRequestToEntry=7.160607, indexEnteredBody=28.211965, indexEnteredThroughReturn=31.433966.
Last work stage before reader cancellation: `{"connection-open": 3, "drafts-ready": 4, "page-hits-start": 4, "scope-end": 11, "scope-start": 1}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.404564 | 4.799376 | 5.093916 | 30 |
| enteredThroughReturn | 30 | 5.129723 | 10.827878 | 14.753063 | 0 |
| entryToHeadRead | 30 | 0.778093 | 3.787426 | 3.975898 | 30 |
| headReadToWrite | 30 | 0.212717 | 2.512234 | 2.638379 | 30 |
| headWriteToRevision | 30 | 0.083657 | 1.039031 | 1.750299 | 30 |
| receiptToReturn | 30 | 3.042345 | 7.724019 | 12.261228 | 0 |
| requestToEntry | 30 | 0.475846 | 2.109000 | 30.175421 | 0 |
| revisionToReceipt | 30 | 0.063539 | 0.787030 | 1.531421 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 3.222001, "index.enteredBody": 28.211965, "index.enteredThroughReturn": 31.433966, "index.prepare": 110.243971, "index.requestToEntry": 7.160607, "reader.dispatch": 7.818688, "reader.drafts": 25.856585, "reader.hitTables": 2.309165, "reader.ioRead": 183.30348, "reader.open": 5.855542, "reader.pageHits": 0.0, "reader.registerAndBegin": 2.394262, "reader.scope": 73.293822, "reader.snapshot": 161.211122}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 FULL

Request-to-entry p50/p95/max: 0.491118/6.070280/38.057174 ms. Readers started/completed/cancelled/no terminal: 23/0/23/7.
Save-window overlap ms: readerIO=210.381350, readerSnapshot=181.279315, indexPrepare=111.733049, indexRequestToEntry=7.237568, indexEnteredBody=28.414072, indexEnteredThroughReturn=36.763864.
Last work stage before reader cancellation: `{"connection-open": 3, "drafts-ready": 5, "hits-ready": 2, "page-hits-start": 4, "scope-end": 8, "scope-start": 1}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.171769 | 4.340188 | 7.563844 | 30 |
| enteredThroughReturn | 30 | 4.967440 | 10.778777 | 11.582939 | 0 |
| entryToHeadRead | 30 | 0.730204 | 3.313600 | 7.189454 | 30 |
| headReadToWrite | 30 | 0.200804 | 0.555197 | 2.952686 | 30 |
| headWriteToRevision | 30 | 0.083968 | 1.351758 | 2.589197 | 30 |
| receiptToReturn | 30 | 2.681860 | 9.413977 | 9.445716 | 0 |
| requestToEntry | 30 | 0.491118 | 6.070280 | 38.057174 | 0 |
| revisionToReceipt | 30 | 0.068689 | 0.184826 | 3.359125 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 8.349792, "index.enteredBody": 28.414072, "index.enteredThroughReturn": 36.763864, "index.prepare": 111.733049, "index.requestToEntry": 7.237568, "reader.dispatch": 3.61196, "reader.drafts": 36.688309, "reader.hitTables": 4.563157, "reader.ioRead": 210.38135, "reader.open": 10.055173, "reader.pageHits": 0.0, "reader.registerAndBegin": 3.147283, "reader.scope": 68.747477, "reader.snapshot": 181.279315}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 READS_ONLY

Request-to-entry p50/p95/max: 0.429182/1.026066/1.485856 ms. Readers started/completed/cancelled/no terminal: 23/0/23/5.
Save-window overlap ms: readerIO=95.135310, readerSnapshot=82.150418, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{"connection-open": 2, "drafts-ready": 7, "hits-ready": 1, "page-hits-start": 2, "scope-end": 10, "scope-start": 1}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.027600 | 2.374136 | 3.551365 | 30 |
| enteredThroughReturn | 30 | 2.319823 | 8.925965 | 9.966710 | 0 |
| entryToHeadRead | 30 | 0.662016 | 2.042416 | 3.234042 | 30 |
| headReadToWrite | 30 | 0.181560 | 0.370161 | 0.544036 | 30 |
| headWriteToRevision | 30 | 0.075310 | 0.786720 | 0.861479 | 30 |
| receiptToReturn | 30 | 1.292785 | 5.374600 | 8.308772 | 0 |
| requestToEntry | 30 | 0.429182 | 1.026066 | 1.485856 | 0 |
| revisionToReceipt | 30 | 0.062967 | 0.143518 | 0.160960 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"reader.dispatch": 2.233059, "reader.drafts": 25.084901, "reader.hitTables": 1.138346, "reader.ioRead": 95.13531, "reader.open": 4.074545, "reader.pageHits": 0.0, "reader.registerAndBegin": 2.368665, "reader.scope": 35.186133, "reader.snapshot": 82.150418}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 INDEX_TRACKER

Request-to-entry p50/p95/max: 0.363429/0.760170/0.760761 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.012031 | 1.308194 | 1.579690 | 30 |
| enteredThroughReturn | 30 | 2.285961 | 6.854366 | 9.766946 | 0 |
| entryToHeadRead | 30 | 0.662999 | 1.024935 | 1.065671 | 30 |
| headReadToWrite | 30 | 0.178494 | 0.273381 | 0.435423 | 30 |
| headWriteToRevision | 30 | 0.073217 | 0.213048 | 0.216776 | 30 |
| receiptToReturn | 30 | 1.281985 | 5.825344 | 8.754915 | 0 |
| requestToEntry | 30 | 0.363429 | 0.760170 | 0.760761 | 0 |
| revisionToReceipt | 30 | 0.060322 | 0.120805 | 0.230120 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 0.0, "index.enteredBody": 0.0, "index.enteredThroughReturn": 0.0, "index.prepare": 0.0, "index.requestToEntry": 0.0}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 INDEX_ONLY

Request-to-entry p50/p95/max: 0.316822/0.461702/0.469508 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 1.008223 | 2.454075 | 4.457647 | 30 |
| enteredThroughReturn | 30 | 2.155446 | 6.840679 | 11.343802 | 0 |
| entryToHeadRead | 30 | 0.669881 | 1.012130 | 1.955474 | 30 |
| headReadToWrite | 30 | 0.171861 | 0.365101 | 3.541376 | 30 |
| headWriteToRevision | 30 | 0.074749 | 0.124303 | 0.384919 | 30 |
| receiptToReturn | 30 | 1.126404 | 4.599733 | 10.281838 | 0 |
| requestToEntry | 30 | 0.316822 | 0.461702 | 0.469508 | 0 |
| revisionToReceipt | 30 | 0.060202 | 0.128410 | 0.191497 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{"index.bodyToReturn": 0.0, "index.enteredBody": 0.0, "index.enteredThroughReturn": 0.0, "index.prepare": 0.0, "index.requestToEntry": 0.0}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 TRACKER

Request-to-entry p50/p95/max: 0.375211/0.659843/0.684900 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 0.905110 | 1.295361 | 1.988715 | 30 |
| enteredThroughReturn | 30 | 1.664973 | 2.709061 | 5.037621 | 0 |
| entryToHeadRead | 30 | 0.617723 | 0.874113 | 0.909379 | 30 |
| headReadToWrite | 30 | 0.140954 | 0.202539 | 0.245349 | 30 |
| headWriteToRevision | 30 | 0.067737 | 0.132407 | 0.833006 | 30 |
| receiptToReturn | 30 | 0.764460 | 1.039532 | 4.050707 | 0 |
| requestToEntry | 30 | 0.375211 | 0.659843 | 0.684900 | 0 |
| revisionToReceipt | 30 | 0.057838 | 0.147847 | 0.199633 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

### Block 1 IDLE

Request-to-entry p50/p95/max: 0.261288/0.406559/0.567040 ms. Readers started/completed/cancelled/no terminal: 0/0/0/0.
Save-window overlap ms: readerIO=0.000000, readerSnapshot=0.000000, indexPrepare=0.000000, indexRequestToEntry=0.000000, indexEnteredBody=0.000000, indexEnteredThroughReturn=0.000000.
Last work stage before reader cancellation: `{}`.

Save phase wall distributions (same-thread CPU remains a non-exclusive counter):

| Phase | n | p50 ms | p95 ms | max ms | Valid same-thread CPU pairs |
|---|---:|---:|---:|---:|---:|
| enteredBody | 30 | 0.891426 | 1.081090 | 1.218887 | 30 |
| enteredThroughReturn | 30 | 1.510032 | 1.906301 | 2.058175 | 0 |
| entryToHeadRead | 30 | 0.589080 | 0.726878 | 0.803421 | 30 |
| headReadToWrite | 30 | 0.139260 | 0.223087 | 0.256359 | 30 |
| headWriteToRevision | 30 | 0.072996 | 0.125103 | 0.160260 | 30 |
| receiptToReturn | 30 | 0.545780 | 0.839288 | 0.849247 | 0 |
| requestToEntry | 30 | 0.261288 | 0.406559 | 0.567040 | 0 |
| revisionToReceipt | 30 | 0.062486 | 0.117019 | 0.169396 | 30 |

Read/index phase unions clipped to the actual save window (ms): `{}`.
All pre-readiness and cleanup intervals remain in JSON; the phase wall summaries there are not restricted to the save window.

## Interpretation limits

- Causal-only, one instrumented run. These samples do not replace the frozen public probe or any original gate.
- All 30 saves per case remain in original order. p50 uses ceil(.5*n)-1; p95 uses ceil(.95*n)-1; no trimming or pooling across blocks.
- Mode differences are not additive causal effects: readiness differs (fixture, tracker registration, first source check or first index commit); background progress/cache/scheduling differs.
- Equal logical fixture hashes within a block do not imply equal physical database/cache/WAL state at save start.
- requestToEntry includes scheduling, Room serialization and transaction begin. It is not a direct SQLite lock-wait measurement.
- Same-thread CPU deltas are thread counter increments, not exclusive operation CPU across coroutine suspension. Cross-thread or invalid-counter CPU deltas remain null.
- Overlap shows co-occurrence, not causation. Index enteredBody is inside the transaction; enteredThroughReturn also includes transaction exit and possible coroutine resumption.
- A committed-rows callback proves a completed batch, not its writer hold interval. Counter changes are not substitutes for stage timestamps.
- Incomplete stage pairs are retained and never extended to the save end. Complete-interval overlap can undercount activity whose endpoint was not observed.
- READS_ONLY does not inject diagnostics into LibrarySearchObservation, so absent observe-invalidation/cancel events cannot be compared with FULL as absent invalidations.
- Diagnostic callbacks/clocks/queues perturb both save and warm timings. UDF-off planned reads include EXPLAIN and are not the public warm benchmark.
- Page residual subtracts only title/body/tags callbacks. Source callbacks occur before page SQL and are excluded from that subtraction.
- Args.getString timing starts inside the Java callback; it omits earlier SQLite argument evaluation/materialization. Residual time is not exclusively JNI, I/O or locks.
- Kotlin reference excludes preload, FTS, SQL, sorting and result construction; its wall/CPU is not a complete library read. Result identity is asserted in the instrumented test, not reconstructed from IDs in logs.
- Artifact/source/APK/environment attestation remains external. This script records input byte hashes and checks relations but does not recreate the controller verification.

## Relation issues

No relation issues detected. This is not a performance acceptance verdict.
