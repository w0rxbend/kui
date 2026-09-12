# `docs/plan/`

The working plan for turning this repository into the product read in
[`research/design/SCREENS-V4.md`](../../research/design/SCREENS-V4.md) from the twenty-three
screenshots in [`screens/`](../../screens), which are **tracked**: `git ls-files screens` answers
`23`, so the link resolves in a clone and the coverage claim every wave close publishes can be
re-taken by anyone. [ADR-058](../adr/ADR-058-design-captures-are-tracked.md) is the decision and
says what it costs; it supersedes ADR-057, which decided the other way.

## What lives here

| File | Lifetime |
| --- | --- |
| `ROADMAP.md` | **long-lived.** The milestone order and the exit criterion of each. It is edited when a milestone's shape changes, never deleted. |
| `WAVE-NN.md` | **temporary.** The task packets of exactly one wave, in enough detail that one agent can execute one packet with no further questions. |
| `verification/W<NN>-<packet>.md` | **temporary, one wave.** One file per verification pass, written *before* the packet it verifies is declared frozen: one row per finding — file, exact mutation, suite command, and the case that would close it. It is the closer's input. Deleted with the wave file. |
| `README.md` | this file. |

There is at most **one** `WAVE-NN.md` at a time. If you are looking at two, the older one was not
pruned and its exit criteria should be checked before anything in it is believed.

**One deliberate exception, live right now.** `verification/` holds **thirty-eight** files and **they
were carried past `WAVE-13.md`'s deletion rather than deleted with it.** The four `W13-*` files are
wave 14's input — and there are four rather than five, because `W13-A1.md` was never written: that
packet's harness forbade it writing a report file, and its findings survive only in `ROADMAP.md`'s
wave-13 retrospective. Of the thirty older ones, three cannot be deleted without creating a dangling
reference — `W8-04.md` and `W8-07.md` are cited from `docs/FEATURE_MATRIX.md`, and `W9-03.md` from
`frontend/e2e/topics.spec.ts` and `brokers.spec.ts` — and `W8-07.md` §1 is in any case the only
published screen-to-spec-to-case mapping in this repository, used again at the wave-12 close to count
23 of 23 captures against a green browser run. The rest are discharged and cited by nothing but wave
files that are already gone. After W13-01's roster reconciliation, a dangling citation inside a
checked region is a gate failure rather than a broken link, which is the first time that decision has
had a mechanism behind it.

**House rule 18 is repaired for building packets and has now failed twice for adversaries, both
times because of the harness rather than the partition.** Wave 9 lost one verification file to a
partition argument and wave 10 lost three — each packet reasoned that this directory belonged to the
adversarial closer and left its rows in a packet result, and each verifier then wrote the file
itself. The rule was amended so that **`verification/W<NN>-<packet>.md` belongs to the packet the
file is about, always, and is never inside any other packet's `Owns`.** Wave 11 ran seven packets and
filed **seven** files, every one written by the packet it is about. The directory is nobody's tree.
**And that amendment was insufficient, twice.** W9-A1 and W13-A1 each ran a full adversarial sweep
and filed nothing, because naming an owner does not give it a pen: both were launched under harness
instructions that forbid writing report `.md` files at all. Wave 14 amends the rule again, about the
harness rather than the owner — **a packet that cannot write its verification file is not launched.**

## How a wave runs

1. `WAVE-NN.md` is written from `ROADMAP.md`: one packet per independently-ownable piece of work.
2. Every packet declares the files it **owns**. Ownership is partitioned so that no two packets in
   a wave can edit the same file. That is what makes the wave parallel; it is also the only thing
   preventing two agents from producing a merge that neither of them tested.
3. Every packet declares the **contract** it writes against — the existing types, endpoints and
   rules it must not break — and an **acceptance check** that is a command someone can run.
   A packet whose acceptance is "it looks right" is not finished being written.
4. The packets are executed.
5. Every verification pass writes `verification/W<NN>-<packet>.md` **first, as a stub naming the
   mutations it intends to apply, and fills a row the moment its mutation is run** — so that a
   finding is a row a later packet can close rather than a paragraph nobody reads. Wave 7's closer
   was written to consume those reports, never received them, and its eleven findings had **zero
   overlap** with the forty-four its verifiers had already made — which is why this is a file and not
   an intention. Wave 8 then proved *why it must be written first rather than last*: it ran at three
   of ten, not because anybody disagreed but because the file was a pass's last act and seven passes
   were killed mid-wave. A pass killed halfway must leave a partial file, which is strictly more than
   nothing.
6. When every packet's acceptance check passes, `WAVE-NN.md` and `verification/` are **deleted** and
   `WAVE-(NN+1).md` is written in the same commit. The deletion is the record that the wave closed; the git history
   holds what it said.

## Why the file is deleted rather than marked done

A plan directory that accumulates finished waves stops being a plan and becomes an archive that
somebody has to read before they can find the current one. Every stale checklist in this repository
has cost more than it saved — `docs/ROADMAP-SOLID.md` was contradicted by two commits within a day
of being written, and it is still being cited. `ROADMAP.md` is the only document here that is
allowed to describe work that has not happened yet, and it says what a milestone *is* rather than
which of its tasks are ticked, so it cannot go stale in that way.

## Where the other planning documents stand

* `docs/ROADMAP.md` — the historical M0–M8 record of how the backend was built. Not a plan for this
  work; not edited except to correct facts.
* `docs/ROADMAP-SOLID.md` — **superseded** by `docs/plan/ROADMAP.md`.
* `docs/FEATURE_MATRIX.md` — the capability register. It records what *is*, not what is planned, and
  a milestone is not finished until the rows it touched are correct.
