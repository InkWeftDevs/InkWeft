# Targeted raster tools

Prepared in scratch only. Current source may contain later raster diagnostics; the saved v4 APKs remain bound to the earlier c60/v4 source and artifact manifests. No new build, device operation, signature verification or native inspection was executed while preparing these tools. Nine pure JUnit parser checks, syntax compilation and both CLI help commands passed; see `raster-targeted-tools-review.json`.

## Preserve and restore existing APK identities

The four saved files are private (`0700` directories, `0600` files):

- `/workspace/scratch/pr20-preserved-apks/candidate-v4/app-debug.apk`
- `/workspace/scratch/pr20-preserved-apks/candidate-v4/app-debug-androidTest.apk`
- `/workspace/scratch/pr20-preserved-apks/baseline-final/app-debug.apk`
- `/workspace/scratch/pr20-preserved-apks/baseline-final/app-debug-androidTest.apk`

Every source and copied file hash matched the corresponding existing artifact manifest. Receipt: `/workspace/scratch/pr20-preserved-apks/v4-baseline-app-preservation.json`. Do not publish the APK payloads as evidence; retain the non-sensitive hash receipt.

## Verify the two newly built APKs, optionally install

```bash
python3 /workspace/scratch/pr20-raster-targeted-apks.py candidate-v5 \
  /workspace/InkWeft/android/app/build/outputs/apk/debug/app-debug.apk \
  /workspace/InkWeft/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --install
```

Omit `--install` for verification only. The tool records both APK hashes, checks app MuPDF native ELF headers/ABI identifiers for all four expected ABIs, and invokes the official SDK `apksigner.jar verify --verbose` for both APKs. Both must pass before installation begins. Hashes are checked again after verification and around each requested installation.

Output: `/workspace/scratch/pr20-closure-evidence/candidate-v5-targeted-apks.json`. JVM, lint, dataTest and instrumentation are explicitly `NOT_RUN`; prior XML reports are never consulted. Bind newly built APKs to a separate source/build snapshot. This tool does not establish source identity by itself.

To restore saved v4 later, pass the two preserved v4 paths and a fresh phase name with `--install`. Use the two baseline paths similarly. The tool installs only these two APKs to `emulator-5554`; it does not clear app data or touch the data-local test APK.

## Run one app instrumentation selection

```bash
python3 /workspace/scratch/pr20-raster-targeted-instrumentation.py \
  candidate-v5-metadata \
  org.inkweft.app.TileLocalRedrawTest \
  2 --diagnostics
```

CLI is `phase selection expected [--diagnostics]`. Omit `--diagnostics` to use the ordinary test path. The flag adds only `-e rasterMetadataDiagnostics true`. Selecting the full unchanged class keeps its pan/zoom warmup and 1/4/8-layer sequence; a `Class#method` selection is supported when deliberately requested with its correct expected count.

The tool retains the existing awake setup (`KEYCODE_WAKEUP`, `wm dismiss-keyguard`, filtered power-state readback) and requires `mWakefulness=Awake`. Setup commands retain their 30-second outer timeouts. Instrumentation retains its 1800-second outer timeout; no test assertion or internal deadline is changed. Success requires exit 0, exactly one JUnit `OK` line with the expected count, and no failure/timeout record. An outer timeout is recorded as exit 124 and requires root to inspect the device process before continuing.

Outputs use the exact phase prefix:

- `candidate-v5-metadata-targeted-setup.json`
- `candidate-v5-metadata-targeted.log`
- `candidate-v5-metadata-targeted.json`

All outputs live under `/workspace/scratch/pr20-closure-evidence`. Both tools reject existing output paths; use a new phase for each attempt. Existing historical scripts were not edited. Run build, artifact verification/installation, environment recording and native phases serially under root coordination.
