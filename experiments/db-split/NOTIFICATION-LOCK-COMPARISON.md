# External notification lock comparison

Plan: NOTIFICATION-LOCKS-v1. This replaces the DB_CLAIM vs REDIS_OUTBOX load comparison. Historical results are not relabeled.

| Report strategy | Implementation | HTTP transaction boundary |
| --- | --- | --- |
| PESSIMISTIC | DB_INLINE: SELECT FOR UPDATE on notification row then delivery | DB connection and row lock held during HTTP |
| OPTIMISTIC | Read eligible version then version CAS; only winner sends | CAS commits before HTTP |
| REDIS | REDIS_OUTBOX: notification ID Redis lock plus durable owner/lease state | DB connection released before HTTP |

All modes share immutable outbox payload, owner-conditional acknowledgement, lease recovery and provider idempotency key. Redis is not the only safety mechanism. Optimistic coordination is a version-based processing claim, not an API call followed by a version check. No automatic conflict retry in this load test; the winning duplicate handles delivery. Pending/retry recovery is tested separately. External API latency and transaction boundaries both affect results; this is not a pure lock primitive microbenchmark.

Two independent HTTP JVMs on one host. Each has 8 workers and 10 DB connections. Defaults: 1000 unique notifications, 1000ms mock provider delay, 200 planned unique arrivals/s. UNIQUE has one attempt per notification. DUPLICATE has two attempts on opposite servers. Three rounds rotate PESSIMISTIC, OPTIMISTIC, REDIS. Two scenarios give 18 measured runs. Twenty warmup messages per run are excluded. No real messages are sent.

Run from repository root with Docker Desktop running:

```powershell
& .\experiments\db-split\scripts\run-notification-load.ps1 -Messages 1000 -Rounds 3 -WorkersPerServer 8 -PoolSizePerServer 10 -ProviderDelayMs 1000 -UniqueRps 200 -DuplicateCopies 2
```

Read notification-load.json plus conditions.json in the resulting notification-load-*.zip. Compare completion, scheduled-arrival-to-success P95, providerCalls versus providerUnique, read P95, DB acquisition/usage deltas, sampled peak pool wait and versionConflicts. P95 includes the bounded load-client executor queue. Reads use SELECT 1 with 20ms post-completion pacing, not course search or guaranteed 50RPS. Waiting workers can leave only two pool connections for reads in the pessimistic default; no pool saturation is guaranteed. No destructive cleanup or reuse of old result folders.

## Sensitivity extension

Run `run-notification-load.ps1 -Sensitivity -Messages 1000 -Rounds 3 -ProviderDelayMs 1000`.
The switch selects four fixed conditions, overriding the base arrival RPS/copy arguments:

| Unique notifications duplicated | Unique arrivals/sec | Planned duplicate gap |
| --- | --- | --- |
| 50% | 200 | 0ms |
| 100% | 5 | 0ms |
| 100% | 200 | 100ms |
| 100% | 200 | 500ms |

Three strategies and three rounds give 36 measurements. Each has two fresh JVMs and excluded warmup.
50% means 1000 unique messages produce 1500 attempts, evenly distributed through the run.
Original and duplicate events are sorted by planned time; delaying a duplicate never blocks scheduling later originals.
Conditions rotate across rounds, as do strategies. No automatic escalation or retries.
Per-row `duplicatePercent`, `uniqueArrivalRps`, `duplicateGapMs` are authoritative (top-level base RPS is not the low-load condition).
`queueWait`, `httpProcessing`, and `observedDuplicateHttpStartGap` separate queue and HTTP costs. The observed gap is signed: a negative value means the duplicate HTTP request started first. These are client HTTP start times, not observed server arrivals. Do not describe 100ms/500ms as exact server arrival intervals under saturation.
Existing 0%/100% simultaneous results remain historical reference points; verify same configuration and scheduling behavior before combining comparisons across revisions. The new load scheduler does not itself establish equivalence to old runs.
Low load is a hypothesis: verify queue wait and actual delivery before labeling it unsaturated.
