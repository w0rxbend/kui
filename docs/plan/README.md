# `docs/plan/`

The working plan for turning this repository into the product drawn in
[`screens/`](../../screens) and read in
[`research/design/SCREENS-V4.md`](../../research/design/SCREENS-V4.md).

## What lives here

| File | Lifetime |
| --- | --- |
| `ROADMAP.md` | **long-lived.** The milestone order and the exit criterion of each. It is edited when a milestone's shape changes, never deleted. |
| `WAVE-NN.md` | **temporary.** The task packets of exactly one wave, in enough detail that one agent can execute one packet with no further questions. |
| `verification/W<NN>-<packet>.md` | **temporary, one wave.** One file per verification pass, written *before* the packet it verifies is declared frozen: one row per finding — file, exact mutation, suite command, and the case that would close it. It is the closer's input. Deleted with the wave file. |
| `README.md` | this file. |

There is at most **one** `WAVE-NN.md` at a time. If you are looking at two, the older one was not
pruned and its exit criteria should be checked before anything in it is believed.

**One deliberate exception, live right now.** `verification/` holds `W8-02.md`, `W8-04.md` and
`W8-07.md` — three wave-8 files carried past their wave file's deletion, because their open rows are
wave 9's closer's input and because the other seven passes were killed before they could write
anything. They are deleted with `WAVE-09.md`, and anything still true in them is folded into
`ROADMAP.md` first.

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
