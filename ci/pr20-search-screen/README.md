# PR20 focused search screening controller

This bounded, one-use controller compares frozen d66 and a9cc data-layer test APKs. It is SCREENING_ONLY: 10 related Room methods, then the unchanged public library probe in four counterbalanced blocks (8 calls). It does not close original-B regressions, raster results, GC exposure, or full acceptance.

Inputs and source manifests are frozen in [the plan](../../android/verification/pr20-search-screen-20261014/PLAN.md), commit `03a335f3d92e3682080f3ce7e4c661434956c075`. No app APK, new build cache, MuPDF replacement, retry, or expanded runner permission is used. Preparation and common work stop by their cumulative 30/35/50-minute cutoffs; all work stops by55 and the standard runner job by60.

`workflow.proposed.yml` is inactive here. A dedicated execution clone receives this exact workflow, creates a preparation commit, and changes only the exact-parent guard to that commit for one activation. The execution checksum list must bind the actual active workflow and controller before pushing. A root review checks the diff and hashes; this document itself is not execution evidence.

The final offline check covers strict JUnit and marker parsing, malformed/truncated/duplicate data, actual isolated first-step clock exports, actual existing data-APK manifest parsing, phase deadlines, natural process exit, installed APK hash mismatch, and text-only packaging/missing-file handling. Its synthetic fixtures and prior-run data are not a new native run. Procedure and receipts are in [preflight](../../android/verification/pr20-search-screen-20261014/preflight/).

Only fixed, validated synthetic probe JSON is forwarded to official job logs. Completeness requires final method, installation, cleanup and packaging gates; original instrumentation and APK binary verification cannot be independently reconstructed from this limited log stream. Original text artifacts remain at most20MiB with3-day retention; no APK or database is uploaded.
