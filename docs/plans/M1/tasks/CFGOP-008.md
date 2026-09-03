# CFGOP-008 — Milestone documentation, operator pages, feature matrix, ADR amendments

- **ID:** CFGOP-008
- **Title:** Milestone documentation, operator pages, feature matrix, ADR amendments
- **Milestone / Feature:** M1 / OT-010, and the closing of every M1 row
- **Owner role:** Infrastructure Lead
- **Context / service:** documentation, repository-level records
- **Size:** M
- **Dependencies / blocked by:** everything (it is the last task in the milestone)

## Goal (user value)

Someone who was not here can operate what M1 built: point KUI at their secured cluster, decide
whether to run the metadata store, back it up, recover from a lost encryption key — or decide not
to run it at all. And someone who *was* here can tell, from the repository, exactly what shipped
and what is still owed.

## Scope

Documentation and records only. **No production code changes.** If this task finds a defect, it
files it — in `TECH_DEBT.md`, or as a change to the task that owns the code — and does not fix it
here; a documentation commit that also changes behaviour is a commit nobody reviews properly.

1. `docs/operations/metadata-store.md` — brought from design document to description of what
   shipped, with the sections ADR-042 and OT-010 require and M0 could not write.
2. `docs/operations/configuration.md` — the M1 key surface, including R-1's mechanism table with
   its integration-test coverage column.
3. `docs/domain/cluster.md` — the real aggregate, the ports, the invariants; the `Ping` paragraph
   gone.
4. ADR amendments: **ADR-022** (where the connection ADT lives), **ADR-042** (what the
   implementation learned about replay timing), **ADR-041** (A9 and A10 — written by CFGOP-003;
   this task verifies it landed and is consistent).
5. `ARCHITECTURE.md` §3, §4.2, §9 and §10.1 where an M1 task found a delta — §4.2's sketch
   signatures replaced by links to the implementing files.
6. `docs/FEATURE_MATRIX.md` — the 22 M1 rows to `DONE`. **This task is the only one that edits
   that file** (DEVPLAN §6.5); no task flips its own row.
7. `docs/ROADMAP.md` — M1 marked complete; anything M1 discovered that changes M2's scope
   recorded there rather than in a private note.
8. `STATUS.md` — the milestone record, the CI run id, the evidence table, and the **manual
   external-cluster acceptance** (risk R-10), with its script and its template.
9. `TECH_DEBT.md` — every debt taken during M1, each with what it costs and what would repay it.
10. `docs/api/error-codes.md` — the store and Kafka codes.
11. `README.md` — the fifteen-minute first run, verified by actually doing it.

## Non-goals

- **No new features, no code, no test changes.** Including "just one" documentation-driven fix.
- **No new ADRs.** M1 takes decisions inside its plan (DEVPLAN §10); the two that change an
  existing ADR's substance get amendments, and the rest live in the plan and in the task specs
  where they were taken. A new ADR for a decision that amends an old one splits the record.
- **No re-litigating a DEVPLAN §10 decision.** If one turned out wrong, that is a `TECH_DEBT.md`
  entry naming the decision and what it cost, and an input to M2's grooming.
- **No OpenAPI work.** CLAPI-010 owns the regenerated document and the contract snapshot; this
  task checks it is committed and that `docs/api/error-codes.md` agrees with it.
- **No waiting for the manual acceptance.** See D-3.

## Design references

M1 DEVPLAN §2 (the exit criteria, which this task turns into an evidence table), §9 (the
thirteen-point definition of done — this task is where twelve of them are checked and the
thirteenth is item 13 itself), §8 (the risk register: R-1's coverage column, R-3's key warning,
R-10's manual acceptance are all documentation obligations), §10 (the ten decisions, D1 and D7
of which need an ADR amendment); ADR-042 in full; ADR-022; ADR-041 and its amendments;
`docs/operations/metadata-store.md` as it stands (M0 wrote it as a design document — sections 1–4
are already there and are mostly right, so this is a revision, not a rewrite);
`docs/plans/M0/tasks/` for the level of detail every operator page in this repository holds.

## Files to change

