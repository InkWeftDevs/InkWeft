# Raster repeat review and next diagnostic

**The single-layer regression is not closed.** All four unchanged-APK repeat
invocations pass both `TileLocalRedrawTest` tests, but functional passes do not make
the timing regression disappear. Including the original pair, each version has
three observations per layer count. Candidate single-layer median is 4,176.865 ms
versus baseline 1,817.982 ms. Do not drop the original 6,427.580 ms result or select
only the later fast candidate sample. Delivery remains on hold for raster review.

This review introduces no source/test changes and executes no build, ADB or native
test. Full parsed records and hashes are in `raster-repeat-review.json`.

## All observations retained

Append wall times, in milliseconds:

| Run | 1 layer | 4 layers | 8 layers |
| --- | ---: | ---: | ---: |
| Original candidate-v4 | 6,427.580 | 6,600.951 | 8,622.743 |
| Original final baseline | 1,435.516 | 4,689.298 | 8,440.891 |
| Candidate repeat 1 | 4,176.865 | 8,065.451 | 15,461.609 |
| Baseline repeat 1 | 1,817.982 | 7,575.869 | 19,706.451 |
| Baseline repeat 2 | 2,358.944 | 7,532.984 | 8,642.036 |
| Candidate repeat 2 | 1,611.903 | 4,836.379 | 9,130.195 |

The four repeats ran C/B/B/C, with the same complete class and its original
pan/zoom warmup, layer loop, deadlines and assertions. Each repeat reports 2 tests,
0 failures. Every pan/zoom, append and completed destructive-update bitmap
comparison passed within its own version. Local decoded counts remain 13/21/11
and local rebuilt count remains one for 1/4/8 layers. Passing comparisons do not
assert cross-version bitmap identity or hardware screenshot correctness.

| Layers | Baseline median (range) | Candidate median (range) | Candidate median change |
| --- | ---: | ---: | ---: |
| 1 | 1,817.982 (1,435.516–2,358.944) | 4,176.865 (1,611.903–6,427.580) | +129.75% |
| 4 | 7,532.984 (4,689.298–7,575.869) | 6,600.951 (4,836.379–8,065.451) | −12.37% |
| 8 | 8,642.036 (8,440.891–19,706.451) | 9,130.195 (8,622.743–15,461.609) | +5.65% |

These are descriptive medians of three observations, including the earlier pair,
not statistical significance, confidence intervals or stable physical-device
latencies. The original one-layer 4.48× candidate/baseline ratio remains recorded.
The candidate can complete that same geometry in 1,611.903 ms without code changes,
and baseline eight-layer times range from 8,440.891 to 19,706.451 ms. These establish
substantial observed variation, not that every difference is noise or harmless.
The v3 single-layer 1,868.806 ms result is historical context and is not pooled into
these version-specific samples.

All eight repeat before/after environment receipts have comparison SHA-256
`425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`, matching the
original pair. APK reinstall receipts retain the original baseline and v4 hashes;
source and tests were unchanged. Matching settings/process identity does not
measure instantaneous scheduling, GC, CPU use or cache state. UUIDs and physical
database byte layouts differ across newly created fixtures. API 35 x86_64 software
CPU/GPU limitations remain; they are not a proven unique cause.

## Evidence directing the next step

Single-layer append phase wall times:

| Run | Metadata | Raster | Publication |
| --- | ---: | ---: | ---: |
| Original final baseline | 728.762 | 518.699 | 9.981 |
| Original v4 | 3,020.313 | 2,381.227 | 274.363 |
| Candidate repeat 1 | 2,676.288 | 774.952 | 16.404 |
| Baseline repeat 1 | 890.599 | 651.219 | 22.451 |
| Baseline repeat 2 | 736.938 | 1,349.827 | 20.046 |
| Candidate repeat 2 | 710.591 | 594.303 | 15.829 |

Candidate repeat 1 is again slow mainly in metadata, while its raster/publication
times are much closer to normal. Candidate repeat 2's metadata is already below
all three baseline metadata samples. Current timers measure wall time, not thread
CPU or exact SQL lock waiting. Multi-layer phase sums also overlap, so their sums
cannot be interpreted as end-to-end serial CPU time.

Code facts established by the source review:

- `rasterRegions`/`SpatialIndex` have no baseline-to-v4 changes. Its metadata timer
  finishes before the new sparse/cache visit and per-stroke native reuse execute.
  Baseline and candidate use the same raster dependencies/build configuration here.
- Eight relevant v3/v4 raster/cache/fixture/factory files have identical frozen
  hashes. The changed v4 indexing build path is not called by this tile fixture.
- With one dirty tile, the native-construction loop has the same 13 pen strokes
  structurally on both versions. The receipts do not print that native counter;
  this is a source-path observation, not a new measured baseline counter.
- A new-revision cache is cold and does add small map/filter/epoch work and bounded
  retention. That can be investigated, but it does not directly execute inside the
  metadata timer. Removing its epoch checks would lose a correctness guarantee.
- Metadata result conversion currently makes 32 `String.format("%02x", byte)` calls
  per SHA-256 signature: 768 Formatter invocations for 24 tiles. This is a concrete
  repeated-work candidate shared by both versions, **not yet a measured hotspot**.

## Recommended minimal next action

Add **opt-in, in-memory metadata-stage diagnostics**, with no algorithm change and
no generic tracing framework, then run the same fixture under a new frozen
diagnostic source/APK identity. Record one result per `rasterRegions` call and emit
it after the frame, not per SQL row or per stroke:

1. Elapsed time from entering `SpatialIndex.read` to entering its ready read
   callback. Without a deeper transaction-entry hook, call this **admission plus
   index preparation**, not pure transaction/SQL lock acquisition. Its existing
   `onBatch` callback can count any preparation batches/rows. If this combined
   interval dominates, only then add the narrow actual transaction-entry timing.
2. Query/layer/revision checks and per-region digest updates, with wall time and
   CPU time sampled on the actual Room transaction thread. Record row/query/region
   counts and thread IDs. A CPU clock on the raster worker would miss Room-thread
   work; discard a CPU delta if the segment changes thread.
3. Digest hexadecimal encoding/result construction, separately measured for wall
   and that same transaction thread's CPU time. This isolates the 768 Formatter
   calls without conflating them with admission or spatial queries.

An optional callback on `InkRepository`, following its existing read-observer
pattern, can keep this confined to the existing method and fixture. No per-stroke
native profiling is needed for this first split. Default production behavior and
all original fixture deadlines/pixel assertions should remain unchanged.

If hex encoding is a material CPU/wall contributor, the smallest proposed
production change is a direct fixed lowercase hexadecimal conversion (two chars
per unsigned byte) in that one result path. Verify exact equality for all byte
values against the prior conversion, then rerun the unchanged pixel/Tile tests and
the same stage probe. The fix must preserve signature bytes, ordering, region
semantics and revision checks. It would remove real allocation work, but should
not be called the original regression's cause without the stage evidence.

If admission/preparation or query/digest work dominates instead, do not claim
replacing Formatter resolves it; follow that measured segment. A one-dirty-region
filter shortcut or cold-cache mapping shortcut is a possible later narrow raster
optimization, but neither currently explains the slow metadata phase. **Do not
blindly revert native reuse or disable the cache/epoch guard.**

This targeted diagnostic is the recommended next step, not acceptance of the
current state as finished. The original 1,201-stroke 30 s dense gate also remains
unmet and is not exercised by these repetitions.
