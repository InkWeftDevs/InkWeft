# Controlled snapshot diagnostic — ready source, native not yet run

Implemented one independently opted-in Android method:
`org.inkweft.data.LibrarySnapshotHoldDiagnosticsTest#idleHeldSnapshotAndActiveSearchSaveBoundaries`, argument `librarySnapshotHoldDiagnostics=true`.

The fixed order is IDLE, HELD_SNAPSHOT, ACTIVE_SEARCH, each with the same independent seed-4101 fixture, exact initial six-table digest, author revision 1 and identical 30 generated save commands. FULL and WAL remain unchanged. There is no index builder. The previous public probe, historical 90-save test and production save diagnostics remain unchanged.

HELD performs a real main-database revision query, closes its cursor, then waits on a bounded latch without polling or SQL. It rechecks revision 1 on the same reader thread/snapshot after the 30 writes; the repository subsequently sees revision 31. IDLE closes the same initial snapshot before save 0. ACTIVE uses the existing original search; the pending-source page-hits SQL itself must contain the whole save window. This is deliberately stronger than merely waiting for read-end. Early completion sets coverage invalid and fails JUnit; no retry, looping search, replacement arm or extra sample is introduced. SaveNote construction occurs before each timed save in all three arms; these controlled timings are not a replacement for the historical public windows.

Cleanup releases the latch, separately confirms reader exit and Room writer drain, then closes and checks database deletion. Failed joins/drain retain the synthetic database. Reader-body exceptions stay primary if native END also fails; END/close faults are suppressed. END markers expose readerExited, writersExited, databaseClosed, databaseDeleted and cleanupErrors. A cleanup failure cannot report completed=true.

`contract.json` gives exact method/argument, four marker kinds, order, finite closed fields, 24 boundary and 29 read-stage codes, all 10/7/5-column tuples and bounds. Caps are 1536 boundary, 128 read/control and 30 save rows per arm, chunks of 96. `success-schema.json` is the successful payload shape contract. Whole-method order, exact counts, full author chains, thread/time relationships and reader lifecycle/coverage still require the separate semantic controller/analyzer. The parent owns that analyzer; no duplicate implementation was created here. Failures preserve raw values and are not clipped into valid evidence.

Validation completed:

- Offline `:data-local:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` PASS, exit 0, 374.626 seconds; 51 tasks (11 executed, 9 from cache, 31 up-to-date). All 761 source/build input hashes are identical at start and end, including the GC and raster candidates. Full command, log and before/after inputs are in `host/`.
- Host Java invoked the actual compiled core SaveNote constructor for 90 seeded commands, verifying identical valid UUID/revision sequences across the three arms. `constructor-proof.json` records the scope and class/source bindings. This does not execute Room or calculate a replacement fixture digest.
- JSON Schema Draft202012 check and 16 synthetic payload-shape/ceiling checks passed. No synthetic timings or fake native marker stream were produced.
- Independent GC-agent review closed the three cleanup findings and found no remaining host-compilation blocker. Source/contract mappings, six-point transaction success flags, fixture ID consumption and strict ACTIVE coverage were reviewed.

No new Android/native run, performance result, public acceptance, or B-to-C1 effect is claimed. Fixed arm order, one seed and enabled observer overhead remain limitations. The controlled hold experiment is mechanism screening. It cannot label a low-CPU END interval as pure fsync, lock wait, or scheduling without further evidence.

`plan.md` preserves the initial proposal; this README and the frozen implementation/contract describe the delivered scope. No schedstat probe or new diagnostic framework was added. The parent owns commits, pushes and CI activation.