```
docs/operations/metadata-store.md
docs/operations/configuration.md
docs/operations/manual-cluster-acceptance.md            (new)
docs/domain/cluster.md
docs/adr/ADR-022-typed-kafka-cluster-auth.md            (amendment)
docs/adr/ADR-042-kafka-backed-metadata-store.md         (consequences)
docs/adr/ADR-041-layering-rules-machine-enforced.md     (verify CFGOP-003's amendment)
docs/api/error-codes.md
ARCHITECTURE.md                                          (§3, §4.2, §9, §10.1)
docs/FEATURE_MATRIX.md
docs/ROADMAP.md
STATUS.md
TECH_DEBT.md
README.md
scripts/manual-acceptance.sh                             (new)
```

## What each document must contain

### `docs/operations/metadata-store.md`

Sections 1–4 exist and describe the design. Revise them to describe the implementation — every
number, topic setting and error message replaced by the one the code actually produces — and add:

- **§5 The encryption key.** R-3's warning in the **first paragraph**: losing
  `kui.store.encryptionKey` makes every stored secret permanently unreadable, there is no recovery
  path, and the file adapter is a supported way to run with no such risk. Then: how to generate
  one, how to supply it, what `keyId` is for, and how a rotation works (write new records under a
  new key; old records stay readable; there is no re-encrypt-everything command in M1 and say so).
- **§6 Backup and restore.** The topics are compacted Kafka topics; backing them up is backing up
  a Kafka topic, with the exact `kafka-console-consumer` / `kafka-console-producer` commands, the
  `--property print.key=true` flag that makes the dump restorable, and the warning that a restore
  into a topic that already has records produces a merge by key rather than a replacement.
- **§7 Migrating from the file adapter to Kafka.** The order — configure the store, start, let
  the topics be created, write the records — and what happens to a file-adapter deployment's
  existing data (nothing; it is read-only and stays as the static base).
- **§8 Running without the store.** Which endpoints report `NotConfigured`, what still works
  (everything else in M1), and when an operator should choose this.
- **§9 What KUI creates today.** Two topics, not three (DEVPLAN §10 D7). Say plainly that
  `__kui_audit` is documented here and created by the milestone that first writes to it, so an
  operator who pre-creates it is not surprised and one who does not is not confused by an empty
  topic.
- **§10 Diagnosing a store problem.** The named errors — `KUI-STORE-TOPIC-INCOMPATIBLE`,
  `KUI-STORE-REPLAY-TIMEOUT`, `KUI-CONFIG-VERSION-CONFLICT`, and whatever else STORE-005…008
  actually named — each with what it means and the first thing to check. Take the list from
  `docs/api/error-codes.md`, not from memory.

### `docs/operations/configuration.md`

The cluster keys are CFGOP-001's and CFGOP-002's to write. This task checks and completes:

- The **mechanism table carries its integration-test coverage column** (R-1), and that column
  agrees with what CFGOP-004 actually built. A mechanism that is unit-tested only says so in the
  same table that lists it as supported — that sentence is the entire mitigation for R-1 and it
  must not be softened.
- A worked example per security mode, copy-pasteable, each one actually pasted into a file and
  loaded before it is committed.
- The `kui.store.*` section, cross-referenced to the metadata-store page rather than duplicated.
- The "What is not here yet" section rewritten: the M1 forward references are now past tense.

### `docs/domain/cluster.md`

DEVPLAN §9.9: it no longer says "scaffolded, not modelled". It documents the real aggregate, the
three ports, their invariants, and the `Ping` paragraph is gone. CLDOM-001 and CLDOM-002 update
this file as they go; this task's job is the final read-through for consistency and the deletion
of anything the milestone left stale.

### The ADR amendments

**ADR-022** gains an amendment, dated, in the style ADR-041's amendments already use:

