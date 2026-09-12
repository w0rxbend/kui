# Wave 15 — the gate that reads listings, read against itself

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 15, when waves 9, 10, 11, 12, 13 and 14 were each written to be the last.**

Wave 14 did the load-bearing thing it said it would, and it is the fourth wave in a row that did.
The listing claim exists, it derives from `git ls-files` in every reader, it is gated in both
directions by eight new fixtures, and it caught a change to the repository that arrived from outside
the wave within minutes of the commit. Reproduced here, on the shipped bytes, at the wave-15 open:

```
$ # ├── benchmarks/  nothing      -- added to ARCHITECTURE.md §16's fenced tree
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: ARCHITECTURE.md (checked: listings #1) asserts [benchmarks/] as a name in the
  repository layout and `git ls-files` carries no such name. …
feature-matrix-check: 1 disagreement(s) over 472 compared claims.        # exit 1
```

Wave 13's prediction reproduces too, on the same tree — a false quotation appended inside
`TECH_DEBT.md`'s `debt-register` block is one disagreement over 472 — so both of the last two waves'
acceptance sentences are still red. `ARCHITECTURE.md` restored byte-identical
(`md5sum 63f0a2654d1b7900efdab3629f304404`), `TECH_DEBT.md` likewise
(`264b1745bddb526c4945f565b3b89e71`).

**And item 4 is still not met, and this time three of the five reasons are the new gate's own.**

The wave built a reader for documented listings. Here is the same document, the same section, one
line, no code mutation, run at the wave-15 open:

```
$ # ├── NOTICE  CODEOWNERS  Makefile      -- added to ARCHITECTURE.md §16's fenced tree
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: 472 claims checked, all true.                      # exit 0
```

Three phantom top-level names, accepted. `listing_is_name` answers true only for a token ending in
`/`, a token whose final extension is in `listing_file_extensions`, or a dotfile — so an
extensionless top-level **file** is invisible to the phantom direction, and `screens` drawn without
its trailing slash (the wave's own subject, in the wave's own spelling-minus-one-character) is
accepted too. §16 already draws five real names of that shape. **The gate wave 14 built to compare a
documented listing against the repository does not see one of the two shapes the document actually
uses**, and it was disclosed nowhere: W14-01's verifier found it, W14-A1 closed the dotfile quarter
of it and filed the rest, and nothing in §7 of the packet's report names it as the honest limit.

**And the repository moved under the wave, in the direction nobody planned for.** `582c9bc5`
("Add screens", 2026-09-12 11:52, the repository owner, after all three verification passes had
filed) force-added the twenty-three PNGs past `.gitignore:43`. `git ls-files screens` now answers
**23**. `ARCHITECTURE.md` §16 was repaired by the integrator to say so. Four other documents were
not, and each of them publishes a **command and its answer**:

```
$ grep -n 'answers `0`' research/design/SCREENS-V4.md docs/plan/README.md
research/design/SCREENS-V4.md:37:…`git ls-files screens` answers `0`,
docs/plan/README.md:7:…`git ls-files screens` answers `0` and `git log --all -- 'screens*'`
$ git ls-files screens | wc -l
23
```

`docs/adr/ADR-057-design-captures-stay-outside-the-repository.md` — **Status: Accepted, dated the
same day** — states the same thing at greater length, and `.gitignore:40-42`'s comment states it a
fifth time. The wave's own mechanism cannot refuse any of them: a sentence is not a fenced listing,
and none of those five places carries a marker.

**Worse, and this is the one a clone notices.** ADR-057 is **untracked**:

```
$ git ls-files 'docs/adr/ADR-057*' | wc -l          # 0
$ ls docs/adr/ADR-*.md | wc -l                      # 57
$ git ls-files 'docs/adr/ADR-*.md' | wc -l          # 56
$ grep -c 'ADR-057' DECISIONS.md                    # 1, a row that links it
$ ./scripts/feature-matrix-check.sh | tail -1
  DECISIONS.md: 57 rows over 57 ADRs in docs/adr.
```

`DECISIONS.md` carries a row linking a file **a clone taken today does not have**, and the
`adr-index` section — 114 claims, the largest in the script — prints *57 over 57* because
`adr_files` is built with `find docs/adr`. That is **house rule 27's exact shape inside the gate
that exists to enforce house rule 27**, and the wave knew: the comment at
`scripts/feature-matrix-check.sh:4715` says so in words and files it rather than fixing it.

**This is the sixth close at which the shape has been named rather than discovered, and the shape
has changed.** Waves 9 through 13 were open on a *kind of fact nothing read* — a figure, a sentence,
a quotation, a roster, a listing. **Wave 15 is open on the readers themselves being read.** Three of
the five things below are defects in the gate, not in a document: a blind spot in the newest claim, a
`find` in the oldest section, and a published banner asserting a property of a page that is a
one-time manual sweep. The fourth is a decision a human made that five documents contradict. The
fifth is a table of component names in the document a newcomer is sent to, promising it *"names the
file that draws it"* over three files that do not exist.

**Wave 15 is three building packets and one closer, at 3:1.** No new service, no new endpoint, no new
feature package, no new screen. No new claim *kind* either — every repair below is a derivation
swapped, a marker moved, or a sentence made true.

---

## The definition of done, judged item by item at the wave-14 close

Every figure below was printed by the command beside it on **2026-09-12**, by the integrator judging
this close, one command at a time, against a quickstart brought up from this tree and the
eleven-container distributed stack built from the same one. **Nothing below is read off a packet
report. Every line was re-run here, including the four the four packet reports agreed on, and two of
the numbers came back different from the ones the reports published.**

