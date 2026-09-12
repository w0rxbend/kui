# Wave 14 — the listing nobody derived, and a subject that is not in the repository

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 14, when waves 9, 10, 11, 12 and 13 were each written to be the last.**

Wave 13 did the load-bearing thing it said it would, and it is the third wave in a row that did.
`quotation_swept_kinds` is gone; the set of marked blocks is derived from the tree by
`marked_blocks_on_disk` and reconciled against the written-out roster in both directions by
`reconcile_swept_roster`. The wave's own acceptance was re-run at this close, by the closer, on the
shipped bytes, `TECH_DEBT.md` restored byte-identical (`md5sum 0e437fa81e4a833218e787ad523c5116`):

```
$ # appended inside TECH_DEBT.md's <!-- checked: debt-register --> block, nothing else changed:
$ #   See `docs/operations/masking.md`, which says *a masked value is never longer than the
$ #   value it replaced*.
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: a marked block of another kind attributes a quotation to a file that does
  not contain it: TECH_DEBT.md (checked: debt-register #1) -> docs/operations/masking.md: …absent.
feature-matrix-check: 1 disagreement(s) over 444 compared claims.        # exit 1
```

**And the inverse was closed inside the same wave, which has never happened before.** W13-01's
verifier found that the identical sentence in a `quotations` block in any *third* file was still
green — the defect surviving one kind over, four lines above the packet's own fix — and W13-A2 closed
it by derivation rather than by adding a second hand-written name. Re-run here on a scratch file:
**two** independent refusals, the quotation by name and the undeclared block in the roster
reconciliation.

**And item 4 is still not met, and this time the measurement is one command long.**

```
$ git log --all --oneline -- 'screens*'          # empty: never committed
$ git ls-files screens | wc -l
0
$ grep -n 'screens/' .gitignore
43:screens/
$ grep -n 'screens/' ARCHITECTURE.md
1162:├── screens/     the captures the browser suite is checked against
```

`ARCHITECTURE.md` §16 is the repository-layout listing **wave 13 rewrote**, in the section whose
entire subject is *"directories that are not on disk"*, and the rewrite added a fifth one. The
method was `ls` on a working tree, and `ls` cannot tell a tracked directory from an ignored one —
`.gitignore:43` ignores `screens/`, and the comment three lines above it says this repository keeps
the captures **outside itself**, which `research/design/SCREENS.md:17` states as `.agent/design/
screens/`. So the document that says four phantom directories were deleted now carries a fifth, and
it is contradicted by a comment in the repository's own `.gitignore`.

**This is the same shape as every previous close, and it is the fifth time the shape has been named
rather than discovered.** Wave 9's open item was a figure. Wave 10's was a figure and a sentence.
Wave 11's was a quotation. Wave 12's was a quotation the gate could read. Wave 13's was the list of
places the gate looks. **Wave 14's is the listing itself** — a fenced directory tree, a package list,
a container count, a row citing a file — the one class of documented fact that is mechanically
checkable against `git ls-files` and that nothing in this repository has ever compared.

**Wave 14 is three building packets and one closer.** No new service, no new endpoint, no new
feature package, no new screen. One new claim kind — the one mechanism that nine of the eleven rows
wave 13 filed and left open all need — eight measured false statements, one shell harness, and one
decision that has to be made in the open because the definition of done's own subject is not in the
repository.

**There is no hunter this wave, and that is a decision rather than an omission.** W13-A1 ran 36
non-equivalent mutants for 8 rules and closed all 8 itself; it produced no rows for anybody else.
What is left is one mechanism and eight sentences, and a hunter's output would be rows that no
packet in this wave owns the tree to close. House rule 21 says close before you hunt; at this size
that means do not hunt.

---

## The definition of done, judged item by item at the wave-13 close

Every figure below was printed by the command beside it, on **2026-09-12**, by the closer, one
command at a time, against a quickstart brought up from this tree and the eleven-container
distributed stack built from the same one. **Nothing below is read off a packet report; every line
was re-run here, including the four the packet reports agreed on.**

