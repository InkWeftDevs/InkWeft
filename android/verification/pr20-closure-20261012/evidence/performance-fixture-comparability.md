Performance environment recorder and fixture comparability

The final baseline and current candidate have byte-identical copies of all three performance probes. Their fixtures are constructed independently. Equal probe code and scale do not establish equal database bytes.

| Probe | Fixed fixture content and scale | Random or time-dependent inputs |
| --- | --- | --- |
| LibraryReadPerformanceTest | 500 corpus notebooks × 40 pages = 20,000 searchable corpus pages, fixed titles/body templates and target position; an additional author workspace; 30 direct-search saves and 30 observe/index saves | Random database name, notebook/page IDs and save command IDs. Corpus initial `updatedAt` values use the fixed book index, but author workspace creation and subsequent saves use repository wall-clock timestamps. |
| HistoryCollectionBenchmarkTest | 2,000 historical roots, 4,096 detached source revisions and one current retained source revision; fixed stroke geometry/text and 30 saves in each paused/live scenario | Random database name, workspace/source/card/stroke IDs and rename command IDs. The random stroke ID is part of the encoded source payload. Workspace creation and rename revisions use repository wall-clock timestamps. |
| RasterPerformanceProbe | Deterministic name-based stroke/commit UUIDs from `raster-probe-20261012-$index`; 320 initial strokes plus two separated appends; fixed geometry, sample times 0/10, viewport sequence and 2,400 × 1,800 bitmaps; 14 frame comparisons | Random database name and repository-created workspace/page ID. Workspace creation and the ink commit's notebook `updatedAt` use wall-clock time. `System.nanoTime()` measures work; it does not make database content deterministic. |

Source evidence was read from the paired probe files and these repository methods on both revisions:

- [LibraryReadPerformanceTest.kt](/workspace/InkWeft/android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryReadPerformanceTest.kt:24): random ID helper, corpus insertion, author saves and observe scenario.
- [HistoryCollectionBenchmarkTest.kt](/workspace/InkWeft/android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryCollectionBenchmarkTest.kt:25): random identifiers, encoded source payload and rename calls.
- [RasterPerformanceProbe.kt](/workspace/InkWeft/android/app/src/androidTest/java/org/inkweft/app/RasterPerformanceProbe.kt:22): deterministic stroke IDs, random database/workspace and fixed transformations.
- [WorkspaceRepository.kt](/workspace/InkWeft/android/data-local/src/main/kotlin/org/inkweft/data/WorkspaceRepository.kt:49): `UUID.randomUUID()` and `System.currentTimeMillis()` during creation.
- [NoteRepository.kt](/workspace/InkWeft/android/data-local/src/main/kotlin/org/inkweft/data/NoteRepository.kt:25): wall-clock times in save/rename; baseline equivalents are lines 23/38.
- [InkRepository.kt](/workspace/InkWeft/android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt:588): wall-clock notebook touch on ink commit; baseline equivalent is line 555.

Random keys can change index ordering and storage layout. Timestamps, WAL history and the implementations' different derived data also prevent an unsupported byte-identity claim. No database snapshot checksum or seeded-ID equivalence was established by this task. The raster pixel assertion compares active rendering with a complete cold rebuild within each execution; it is not a cross-APK database byte comparison.

Suggested result wording: “在同一云软件模拟器上，使用字节一致的性能探针，按相同规模与内容规则分别创建合成夹具进行配对测试。UUID 和仓库时间戳不同，未证明数据库字节相同。” Use “same device settings” only after the two phase environment records confirm them, and retain the raw save/progress evidence to assess actual indexing/save overlap.

Recorder: `/workspace/scratch/record-inkweft-performance-environment.py`

Parent-side invocation, after other instrumentation/build work stops and before the named phase:

```sh
python3 /workspace/scratch/record-inkweft-performance-environment.py baseline-final-library /workspace/scratch/pr20-closure-evidence/baseline-final-library-environment.json
python3 /workspace/scratch/record-inkweft-performance-environment.py candidate-v3-library /workspace/scratch/pr20-closure-evidence/candidate-v3-library-environment.json
```

Use a separate new output path for every phase or retry. The script refuses existing output before ADB, restricts writes to scratch, pins the existing local ADB server and `emulator-5554`, and checks the process's SDK path, exact AVD/port, PID and start time. It records API/ABI/build fingerprint/timeout multiplier, display dimensions/density, font scale, animation settings, KVM presence and cgroup CPU/memory observations. It never sets properties/settings, changes privileges or reads environment variables, credentials or notes.

Current host-only inspection found `/proc/PID/exe` unreadable. The recorder marks that field unavailable and uses the readable known emulator argv0, exact AVD/port and process start time; it does not request a permission change. No console-auth token or emulator console command is used.

Successful recording returns 0 and `status: RECORDED`; a failed/incomplete observation returns 2 and writes `status: INCOMPLETE`. Compare `comparison_values` and `comparison_sha256`; these include emulator identity and settings, excluding timestamps and dynamic CPU/memory counters. Inspect those dynamic counters separately. Matching settings do not prove equal scheduler load, cache state, settings throughout a later run or database bytes. Literal `null` settings are retained without guessing their effective defaults. TCG/SwiftShader measurements remain software-emulator measurements.

Preparation validation is recorded in `performance-environment-recorder-review.json`: Python AST, synthetic display parsing/comparison-key checks, and overwrite refusal before runtime discovery. The recording routine and ADB were not executed by this preparation task; no new device environment or performance result is claimed.