| # | Item | Verdict | Command, and what it printed |
| --- | --- | --- | --- |
| 1 | Every screen in `screens/` renders real data | **MET** | `ls screens/` → **23**; `git ls-files screens` → **23** (first close at which those two agree); `pnpm -C frontend e2e --reporter=list` → **108 passed, 2 skipped, 0 failed** over 110 against a live quickstart; **23 of 23** captures have a named case that ran green in that run, mapped below; the stack answered real broker data at `/api/v1/clusters` (`kafkaClusterId: kui-quickstart-0000000001`, `onlinePartitionCount: 169`, `totalDiskUsageBytes: 281160`) |
| 2 | Every backend capability exists as a service or an endpoint | **MET** | `ls services/` → **11**; `./mill --no-daemon __.openApiCheck` → **2544/2544 SUCCESS**; `./mill --no-daemon checkArchitecture` → **195 modules, 10 rules, no layering violations** |
| 3 | Unit and component tests, a11y in both themes, a browser suite | **MET** | `./scripts/run-tests.sh` → **81 modules (81 with tests), 4,420 test cases, all passing** (wave 13: 4,412); `pnpm test` → **1,936 over 83 files**; `pnpm typecheck` → exit 0 over both projects; `pnpm a11y` → **782 stories × 2 themes, no violations** (budget line: *load 27.3 over 16 cores, theme budgets 20/40/80s*); `pnpm lint:boundaries` → **423 files in 11 packages**; `./mill __.checkFormat` **495/495**, `__.fix --check` **10672/10672** |
| 4 | Documented: README, ARCHITECTURE, ADRs, an accurate FEATURE_MATRIX, an overview | **NOT MET** | five defects measured below, three of them in the gate itself; `git ls-files 'docs/adr/ADR-057*'` → **0** against a `DECISIONS.md` row that links it |
| 5 | One command brings the whole product up under `docker compose` | **MET** | `./deployment/quickstart/quickstart.sh` → exit 0, *"KUI is running: http://localhost:8090/ui/"*, `connect-seed` exit 0; `down` → *"Removed. Nothing from the quickstart is left running or stored."*; `./deployment/compose/smoke.sh` → **PASSED** over the eleven-container stack, fault isolation and recovery included |

### Item 1, measured rather than assumed — and its index is still a hand roster

`docs/plan/verification/W8-07.md` §1 is still the only **published** screen-to-spec-to-case mapping
in this repository. It was taken mid-wave-8 and it is **stale in three rows**: it scores `M08`
uncovered, `M14` partial and `M20` uncovered. All three now have a case, and all three ran green in
this close's run:

```
✓   78 shell.spec.ts:301  the shell › switching cluster names where you have arrived   (M08)
✓   95 topics.spec.ts:482 … › a bulk delete says how many it deleted, in the plural    (M14)
✓   52 ksql.spec.ts:111   ksqlDB › the gateway routes the objects endpoint …           (M20)
✓   16 brokers.spec.ts:371 … › follows a cluster change into the second cluster's …    (M09)
```

Per-spec green counts from this run: `alerts` 5, `brokers` 11, `connect` 4, `consumers` 6,
`dashboard` 9, `features` 16, `ksql` 4, `messages` 7, `search` 8, `shell` 9, `topics` 20,
`traffic` 11 — **108**. The two skips are `alerts.spec.ts:270` and `ksql.spec.ts:175`, each naming a
*different deployment*; no third skip appeared.

**So the count is 23 of 23, and here is the honest qualification the last three closes have not
made.** *Nothing in this repository reconciles the `M01`…`M23` index against the case list.* The
mapping lives in a wave-8 verification file that is stale in three rows, in a `WAVE-13.md` re-take
that has been deleted, and in this paragraph. A screen's coverage is asserted by a human matching a
case title to a screen id by eye, once per close, five closes running. **`M01`…`M23` is a hand roster
in the sense of house rule 26, it is the denominator of definition-of-done item 1, and it is
reconciled by nothing.** ADR-057 §3.3 proposes *moving* item 1's denominator onto that roster, which
would make the project's first exit criterion depend on a list nobody derives. That is W15-02's row
and it is stated here because it changes what item 1 means, not merely how it is checked.

### Item 4, the five things it is open on

Each measured at the wave-15 open with the command beside it, on the shipped bytes, tree restored
byte-identical after every mutation.

1. **The listing claim is blind to extensionless names, which is one of the two shapes §16 draws.**
   ```
   $ # ├── NOTICE  CODEOWNERS  Makefile   added to ARCHITECTURE.md §16
   $ ./scripts/feature-matrix-check.sh     # 472 claims checked, all true.   exit 0
   ```
   `listing_is_name` accepts a trailing `/`, a known extension, or a leading `.`; nothing else. §16
   itself draws `mill`, `LICENSE`, `repo.txt`, `build.mill` and five more in column one, so the rule
   is silently unenforced on entries the document already carries — and `├── screens` **without** the
   slash, the wave's own defect one character short, is accepted. W14-01's verifier found it,
   W14-A1 closed the dotfile quarter and filed the rest against a real obstacle: §16 separates its
   two columns with eight spaces on one line and one space on another, so no lexical rule can tell
   `NOTICE` from `the`. **The fix is the document's shape and the rule together, and it is why
   W15-01 and W15-02 both have a row for it.**

2. **`adr_files` is built with `find`, and it is hiding an untracked ADR from a green run.**
   `ls docs/adr/ADR-*.md | wc -l` → 57; `git ls-files 'docs/adr/ADR-*.md' | wc -l` → **56**;
   `DECISIONS.md` → 57 rows; the gate prints *57 rows over 57 ADRs* and exits 0. A clone taken today
   has a `DECISIONS.md` row linking a file it does not carry. The comment at
   `scripts/feature-matrix-check.sh:4715` describes this exactly and files it. **House rule 27, in
   section 5 of the script that enforces house rule 27, and it is currently false in a clone.**

