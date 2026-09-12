# ADR-058 — The design captures are tracked, because they are the denominator of item 1

- Status: Accepted
- Date: 2026-09-12
- Supersedes: [ADR-057](ADR-057-design-captures-stay-outside-the-repository.md)

## Context

ADR-057 decided, hours before this one, that the twenty-three design captures stay outside the
repository and that every document saying otherwise be corrected to match `.gitignore:43`. Those
corrections were made and were accurate.

Then the repository's owner committed the captures — `582c9bc5 "Add screens"`, twenty-three PNGs
force-added past the ignore rule — and the listing claim that wave 14 had landed minutes earlier
caught the disagreement in the omission direction: `git ls-files` carried `screens` and
`ARCHITECTURE.md` §16 did not name it.

So the question ADR-057 answered is reopened by a decision that outranks it, and this ADR records
the reasoning rather than leaving a tracked directory contradicted by four documents and an ignore
rule.

**ADR-057 made the case against itself.** Its own Context section says:

> `docs/plan/ROADMAP.md`'s definition-of-done item 1 is *"Every screen in `screens/` renders real
> data"*, so the denominator of the project's own first exit criterion is a fact about one working
> tree and not about the repository. Anyone who clones this repository cannot run that criterion,
> cannot see the twenty-three captures, and cannot check the twenty-three-of-twenty-three claim
> every wave close since wave 10 has published.

ADR-057 read that as a reason to weaken the criterion's wording. It is a better reason to track the
files. An exit criterion whose denominator only exists on one machine is not an exit criterion; it
is a claim, and this project spent six waves building a gate whose entire purpose is to refuse
claims that nothing can check.

## Decision

**The captures are tracked. `.gitignore:43` is removed, ADR-057 is superseded, and every document
corrected by ADR-057 §1 is corrected back.**

### 1. What this buys

`ls screens | wc -l` and `git ls-files screens | wc -l` now agree at twenty-three, on any clone. The
twenty-three-of-twenty-three coverage claim — published at every wave close since wave 10 and
re-taken at each one — becomes reproducible by anyone, which it has never been. Definition-of-done
item 1 can be run rather than believed.

### 2. What it costs, stated plainly

Six megabytes per design revision, in history, forever. ADR-057's objection was correct about the
cost and wrong about the trade: this repository already commits `docs/api/openapi.json` and eleven
per-service documents that are generated and could be regenerated, on the reasoning that a reader
holding only the repository must be able to check what it claims. The captures are the same
argument with a larger file size.

If a future revision adds another twenty-three, the question to ask is not "delete these" but
whether the *previous* set is still the specification. A superseded capture set can be removed in
the commit that supersedes it, which keeps one set in the working tree and all of them in history —
the same shape `docs/plan/` already uses for waves.

### 3. The reading is still the artefact

`research/design/SCREENS-V4.md` remains the specification of record. The captures are evidence for
it, not a replacement: nothing should read a pixel where the reading states a measurement, and the
reading states measurements precisely so that a screen can be checked without opening an image.

## Consequences

- `.gitignore` no longer ignores `screens/`; `frontend/storybook-static/` is untouched and stays
  ignored, for the reason given beside it.
- `ARCHITECTURE.md` §16 names `screens/`, derived from `git ls-files` as §16 now requires.
- `research/design/SCREENS-V4.md` and `docs/plan/README.md` say the captures are tracked.
- `ADR-057` is marked Superseded and is not edited otherwise. Its reasoning is sound and its
  Context section is the clearest statement of why this decision went the other way.
- The `listing` claim in `scripts/feature-matrix-check.sh` gates the result in both directions and
  is indifferent to which way it was decided; it compares the document against `git ls-files`.
