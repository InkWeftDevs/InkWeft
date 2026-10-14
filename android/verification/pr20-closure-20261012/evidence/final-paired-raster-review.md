# Final paired raster and tile review

The final baseline and candidate-v4 each pass `RasterPerformanceProbe` (1 test)
and `TileLocalRedrawTest` (2 tests). All recorded bitmap comparisons pass within
each version. Payload reuse and separated-region reads improve the measured work
counts, and this run's transform/sparse timings improve; **the initial frame and
all three 1/4/8-layer local append samples are slower in the candidate**. This is
not a uniform raster performance improvement or closure of the original dense
30 s acceptance gate.

This independent review reads existing receipts and source hashes. It runs no
build, native test, ADB, commit or push. Exact raw records, calculations and file
hashes are preserved in `final-paired-raster-review.json`.

## Identity and comparability checks

The baseline is fixed `a128bb07e34cb82f00eeafc8e62a47bf7b95b128` with exactly the
common `PreservingOpenHelperFactory.kt` and three new performance probe paths
allowed by `baseline-final-provenance.json`. It is **not an untouched a128 APK**.
Candidate-v4's 744 frozen Android files bind to local source commit
`c60fba7455cfc0b919d6f9c2d3b06f17ec25713d` in
`final-committed-source-binding.json`.

| Artifact / manifest | Final baseline SHA-256 | Candidate-v4 SHA-256 |
| --- | --- | --- |
| Android source SHA manifest | `386046c9c28eb27d33fd3977fb2baa06c94a855511f024f4a83188a4c304bbfd` | `8285c54019d0a4409a6dbf6f5d36e87298b5be9b6d47f93801ea9123bb7d859f` |
| Main APK | `c820103a3dae3b52f7a6d30aa345112c5c71c47f00ad89900eb6fe8ec5dd70fe` | `79fe8a40024d0cbd7283145f52febc3d54a6312ec231acea11182822e8f6aaf4` |
| App test APK | `d706b3b015d6111e7721f6881405726fe97da66c6799a456531b7bccf50909cd` | `37dc4f858fdf13fde56fd514515f6e043356e482db0a8a0bc7339fce4504c5b2` |

Both builds record unchanged frozen source and successful exit. Each APK's recorded
hash matches its successful installation receipt and the post-performance artifact
continuity receipt. This review also independently checks five relevant current
source hashes per tree against their frozen manifests.

`RasterPerformanceProbe.kt` and the common database factory are byte-identical.
The whole `TileLocalRedrawTest.kt` file is not: candidate adds only two assertions
after the pan/zoom method's final zoom (zero payload decodes and positive cache
reuse). Removing exactly those lines produces the baseline file. Its local-append
method, fixtures and timing path are identical; neither its timing gates nor pixel
assertions are weakened. Missing baseline `nativeStrokeBuilds`, `reusedStrokes` and
`geometryBytes` stay **null**, not zero.

The four before/after environment records have the same comparison SHA-256,
`425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`:
the same API 35 x86_64 emulator process, TCG/no KVM, SwiftShader, two guest cores,
2 GiB RAM, display/settings and host limits. Equal recorded settings do not prove
equal scheduler load or cache state. Candidate-v4 ran first and final baseline
later, serially; these are not randomized repeated trials or physical-device
latencies. Both raster probes directly report writer SQLite `page_size=1024`,
`auto_vacuum=1` (FULL), `journal_mode=wal`, `synchronous=2`. The older
4096/INCREMENTAL receipts are not pooled into this pair.

## Continuous transforms and sparse append

Each side has 14 frame comparisons: initial, 12 transforms and separated append.
All 28 recorded `pixelsEqual` values are true. Each compares its active frame with
a fresh cold rebuild using **that same version**. Cross-version image hashes were
not exported, so this is not a direct assertion of baseline pixels equaling
candidate pixels.

| Metric | Final baseline | Candidate-v4 | Observation |
| --- | ---: | ---: | --- |
| Initial complete frame | 12,140.888 ms | 12,580.622 ms | 3.62% slower |
| 12-transform mixed median complete time | 7,098.962 ms | 5,415.061 ms | 23.72% lower in this sequence |
| 12-transform newly decoded payload strokes | 3,760 | 0 | Same-revision retained geometry supplies these visits |
| 12-transform successful snapshot rows | 3,760 | 0 | Metadata reads are not counted here |
| 12-transform rebuilt tiles | 212 | 212 | Actual rerasterization still occurs |
| 12-transform native Stroke constructions | unavailable | 2,916 | No baseline native-count reduction is calculable |
| Separated append complete time | 2,462.407 ms | 1,713.317 ms | 30.42% lower in this sample |
| Separated append decoded strokes / snapshot rows | 322 / 322 | 24 / 24 | Payloads in unchanged middle regions are skipped |
| Separated append rebuilt tiles | 2 | 2 | Same dirty tile count |