3. **Five places say the design captures are not in this repository, and they are.**
   `git ls-files screens` → **23**. `research/design/SCREENS-V4.md:35-38` (*"ignored and never
   committed"*, *"`git ls-files screens` answers `0`"*), `docs/plan/README.md:5-8` (the same command
   and the same answer), `docs/adr/ADR-057-*.md` (**Status: Accepted**, the whole document),
   `.gitignore:40-42`'s comment, and `DECISIONS.md`'s ADR-057 row. `ARCHITECTURE.md` §16 was repaired
   at integration and is the one that now agrees with the index. **This is a decision, not a repair:
   either the captures come back out (`git rm --cached screens/*.png`, after which all five are
   already correct) or ADR-057 is superseded. An integrator may not reverse a human's own commit and
   a gate cannot choose.** Filed as **TD-059**.

4. **`docs/frontend/README.md`'s "names the file that draws it" table names three files that do not
   exist.** `:674` promises *"names the file that draws it, so a wrong pixel can be traced to a line
   without a search"*; `:681-684`'s last column names `Sidebar`, `CapabilityBanner` and
   `FeatureFallbackPanel`.
   ```
   $ for n in Sidebar CapabilityBanner FeatureFallbackPanel; do git ls-files | grep -ic "$n"; done
   0
   0
   0
   ```
   The banner's section-by-section disclosure lists `FeatureFallbackPanel` under a *different*
   section and does not name this table at all. `:741` *Rendering a `Section`* is not in the list
   either and publishes Scala constructor syntax — `Ok(data, fetchedAt)`, `Unavailable(reason,
   message, since)` — for a TypeScript discriminated union at
   `frontend/packages/api/src/section.ts:44` whose sixth member, `"unreadable"`, the table omits.
   **A disclosure that names four sections and misses two is the defect this project has repaired in
   five documents, in the document it was written to fix.**

5. **The same banner publishes a property it does not have, and a line count called an identifier
   count.** It asserts *"Every path this page names is resolved against `git ls-files` and not
   against a working tree"*:
   ```
   $ sed -i 's#shell/src/health\.ts#shell/src/healthz.ts#' docs/frontend/README.md
   $ ./scripts/feature-matrix-check.sh     # 472 claims checked, all true.   exit 0
   ```
   It is a one-time manual sweep, not a property. And it proves *"no `kui.ui.*` identifier outside
   this note, where the one below is a quotation"* with `tail -n +40 … | grep -c`, which counts
   **lines**; line 897 carries `kui.ui.kernel.prefs` **and** `kui.ui.kernel.theme`. Published figure
   1, true identifier count 2, in the sentence arguing that the page's figures are measured.

**And eighteen ungated rules the wave found and did not close.** W14-A1 filed 27 rows, closed 15
outright, closed 2 in half, and declined 10 with the owner named. Of the declined, eight are blocked
on prose or a marker in a document that packet did not own — **the same partition result wave 13
reported (11 of 22)**, which is now the most repeated finding in the project after the disclosure
rate. They are listed in W15-01 and W15-02's briefs with the exact mutation.

### What is NOT open, and should not be re-hunted

* **The listing class is real and gated in both directions**, for the shapes it sees. Both
  reproductions at the head of this file are red on the shipped bytes. The omission direction works —
  it is what caught `582c9bc5` within minutes. Do not re-litigate the mechanism; fix its blind spot.
* **The quotation class is closed**, third wave running, reproduced here.
* **The roster class is closed** — `marked_blocks_on_disk`, the prune list and the `docs/plan`
  exclusion are all gated.
* **`connect-seed.sh` is now driven by a real harness.** W14-A1 added four `ConnectSeedSuite` cases:
  the FAILED short-circuit, the non-2xx registration arm, the announce guard counted rather than
  tested for membership, and the `-ge 2` threshold. All four go red under the mutation that
  motivated them. `libs.config.test` is 395/395.
* **The storybook image copies every workspace manifest**, gated by `ShippedImageManifestSuite`
  derived from `git ls-files` in both directions — *and its stated justification is wrong*, see the
  W15-03 brief. The gate is right; the comment beside it is not.
* **Every gate in the tree table below is green from images built from this tree**, re-run at this
  close. Do not re-run the browser suite or either deployment shape to prove a documentation repair.

---

## The tree you start from

Every figure printed by the command beside it at the wave-14 close, one gate at a time, on an
otherwise idle machine (`docker ps -a` empty, nothing listening on `:6017`, `:8080`, `:8090`).

| Gate | Figure |
| --- | --- |
| `./scripts/run-tests.sh` | **4,420 cases over 81 modules**, all 81 carrying tests (wave 13: 4,412) |
| `pnpm -C frontend test` | **1,936 over 83 files** |
| `pnpm -C frontend e2e` | **108 passed, 2 skipped, 0 failed** over 110 |
| `./scripts/feature-matrix-check.sh` | **472 claims over 13 sections**, all true (444 over 12) |
| `./mill __.checkFormat` | 495/495 |
| `./mill __.fix --check` | 10672/10672 |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2544/2544 |
| `./mill build-tests.test` | 139/139 SUCCESS (115 cases; `BundleShapeSuite` is alive, 12 cases — the wave-14 question is answered) |
| `pnpm -C frontend typecheck` | exit 0 over both projects |
| `pnpm -C frontend lint:boundaries` | **423 files in 11 packages** |
| `pnpm -C frontend a11y` | **782 stories × 2 themes, no violations** |
| `./deployment/quickstart/quickstart.sh` | exit 0, `connect-seed` exit 0, two registered clusters |
| `./deployment/compose/smoke.sh` | **PASSED** over eleven containers |

`feature-matrix-check.sh`'s section sizes: self-check 7, rows 30, merged-document 50, milestones 49,
**adr-index 114**, openapi-totals 15, **guard-fixtures 37**, capability-claims 44, quotations 12,
debt-register 3, **listings 18**, gate-table 5, dependencies 88 over 12 named manifests.
`wc -l scripts/feature-matrix-check.sh` → **5,559**.

