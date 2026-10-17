# Raster screen recovery preparation

The original run [39053188644](https://github.com/InkWeftDevs/InkWeft/actions/runs/39053188644) failed its first data guard at RasterReadCacheTest.kt:56. InkStroke is a plain class; two independent decoded lists were incorrectly compared by object identity. The remaining nine methods were NOT_RUN and no paired performance measurements exist. Original raw failure and summary are retained here; this recovery does not relabel that failure.

## Minimal test repair

Only the data-test assertion changes: check count and canonical stored value at each paint index. The canonical codec covers persisted identity, pen/color/width/tool, world/sample geometry and masks/appearance where encoded. The fixture's default appearance is canonical. The existing 300 ordered IDs, 256/44 batch split, decode/cache-hit counts and warm object-identity assertion are unchanged. No production equality, pixels, epochs, timing gates or sample counts change. Both exact sources contain identical repaired test bytes; their sole whole-tree difference is the six-line all-miss branch in InkRepository.kt.

## adb ownership repair

The old cleanup receipts verified recorded groups, emulator and ACL restoration. They did not track adb's detached auto-started server; the GitHub post-job sweep later killed that process. A bounded host-only reproduction confirmed that limitation.

The recovery prebinds loopback port 5037, refusing an existing listener without signaling it. It passes that socket to an owned foreground adb process via the [documented socket-activation interface](https://android.googlesource.com/platform/packages/modules/adb/+/refs/tags/android-platform-15.0.0_r12/SOCKET-ACTIVATION.txt). It records PID, UID and start time, verifies readiness under a 15-second/setup cap, rejects clients without the owned identity, and includes that group in both bounded cleanup passes. Explicit host arguments and inherited ADB_SERVER_SOCKET/ANDROID_ADB_SERVER_ADDRESS prevent clients from auto-starting a detached replacement. [Emulator AdbInterface](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/emu/adb/interface/src/android/emulation/control/adb/AdbInterface.cpp) appends device/command arguments without a server-host override; its environment therefore retains that route. Parent-provided testing of actual platform-tools 37.0.1 confirmed foreground socket/PID/group ownership, refusal to auto-start at an unused explicit host endpoint and bounded termination. A second actual-binary probe using only the inherited environment (no -H argument) also refused an unused endpoint, exited in 0.011 seconds and left no daemon or listener. The owned Android configuration directory is created before startup; a foreign configuration path is refused. The exact runtime version/provenance will be captured again on Actions.

## Tests and runtime scope

Host checks are retained under checks/. Kotlin/native execution of the repaired assertion is NOT_RUN until the approved recovery job. No local compiler or Gradle cache is available; no build result is inferred from static review. Ownership tests use synthetic child processes and sockets, never Android or KVM. Initial host-fixture failures (shared-port TIME_WAIT and floating-point epoch tolerance) were corrected; all final checks are reported separately.

The previous job's setup was 43.779 s, C1 build 537.705 s, C2 build 455.516 s and emulator 30.395 s, planning only. Rebuild all five APKs from exact repaired source commits. Use normal official dependency resolution/shared per-job dependency cache; never inject cached APKs or reuse the old failed test APK. Keep the same ten selectors, two seeds, all frame/pixel/resource checks and 20 MiB publication cap. Estimate 20–30 minutes, with absolute setup/data/raster/work/job cutoffs 30/45/50/55/60 minutes. No start after 23:00 UTC and cleanup must finish before 2026-10-18 00:00 UTC.

This is one reviewed recovery for a concrete test/controller defect, not an extra performance resample. The old 54-comparison 45/5/4 result and four-pair acceptance gate remain unchanged. No new run is authorized by unbound files alone.