The transform median is computed over **one mixed 12-case sequential run per side**,
not repeated trials of one pan/zoom operation. All 12 individual transform times
are lower in this candidate run; their range is 3,219.983–8,162.789 ms at baseline
and 1,926.162–6,812.705 ms at candidate. These samples supply no confidence interval
or physical-device percentile.

Candidate reports 3,088 cached-stroke visits and a maximum 204,800-byte logical
geometry weight across these transforms. This is not total native/process memory;
native geometry is still constructed 2,916 times. Both fixtures prepare their
spatial index before timing; index backfill and metadata row traversal are outside
the successful payload-read counter. The initial 320 two-point strokes follow the
same deterministic content rule; each run creates a distinct disposable database.

## Tile-local results, including regressions

Both pan tests rebuild 12 of 20 tiles and pass final pan/zoom bitmap equivalence.
`previewCaptureReady=false` checks raster readiness state only. It does not validate
a hardware window screenshot, compositor, export/capture pipeline or real display.

| Visible layers | Baseline append | Candidate append | Candidate increase | Local decoded, both sides | Local/full rebuilt, both sides |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,435.516 ms | 6,427.580 ms | +4,992.065 ms / +347.75% | 13 | 1 / 24 |
| 4 | 4,689.298 ms | 6,600.951 ms | +1,911.653 ms / +40.77% | 21 | 1 / 24 |
| 8 | 8,440.891 ms | 8,622.743 ms | +181.852 ms / +2.15% | 11 | 1 / 48 |

All three layer configurations have the same local decode/rebuild counts on the
two versions; these cases do not demonstrate a further reduction in local payload
work. The tile counts and `decoded` fields are production `BuildStats`; this test
does not collect separate payload-read callback row counts. Both versions decode
all 321 strokes in their respective forced full rebuilds.

Initial and forced-full timings are also slower for candidate in this run:

| Layers | Initial baseline → candidate | Forced full baseline → candidate |
| --- | ---: | ---: |
| 1 | 7,435.168 → 7,815.863 ms | 6,352.816 → 9,800.771 ms |
| 4 | 8,467.590 → 13,239.767 ms | 8,042.412 → 13,071.941 ms |
| 8 | 11,653.570 → 14,978.748 ms | 11,622.602 → 15,021.319 ms |

These are one observation per layer count, not repeated medians. The separately
recorded v3 one-layer append was 1,868.806 ms, also much lower than v4's 6,427.580 ms;
it is historical context and is not substituted for the final baseline. The current
data do not isolate a deterministic source-code cause from runtime variance, but
the observed regressions must remain visible rather than being described as uniform
speedups or dismissed solely because the CPU is emulated.

All append bitmap comparisons pass, and each test also hides the new stroke,
checks no stale pixels while pending, then compares the completed destructive
update against a full rebuild. Layer locking and paint order are retained by the
fixture. Bitmap byte observations (14,371,200 / 12,147,408 / 24,294,816 on both sides)
are collected after that destructive update, not at an append-frame memory peak.

Per-layer metadata/raster/publication durations are **summed across layer jobs**;
they must not be added to completion wall time or treated as exclusive CPU stages.
For example, candidate eight-layer publication sums to 17,981.109 ms while the
append wall time is 8,622.743 ms. Pending publication intervals can overlap.

## Remaining acceptance boundary

The original 1,201-stroke / 76,802-point 30,000 ms test still has its recorded
baseline and candidate failures. This final performance pair uses different
fixtures and does not revalidate or supersede that gate. The v2 diagnostic reached
512 decoded rows and timed out in native Stroke construction before publication;
its complete-frame, full-row and strict release assertions were not reached. One
stack sample does not establish the whole cost or prove TCG is the only cause.

Cross-tile native reuse has its separate v2 whole-bitmap differential pass
(2/2/1/0 vs test-only per-tile 24/32/16/0 constructions). Those are not installed
baseline counts. It removes no construction for the dense fixture where every
stroke hits a single tile. The passing 258-stroke/two-batch case and targeted
resource checks remain useful functional evidence with their original source/APK
identity; they cannot replace the original dense deadline or physical-device
validation.