**Anything red is yours. Nothing is red.** The five things that are wrong are three gate defects, one
decision and one table, and the gate is green over every one of them.

---

## House rules

The first twenty-seven are waves 8 through 14's and still apply in full; read them in the git
history of `docs/plan/WAVE-14.md`. Four are amended and one is new.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not
otherwise changing.**

5. **Report a mutation that stayed green. Twelfth wave, no movement.** Three building packets, three
   disclosures — each exactly the one its brief demanded, none more — and **twenty-seven** more found
   by the verifiers and the closer. The rate has not moved in twelve waves and it has never once been
   improved by being measured. Stop expecting it to; budget the verifier instead.
17. **AMENDED: a published figure is re-derived after the last edit, and a figure derived by a
    command is not the same as a figure the command *answers*.** W14-01's own report published *"45
    backticked tokens"* (47), *"42 of 42 resolve"* (41 of 42) and shipped a code comment saying *"40
    of them resolve"* — three figures, one subject, in the packet whose whole subject is documented
    figures nobody re-derives. And `docs/frontend/README.md` publishes `grep -c` over **lines** as a
    count of **identifiers**. **If the figure and the command disagree about what is being counted,
    the command is the bug.**
18. **AMENDED, fourth failure, and the harness amendment did not hold.**
    `docs/plan/verification/W14-03.md` exists and was written by its *verifier*, not by the packet;
    the packet's own report survives only in a JSON field the integrator folded in by hand. That is
    W9-A1, W13-A1 and now W14-03. **A packet is not launched until its harness has written a
    zero-byte `docs/plan/verification/W<NN>-<packet>.md` and shown it on disk.** Naming the owner did
    not work; naming the harness did not work; prove the pen exists before the packet starts.
21. **Unchanged, and it produced 15 of 27 outright plus 2 in half.** Ten declined, and **eight of the
    ten were blocked on prose or a marker in a document the closer did not own.** That is the same
    partition result wave 13 reported. **This wave's partition is written against it: W15-01 owns
    the checker and every marker, W15-02 owns every document's prose, and they are told to land in
    that order and to publish the line ranges.** If this does not fix it, the conclusion is that the
    partition is wrong, not that the closer is slow.
23. **Unchanged, and it decided this wave again.** Wave 14 predicted the listing claim would turn
    item 4 into a gate; the prediction is red on the shipped bytes. **And the inverse — the same
    document, the same section, one line, no code mutation — is green.** Test the prediction and its
    inverse; the inverse is where the next wave lives. Fourth wave running.
28. **NEW: the reader is a claim too.** Wave 13 proved a roster is a claim. Wave 14 proved a
    documented listing is a roster. **Wave 15's three gate defects are all the next level out: a
    derivation (`find` for ADRs), a shape rule (`listing_is_name`), and a published property of a
    page (*"every path is resolved against `git ls-files`"*) that is a human's one-time sweep.** A
    gate that is not driven over an input it was written to refuse is prose with an exit code. Every
    rule you ship this wave is driven by a `guard-fixtures` case or it is disclosed under house
    rule 5 — there is no third option left.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
and `npx --yes pnpm@11.25.0 <script>` works from `frontend/`. Use `./mill --no-daemon` for anything
you record a number from. **The a11y sweep is three commands** — build Storybook, serve
`storybook-static` on `:6017`, sweep — it prints the per-theme budget it chose for the machine's
load, and **that line is part of the measurement**; `npx http-server` leaves a listener, kill what
`ss -ltnp` names. `./mill a.test b.test` runs **zero** tests and exits 0: the second word is a munit
name filter. `./mill build-tests.test` prints a task count (139), not a case count (115) — do not
publish the one as the other.

**And one about Docker that cost the wave-14 integrator an hour.** `./mill
deployment.docker.__.build` reports SUCCESS from cache and **will not notice that you deleted the
image**. A run that trusts that SUCCESS drives whatever the daemon happens to hold. `./mill clean
deployment.docker` first, every time you are about to record a figure from a container.

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **472 claims over 13 sections**; `close_section` literals 37/44/12/3/5/18; fixtures 26–37 | **W15-01**, which must raise the total and not lower it, and must not weaken a fixture |
| `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md`, `docs/frontend/README.md`, `README.md`, `docs/overview/README.md`, `docs/plan/README.md`, `research/design/SCREENS-V4.md` | every figure inside a `<!-- checked: -->` region, and the `gate-table` block's **472** | **W15-02** for prose and figures; **W15-01** for markers only |
| `DECISIONS.md` + `docs/adr/**` | 57 rows over 57 files, ids unique, every row's link resolving **in the index** | **W15-02** |
| `TECH_DEBT.md` | 59 rows, highest `TD-059`, next free `TD-060`, ids unique | **W15-03** (rows), **W15-01** (markers only) |
| `frontend/e2e/**` | **108 passed, 2 skipped, 0 failed.** Both skips are deployment-shaped; **no packet may add a third** | nobody this wave without saying so |
| `libs/config/test/.../ConnectSeedSuite.scala`, `ShippedImageManifestSuite.scala` | wave 14's two new suites — six cases and two | **W15-03** may add or correct a *comment*, never weaken a case |
| `deployment/quickstart/seed/connect-seed.sh` | the FAILED short-circuit, the `2*)` arm, the announce guard, `-ge 2`, the guard's position | **W15-03** alone |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 | every packet |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W15-01 — The readers, read

**Owns:** `scripts/feature-matrix-check.sh` entire, and the `<!-- checked: -->` markers in any file
it needs one in — markers only, never prose or a figure inside another packet's document.
**Lands first.** W15-02 edits inside the ranges you publish.

