# Candidate raster hex v1 change

Only the candidate `rasterRegions` result line changes from `digest().joinToString(""){"%02x".format(it)}` to `digest().toHexString()`. The baseline retains the original Formatter implementation. No query, clock, transaction, epoch, cache, fixture, assertion, wrapper or dependency changes are included.

A one-off host program directly called the existing Kotlin 2.3.10 stdlib. All 1869 comparisons passed: 267 cases in each of ROOT, en-US, zh-CN, tr-TR, ar-EG, fa-IR and hi-IN; cases cover all 256 byte values individually, ascending/reversed full byte arrays, empty input, zero/FF 32-byte arrays and six SHA-256 arrays. Output equals concatenated original per-byte Formatter output, has two lowercase hex characters per byte, and preserves leading zeroes. This is host representation compatibility, not Android execution or native performance.

Evidence: `RasterHexCompatibility.java`, `raster-hex-v1-host-compatibility.json`/`.log`, `candidate-raster-hex-v1-one-line.patch` and `candidate-raster-hex-v1-source-change.json`. Prior v1 failure, v2 pair and patches are retained unchanged. The new source record verifies the candidate-before hash equals diagnostic-v2, the baseline hash remains unchanged, and Tile source is unchanged. `git diff --check` passes.

No Android build, ADB or native timing was run by this agent. The parent must freeze/build candidate-raster-hex-v1 and verify the existing functional and performance gates. The regression and original dense 30-second failure remain open. The unchanged v2 timing KDoc mentions Formatter; in the new candidate the same hex/result interval now covers stdlib conversion instead. Interpret the new phase accordingly; the timing code itself is unchanged.
