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
   KU-001 … KU-009, MT-007, CW-001, NX-005, NX-006, **NX-007** and OT-005 → `DONE`.
2. `TECH_DEBT.md`: rewrite TD-007 to the decision UI-002 took (KUI owns its token set; the
   remaining debt is optional reconciliation), update TD-002/TD-003 if M0 changed their
   situation, and add a row for any compromise an M0 task accepted (each Implementation Report
   that says "deviation" must map to a row or to nothing at all).
3. `BLOCKERS.md`: move B-001 to **Resolved**, with the note "decided around: KUI owns its
   design token set (UI-002); reconciliation with an import is optional (UI-013)". A blocker
   owned outside the execution loop is closed by deciding around it, not by waiting — if a
   later blocker appears, the same rule applies: propose the decision, take it, record it.
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