1. **Close the extensionless blind spot, and close it by reconciling a set rather than by widening a
   lexical rule.** The measured defect is at the head of this file: three phantom top-level names
   accepted, and `├── screens` without its slash accepted. **Do not add `NOTICE` to a list of
   accepted spellings** — that is the roster mistake house rule 26 exists for, and W14-A1's report
   argues correctly that no lexical rule can separate §16's two columns (eight spaces on one line,
   one space on another). The close is a **shape contract with W15-02**: §16 draws each top-level
   entry so that column one is decidable — one entry per line, or every column-one name in
   backticks — and `check_repository_layout_region` reconciles that column as a **set** against
   `git ls-files | cut -d/ -f1 | sort -u`, which the omission half already computes. Agree the shape
   with W15-02 in writing, in your report, before you write the reader.
2. **Replace `adr_files`' `find docs/adr -maxdepth 1` with `git ls-files 'docs/adr/ADR-*.md'`, and
   let the run go red.** It will: 56 tracked against 57 rows in `DECISIONS.md`. **That refusal is
   correct and it is the point** — a `DECISIONS.md` row linking an untracked file is a dangling link
   in every clone. Do not soften it; land the derivation, publish the refusal text in your report,
   and W15-02 makes it green by committing or superseding ADR-057. If the two packets land out of
   order the gate is red for one integration and that is the right failure. Sweep
   `service_directory_names` and `package_directory_names` in the same pass — both are `find`-shaped
   and both are section 5's.
3. **Drive every claim you already ship that nothing drives.** W14-A1 declined three rows for
   defensible reasons and they are still open: `listing_cited_path_count`'s independence from
   `listing_cited_paths` (close it as a **reader of the shipped script**, `ShippedScriptGuardPosition
   Suite`'s shape, asserting the body does not call the first reader — the two are
   input-indistinguishable so no fixture can do it); the never-empty `listing_index` guard (close it
   or **delete the branch**, and deleting it is an acceptable answer this wave); and
   `check_repository_layout_region`'s `local where=$1 text=$2 … residue_text=$text`, which reads the
   **caller's** `text` and dies under `set -u` in any driver that has none — one token,
   `residue_text=$2`.
4. **Close the eight W14-02 rows that were declined for want of a marker or a claim kind**, each
   named with its filed id in `docs/plan/verification/W14-A1.md`: V-04 (a one-character path change
   in `docs/frontend/README.md` stays green — put `$frontdoc` in scope of `audit_cited_paths`, with
   the three shape rules W14-A1 measured: a token carrying `<`, `>` or `*` is a template, a relative
   link resolves against the citing document's directory, and a rostered absence needs its
   `path::fragment`); V-06 (the ReasonCode table compared cell-for-cell against
   `frontend/packages/api/src/constants.generated.ts`); V-07 (the banner's two published `grep -c`
   proofs re-run and compared to the numbers beside them — and see W15-02's row 4, because one of
   them counts the wrong thing); V-09 and V-12's services half (both blocked on prose W15-02 moves
   first — coordinate, do not guess); V-11 (`all fifty-seven` in `docs/overview/README.md`, whose
   cleanest close is moving the sentence inside the checked region 200 lines below that already
   gates the same figure).
5. **Disclose one rule of your own that survives mutation with the run green**, in the shape house
   rule 5 asks for. The script is 5,559 lines and five consecutive waves have each found one inside
   the hour. **And put it in §5 Disclosures** — W14-01 put its most load-bearing one (the
   path-component anchoring it called *"not a detail, it is the comparison"*) only in a code comment
   and a `needsOutsideOwnership` field, which is where a disclosure goes to not be read.

**Acceptance:** `./scripts/feature-matrix-check.sh` exits 0 with a total **above 472**, with
W15-02's §16 shape landed; **`├── NOTICE  CODEOWNERS  Makefile` in §16 is RED and the refusal text is
quoted in your report**; `├── screens` without its slash is RED; `git ls-files 'docs/adr/ADR-*.md'`
is the ADR derivation and `grep -nE 'find |ls ' ` over your diff has every surviving hit argued;
fixtures 26–37 unchanged and still red under their own mutations; the line ranges you wrapped
published for W15-02.

---

## W15-02 — The decision, and the documents that describe a different repository

**Owns:** `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md`, `docs/frontend/README.md`,
`docs/overview/README.md`, `README.md`, `research/design/SCREENS-V4.md`, `docs/plan/README.md`,
`DECISIONS.md`, `docs/adr/**`, and `.gitignore`'s comment block at `:40-43`.

1. **Settle `screens/`, in the open, and make all five places agree with `git ls-files`.** TD-059.
   Two exits and **you must take one, not describe both**:
   *(a)* `git rm --cached screens/*.png` — after which ADR-057, `SCREENS-V4.md`, `docs/plan/README.md`
   and `.gitignore`'s comment are **already correct** and only `ARCHITECTURE.md` §16 moves back; or
   *(b)* an ADR superseding 057 that says why six megabytes per design revision became worth keeping,
   after which four documents and one `.gitignore` comment move. **ADR-057 is Accepted and an
   Accepted ADR is superseded, never edited** — if you choose (b), 057 keeps its text and gains a
   `Superseded by` line. **This is a decision the repository owner made with a commit**
   (`582c9bc5`, 2026-09-12 11:52, `git check-ignore --no-index -v` still reports `.gitignore:43`
   against every file); if you choose (a) you are reversing it, so say so in the ADR and in your
   report, in those words.
2. **Commit ADR-057 — or delete it and its `DECISIONS.md` row together.** `git ls-files
   'docs/adr/ADR-057*'` → 0 while `DECISIONS.md:64` links it. W15-01's derivation change makes this
   a red run until you do. There is no third state: an ADR that exists on one machine is not a
   decision this project has recorded.
3. **Draw §16 so its first column is decidable, in the shape you agreed with W15-01.** One top-level
   entry per line, or every column-one name in backticks. This is the *document* half of defect 1 and
   W15-01 cannot land without it. Do not also rewrite §16's prose in the same edit — W15-01's markers
   are in it.