| # | Item | Verdict | Command, and what it printed |
| --- | --- | --- | --- |
| 1 | Every screen in `screens/` renders real data | **MET, with a caveat about its subject** | `ls screens/` → 23 captures; `pnpm -C frontend e2e` → **108 passed, 2 skipped, 0 failed** over 110; **23 of 23** captures have a named case that ran green in that run (mapping below); the landing page and the cluster dashboard driven in a browser here — six tiles each carrying a sentence, `BROKERS ONLINE 1`, `TOPICS 12 · 169 partitions`, `PARTITIONS IN SYNC 100.0%`, `PRODUCTION 1.1 kB/s`, `CONSUME 119.3 B/s`, `CONSUMER LAG 10 · 1 group not counted`, gaps drawn `—` and never `0`, **0** page errors, **0** figures with three or more decimals across **1,159** `<td>` cells. **The caveat:** `screens/` is untracked (`git ls-files screens` → 0, never committed), so the item's own denominator is a fact about this working tree and not about the repository |
| 2 | Every backend capability exists as a service or an endpoint | **MET** | `ls services/` → **11**; `./mill __.openApiCheck` → **2544/2544**; `./mill checkArchitecture` → **195 modules, 10 rules, no layering violations** |
| 3 | Unit and component tests, a11y in both themes, a browser suite | **MET** | `./scripts/run-tests.sh` → **81 modules (81 with tests), 4,412 cases, all passing**; `pnpm test` → **1,936 over 83 files**; `pnpm typecheck` → exit 0 over both projects; `pnpm a11y` → **782 stories × 2 themes, no violations**; `pnpm lint:boundaries` → **423 files in 11 packages**; `./mill __.checkFormat` **495/495**, `__.fix --check` **10672/10672** |
| 4 | Documented: README, ARCHITECTURE, ADRs, an accurate FEATURE_MATRIX, an overview | **NOT MET** | eight measured false statements below, plus nine filed ungated rules nobody closed |
| 5 | One command brings the whole product up under `docker compose` | **MET** | `./deployment/quickstart/quickstart.sh` → exit 0, *"KUI is running: http://localhost:8090/ui/"*, `connect-seed` exit 0 on a cold machine printing its own waiting line; `down` → *"Removed. Nothing from the quickstart is left running or stored."*; `./deployment/compose/smoke.sh` → **PASSED** over the eleven-container stack, fault isolation and recovery included |

### Item 1, measured rather than assumed

The twenty-three captures were re-counted against `docs/plan/verification/W8-07.md` §1 — still the
only published screen-to-spec index — and against this run's own case list, matched by case title in
the `--reporter=list` output rather than by file. Per-spec green counts from this run:
`alerts` 5, `brokers` 11, `connect` 4, `consumers` 6, `dashboard` 9, `features` 16, `ksql` 4,
`messages` 7, `search` 8, `shell` 9, `topics` 20, `traffic` 11.

| Screen | Case that drove it, green in this run |
| --- | --- |
| `M01` | `traffic.spec.ts` *carries the same stat cards as Overview, and its own last row* + `dashboard.spec.ts` |
| `M02`, `M22` | `shell.spec.ts` *the appearance popover names every choice in words, and Light repaints the frame* |
| `M03` | `traffic.spec.ts` — 11 green |
| `M04` | `dashboard.spec.ts` *the Storage tab replaces the body rather than repeating the Overview's rows* |
| `M05` | `alerts.spec.ts` — 4 green + 1 deployment-shaped skip |
| `M06` | `shell.spec.ts` *the bell carries the open count…*, *…opens a panel…*, *acknowledging an open event moves the bell* |
| `M07` | `brokers.spec.ts` — 11 green |
| `M08` | `shell.spec.ts` *switching cluster names where you have arrived* |
| `M09` | `brokers.spec.ts` *follows a cluster change into the second cluster's brokers screen* |
| `M10`–`M13`, `M16`, `M23` | `topics.spec.ts` — 20 green |
| `M14` | `topics.spec.ts` *a bulk delete says how many it deleted, in the plural* |
| `M15` | `messages.spec.ts` — 7 green |
| `M17` | `consumers.spec.ts` — 6 green |
| `M18` | `features.spec.ts` — 16 green |
| `M19` | `connect.spec.ts` — 4 green, no skips |
| `M20`, `M21` | `ksql.spec.ts` — 3 green + 1 deployment-shaped skip |

**23 of 23, and the two skips are the right two** — `alerts.spec.ts:270` and `ksql.spec.ts:175`, each
describing a *different deployment*. No third skip appeared.

**Two honest qualifications the previous four closes did not make.**

1. **The index is stale and the count does not come from it.** `W8-07.md` §1 as written scores `M08`
   uncovered, `M14` partial and `M20` uncovered, because it was taken mid-wave-8. The 23 of 23 comes
   from `WAVE-13.md`'s re-take, which this close re-ran case by case. Anyone who reads `W8-07.md`
   alone and reports 20 of 23 is reading the document correctly.
2. **`screens/` is not in the repository, and neither is any statement of that fact in the documents
   that name it.** `research/design/SCREENS-V4.md:3` says the captures are *"held in `screens/` in
   this repository"* and `:34` says *"`screens/`, committed, twenty-three PNGs"*. Both are false and
   both are contradicted by `research/design/SCREENS.md:17` in the same directory. That makes item 1
   as written unreproducible by anyone who clones this repository, which is an item-4 defect about
   an item-1 subject, and it is why the decision below is a wave item.

### Item 4, the things it is open on

**Eight false or unsupported statements, each measured here with the command beside it.** Every one
sits outside every `<!-- checked: -->` region, and every one is a *listing* or a *path*, which is why
this wave has one mechanism in it.

