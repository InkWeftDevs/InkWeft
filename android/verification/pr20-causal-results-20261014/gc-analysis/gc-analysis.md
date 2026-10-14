# GC conditional-arrival record analysis

CONDITIONAL_RECORDS_CONSISTENT_NOT_ACCEPTANCE

This is diagnostic arithmetic, not natural-load latency acceptance or an artifact-integrity verdict.

| Connection | Samples / committed | Precommit point / DELETE interval / start-during-DELETE | All-save p50 / p95 / max ms | Root scans first + finish | Collection first + finish ms |
|---|---:|---:|---|---:|---:|
| same-room | 30 / 30 | 30 / 30 / 30 | 11.223908 / 15.425405 / 16.344505 | 30 + 30 | 749.0795929999999 + 1118.598952 |
| separate-room | 30 / 30 | 30 / 30 / 30 | 12.791486 / 56.665894 / 57.277407 | 30 + 30 | 918.492997 + 958.301233 |

All raw sample arrays, interval counts, guard deltas, deleted counts and recomputed summaries are retained in gc-analysis.json.

Exposed-subset quantiles are null below 20 or when the 30-trial method is incomplete. Pure SQLite lock wait remains unknown.

Issues:
- None in the checked records; external run integrity still requires its independent receipt.