4. **Repair `docs/frontend/README.md`, which is the document a newcomer is sent to by
   `README.md:466` and which fails its own banner three ways.** *The rendering rules, and where each
   one lives* (`:674`) promises *"names the file that draws it"* and names `Sidebar`,
   `CapabilityBanner` and `FeatureFallbackPanel` — `git ls-files | grep -ic` answers 0, 0, 0.
   *Rendering a `Section`* (`:741`) publishes Scala constructor syntax for the TypeScript union at
   `frontend/packages/api/src/section.ts:44` and omits its sixth member, `"unreadable"`. And the
   banner's own proof, *"no `kui.ui.*` identifier … → 1"*, counts **lines**; line 897 carries two.
   **Either name the real files and the real union, or put both sections in the banner's
   section-by-section "not reconciled" list — and fix the identifier count either way.** A
   disclosure that names four sections and misses two is worse than no disclosure, because it is
   read as exhaustive.
5. **Re-derive every figure you touch, after your last edit**, and put it inside a checked region or
   write the command instead of the number. Specifically: `docs/plan/README.md:23`'s *"`verification/`
   holds **thirty-four** files"* (`ls` answers 38, `git ls-files` answers 34 — say which reading it
   is, and prefer the command); `README.md:194`'s *"a script that had grown to 4,446"*
   (`wc -l scripts/feature-matrix-check.sh` → **5,559**, in the sentence whose own argument is that
   this figure rots); `docs/overview/README.md`'s *"all fifty-seven"*, *"gives eleven directories"*
   and *"eleven committed documents"*. **And `docs/plan/README.md:23-30`'s paragraph calls the four
   `W13-*` files *"wave 14's input"*, which the wave-14 close left stale; it is the same paragraph as
   the thirty-four, so fix both in one edit or delete the paragraph.**
6. **Say what item 1's denominator is.** `M01`…`M23` is a 23-row hand roster reconciled by nothing,
   ADR-057 §3.3 proposes making it definition-of-done item 1's subject, and the only published
   screen-to-case index is a stale wave-8 verification file. Either propose the reconciliation (a
   claim comparing the index against `frontend/e2e/**`'s case titles — W15-01's to build if you ask
   for it) or state plainly in the ADR that item 1 is checked by a human reading two lists. **Do not
   move the denominator onto an underived roster without saying that is what you are doing.**
7. **Do not add a claim kind.** If a repair needs one, it is W15-01's and you say so in
   `needsOutsideOwnership` with the exact shape.

**Acceptance:** `./scripts/feature-matrix-check.sh` green **with W15-01 landed**; `git ls-files
screens | wc -l` and every document that names it agree, with the command re-run in your report
beside each; `git ls-files 'docs/adr/ADR-*.md' | wc -l` equals the `DECISIONS.md` row count; every
backticked repository path in the seven owned documents resolved against `git ls-files` with the
unresolved list **published before you narrow anything** and each survivor argued; `├── NOTICE` in
§16 red under W15-01's reader.

---

## W15-03 — House rule 27 in the tooling, and one comment that is false