1. **`ARCHITECTURE.md:1162` lists `screens/` as a top-level repository directory.**
   `git log --all -- 'screens*'` → no commits; `git ls-files screens` → 0; `.gitignore:43`. Added by
   wave 13's own rewrite of the section whose subject is phantom directories.
2. **`ARCHITECTURE.md` §16 omits `.github/`**, which is tracked (`git ls-files .github` → 2) and
   holds the `ci.yml` the same document cites repeatedly, while the listing includes other dotfiles
   (`.scalafmt.conf`, `.tool-versions`, `.mill-version`). The same omission direction §16 says it
   fixed.
3. **`docs/FEATURE_MATRIX.md:355` (AU-005) says the account menu *"now exists"* and names
   `layout/UserMenu.scala`.** `git ls-files | grep -i usermenu` → nothing. Present tense, naming a
   file ADR-048 deleted, in the document item 4 names by name as *accurate*. Same class as KU-033,
   which wave 13 repaired four rows away.
4. **`docs/FEATURE_MATRIX.md:351` (AU-001) cites `frontend/ui-shell/.../page/LoginPage.scala`.**
   `ls frontend/ui-shell` → no such directory. The row discloses the deletion in a parenthesis and
   then keeps the dead path as its evidence; the tense is past, so this is the milder of the two and
   is listed so the repair is not half done.
5. **`docs/frontend/README.md` instructs the reader four times (`:474`, `:480`, `:505`, `:530`) to
   record design disagreements in `research/design/gaps.md`.** `ls research/design/` →
   `REFERENCE.md  SCREENS-V4.md  SCREENS.md`. A process document naming a register that does not
   exist is a process nobody can follow.
