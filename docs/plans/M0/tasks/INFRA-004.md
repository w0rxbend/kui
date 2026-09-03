# INFRA-004 — Milestone documentation and feature-matrix update

- **ID:** INFRA-004
- **Title:** Milestone documentation and feature-matrix update
- **Milestone / Feature:** M0 / all rows
- **Owner role:** Program Lead
- **Size:** S
- **Dependencies / blocked by:** every other M0 task

## Goal (user value)

The written record matches the code, so the next milestone's grooming starts from facts rather
than from re-reading diffs.

## Scope

1. `docs/FEATURE_MATRIX.md`: set the M0 rows to their true state —
   KU-001 … KU-009, MT-007, CW-001, NX-005, NX-006, OT-005 → `DONE`;
   **NX-007 → `PARTIAL`** with a note naming UI-013 and TD-007.
2. `TECH_DEBT.md`: update TD-007 (placeholder tokens shipped) and TD-002/TD-003 if M0 changed
   their situation; add a row for any compromise an M0 task accepted (each Implementation
   Report that says "deviation" must map to a row or to nothing at all).
3. `BLOCKERS.md`: annotate B-001 as "does not block M0 completion; tracked by UI-013".
4. `ARCHITECTURE.md`: apply the deltas the tasks were told to record — §4.5 precedence table,
   §4.6 link, §5 correlation-id rule and prefix rewrite, §6 stream-has-no-cursor note, §7
   golden wire format, §11 in-process caveat, §12 navigation implemented, §15 interceptor.
5. `docs/ROADMAP.md`: no change unless an M0 finding moved scope; if it did, say so and why.
6. `STATUS.md`: the M0 close entry — the CI run id, the exit-criteria evidence table, and CEO
   acceptance (PLAN §46).
7. `docs/plans/M0/RETROSPECTIVE.md`: a written delta, not a narrative — what the plan got
   wrong, which task estimates were off by more than double, and the concrete process change
   proposed for M1 (PLAN §39 Phase C).

## Non-goals

No new code. No re-opening decided ADRs (PLAN §39: reopening requires new evidence and a
superseding ADR).

## Design references

PLAN §35 (documentation requirements), §39 Phase C, §44 (feature matrix states), §46,
`docs/plans/M0/DEVPLAN.md` §9 (the definition of done this task verifies).

## Files to change

```
docs/FEATURE_MATRIX.md
TECH_DEBT.md
BLOCKERS.md
ARCHITECTURE.md
STATUS.md
docs/plans/M0/RETROSPECTIVE.md      (new)
```

## Acceptance criteria

```
$ grep -c 'RESEARCHING' docs/FEATURE_MATRIX.md      # M0 rows no longer researching
$ ./mill __.compile && ./mill __.test && ./mill e2e.test    # the evidence is real, not claimed
```

Every line of `DEVPLAN.md` §9 has an evidence link in `STATUS.md`: a CI run, a committed file,
or a test name. A criterion without evidence is not met.

## Tests required

None. This task's rigour is that it refuses to mark a row `DONE` without naming the test or
command that proves it.

## Observability / Degraded behavior

Not applicable.

## Docs to update

Listed above; they are the deliverable.