**Owns:** `deployment/**` entire, `TECH_DEBT.md` entire (rows; markers are W15-01's),
`.github/workflows/ci.yml`, `libs/config/test/**` and `build-tests/**` for additions and for
correcting comments.

1. **Correct the storybook manifest comment, which states something two builds disprove.**
   `deployment/storybook/Dockerfile:43-49` says that with eight manifests `--frozen-lockfile` *"had
   nothing to be frozen against"* for `feature-alerts`, `feature-connect` and `feature-ksql`, and
   `ShippedImageManifestSuite.scala:162-164` says *"so the install fails"*. W14-03's verifier built
   **both trees**: the pre-repair Dockerfile exits 0, pnpm prints *"Lockfile is up to date,
   resolution step is skipped"*, and the build emits **77** story bundles **including**
   `alerts.stories`, `connect.stories` and `ksql.stories` — the identical 77 the repaired tree emits,
   because `COPY frontend/ ./` restores the workspace after the install layer. **The gate is right
   and its stated reason is false, shipped, in the wave whose subject is documented statements a
   command contradicts.** Restate both as *"the cache layer above the install must be a complete
   workspace or `--frozen-lockfile` asserts nothing"*, drop *"so the install fails"*, and **rebuild
   both trees yourself** rather than trusting this paragraph. While you are there:
   `frontend/pnpm-lock.yaml` declares **twelve** importers (`.` plus eleven packages), not the
   *"eleven"* both files state; and `ShippedImageManifestSuite.scala:50-51`'s claim to be *"the only
   form of the rule that is both true of this tree and able to fail"* is disproved by the suite's own
   sibling case.
2. **TD-057: `.github/workflows/ci.yml:301` derives the package roster with `ls` on the runner's
   working tree.** `present="$(ls frontend/packages | sort)"`. House rule 27, in CI, where the
   working tree is a fresh checkout today and is whatever the cache holds tomorrow. One line, and
   `BuildWiringSuite` already reads this file, so the fix has a home for its case.
3. **TD-055: derive `ShippedConfigurationSuite`'s two-entry `quickstarts` roster** from the suite's
   own `deploymentYaml(root)` walk filtered to `deployment/quickstart/`, then add a third quickstart
   YAML with one masking rule and watch the cross-file case go red. Green before, red after, is the
   close; adding a name to the list is not. W14-03 filed it rather than fixing it because it owned
   the tree for additions only; **you own it for edits.**
4. **TD-058 and TD-056, in that order.** The repository-root walk is copied fourteen times and the
   comment says four; the fourteen `entrypoint`/`command` lines naming `/seed/<name>.sh` across three
   compose files are reconciled against the scripts by nothing. TD-056 is the bigger of the two and
   the one a broken quickstart would show first: misspelling
   `docker-compose.quickstart.yml:351`'s entrypoint leaves `libs.config.test` 395/395 and
   `build-tests.test` green.
5. **Disclose one ungated rule** in `deployment/**` or in a suite you touched, in the shape house
   rule 5 asks for.

**Acceptance:** `./mill --no-daemon libs.config.test` green and **red** under each mutation you
claim to have closed, re-applied by you rather than quoted; `./mill --no-daemon build-tests.test`
green; `docker build -f deployment/storybook/Dockerfile --target build .` run on **both** the current
and the eight-manifest tree with the bundle-name sets diffed in your report;
`./deployment/quickstart/quickstart.sh` up on a cold machine with `connect-seed` exit 0;
`./deployment/compose/smoke.sh` **PASSED**; `./scripts/feature-matrix-check.sh` green with the
register's id arithmetic still true.

---

## W15-A1 — The closer

**Owns:** every test tree in the repository, for additions; no production file except as a declared,
minimal, behaviour-preserving seam.

House rule 21: **close before you hunt, and this wave do not hunt at all.** Wave 14's closer produced
27 rows and closed 17; **eight of the ten it declined were blocked on a document it did not own**,
which is the second wave running that the partition, not the capacity, set the rate. This wave's
partition is written against exactly that, so the measurement to report is whether it worked.

**Standing instruction, from wave 13 and re-confirmed by wave 14:** when a filed row's suggested fix
is *"add X to the list"*, the close is the **derivation**, and the row's own suggestion is the thing
to argue against in your report. W14-A1 did this correctly for the roster exemption and it is why the
close held; it is also the instruction W15-01 row 1 depends on.

**And one this wave adds.** W14-A1 changed a production file (`deployment/storybook/Dockerfile`) and
shipped a comment beside it stating something two builds disprove. **A seam or a repair you add to
production carries a justification you measured, not one you reasoned to** — and if the measurement
contradicts the packet whose row you are closing, that contradiction is the finding.

**Acceptance:** every row filed by W15-01, W15-02 and W15-03 either closed with the case named and
the original mutation re-applied against it, or declined in `needsOutsideOwnership` with the exact
diff and the owner named; **the count of declines blocked on ownership reported as a number**, next
to wave 13's 11-of-22 and wave 14's 8-of-10; the tree byte-identical to how you found it, restored
from bytes saved before each mutation and never with `git checkout`, `git restore` or `git stash`.

---

## Where the packets meet

* **W15-01 and W15-02 meet at §16's shape and at ADR-057, and both are hard couplings.** §16's
  first column must become decidable (W15-02) before the reader that reconciles it can land
  (W15-01); the ADR must become tracked or superseded (W15-02) before the `git ls-files` ADR
  derivation can be green (W15-01). **W15-01 goes first for markers, W15-02 goes first for those two
  shapes.** Write the contract down in both reports on day one; a wave that discovers this at
  integration loses the wave.
* **W15-01 and W15-03 meet at `TECH_DEBT.md`.** Markers W15-01's, rows W15-03's — unchanged from
  waves 13 and 14, where it worked twice.
* **W15-02 and W15-03 meet at nothing.** `deployment/**` and `ci.yml` carry no prose W15-02 owns.
* **W15-A1 shares a checkout with nobody's mutation pass.** There is no hunter. Wave 14's verifiers
  lost measurements to a concurrently-rewritten `feature-matrix-check.sh` (two runs exited 127 mid-
  rewrite) and W14-02 could not attribute its own edits because W14-01 rewrote the same paragraphs;
  **that is the cost of ignoring "W14-01 goes first", and it is written here so it is not paid a
  third time.**
* **Nobody owns `services/**` or `frontend/packages/*/src/**` this wave.** No document repair needs
  them and no production behaviour is in question.

## The partition, checked

| Path | Owner |
| --- | --- |
| `scripts/feature-matrix-check.sh` | W15-01 |
| `<!-- checked: -->` markers, in any file | W15-01 |
| `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md`, `docs/frontend/README.md`, `docs/overview/README.md`, `README.md`, `research/design/SCREENS-V4.md`, `docs/plan/README.md` | W15-02 (prose and figures only) |
| `DECISIONS.md`, `docs/adr/**`, `.gitignore:40-43` | W15-02 |
| `screens/*.png` (the index entry, not the pixels) | W15-02, and only as the ADR decides |
| `deployment/**`, `.github/workflows/ci.yml` | W15-03 |
| `TECH_DEBT.md` | W15-03 (rows), W15-01 (markers only) |
| `libs/config/test/**`, `build-tests/**` | W15-03 (additions and comments), W15-A1 (additions) |
| every other test tree | W15-A1 |
| `docs/plan/verification/W15-*.md` | the packet it is about, and nobody else (house rule 18) |

## Files owned by NOBODY

**Unowned and correct as they stand:** `scripts/run-tests.sh`; `frontend/scripts/*.mjs`;
`frontend/packages/api/src/**`; fifty-six of fifty-seven ADRs (ADR-057 is W15-02's);
`docs/api/**`; `docs/domain/**`; `docs/operations/**`; `libs/**` production; `services/**`;
`apps/**`; `frontend/e2e/**`. `research/design/SCREENS.md` and `REFERENCE.md` are unowned and
correct — `SCREENS.md:17` is the sentence `SCREENS-V4.md` has contradicted for two waves.

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave:**
`services/metrics`' five unclosed payload rows under TD-024; `frontend/packages/shell/src/overview/
LatencyCard.tsx` and `ThroughputCard.tsx`'s hidden-table `ABSENT` decision.
**`build-tests/src/kui/build/BundleShape.scala` is NOT one of these any more** — wave 14 asked
whether it was dead; it is not. `BundleShapeSuite` carries 12 cases and runs inside
`./mill build-tests.test` (115 cases). Its scaladoc still reasons about the Scala.js linker ADR-048
deleted, which is a comment row and not a deletion.

**Unowned and deliberately left alone:** `stash@{0}` — nine waves old, 46 files, unpoppable without
conflicts, **no packet may drop it**; `docs/ROADMAP.md` and `docs/ROADMAP-SOLID.md`;
`repo.txt`, whose tracked-ness is TD's question and not this wave's.

---

## For the integrator, before wave 15 starts

1. **Read `git log` and `git status` yourself before you believe any row in this plan.** Wave 14 was
   judged against a tree that moved twice under it: `ARCHITECTURE.md` §16 was rewritten by a
   concurrent session while `WAVE-14.md` was being drafted, and `582c9bc5` landed **after all three
   verification passes had filed**. Two packets' acceptance figures are therefore unattributable by
   anyone. `git status --porcelain` at the writing of this file is 14 modified, 1 deleted, 7
   untracked.
2. **`docs/plan/verification/W14-03.md` was written by its verifier, not by the packet.** Fourth
   instance in six waves. Judge the tree: W14-03's work (the seed harness, `ShippedImageManifest
   Suite`, the storybook manifests, five register rows) is all on disk and green.
3. **`docs/plan/` is `README.md`, `ROADMAP.md`, this file and `verification/`.** `verification/`
   holds 38 files on disk and 34 in the index. `W8-04.md` and `W8-07.md` are cited from
   `docs/FEATURE_MATRIX.md` and `W9-03.md` from two e2e specs, so deleting them creates dangling
   references — and after W15-01's path work, a dangling one inside a checked region is a gate
   failure rather than a broken link. **`W8-07.md` §1 is stale in three rows and is still the only
   published screen index; fix it or replace it, do not delete it.**
4. **Check `ss -ltn` for `:6017`, `:8080`, `:8090` and `docker ps -a` before you record a figure**,
   and sweep `ps -eo etimes,args` for spinning shells before you trust a timing-sensitive gate.
5. **`./mill deployment.docker.__.build` reports SUCCESS from cache over a deleted image.**
   `./mill clean deployment.docker` before any figure taken from a container.
6. **The a11y sweep prints the per-theme budget it chose for the machine's load.** Record that line
   with the result; a sweep taken under load 27 and a sweep taken idle are not the same measurement.

---

## What wave 16 will be, and whether there is one

**There should not be, and this is the seventh time that has been written. Here is what is different,
stated so the next reader can check it rather than believe it.**

Every previous wave was open on a **kind of documented fact nothing read**. That sequence has ended:
a figure, a sentence, a quotation, a roster and a listing all have readers, all five readers are
driven by fixtures, and the two most recent waves' acceptance predictions are both still red on the
shipped bytes. **Wave 15 is the first wave whose open items are not a missing reader.** Three of them
are defects in readers that exist, one is a decision a human made that five documents have not caught
up with, and one is a table of names.

**What would make a wave 16 necessary, in order of likelihood:**

1. **The §16 shape contract between W15-01 and W15-02 is agreed late or agreed differently.** This is
   the likeliest by a distance. It is a hard coupling between two packets across a document boundary,
   which is the exact shape that cost waves 6, 9 and 14 a packet each. The defence is in both briefs
   and it is a date, not an intention: **the contract is written into both reports before either
   packet edits `ARCHITECTURE.md`.**
2. **The `screens/` decision goes to (b) and the supersession is bigger than it looks.** Superseding
   an Accepted ADR touches `DECISIONS.md`, five documents, a `.gitignore` comment and — if ADR-057
   §3.3's proposal is taken — the wording of definition-of-done item 1 in `ROADMAP.md`, which is the
   integrator's and not a packet's. **Decide it in the first day of the wave, not the last.**
3. **The `git ls-files` ADR derivation refuses something nobody expected.** 56 against 57 is the
   known case. If there is a second, the honest response is to publish it and not to narrow the
   derivation — that is how every claim kind in this repository has been defeated and it is written
   as a prohibition in W15-01's brief.
4. **The closer's decline rate stays ownership-bound for a third wave.** If W15-A1 reports another
   eight-of-ten blocked on documents it does not own, the conclusion is that a wave of four packets
   cannot close rows across a document partition, and wave 16 is **one packet** with the whole tree.

**And if none of those fires, the closing act is this.** The integrator runs **the five items**, not
the gate list: opens the product at the address it lands on; appends a false sentence to a checked
region, a false quotation to a block the roster was not written against, a phantom directory to a
documented listing **and a phantom extensionless top-level file to the same listing**, and requires
all four to be refused; resolves every backticked repository path in the documents item 4 names with
`git ls-files`; runs `git ls-files 'docs/adr/ADR-*.md' | wc -l` against `DECISIONS.md`'s row count;
re-runs every figure the newcomer's overview publishes; **clones the repository into a fresh
directory and checks that every link in `DECISIONS.md` resolves there**, which is the one check no
wave has ever run and the one that would have caught ADR-057 on the day it was written; writes the
wave-15 retrospective into `ROADMAP.md`; folds whatever is still true out of
`docs/plan/verification/` into it; writes `CLOSING-REPORT.md`; and deletes `docs/plan/WAVE-15.md`.
`docs/plan/` is then `README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and the plan is finished.

**The ratio, for the record.** Wave 10 ran six and two at 3:1 and closed four of five items. Wave 11
ran five and two at 2.5:1. Wave 12 ran four and two at 2:1 and closed eighteen of eighteen filed
rules. Wave 13 ran three and two at 1.5:1 and closed nineteen of thirty. Wave 14 ran three and one at
3:1 and closed seventeen of twenty-seven. Wave 15 is **three and one at 3:1** — the same shape as
wave 14, deliberately, because wave 14's shape was not what failed. What failed was that two packets
shared five documents and the ordering rule they were given was not honoured.
