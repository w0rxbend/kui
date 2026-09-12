# ADR-057 — The design captures stay outside the repository, and every document that said otherwise is corrected

- Status: Superseded by [ADR-058](ADR-058-design-captures-are-tracked.md)
- Date: 2026-09-12

## Context

Three documents in this repository disagreed with a fourth about whether twenty-three PNG
screenshots are part of it, and the disagreement had survived five waves of documentation repair
because nothing here has ever compared a documented listing against `git ls-files`.

What is actually true, measured on 2026-09-12:

```
$ git ls-files screens | wc -l
0
$ git log --all --oneline -- 'screens*'          # empty: never committed
$ grep -n 'screens/' .gitignore
43:screens/
$ ls screens | wc -l                             # a working tree, not the repository
23
```

`.gitignore:43` is not an accident. The three lines above it say why:

> Design captures. `research/design/SCREENS.md` records that this repository keeps screenshots
> outside itself; the reading of them is what is committed (`SCREENS-V4.md`), not the pixels. Six
> megabytes of PNG per design revision is not history anybody will thank us for keeping.

And `research/design/SCREENS.md:17` states the same rule for the v3 captures, in words, and gives
the reason:

> **Where the images are.** `.agent/design/screens/`, outside the repository by way of
> `.git/info/exclude`, for the same reason the artboard is: design source does not enter this
> repository, but a reading of it must be reproducible by someone holding the source.

Against that, at `3bd6c8cf` — the commit these line numbers are taken from —
`research/design/SCREENS-V4.md:3` said the captures were *"held in `screens/` in this
repository"* and `:34` said *"`screens/`, committed, twenty-three PNGs"*. `docs/plan/README.md:4`
linked `[`screens/`](../../screens)` — a link that resolves to nothing in a clone — and
`docs/plan/ROADMAP.md:4` names the directory as where the product is *"drawn"*, without a link.
And `ARCHITECTURE.md` §16 listed `screens/` as a top-level repository
directory, added there by the wave-13 rewrite **of the section whose entire subject is directories
that are not on disk**, because that rewrite derived the tree with `ls` on a working tree and `ls`
cannot tell a tracked directory from an ignored one.

This is not only a documentation defect. `docs/plan/ROADMAP.md`'s definition-of-done item 1 is
*"Every screen in `screens/` renders real data"*, so the denominator of the project's own first exit
criterion is a fact about one working tree and not about the repository. Anyone who clones this
repository cannot run that criterion, cannot see the twenty-three captures, and cannot check the
twenty-three-of-twenty-three claim every wave close since wave 10 has published.

Three answers were defensible and the decision had to be taken in the open rather than left as a
three-way contradiction.

## Decision

**The captures stay outside the repository. `.gitignore:43` stands, and every document that said
otherwise is corrected to say what is true.**

### 1. What is corrected, and to what

| Document | Said | Now says |
| --- | --- | --- |
| `research/design/SCREENS-V4.md` §head, *Where the images are* | *held in `screens/` in this repository*, *`screens/`, committed* | the captures are in an ignored working-tree directory, with `git ls-files screens` → `0` quoted beside the claim |
| `ARCHITECTURE.md` §16 | `screens/` listed as a top-level directory | removed from the listing; the section states in words that the captures are untracked, and its derivation is `git ls-files` rather than `ls` |
| `docs/plan/README.md` §head | `[`screens/`](../../screens)` | the link is gone and the absence is stated |

### 2. The reading is the artefact, not the pixels

This is the rule `SCREENS.md:17` already stated and it is now the project's, not one document's: a
design capture is *source material*, and what this repository commits is a **reading** of it —
`REFERENCE.md`, `SCREENS.md`, `SCREENS-V4.md` — in which every colour is quoted as a hex value at
the coordinate it was sampled from and every distance as a device-pixel span with its CSS value
beside it. A reader without the images can still check every derived number against the shipped
tokens and the shipped CSS, which is the check that actually matters; a reader with the images can
re-take any measurement, because the file names are quoted throughout and indexed in
`SCREENS-V4.md` §1.

### 3. What this costs, stated rather than glossed

Three things, and the third is the one that reaches outside this document.

1. **A clone cannot re-run a measurement, only check it.** Every `magick` command in
   `SCREENS-V4.md` §0 needs the PNGs. The mitigation is that the *results* are written out, not
   referred to: the twenty-eight-sample colour table, the frame scan of row 1100, the capture-scale
   derivation. Nothing in either reading says "see the capture" in place of a number.
2. **A future design revision has the same problem again**, and nothing here prevents somebody
   writing *"committed"* a second time. That is what the listing claim in
   `./scripts/feature-matrix-check.sh` is for: a documented listing is compared against
   `git ls-files`, in both directions, so a phantom path and an omitted one both fail a run rather
   than being noticed five waves later.
3. **Definition-of-done item 1 names a subject a clone cannot see.** *"Every screen in `screens/`
   renders real data"* is, as written, a criterion about one working tree. The honest reading is
   that the item is really about the twenty-three screens **indexed as `M01`…`M23` in
   `research/design/SCREENS-V4.md` §1** — which is committed, is stable, and is what every wave
   close has actually counted against. Rewording it is `docs/plan/ROADMAP.md`'s to do and that
   document is the integrator's, so this ADR records the finding and the proposed wording rather
   than making the edit.

### 4. What was rejected, and why

**Commit the twenty-three PNGs and delete `.gitignore:43`.** Rejected. It is roughly six megabytes
per design revision, in a history that keeps every revision for ever, for files that are
regenerated wholesale rather than edited — and the repository already keeps the only artefact that
survives a redesign, which is the reading. `.gitignore`'s own comment made this argument before this
ADR existed and nothing has changed it.

**Commit a reduced-size set** — thumbnails, or a subset. Rejected, and this is the more interesting
rejection: a reduced capture cannot answer the questions the readings were taken to answer. Every
colour in `SCREENS-V4.md` §0 is a hex sample at a named pixel and every distance is a device-pixel
scan across a boundary; both are destroyed by resampling. A committed image nobody can measure would
be worse than no image, because it would look like evidence.

**Leave it.** Not defensible, and it is the state this ADR ends. Three documents saying *committed*
and a fourth plus `.gitignore` saying otherwise is the exact shape of defect this project has spent
five waves removing, sitting on the subject of its own first definition-of-done item.

## Consequences

- `research/design/SCREENS-V4.md`, `ARCHITECTURE.md` §16 and `docs/plan/README.md` no longer claim
  the captures are in the repository; each states the absence with the command that shows it.
- `research/design/SCREENS.md` is unchanged. It was right all along, and `:17` is the sentence the
  other documents contradicted.
- A reader who wants the images asks whoever holds the design source. There is no index of who that
  is, and inventing one here would be a process nobody follows.
- `docs/plan/ROADMAP.md`'s item 1 wording is left to the integrator, with §3.3 above as the proposed
  replacement subject.