> **Amendment 1 (M1).** The decision says the typed security ADT lives "in `libs/config` /
> `ClusterProfile`". That is not implementable under ADR-041: rule A1 forbids a service's domain
> module any dependency but `libs/kernel`, and rule A5 forbids `libs/kafka` depending on a
> service, so an ADT in `libs/config` cannot be composed by `ClusterProfile` and an ADT in the
> domain cannot be read by `libs/kafka-auth`. **The ADT lives in `libs/kernel`**, in the pure,
> cross-compiled `kui.kernel.cluster` package: `BootstrapServers`, `ClusterSecurity`,
> `ClientProperties`, `AdminTuning`. The domain's `ClusterProfile` composes it, `libs/config`
> decodes it, `libs/kafka-auth` renders it and `libs/contracts-core` derives the redacted DTO
> from it — one definition, no mapper. Nothing else in the decision changes.

**ADR-042** gains, in its Consequences section, what the implementation learned: the measured
replay time for a small store on a development broker (from CFGOP-006's metric), the replay
timeout that shipped, and the observation that readiness gates on replay so a hang is visible as
"not ready with a logged reason" rather than as silence (R-2's mitigation, in the ADR that owns
the design).

**ADR-041**: verify CFGOP-003's A9/A10 amendment is present, that `ARCHITECTURE.md` §3's rule
table matches it, and that the missing-A7 numbering hole is noted as cosmetic rather than
silently renumbered.

### `STATUS.md`

`STATUS.md` is currently the pre-M0 grooming record. Restructure it once, here, into the form
every later milestone will append to:

- current phase and date;
- a table of milestones with their state and the CI run id that produced their evidence;
- for M1, an **evidence table**: one row per exit criterion in DEVPLAN §2, the command that
  demonstrates it, and where its output is recorded;
- the manual external-cluster acceptance (below);
- CEO acceptance.

### The manual acceptance (R-10)

`docs/operations/manual-cluster-acceptance.md` plus `scripts/manual-acceptance.sh`: a fifteen-step
procedure someone runs against a real external cluster, and a script that does the API half of it
and prints a report. What it checks: connection under the operator's real security settings,
broker list, broker configs, log dirs, the detected broker version against ADR-030's floor, the
capability probe's results, and the wall-clock time of a first snapshot. The `STATUS.md` template
is a filled-in table: date, cluster description (vendor, version, security mode, broker count),
each check's result, and any surprise.

## Decisions taken here

**D-1 — the evidence table is per exit criterion, not per test.** DEVPLAN §9.1 requires every
criterion to be demonstrated by a command in CI. A table of test names would not show a *missing*
criterion; a table keyed by criterion shows a gap as an empty cell.

**D-2 — a feature-matrix row goes to `DONE` only with a named piece of evidence.** The state
column gets the row's state and the notes column gets the command or the suite that proves it. A
row marked done because its task merged is a row nobody can check later.

**D-3 — the manual acceptance is recorded, not waited for.** DEVPLAN's standing rule and R-10:
this task prepares the script, the procedure and the `STATUS.md` template; the milestone's
automated evidence is complete without it, and no task blocks on a person outside the loop. If
the acceptance has not happened when M1 closes, `STATUS.md` says "not yet performed", with the
date the template was prepared. An honest empty row beats a milestone that stalls.

**D-4 — the fifteen-minute first run is timed, on a cold Docker cache, and the number is
written down.** DEVPLAN §9.13 is a promise with a number in it, and a promise nobody measured is
a promise nobody keeps. If it is over fifteen minutes, that is a `TECH_DEBT.md` entry naming what
took the time — almost certainly the image pulls — not a quietly relaxed claim.

**D-5 — `TECH_DEBT.md` entries have four fields**: what was taken, why it was taken then, what it
costs now, and what would repay it. A debt entry without the fourth field is a complaint.

## Library coordinates

None.

## Acceptance criteria

```
$ ./mill __.compile && ./mill __.test              # JVM
$ ./mill frontend.__.test                          # Scala.js, separate invocation (CLAUDE.md)
$ ./mill __.checkFormat && ./mill __.fix --check
$ ./mill checkArchitecture
$ ./mill e2e.test
# all green; the run id recorded in STATUS.md
```

```
$ grep -c "| DONE |" docs/FEATURE_MATRIX.md
# the 22 M1 rows, each with evidence in its notes column

$ ./scripts/manual-acceptance.sh --config my-real-cluster.yaml
KUI manual cluster acceptance
  cluster:            prod-eu (SASL_SSL / SCRAM-SHA-512)
  broker version:     3.7.1                      OK (>= 2.8, ADR-030)
  brokers:            6                          OK
  controller:         3                          OK
  broker configs:     412 entries                OK
  log dirs:           6 brokers, 6 directories   OK
  capability probe:   11 of 14 features present  OK
  first snapshot:     1.8s                       OK
```

Documentation-specific acceptance, and it is not a formality:

- Every command printed in every page this task touches has been **run**, and its output pasted
  is the output it produced.
- Every internal link resolves (`docs/`, `research/`, ADR references).
- The mechanism table in `docs/operations/configuration.md` and the mechanism table in
  `CFGOP-001.md` list the same eight mechanisms with the same coverage column.
- A reader who has never seen the repository can follow `README.md` from clone to populated
  dashboard. Have someone who has not worked on M1 do it, and record how long it took.

## Tests required

No new Scala tests. Two checks that are cheap and stop a specific, recurring rot:

- Extend `ComposeConfigSuite` (CFGOP-006) to also load every YAML fragment embedded in
  `docs/operations/configuration.md` between ```` ```yaml ```` fences through `KuiConfigSource`
  and assert no problems. A documented example that does not load is worse than no example, and
  this is the only mechanical way to keep them true.
- Extend `AdminTuningSuite`'s `defaultsMatchTheDocumentedTable` (CFGOP-002) to read the numbers
  from the documentation table rather than from a literal, so the two cannot drift.

Both are small and both belong to this task because it is the one that discovers the drift.

## Observability

Nothing at runtime. The observability this task delivers is for the *operator*: §10 of the
metadata-store page maps every named error to its first diagnostic step, and the manual
acceptance script prints one line per check.

## Degraded behavior

If an M1 exit criterion cannot be demonstrated — a mode that could not be containerised, a
criterion the suite skips — this task records it as **not demonstrated**, in the evidence table,
with what is missing and what it would take. It does not mark the milestone green with a
qualifier in a footnote. The evidence table is the milestone's honesty mechanism and its value is
entirely in what it is willing to leave empty.

## Docs to update

This task *is* the documentation. The list is in "Files to change" above.

## Deviations

Recorded during implementation.

## M1 gate review additions

Three documentation corrections were found at the gate and are this task's to make. They are not
optional; each of them currently describes something that does not exist.

1. **`docs/operations/metadata-store.md` §4.2 step 3 documents `POST /internal/v1/store/rekey`.**
   No M1 task builds it — DEVPLAN decision D6 ships exactly one write endpoint,
   `PUT /internal/v1/clusters/{id}`. Rewrite step 3 as the manual rotation procedure STORE-002
   specifies (add the new key to the keyring as the write key, keep the old one for reads,
   rewrite each record through the ordinary write path), and record the endpoint in
   `TECH_DEBT.md` as deferred to the milestone that needs bulk rekeying.
2. **Section ownership of `docs/operations/metadata-store.md`.** DEVPLAN §6.5 gives the STORE
   area sections 2–6 and names no owner for §1 or §7 onward. STORE-004 corrects §1's key table
   in its own commit; **everything from §7 onward is this task's**, including the OT-010
   operator guidance, backup/restore and file-to-Kafka migration.
3. **The ADR amendments named in DEVPLAN §9 item 10 were written at the gate, not here.**
   [ADR-006 Amendment 1](../../../adr/ADR-006-fs2-kafka-and-admin-ports.md) (raw `Admin` for
   admin work, fs2-kafka for consumers and producers),
   [ADR-022 Amendment 1](../../../adr/ADR-022-typed-kafka-cluster-auth.md) (the ADT lives in
   `libs/kernel`), [ADR-041 Amendment 3](../../../adr/ADR-041-layering-rules-machine-enforced.md)
   (A9, A10, and A1 not widened) and the new
   [ADR-044](../../../adr/ADR-044-store-record-envelope-and-field-encryption.md) are already
   Accepted. This task's remaining ADR work is ADR-042's consequences section — what the
   implementation learned about replay timing — and ADR-030's consequences, which must record
   that a feature probe has three outcomes and not two (F-05).