6. **`docs/frontend/README.md` names six `kui.ui.*` Scala.js identifiers ADR-048 deleted**
   (`:183`, `:616`, `:624`, `:806`, `:900`), two of them in the present tense (*"They live in
   `kui.ui.shell.Messages`"*, *"why they live in `kui.ui.kernel.prefs`"*), and it carries **three
   ` ```scala ` fences** in a document about a TypeScript workspace. The page's own banner discloses
   the port and asks the reader to *"read the rule and not the syntax"* — and then says *"sections
   marked with their real paths and commands have been reconciled"*, which these are not. The
   disclosure is honest about the syntax and wrong about the paths.
7. **`research/design/SCREENS-V4.md:3` and `:34` say the captures are committed** (item 1 above).
   `research/**` is listed in `WAVE-13.md` under *"Unowned and correct as they stand"*. It is not.
8. **`docs/plan/README.md:3` links `[`screens/`](../../screens)`** — a directory that is not in the
   repository, from the document that explains how this directory works.

**And nine filed ungated rules that wave 13's closer left open, for one reason: they are one missing
mechanism.** `docs/plan/verification/W13-03.md` files V-01…V-09 and W13-A2's report argues, correctly,
that closing them needs a claim kind that reduces a documented listing or figure to the command that
prints it, plus markers in documents that packet did not own. Reproduced here on the shipped bytes,
`docs/overview/README.md` restored byte-identical: `**Eleven containers.**` → `**Fourteen
containers.**` leaves `./scripts/feature-matrix-check.sh` at **444 claims checked, all true**, exit 0.

| Row | The substitution that stays green |
| --- | --- |
| V-01 | `ARCHITECTURE.md` §16: add `benchmarks/` back into the fenced tree |
| V-02 | `ARCHITECTURE.md` §16: delete the `build-tests/` line (the omission direction) |
| V-03 | `docs/frontend/README.md`: delete `feature-ksql/` from the packages listing |
| V-04 | `docs/overview/README.md` §2: `eleven committed documents` → `twelve` |
| V-05 | `docs/overview/README.md` §4: `**Eleven containers.**` → `**Fourteen containers.**` |
| V-06 | `docs/overview/README.md` §2: `a seventh module, ` + "`client`" → "`clients`" |
| V-07 | `docs/FEATURE_MATRIX.md` KU-033: `deployment/compose/smoke.sh` → `smoke-isolation.sh` |
| V-08 | `docs/FEATURE_MATRIX.md` KU-033: `stops **one** container` → `**four**` |
| V-09 | `README.md` banner: `open on one of the five` → `four of the five` |

**Two more ungated rules, in a different tree and needing a harness rather than a claim.**
`docs/plan/verification/W13-02.md` files them and W13-A2 declined both for the right reason — they
need a scripted Connect worker, which is a harness and not an assertion.

* **`connect-seed.sh:154-159`** — deleting the whole announce block leaves
  `./mill libs.config.test` at **395/395 SUCCESS**, and the wave's own acceptance item (*"require the
  script to say so and keep waiting"*) is met only by a hand-run control.
* **`connect-seed.sh:161`** — `[ "${running}" -ge 2 ]` → `-ge 1` leaves the suite green, and against
  a worker answering connector `RUNNING` with `tasks[0].state=UNASSIGNED` the seed exits 0 printing
  the false sentence *"quickstart-file-source and its task are RUNNING"*.

### What is NOT open, and should not be re-hunted

* **The roster class is closed, in both directions and one kind over.** Both reproductions above are
  red on the shipped bytes. `marked_blocks_on_disk` walks the tree; the prune list and the
  `docs/plan` exclusion are both gated (fixture 27, re-run here: reducing `marked_markdown_prunes`
  to `(.git)` is **1 disagreement**, naming `node_modules/vendored/README.md` and
  `target/generated.md`). Do not re-litigate it.
* **The quotation class is closed.** `grep -rn "masked value is never longer"` outside `docs/plan/`
  returns the gate's own fixture and nothing else.
* **Both prose moods are refused**, and W13-A2 closed V-10 — the README's hand copy of
  `negation_tokens` and `state_tokens` is now compared against the arrays in both directions.
* **`kui-quickstart-auth.yaml`'s published `awk` range is repaired and gated**, by
  `PublishedEvidenceCommandSuite`, which reads the program out of the comment and runs it. TD-051's
  class has a gate for the first time in four instances.
* **`connect-seed.sh`'s first-poll race is fixed and the fix is gated** at the position of the guard
  (`ShippedScriptGuardPositionSuite`), function indirection included. Verified on a cold machine at
  this close: `connect-seed` exits 0 and prints *"waiting for quickstart-file-source: connector
  RUNNING, 1 RUNNING state(s)"* before the success line.
* **`smoke.sh`'s two hidden unguarded `grep` stages are repaired**, found by W13-A2's own closing
  case rather than by a filed row.
* **Every gate in the tree table below is green from images built from this tree.** Do not re-run
  the browser suite or either deployment shape to prove a documentation repair.

---

## The tree you start from

Every figure printed by the command beside it at the wave-13 close, one gate at a time, by the
closer, on an otherwise idle machine.

| Gate | Figure |
| --- | --- |
| `./scripts/run-tests.sh` | **4,412 cases over 81 modules**, all 81 carrying tests (wave 12: 4,401) |
| `pnpm -C frontend test` | **1,936 over 83 files** (1,935) |
| `pnpm -C frontend e2e` | **108 passed, 2 skipped, 0 failed** over 110 (unchanged) |
| `./scripts/feature-matrix-check.sh` | **444 claims over 12 sections**, all true (438) |
| `./mill __.checkFormat` | 495/495 over **1,152 sources** |
| `./mill __.fix --check` | 10672/10672 |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2544/2544 |
| `pnpm -C frontend typecheck` | exit 0 over both projects |
| `pnpm -C frontend lint:boundaries` | **423 files in 11 packages** |
| `pnpm -C frontend a11y` | **782 stories × 2 themes, no violations** |
| `./deployment/quickstart/quickstart.sh` | exit 0, eight containers healthy, two registered clusters, `connect-seed` exit 0 |
| `./deployment/compose/smoke.sh` | **PASSED** over eleven containers |

`feature-matrix-check.sh`'s section sizes: self-check 7, rows 30, merged-document 50, milestones 49,
adr-index 112, openapi-totals 15, **guard-fixtures 29**, **capability-claims 44**, **quotations 12**,
debt-register 3, gate-table 5, dependencies 88 over 12 named manifests.

**Anything red is yours. Nothing is red.** The eight things that are wrong are not gate failures —
they are listings and paths in documents, and no gate in this repository has ever read one.

---

## House rules

The first twenty-six are waves 8 through 13's and still apply in full; read them in the git history
of `docs/plan/WAVE-13.md`. Three are amended and one is new.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not
otherwise changing.**

5. **Report a mutation that stayed green. Eleventh wave, no movement, and this time the denominator
   moved instead.** Three building packets, **two** disclosures — W13-03 shipped no rule of its own
   to disclose, the first building packet in thirteen waves with nothing to name — and **twenty**
   more found by the verifiers. The rate has not moved in eleven waves.
17. **Unchanged, and it worked.** W13-01 was told to re-take or delete the census figure and did
    neither by number: it shipped `--census` as a command and no figure, and three independent
    readings since (1,650/1,638, 1,626/1,614, 1,626/1,614) confirm that was the right call.
18. **AMENDED, third failure, and the amendment is about the harness rather than the owner.**
    `docs/plan/verification/W13-A1.md` does not exist. The packet ran — 36 non-equivalent mutants,
    8 rules, 8 closing cases, all of it on disk and re-measured at this close — and its harness
    instructions forbade it writing a report `.md` file. Wave 9 lost W9-A1 to exactly this. **A
    packet whose harness cannot write `docs/plan/verification/W<NN>-<packet>.md` is not launched
    until the harness can, and the wave brief says so in the packet's own Owns line.** The rule
    naming an owner is not enough when the owner is unable to hold the pen.
21. **Unchanged, and it produced 11 of 22 rather than 13 of 13** — because eleven of the twenty-two
    filed rows needed a mechanism or a harness in a tree the closer did not own. That is a partition
    result and not a capacity one, and this wave's partition is written to fix it: **W14-01 owns the
    checker AND the markers in every document, and W14-03 owns the harness.**
23. **Unchanged, and it decided this wave in both directions for the first time.** `WAVE-13.md`
    predicted the roster reconciliation would turn item 4 into a gate; the prediction is red on the
    shipped bytes, **and its inverse was found by a verifier and closed by the closer inside the same
    wave.** Test the prediction and its inverse; the inverse is where the next wave lives.
26. **Unchanged, and it is the reason this wave exists in its current shape.** A roster is a claim.
    Wave 13 proved it for the list of places a gate looks. **Wave 14 applies it one level out: a
    documented listing is a roster too**, and `ls` on a working tree is not a derivation — it cannot
    distinguish tracked from ignored, which is exactly how `screens/` entered `ARCHITECTURE.md` §16.
27. **NEW: a fact about the repository is derived with `git ls-files`, never with `ls` or `find`.**
    Every phantom and every omission this project has repaired in five waves came from a reading of a
    working tree. A working tree carries build output, vendored trees, another session's scratch file
    and six megabytes of ignored PNG. `git ls-files` is the only reading that a clone reproduces, and
    a document is written for people who clone.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
and `npx --yes pnpm@11.25.0 <script>` works from `frontend/`. Use `./mill --no-daemon` for anything
you record a number from. **The a11y sweep is three commands** — build Storybook, serve
`storybook-static` on `:6017`, sweep — and `npx http-server` leaves a listener; kill what `ss -ltnp`
names. `./mill a.test b.test` runs **zero** tests and exits 0: the second word is a munit name
filter. `clean` before `build-tests.test` is not needed.

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **444 claims over 12 sections**; `close_section` literals 29/44/12/3/5; fixtures 26–29 (the roster derivation, the prune list, the block count, the census) | **W14-01**, which must raise the total and not lower it, and must not weaken a fixture |
| `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md`, `docs/frontend/README.md`, `README.md`, `docs/overview/README.md` | every figure inside a `<!-- checked: -->` region, and the `gate-table` block's **444** | **W14-02** for prose and figures; **W14-01** for markers only |
| `TECH_DEBT.md` | 54 rows, highest `TD-055`, ids unique, the `debt-register` block | **W14-03** alone, and markers stay W14-01's |
| `frontend/e2e/**` | **108 passed, 2 skipped, 0 failed.** Both skips are deployment-shaped; **no packet may add a third** | nobody this wave without saying so |
| `libs/config/test/.../ShippedScriptGuardPositionSuite.scala`, `PublishedEvidenceCommandSuite.scala` | wave 13's two new suites — the guard position and the runnable published command | **W14-03** may add, not weaken |
| `deployment/quickstart/seed/connect-seed.sh` | the `{ grep … \|\| true; }` guard at the grep's own stage, the announce block, `-ge 2` | **W14-03** alone |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 over every tree including tests | every packet |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W14-01 — The listing claim

**Owns:** `scripts/feature-matrix-check.sh` entire, and the `<!-- checked: -->` markers in any file
it needs one in — markers only, never prose or a figure inside another packet's document.

**The wave's load-bearing item, and it is one claim kind.**

1. **Add a claim kind that reduces a documented listing to the command that prints it, and compares
   both directions.** The minimum subject is a fenced block inside a marked region whose lines carry
   repository paths, reduced to the set of names it asserts and compared against a **`git ls-files`**
   derivation — not `ls`, not `find` (house rule 27). Both directions: a name in the document that
   the repository does not carry is a disagreement, **and** a name the repository carries that the
   document's listing omits is a disagreement. The omission direction is half the value: V-02 and
   item 4.2 are both omissions.
2. **Drive it over the four listings that are already wrong or unguarded**, which is what makes it a
   claim and not a framework: `ARCHITECTURE.md` §16's `kui/` tree (top-level names against
   `git ls-files | cut -d/ -f1 | sort -u`), `docs/frontend/README.md`'s `packages/` listing (against
   `git ls-files 'frontend/packages/*/package.json'`), `docs/overview/README.md`'s container
   sentence (against `grep -c 'image: kui-' deployment/compose/docker-compose.yml`), and the
   backticked repository paths in `docs/FEATURE_MATRIX.md` rows (against `git ls-files`) — which is
   V-01, V-02, V-03, V-05, V-07 and item 4.1, 4.2, 4.3, 4.4 in one mechanism.
3. **Add the markers the claim needs**, in W14-02's documents, and say in your report exactly which
   lines you wrapped so W14-02 can edit inside them without moving them. **A marker you add to a
   document whose prose you also want to change is the one thing this partition forbids.**
4. **Do not widen the path check to prose.** A backticked token that is a URL path, a relative
   frontend path or an explicitly-declared absence (`docs/benchmarks/`, `services/config`,
   `deployment/helm/`) must not be refused. W13-03's verifier swept the six documents and found this
   is the honest form; a claim that refuses `/api/v1/search` is a claim that gets silenced.
5. **Disclose one rule of your own that survives mutation with the run green**, in the shape house
   rule 5 asks for. The script is 4,000 lines and the last four waves each found one in it inside the
   hour.

**Acceptance:** `./scripts/feature-matrix-check.sh` exits 0 with a total **above 444**; each of
V-01, V-02, V-03, V-05 and V-07 reproduced from `docs/plan/verification/W13-03.md` and shown **red**,
with the refusal text quoted; `git ls-files` and not `ls` or `find` in every derivation you add
(`grep -n 'find \|ls ' ` over your diff, with each surviving hit argued); fixtures 26–29 unchanged
and still red under their own mutations.

---

## W14-02 — The eight sentences, and the decision about `screens/`

**Owns:** `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md` (prose and figures; W14-01 owns its markers),
`docs/frontend/README.md`, `docs/overview/README.md`, `README.md`, `research/design/SCREENS-V4.md`,
`docs/plan/README.md`, and one new ADR.

1. **Repair items 4.1 through 4.8 above**, each with the command that finds it re-run in your report
   after your edit. `screens/` out of `ARCHITECTURE.md` §16 or the section saying in words that it is
   an untracked working-tree directory; `.github/` in; AU-005 rewritten to name
   `frontend/packages/shell/src/layout/` or whatever `git ls-files` actually answers; AU-001's dead
   path made past-tense-and-deleted rather than past-tense-and-cited; `research/design/gaps.md`
   either written or the four instructions rewritten to name where the register actually is.
2. **Decide the `screens/` question in the open, in an ADR (`ADR-057`), and amend
   `research/design/SCREENS-V4.md` to match it.** Three answers are defensible and one is not:
   commit the twenty-three PNGs and delete `.gitignore:43`; keep them out and rewrite every document
   that says they are in, including the definition of done's own wording; or commit a reduced-size
   set. **What is not defensible is leaving `SCREENS-V4.md` saying *"committed"* and `.gitignore`
   saying otherwise**, three documents apart, over the subject of definition-of-done item 1. Say what
   you chose and what it costs. If the answer changes what item 1 can mean, say so and
   `ROADMAP.md`'s wording is the integrator's to move.
3. **Do the `kui.ui.*` residue properly or say why not.** Six identifiers and three ` ```scala `
   fences in the document a newcomer is sent to by `README.md:466` for *"the whole workspace"*. The
   banner's disclosure covers syntax and claims the paths were reconciled. Either reconcile them,
   or amend the banner to say which sections were not — a disclosure that overstates itself is the
   defect this project has repaired in five different documents.
4. **Every figure you touch is re-derived by the command beside it, after your last edit**
   (house rule 17, third form), and any figure that cannot go inside a checked region is written as
   the command instead. There is no third option left after six waves of this.
5. **Do not add a claim kind.** If a repair needs one, it is W14-01's and you say so in
   `needsOutsideOwnership` with the exact shape.

**Acceptance:** `./scripts/feature-matrix-check.sh` green; `git ls-files` re-run against every
backticked repository path in the seven owned documents with the unresolved list published and each
survivor argued; `ADR-057` in `docs/adr/` and its row in `DECISIONS.md` (the `adr-index` section
compares them, so a missing row is red); no figure in any owned file that a command in this
repository contradicts.

---

## W14-03 — The seed harness, and the register

**Owns:** `deployment/**` entire, `TECH_DEBT.md` entire (rows; markers are W14-01's),
`libs/config/test/**` for additions.

1. **Write the harness both open `connect-seed.sh` rows need.** A scripted Connect worker — a fake
   HTTP server with an ordered list of poll answers — driven against the real shipped script. Two
   cases: first poll answers `UNASSIGNED`/no tasks, require **exit 0** and a line naming the
   intermediate state (closes V-3); a worker answering connector `RUNNING` with
   `tasks[0].state=UNASSIGNED`, require the script to **keep waiting and finally die** rather than
   print *"quickstart-file-source and its task are RUNNING"* (closes V-4). Both must be red under
   the mutations `docs/plan/verification/W13-02.md` names, and you re-apply those mutations yourself
   rather than trusting the row.
2. **`ls`/`find` sweep over `deployment/**` and the register** (house rule 27): any place a shipped
   script derives a repository fact from a working-tree walk instead of `git ls-files` is a row, and
   if it is one line it is also a fix.
3. **Amend the register's new paragraph**, which overstates the reader it describes. W13-02's
   verifier measured it: a row attributing a sentence to a file by path is compared **only** when
   the sentence is a backticked or *italic* span of eight or more words preceded by a backticked
   path. The honest form is *"a quotation written as a marked span after a backticked path"*, and
   the row should say it.
4. **File, do not fix, the `quickstarts` roster** at `ShippedConfigurationSuite.scala:352` — a
   two-entry hand list that nothing reconciles, beside a `shipped` roster that is reconciled against
   a walk of `deployment/`. House rule 26's exact shape, in the suite that proves the quickstarts'
   masking rules. It is one row and it is W14-03's own disclosure if you close it.
5. **Disclose one ungated rule** in `deployment/**` in the shape house rule 5 asks for.

**Acceptance:** `./mill --no-daemon libs.config.test` green, and **red** under each of the two
`connect-seed.sh` mutations above; `./deployment/quickstart/quickstart.sh` up on a cold machine with
`connect-seed` exit 0 and its waiting line printed; `./deployment/compose/smoke.sh` **PASSED**;
`./scripts/feature-matrix-check.sh` green with the register's block intact and its id arithmetic
still true.

---

## W14-A1 — The closer

**Owns:** every test tree in the repository, for additions; no production file except as a declared,
minimal, behaviour-preserving seam.

House rule 21: **close before you hunt, and this wave do not hunt at all.** The three building
packets above will file rows; close them. If they file none, the honest report is a short one, not a
sweep of a tree nobody is repairing.

**One standing instruction, from wave 13's own measurement:** when a filed row's closing case needs
a seam, add the seam **and then re-apply the original mutation in a form the compiler accepts**.

**And one this wave adds.** W13-A2 closed eleven rows by *derivation* where the filed row suggested
a *list* — `marked_quotation_files` instead of adding a second file name to a hand roster — and that
is why the close held one kind over. **When a filed row's suggested fix is "add X to the list", the
close is the derivation and the row's own suggestion is the thing to argue against in your report.**

**Acceptance:** every row filed by W14-01, W14-02 and W14-03 either closed with the case named and
the original mutation re-applied against it, or declined in `needsOutsideOwnership` with the exact
diff and the owner named; the tree byte-identical to how you found it, restored from bytes saved
before each mutation and never with `git checkout`, `git restore` or `git stash`.

---

## Where the packets meet

* **W14-01 and W14-02 meet at five documents.** Markers and claim kinds are W14-01's; prose and
  figures are W14-02's. **W14-01 goes first** — a marker added around a paragraph W14-02 has already
  rewritten is a merge neither of them tested. W14-01 publishes the line ranges it wrapped; W14-02
  edits inside them.
* **W14-01 and W14-03 meet at `TECH_DEBT.md`.** Markers W14-01's, rows W14-03's, unchanged from
  wave 13, where it worked.
* **W14-02 and W14-03 meet at nothing.** `deployment/**` carries no prose either names.
* **W14-A1 shares a checkout with nobody's mutation pass**, because there is no hunter. The wave-12
  lesson costs nothing to honour this wave.
* **Nobody owns `services/**` or `frontend/packages/*/src/**` this wave.** Wave 13 swept
  `services/ksql`, `services/gateway` and `feature-ksql` and closed eight rules; the tree is fresh
  and no document repair needs it.

## The partition, checked

| Path | Owner |
| --- | --- |
| `scripts/feature-matrix-check.sh` | W14-01 |
| `<!-- checked: -->` markers, in any file | W14-01 |
| `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md`, `docs/frontend/README.md`, `docs/overview/README.md`, `README.md`, `research/design/SCREENS-V4.md`, `docs/plan/README.md` | W14-02 (prose and figures only) |
| `docs/adr/ADR-057-*.md`, `docs/adr/DECISIONS.md` | W14-02 |
| `deployment/**` | W14-03 |
| `TECH_DEBT.md` | W14-03 (rows), W14-01 (markers only) |
| `libs/config/test/**` | W14-03 (additions), W14-A1 (additions) |
| every other test tree | W14-A1 |
| `docs/plan/verification/W14-*.md` | the packet it is about, and nobody else (house rule 18) |

## Files owned by NOBODY

**Unowned and correct as they stand:** `scripts/run-tests.sh`; `frontend/scripts/*.mjs`;
`frontend/packages/api/src/**`; fifty-six of fifty-six existing ADRs; `docs/api/**`; `docs/domain/**`;
`docs/operations/**`; `libs/**`; `services/**`; `apps/**`; `build-tests/**`;
`.github/workflows/ci.yml` (its job and step roster is asserted by `BuildWiringSuite`, so a deletion
is a red case). `research/design/SCREENS.md` and `REFERENCE.md` are unowned and, unusually for this
directory, **correct** — `SCREENS.md:17` is the sentence `SCREENS-V4.md` contradicts.

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave:**
`services/metrics`' five unclosed payload rows under TD-024; `frontend/packages/shell/src/overview/
LatencyCard.tsx` and `ThroughputCard.tsx`'s hidden-table `ABSENT` decision; `build-tests/src/kui/
build/BundleShape.scala`, whose scaladoc still reasons about the Scala.js linker ADR-048 deleted —
**check before you touch it whether the suite still runs, and if it does not, that is a wave-15 row
and not a wave-14 deletion.**

**Unowned and deliberately left alone:** `stash@{0}` — eight waves old, 46 files, unpoppable without
conflicts, **no packet may drop it**; `docs/ROADMAP.md` and `docs/ROADMAP-SOLID.md`.

---

## For the integrator, before wave 14 starts

1. **Wave 13 is unrecorded.** `git status --porcelain` is **25 entries** against `350593e0` — 18
   modified, 7 untracked (this file, `WAVE-13.md`'s deletion, wave 13's four verification reports and
   three new `libs/config` test sources). Every gate in the tree table above was run against exactly
   this working tree at this close. **Commit it before anything in wave 14 moves.**
2. **`docs/plan/verification/W13-A1.md` does not exist and the packet ran.** Its eight rules, eight
   closing cases and its one declared production seam (`CircuitFeed.scala`, `ServiceId.unsafe` →
   `ServiceId.from`) are on disk, compile, and are green in `./scripts/run-tests.sh` at 4,412. Judge
   the tree. Its findings are recorded in this wave's `ROADMAP.md` retrospective because there is
   nowhere else they exist.
3. **`docs/plan/` is `README.md`, `ROADMAP.md`, this file and `verification/`.** `verification/`
   holds thirty-four files. `W8-04.md` and `W8-07.md` are cited from `docs/FEATURE_MATRIX.md` and
   `W9-03.md` from two e2e specs, so deleting them creates dangling references — and after W14-01's
   path claim, a dangling one inside a checked region is a gate failure rather than a broken link.
4. **Check `ss -ltn` for `:6017`, `:8080`, `:8090` and `docker ps` before you record a figure.**
5. **Do not trust a cached frontend image layer** — export the image's `ui/assets` and compare the
   content-hashed filenames against a `dist` built from the tree.
6. **The a11y sweep adapts its per-theme budget to machine load** and prints the budget it chose.
   Record that line with the result; a sweep taken under load 7 and a sweep taken idle are not the
   same measurement.

---

## What wave 15 will be, and whether there is one

**There should not be, and this is the sixth time that has been written. Here is what is different,
stated so the next reader can check it rather than believe it.**

Wave 13 is the third consecutive wave whose load-bearing prediction was reproduced rather than
believed, and the first in which the prediction's **inverse** was found and closed inside the same
wave. Four of the five definition-of-done items are met and were re-judged here one command at a
time. What keeps item 4 open is eight sentences and **one mechanism that has never been built** —
and unlike every previous wave's open item, the mechanism is not a new kind of reading. It is
`git ls-files` compared against a fenced block, which is `reconcile_dependencies` with a different
derivation.

**What would make a wave 15 necessary, in order of likelihood:**

1. **The path claim refuses more than it should, and gets narrowed until it refuses nothing.** This
   is the likeliest and it is the failure mode of every claim kind this project has shipped. The
   defence is item 4 of W14-01's brief and it is written as a prohibition rather than a hope:
   publish the unresolved list before you narrow, and argue each survivor.
2. **The `screens/` decision turns out to change what item 1 means.** If the honest answer is that
   the captures stay out of the repository, then *"every screen in `screens/`"* is a definition of
   done whose subject no clone can see, and the item needs rewording — which is a `ROADMAP.md` edit
   and therefore the integrator's, not a packet's. **Decide it before you judge item 1 again.**
3. **The listing claim finds that half the fenced blocks in the documentation are stale.** That is
   the good failure. W14-01 reports the census before repairing anything, and if it is large, the
   repair is wave 15's and the census is wave 14's.
4. **`BundleShape.scala` is dead.** Its scaladoc reasons about a Scala.js linker deleted at
   `41358502`. If the suite no longer runs anything, that is a build-tree row and a deletion, and it
   is bigger than a documentation wave.

**And if none of those fires, the closing act is this.** The integrator runs **the five items**, not
the gate list: opens the product at the address it lands on; appends a false sentence to a checked
region, a false quotation to a block the roster was not written against, **and a phantom directory to
a documented listing**, and requires all three to be refused; greps for a quotation attributed to a
file that does not contain it and requires zero hits outside the gate's own fixtures; resolves every
backticked repository path in the documents item 4 names with `git ls-files`; re-runs every figure
the newcomer's overview publishes; writes the wave-14 retrospective into `ROADMAP.md`; folds whatever
is still true out of `docs/plan/verification/` into it; writes `CLOSING-REPORT.md`; and deletes
`docs/plan/WAVE-14.md`. `docs/plan/` is then `README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and
the plan is finished.

**The ratio, for the record.** Wave 10 ran six and two at 3:1 and closed four of five items. Wave 11
ran five and two at 2.5:1. Wave 12 ran four and two at 2:1 and closed eighteen of eighteen filed
rules. Wave 13 ran three and two at 1.5:1 and closed nineteen of thirty. Wave 14 is **three and one
at 3:1** — one claim kind, eight sentences, one harness, and a closer with nothing to hunt — because
what is left is a mechanism that fits in one function and a list of documents that can be read in an
afternoon.
