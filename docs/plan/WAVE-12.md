# Wave 12 — the quotation, and the sentence the gate still cannot see

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 12, when waves 9, 10 and 11 were each written to be the last.**

Wave 11 did the load-bearing thing it said it would, and it is the first wave in four that did.
The sentence written at the top of `WAVE-11.md` as its acceptance test — the exact sentence that
kept definition-of-done item 4 open for four milestones — **now turns the script red.** Reproduced
by the closer on 2026-09-12, on the shipped script, `README.md` restored byte-identical afterwards
(`md5sum` `ffd2e65225ffe97d781f785319919980`):

```
$ # append inside README.md's <!-- checked: capability-claims --> block:
$ #   Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen.
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: 1 disagreement(s) over 419 compared claims.
$ echo $?
1
```

That is real, it is the first prediction in this plan's history that was reproduced rather than
believed, and house rule 23 is what caught it in wave 11 and confirms it here.

**And item 4 is still not met, for a mechanism nobody had named until this close.** The gate compares
a figure against the thing that produces it. It does not compare **a quotation of one file against
that file**. Wave 11's W11-05 repaired `docs/operations/masking.md` — correctly, in the open, with a
scope — and the two documents definition-of-done item 4 names *by name* still quote the sentence the
page no longer contains, in the present tense, as a live defect:

```
$ grep -rn "masked value is never longer" . --exclude-dir=.git | grep -v docs/plan/
README.md:159:  the operator page overstates what the engine guarantees — *a masked value is never longer
docs/FEATURE_MATRIX.md:379: … `docs/operations/masking.md` publishes *"a masked value is never longer than the
                             value it replaced"* as an invariant an operator is invited to rely on …
                             Scoping that sentence is `docs/operations/**`'s owner's, not this row's.

$ grep -c "a masked value is never longer than the value it replaced" docs/operations/masking.md
0
```

Both sentences are false. Both were true when they were written. Both were made false **inside wave
11, by wave 11's own repair**, in the wave whose entire subject was documents going stale. Neither
sits inside a checked region, and nothing in this repository can see them, because no gate here has
ever compared a quotation against its source.

**This is the same shape as every previous close, and it is the first time the shape has a name.**
Wave 9's open item was a figure. Wave 10's was a figure and a sentence. Wave 11's was a sentence,
and wave 11 built the thing that reads a sentence — in one grammatical mood. Wave 12's is a
**quotation**, which is the one kind of prose that is mechanically checkable without a parser: a
string attributed to a file either occurs in that file or it does not.

**Wave 12 is four building packets and two adversaries. It is the smallest wave in this plan's
history.** No new service, no new endpoint, no new ADR, no new feature package, no new stylesheet,
no new screen, and — for the first time — **no new gate mechanism except one claim kind whose
implementation is a `grep`**. If a packet finds itself writing a capability, it has misread its
brief.

---

## The definition of done, judged item by item at the wave-11 close

Every figure below was printed by the command beside it, on **2026-09-12**, by the closer, one
command at a time, against the quickstart brought up from this tree (`kui-quickstart-kui`
`sha256:765834d8101c`, `kui-quickstart-frontend` `sha256:39f236d7d04e`, both built from it).
**Nothing below is read off a packet report.**

| # | Item | Verdict | Command, and what it printed |
| --- | --- | --- | --- |
| 1 | Every screen in `screens/` renders real data | **MET** | `ls screens/` → 23 captures; `pnpm -C frontend e2e` → **108 passed, 2 skipped, 0 failed**; **23 of 23** captures have a case that ran green in that run; `/ui/` and `/dashboard/overview` probed in a browser — 6 honest tiles, 0 page errors, **0** three-decimal figures in 2,311 `<td>` cells |
| 2 | Every backend capability exists as a service or an endpoint | **MET** | `ls services/` → **11**; `./mill __.openApiCheck` → **2544/2544**; `./mill checkArchitecture` → **195 modules, 10 rules, no layering violations** |
| 3 | Unit and component tests, a11y in both themes, a browser suite | **MET** | `./scripts/run-tests.sh` → **81 modules (81 with tests), 4,389 cases**; `pnpm test` → **83 files, 1,938**; `pnpm typecheck` → exit 0 **including `frontend/e2e/`**; `pnpm a11y` → **790 stories × 2 themes, no violations**; `pnpm e2e` → 108/2/0 |
| 4 | Documented: README, ARCHITECTURE, ADRs, an accurate FEATURE_MATRIX, an overview | **NOT MET** | the grep above, plus seven more below |
| 5 | One command brings the whole product up under `docker compose` | **MET** | `./deployment/quickstart/quickstart.sh` → exit 0, eight containers healthy, *"KUI is running: http://localhost:8090/ui/"*; `./deployment/compose/smoke.sh` → **PASSED** over the eleven-container distributed stack, fault isolation and recovery included |

### Item 1, measured rather than assumed

`/ui/` is honest **and the wave-11 repair landed in the shipped image.** Probed in a browser against
`kui-quickstart-frontend sha256:39f236d7d04e`:

```
createTopic count=1  enabled=false  aria-disabled=true
url before=http://localhost:8090/ui/   after=http://localhost:8090/ui/
stat tiles=6   pageerrors=0   api requests=3
```

Each of the six tiles carries a sentence (*"No cluster is selected, so KUI has not asked how many
brokers are online."*). The inert primary button wave 10 shipped and wave 11's W11-04 repaired is
now `aria-disabled` and swallows its own press. On
`/ui/clusters/quickstart/dashboard/overview`: `BROKERS ONLINE 1 · all in sync`, `TOPICS 12 · 169
partitions`, `PARTITIONS IN SYNC 100.0%`, `PRODUCTION 1.2 kB/s`, `CONSUME 105.6 B/s`,
`CONSUMER LAG 10 · 1 group not counted`. 1,159 `<td>` cells on that page and 1,152 on Traffic;
**zero** figures with three or more decimals on either.

**The twenty-three captures, re-counted against `docs/plan/verification/W8-07.md` §1 and against
this run's own case list.** `ls screens/` is 23 PNGs; `SCREENS-V4.md:157` numbers them `M01`…`M23`
in capture order. **23 of 23 have a case that drove them against the running stack in this run**:

| Screen | Case that drove it, green in this run |
| --- | --- |
| `M01` | `traffic.spec.ts:142` *carries the same stat cards as Overview, and its own last row* + `dashboard.spec.ts` (9 cases) |
| `M02`, `M22` | `shell.spec.ts:225` *the appearance popover names every choice in words, and Light repaints the frame* |
| `M03` | `traffic.spec.ts` — 11 cases, all green |
| `M04` | `dashboard.spec.ts:183` *the Storage tab replaces the body rather than repeating the Overview's rows* |
| `M05` | `alerts.spec.ts` — 5 cases, 4 green + 1 correct deployment-shaped skip |
| `M06` | `shell.spec.ts:117` (bell), `:169` (panel), **`:425` *acknowledging an open event moves the bell, with no reload* — green for the first time**, W11-02's |
| `M07` | `brokers.spec.ts` — 11 cases |
| `M08` | `shell.spec.ts:302` *switching cluster names where you have arrived, and takes the frame with it* — asserts the toast by name off the API's own roster |
| `M09` | `brokers.spec.ts:371` *follows a cluster change into the second cluster's brokers screen* |
| `M10`, `M11`, `M12`, `M13`, `M16`, `M23` | `topics.spec.ts` — 20 cases |
| `M14` | `topics.spec.ts:482` *a bulk delete says how many it deleted, in the plural* — the partial row W8-07 published is closed |
| `M15` | `messages.spec.ts` — 7 cases |
| `M17` | `consumers.spec.ts` — 6 cases |
| `M18` | `features.spec.ts` — 16 cases |
| `M19` | `connect.spec.ts` — 4 cases, **all green, no skips** (W8-07 recorded one failure and three skips) |
| `M20`, `M21` | `ksql.spec.ts` — 3 green + 1 correct deployment-shaped skip |

**The two skips are the right two and are not coverage gaps.** `alerts.spec.ts:270` — *a deployment
with no alerts service draws no card rather than an empty one* — and `ksql.spec.ts:175` — *a
deployment with no ksqlDB says which key to set*. Both describe a **different deployment** from the
one under test. The third skip, which stood for three waves, is gone: W11-02 shipped
`diskUsedWarningPercent: 1` in the quickstart and W11-A2 made the case wait out its own success
rather than read an emptiness it caused, so `108 passed / 2 skipped` is stable across a back-to-back
re-run rather than timing-dependent.

### Item 4, the eight things it is open on

Each was measured at this close, by the closer, with the command beside it.

1. **The quotation class, and it is the wave's subject.** `README.md:159` and
   `docs/FEATURE_MATRIX.md:379` each quote `docs/operations/masking.md` as publishing *"a masked
   value is never longer than the value it replaced"* and each says, in the present tense, that
   scoping it is somebody else's job. `grep -c` on the page is **0** — the page now reads *"A `mask`
   never returns a value longer than the value it replaced … **This does not hold for `replace`**"*.
   Two of item 4's five named documents describe a defect that no longer exists, created by a repair
   in the same wave. **Nothing in this repository compares a quotation of one file inside another.**
2. **The prose gate reads one grammatical mood.** Measured at this close on the shipped script, each
   sentence appended inside `README.md`'s `<!-- checked: capability-claims -->` block, one at a
   time, nothing else changed:

   | Sentence appended inside the checked block | Script |
   | --- | --- |
   | `Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen.` | **exit 1**, 1 disagreement |
   | `KUI ships without a Kafka Connect screen and never built ksqlDB.` | **exit 1**, 1 disagreement |
   | `Kafka Connect remains unimplemented, and ksqlDB is a stub.` | **exit 0**, *419 claims checked, all true* |
   | `The Connect screen is a placeholder and the ksqlDB page is empty.` | **exit 0**, *419 claims checked, all true* |
   | `KUI has no topic detail page and no consumer lag chart.` | **exit 0**, *419 claims checked, all true* |

   The gate refuses a **negated** claim about a named service and does not refuse a **positive-voice
   false** one. Row 4 names both service identities *and* the qualified noun the gate was taught to
   read, and escapes for want of a negation token. It is a direct restatement of the sentence in
   row 1. This was filed by W11-01's own verifier before freeze and is not a discovery.
3. **`docs/overview/README.md`'s snapshot table went stale inside the wave that repaired it.** Three
   of its ten gate rows, each against the command written beside it in the same table:
   `./scripts/run-tests.sh` publishes **4,350** and prints **4,389**; `pnpm test` publishes **1,933**
   and prints **1,938**; `pnpm e2e` publishes **106 passed, 3 skipped** and prints **108 passed, 2
   skipped**. The page dates the column *"measured 2026-09-12, at the close of wave 10"* and its
   header line says *"Every figure below was measured on 2026-09-11"* — **two dates, and today is
   the later one.** The three figures the script derives itself are inside `<!-- checked: gate-table
   -->` and are correct and gated; the three that moved are the three that are not.
4. **`TECH_DEBT.md` TD-047 is false and open.** It publishes *"Deleting `docs/operations/masking.md`
   entirely leaves `./scripts/run-tests.sh` green at 4,350 cases"*. Measured here by deleting the
   page: `./mill --no-daemon libs.config.test` → **3 failed**, each naming the page by path. W11-05
   closed half of that row in wave 11 and W11-01 did not carry the amendment. Separately, TD-044
   still reads *"Attempted by W11-03 in wave 11"* and is open, TD-045 and TD-048 are open, and the
   three rows W11-03 asked for do not exist.
5. **`deployment/quickstart/README.md:162` states the `order-fulfilment` lag wrongly for the fifth
   consecutive wave.** It now says *"behind by single digits with uneven lag across its six
   partitions"*. Measured on a stack **brought up fresh at this close**:
   `curl -s localhost:8080/api/v1/clusters/quickstart/consumer-groups` → `order-fulfilment … totalLag
   10`. W11-03's verifier measured **14** on an older stack. The published history of this one figure
   is 8, 10, 10, *"single digits"*, and the paragraph four lines below it correctly explains that the
   figure *"only ever goes up"* — so the sentence contradicts its own paragraph. **The derivation is
   right and the bound is wrong; delete the bound.**
6. **`deployment/quickstart/kui-quickstart-auth.yaml:294` makes a byte-equality claim that is not
   byte-equality, in the file whose entire wave-11 repair was a false byte-equality claim.** It says
   the masking block is *"Byte for byte the block `./kui-quickstart.yaml` carries"*.
   `diff <(awk '/^      masking:/,/^$/' kui-quickstart.yaml) <(awk … kui-quickstart-auth.yaml)` →
   `2,4c2`. The rules are identical; the leading comment is three lines in one file and one in the
   other.
7. **A production scaladoc asserts as fact something measurement says is a coin flip.**
   `services/gateway/api/src/kui/gateway/api/StreamProxy.scala:124-133` states that the carry is
   unreachable from `withTerminalEvent` and that with it deleted *"the whole suite stayed green,
   including the case named `aTerminalEventSplitAcrossChunkBoundariesIsStillSeen`"*. W11-A2 ran that
   mutation four consecutive times: **run 2 was red.** `relay` drains a `Queue.bounded` from a second
   fibre, so coalescing is a scheduling outcome, not a property. The **test-side** comment now
   carries the table and says so in full; the production file is unchanged (`git diff --stat
   fcd184fe` on it prints nothing) and is owned by nobody.
8. **Two figures corrected in wave 11 were stale before the wave ended.**
   `frontend/e2e/tsconfig.json:12` publishes *"`wc -l frontend/e2e/*.ts`, **5,169** on 2026-09-12"*
   in place of the 4,961 it replaced; the command answers **5,229** today, because W11-A2 then edited
   `shell.spec.ts` in the same pass. `frontend/README.md:57` still says *"`pnpm typecheck` — `tsc
   --build`, strict"* over a script that has been two `tsc` invocations since W11-02. **Both are
   mitigated** — the first writes the command beside the figure, which is the shape wave 11 agreed
   on — and both are still wrong.

### What is NOT open, and should not be re-hunted

* **The exact reproduction sentence from `WAVE-11.md` is red.** Do not re-litigate it.
* **`docs/overview/README.md` has a checked region for the first time**, `gate-table`, with five
  claims. Proved able to fail at this close: `**419 claims**` → `**420 claims**` gives
  *"says this script compares 420 claims; this run compared 419"*, one disagreement, and the page was
  restored.
* **`DM-001` is `COMPLETE`** and the record it closed on is in the row.
* **The third browser skip is dead and stays dead** across a back-to-back re-run.
* **`pnpm typecheck` reads `frontend/e2e/`.** Two invocations, exit 0, and `BuildWiringSuite` now
  asserts the script still names `e2e/tsconfig.json`.
* **`build.mill`'s two `runMain` call sites are gone**, `runDocumentMain` has a suite, and the
  bare-call bypass W11-03's verifier found in its own repair is closed by a receiver-independent
  pattern plus an equality on the body count.

---

## The tree you start from

Every figure printed by the command beside it, at the wave-11 close, one gate at a time, by the
closer.

| Gate | Figure |
| --- | --- |
| `./mill __.compile` | 8251/8251 |
| `./scripts/run-tests.sh` | **4,389 cases over 81 modules**, all 81 carrying tests (wave 10: 4,350) |
| `pnpm -C frontend test` | **1,938 over 83 files** (1,933 over 83) |
| `pnpm -C frontend e2e` | **108 passed, 2 skipped, 0 failed** over 110 (106/3 over 109) |
| `./scripts/feature-matrix-check.sh` | **419 claims over 10 sections**, all true (404 over 9) |
| `./mill __.checkFormat` | 495/495 |
| `./mill __.fix --check` | 10672/10672 |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2544/2544 |
| `pnpm -C frontend typecheck` | exit 0 — **over a tree that now includes `frontend/e2e/`** |
| `pnpm -C frontend lint:boundaries` | 425 files in 11 packages |
| `pnpm -C frontend a11y` | **790 stories × 2 themes, no violations** |
| `./deployment/quickstart/quickstart.sh` | eight containers, healthy, two registered clusters |
| `./deployment/compose/smoke.sh` | **PASSED** over eleven containers, including the SSE `phase` assertion |

`./scripts/feature-matrix-check.sh`'s section sizes: self-check 7, rows 30, merged-document 50,
milestones 49, adr-index 112, openapi-totals 15, **guard-fixtures 21**, **capability-claims 42**,
**gate-table 5**, dependencies 88 over 12 named manifests. `docs/FEATURE_MATRIX.md` 189 rows,
71 COMPLETE, 178 in scope, 40% delivered. `docs/api/openapi.json` 65 paths, 76 operations, 160
schemas, `X-Kui-Principal` on 59 operations over 48 paths. `DECISIONS.md` 56 rows over 56 ADRs.

**Anything red is yours. Nothing is red.** Every gate above is green and was run sequentially on an
unloaded machine at this close. **The four things that are wrong are not gate failures, and that is
the whole problem:**

1. **Three false quotations and one false byte-equality claim**, in four different files, each
   passing every gate this repository has.
2. **Three positive-voice false capability sentences**, green inside the checked block built to
   refuse them, measured in the table above.
3. **Three stale snapshot rows** in the newcomer's overview, made stale by wave 11's own work.
4. **One CI step that says *"every name matched"* over an image with a missing manifest.**
   Reproduced at this close, not read off a report: `COPY frontend/packages/api/package.json
   ./packages/api/` → `COPY frontend/packages/api/tsconfig.json ./packages/api/`, then the
   *"The interface image copies every workspace manifest"* step extracted with `yaml.safe_load` and
   run under `bash -e` from the repo root → `frontend/packages: 11 packages, 11 manifests copied,
   every name matched`, **exit 0**. `deployment/frontend/Dockerfile` restored, `md5sum`
   `c6a9fd3ed2307f4b7796092fc9e4b1a9`.

---

## House rules

The first twenty-four are waves 8 through 11's and still apply in full; read them in the git history
of `docs/plan/WAVE-11.md`. Two are amended and one is new, and each is a wave-11 finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not
otherwise changing**; test sources are inside `checkFormat` and `fix --check` since wave 9.

4. **A gate you cannot make fail is not a gate.** Unchanged. It produced the five-row table above,
   and two of those five rows are why there is a wave 12.
5. **Report a mutation that stayed green.** Unchanged. **Ninth wave, no movement** — see the
   retrospective. Every one of the five building packets disclosed exactly the one its brief demanded
   and its verifier found between two and five more.
17. **AMENDED again — the measurement a packet publishes about its own gate must be taken after its
    last edit to the thing measured.** Wave 11 finally obeyed the amended form and W11-01 still
    published a two-line price its verifier halved to one; separately, W11-A2 corrected
    `frontend/e2e/tsconfig.json` to **5,169** and then edited a file in that directory in the same
    pass, so the figure it repaired was wrong by sixty lines before the pass ended. **A figure about
    your own tree is taken last, or it is written as a command and no figure at all.**
18. **Unchanged and it worked.** Seven packets, **seven verification files**, every one written by
    the packet it is about. Wave 10 lost three to a partition argument; wave 11 lost none.
21. **Unchanged.** The closer converted **16 of 18** filed rows, **89%**, and **16 of 17** whose
    closing case was inside its ownership, **94%**. It did not hunt while filed rows remained.
23. **Unchanged, and it earned its place in one wave.** `ROADMAP.md` and `WAVE-11.md` both predicted
    that the `capability-prose` claim would turn item 4 into a gate. The closer reproduced the
    prediction in the shape the plan wrote it: **it holds for the sentence the plan named and for one
    of its five phrasings.** Without rule 23 this wave would have recorded "the gate landed" and
    stopped.
25. **NEW: a claim about a file you do not own is a quotation, and a quotation is checked or it is
    not made.** Wave 11 produced three of them. A document that quotes another file's sentence
    acquires a dependency on that file, and every previous wave has discovered this by finding the
    dependency already broken. From here: a passage inside a checked region that attributes a quoted
    string to a named path is compared against that path by `scripts/feature-matrix-check.sh`, and a
    packet that wants to say what another file says either puts the quotation inside a checked region
    or names the file without quoting it.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`.
Use `./mill --no-daemon` for anything you record a number from. **Running the a11y sweep is three
commands** — build Storybook, serve `storybook-static` on `:6017`, then sweep. `npx http-server`
leaves **two** processes; kill the listener `ss -ltnp` names.

**And one measured this wave that costs an hour if you do not know it.** `./mill a.test b.test` runs
**zero** tests and exits 0 — the second word is a munit **name filter**, not a second target.
W11-A1 measured it: `./mill --no-daemon libs.filter.test services.message.__.test` printed
*"0 failed, 1 ignored, 0 total"* for both suites and **SUCCESS**, and a whole first sweep of twelve
mutations was reported green on exactly that command; re-run correctly, eleven of twelve were gated.
**Any acceptance line of that shape measures nothing.** Use `-k` and one target per invocation, or
`__`.

**And one more.** Mill caches `build-tests.test` on inputs that do **not** include `build.mill`,
`.github/workflows/ci.yml` or `frontend/package.json` — all three of which `BuildWiringSuite` reads
at runtime. Measured by W11-A2: with the `typecheck` script mutated, `./mill --no-daemon
build-tests.test` printed **137/137 SUCCESS without running the suite**. `clean` first, or you are
measuring the previous tree. **This is W12-02's to fix and everybody's to know.**

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **419 claims over 10 sections**: self-check 7, rows 30, merged-document 50, milestones 49, adr-index 112, openapi-totals 15, guard-fixtures 21, capability-claims 42, gate-table 5, dependencies 88 over 12 manifests | **W12-01**, which must raise it and not lower it |
| `docs/FEATURE_MATRIX.md` | 189 rows, 71 COMPLETE, 178 in scope, 40% delivered, all inside checked regions | **W12-01** alone |
| `docs/overview/README.md` | the `gate-table` block's three script-derived figures, and **three dated rows that are wrong today** | **W12-01** alone |
| `frontend/e2e/**` | **108 passed, 2 skipped, 0 failed.** Both skips are deployment-shaped and correct; **no packet may add a third** | **W12-04**; W12-03 files through it |
| `deployment/quickstart/kui-quickstart.yaml` | `diskUsedWarningPercent: 1`, now asserted by `ShippedConfigurationSuite` — raising it silently returns the browser suite to three skips | **W12-02**, and the suite is W12-01's to leave alone |
| `build-tests/test/.../BuildWiringSuite.scala` | five cases reading `build.mill`, `ci.yml` and `frontend/package.json` as text; **`clean` before you trust a run** | **W12-02** |
| `libs/config/test/.../ShippedConfigurationSuite.scala` | 16 cases, including the disk threshold, both quickstarts' masking rules and `rule.productArity == 5` | **nobody this wave** — W12-A1 may add, not change |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 over every tree including tests | every packet |
| `deployment/compose/smoke.sh` | Nine routed services, each asked for one signed read; the message stream's refusal **is** a 200, asserted on the `phase` frame | **W12-02** |
| `.github/workflows/ci.yml` | the manifest roster step, which **passes over a missing manifest** — see the tree section | **W12-02** |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W12-01 — The quotation, and the mood the gate cannot read

**Owns**
```
scripts/feature-matrix-check.sh      (except section 6, guard-fixtures, which is W12-A2's on freeze)
docs/overview/**
README.md
ARCHITECTURE.md
docs/FEATURE_MATRIX.md
TECH_DEBT.md
DECISIONS.md
docs/adr/**
frontend/packages/api/README.md
```

**Contract.** Definition-of-done item 4, and this is the only packet in the wave that can close it.
It is the sixth consecutive wave to be given this script, and the first with a mechanism that is a
`grep` rather than a heuristic. Read items 1, 2, 3 and 4 of the section above before you write a
line.

**Do**

1. **The `quotation` claim kind, and it is the load-bearing item of this wave.** Inside any
   `<!-- checked: -->` region, a passage that contains a quoted string **and** a path that resolves
   to a file in this repository asserts that the string occurs in that file. Implement it as
   literally as that sentence reads: pull the backticked or italicised span, pull the nearest
   backticked path, `grep -F` the one in the other, and refuse with both sides printed. Scope it
   deliberately and say what you scoped it to — a quotation of **eight words or more** attributed to
   a path ending `.md`, `.scala`, `.ts`, `.tsx`, `.yaml` or `.sh` is a defensible first draft, and a
   heuristic you can state in one sentence is worth more than one you cannot.
   **The acceptance is the two sentences in item 1 of the section above going red**, which means
   both must first move inside a checked region — `README.md:159` is four lines outside the
   `capability-claims` block and `docs/FEATURE_MATRIX.md:379` is outside every region in that file.
   Then **repair both** and show the run green. Drive it from a fixture in section 6's shape in both
   directions.
2. **The positive-voice half of `capability_prose_offences`.** The five-row table above is your
   input; three of those rows are green and must not be. The rule today is *an identity token
   together with a negation token*. The missing half is *an identity token together with a
   **state** token* — `placeholder`, `stub`, `empty`, `unimplemented`, `not implemented`, `coming
   soon`, `TODO`. Hold the vocabulary beside `negation_tokens` as a second named list so a reader
   can see both, and **add one fixture pair per token** — W11-01's verifier found that half the
   negation vocabulary (`without`, `never`) was asserted by nothing until W11-A2 wrote pairs for it,
   and the same will be true of yours the day you ship it.
   **Say in the file what you decided about the false-positive risk**, because this is the one that
   makes the README unwritable if it is too loose: a sentence like *"the Connect screen is the
   placeholder for a worker that is not configured"* is honest prose about a **configured** state and
   your rule must not refuse it. If the honest answer is that the checked block shrinks to the three
   lists and the prose moves outside it, **that is an acceptable answer** — say so and do it, rather
   than shipping a rule that costs the next writer a fight.
3. **`docs/overview/README.md`, and this time do not leave a dated figure that moves.** Three rows
   are wrong: `run-tests.sh` 4,350 → **4,389**; `pnpm test` 1,933 → **1,938**; `pnpm e2e` 106/3 →
   **108/2**. Run each command yourself; do not adjust, and do not copy from this file. Then decide
   the structural question wave 11 left: a figure that needs another process cannot be inside the
   script's ledger, so either **write the command in the cell and no figure at all** (the shape
   `docs/FEATURE_MATRIX.md:487` now uses and the shape that survived this close), or accept that the
   column is a snapshot and **make the date a real one** — the header line says 2026-09-11 and the
   table says 2026-09-12, in the same document, about the same tree.
4. **`TECH_DEBT.md`, which nobody carried in wave 11.** Amend **TD-047** — deleting
   `docs/operations/masking.md` is now **3 failed** in `libs.config.test`, measured at this close, so
   the row is true only of `configuration.md`. Close or amend **TD-044** (it still says *"Attempted
   by W11-03 in wave 11"*), **TD-045** and **TD-048** against what actually landed. File the four
   rows wave 11 asked for and did not get: `.github/workflows/**` is exercised by nothing local;
   `kui-quickstart-auth.yaml` ships no `ksql` block while the container runs; the deployment-claims
   class; and Mill's `build-tests.test` input set. And note in the preamble that *"the next free id
   is TD-049"* **is read by no script** — `feature-matrix-check.sh` never opens this file — so the
   id-collision class that produced the duplicate `TD-035` is still ungated. Gating it is one
   `claim`; do it or say why not.
5. **`docs/FEATURE_MATRIX.md`.** Beyond `DM-001`'s stale caveat (item 1 above), re-read every row
   that cites a figure from another file and check the citation rather than the figure. `:487`'s
   *"drives **eight** cases — `grep -c 'test(' frontend/e2e/search.spec.ts`"* is **correct today**,
   measured, and is the shape the rest of the file should be in.
6. **House rule 17 in its twice-amended form, and house rule 25.** Before you report: construct the
   cheapest attack you can on your finished quotation gate and on your finished prose gate, run both,
   and write down what each cost. Then **re-take every figure you published about your own tree after
   your last edit**, because that is the amendment and wave 11 broke it in the packet that wrote it.

**Acceptance**
```
./scripts/feature-matrix-check.sh                 # 419 -> more, all true, exit 0
grep -rn "masked value is never longer" . --exclude-dir=.git | grep -v docs/plan/   # nothing
```
**Mutation line, and there are three, all of which must go red:** (a) the exact sentence from item 1
of the section above, restored into `README.md` after your repair; (b) `The Connect screen is a
placeholder and the ksqlDB page is empty.` appended inside the `capability-claims` block; (c) one
figure in `docs/overview/README.md`'s checked block moved by one. **And a green one:** report the
cheapest sentence you can construct that your finished gate still lets through. There will be one.

---

## W12-02 — The image that ships without a manifest, and four deployment sentences

**Owns**
```
deployment/**
.github/workflows/**
build.mill
build-tests/**
```

**Contract.** Two of the four things that are wrong live here, and one of them is a CI step that
reports success over a broken image. Nothing in this packet is a new capability.

**Do**

1. **The manifest roster, reproduced at this close and open.** Both derivations in the
   *"The interface image copies every workspace manifest"* step reduce with
   `sed -E 's#^COPY frontend/packages/([^/]+)/.*#\1#'`, so they compare the **package name** on each
   end and never the file. Change `deployment/frontend/Dockerfile:80`'s `package.json` to
   `tsconfig.json` and the step still prints *"11 manifests copied, every name matched"* and exits 0,
   while `packages/api` ships with no manifest and pnpm resolves the smaller workspace exactly as for
   a deleted line. **Fix:** anchor `sources()` on the manifest —
   `sed -E 's#^COPY frontend/packages/([^/]+)/package\.json .*#\1#'` — so a line copying anything
   else fails to reduce and appears whole in the diff; **and** add
   `COPY frontend/packages/decoy/tsconfig.json ./packages/decoy/` to the Dockerfile self-test fixture
   as a line that must **not** appear in the derived list. Prove all four controls red: deleted line,
   kernel duplicated over `feature-ksql`, destination misspelled, and the substitution above.
2. **`deployment/quickstart/README.md:162`, the fifth false statement of one figure.** *"behind by
   single digits with uneven lag across its six partitions"* — measured on a stack brought up fresh
   at this close, `totalLag` is **10**; W11-03's verifier measured **14**. The note four lines below
   derives it correctly and says it *"only ever goes up"*, which is why **no bound is publishable**.
   **Delete the bound.** Keep the stable facts already stated beside it (`EMPTY`, no members, six
   partitions, the `--to-offset 2` derivation that reconciles). Do not replace one bound with another.
3. **`deployment/quickstart/kui-quickstart-auth.yaml:294`.** *"Byte for byte the block
   `./kui-quickstart.yaml` carries"* is false by a three-line comment — `diff` of the two `awk`
   extracts is `2,4c2`. Either make it byte-for-byte or say *"the same rules"*. **And while you are
   in this file:** `--with-auth` still ships with the ksqlDB screens hidden — no `ksql` block on the
   cluster while `docker-compose.auth.yml` overrides only `kui` and `avro-seed`, so the container
   starts and nothing points at it. W11-03 documented it in the header rather than repairing blind,
   and its reasoning was sound. **Decide it now**, in the open, and say which.
4. **Mill's input set for `build-tests.test`.** `BuildWiringSuite` reads `build.mill`,
   `.github/workflows/ci.yml` and `frontend/package.json` at runtime and Mill caches the module on
   none of them — measured: a mutated tree printed **137/137 SUCCESS without running the suite**.
   Declare them as `Task.Source`s (or the smallest equivalent) so the cache invalidates, and prove it:
   mutate `frontend/package.json`'s `typecheck` script and show `./mill --no-daemon build-tests.test`
   red **without** a preceding `clean`. If the right answer is that this cannot be expressed in Mill
   for a suite that reads files outside its own module, say so with the attempt written down, and
   leave the `clean` in the house rules where it now lives.
5. **`.github/workflows/**` is still exercised by nothing local except the two cases W11-A2 added.**
   `everyDocumentGateTheWorkflowRunsAsksItToCheckRatherThanWrite` reads `run:` lines and anchors on
   exactly two invocations. That is a real gate and it is one gate. Say plainly, in a comment or a
   TECH_DEBT row through W12-01, what else in that file could go wrong silently — the roster step
   above is the existence proof that something can.

**Acceptance**
```
./mill --no-daemon clean build-tests.test && ./mill --no-daemon build-tests.test
./deployment/compose/smoke.sh
./deployment/quickstart/quickstart.sh            # then quickstart.sh down
python3 -c "import yaml,sys; ..." | bash -e      # the roster step, from the repo root
```
**Mutation line:** the `package.json` → `tsconfig.json` substitution in `deployment/frontend/Dockerfile`,
shown red. **And a green one:** report one edit to `.github/workflows/ci.yml` that nothing in this
repository notices.

---

## W12-03 — The stream's prose, and the eight auth rules nobody measured

**Owns**
```
services/gateway/api/src/kui/gateway/api/StreamProxy.scala
services/gateway/api/src/kui/gateway/api/auth/**
services/gateway/api/test/src/kui/gateway/api/**
```

**Contract.** One production file publishes a sentence measurement contradicts, and eight mutations
wave 11's hunter planned and ran out of wave for are still unmeasured.

**Do**

1. **`StreamProxy.scala:124-133`.** The scaladoc says the carry is unreachable from
   `withTerminalEvent` and that the whole suite stayed green with it deleted. W11-A2 ran that
   mutation four times and **run 2 was red**, because `relay` drains a `Queue.bounded` from a second
   fibre and coalescing is a scheduling outcome. The **suite-side** comment at
   `StreamProxySuite.scala:288-315` already carries the full table and says the case *"is NOT a gate
   on the carry and must never be counted as one"*. **Bring the production paragraph into line with
   it** — one paragraph, no code change, and say *usually* where it says *cannot*. This is house rule
   25's sibling: a production comment that asserts a measurement is a claim, and this one is wrong.
2. **The eight gateway mutations W11-A1 planned and did not measure.** Each is a one-line edit and a
   suite command; none is reported as gated. Measure them, close what is ungated, and argue down what
   is equivalent **with the algebra**, not with a shrug:
   * `CsrfCheck` — the length term; the cross-site branch; the no-session refusal (**G2–G4**).
   * `SessionMiddleware` — the health-endpoint exclusion; the mint-only-for-the-API rule; `httpOnly`
     (**G5–G7**).
   * login's `PasswordChangeRequired` branch issuing no new cookie (**G12**).
   * the exact-name cookie lookup (**G15**).
   `AuthRoutes` was the one production file in this repository with a whole public surface and no
   behavioural case; W11-A1 gave it `SignInRoutesSuite` and closed six rules there. These eight are
   what is left of that sweep.
3. **Use `getWithoutFollowing`.** `GatewayTestServer.Running.get` is built from sttp's `basicRequest`,
   which **follows redirects** — a case asserting the OIDC callback's 302 silently asserted the
   static SPA fallback's 503 instead. W11-A1 found this and added the seam. Any new case asserting a
   status in the 3xx range uses it.

**Acceptance**
```
./mill --no-daemon services.gateway.api.test
./mill --no-daemon '{services.gateway.api,services.gateway.api.test}.checkFormat'
./mill --no-daemon checkArchitecture
```
**Mutation line:** one of G2–G7, G12 or G15 shown red against your new case. **And a green one:** any
of the eight you conclude is equivalent, with the reason.

---

## W12-04 — The address sweep that reads two controls, and three stale frontend figures

**Owns**
```
frontend/e2e/**
frontend/README.md
frontend/packages/shell/**
frontend/packages/kernel/**
frontend/tsconfig.json
frontend/package.json
frontend/playwright.config.ts
```

**Contract.** Two figures corrected in wave 11 are already wrong, and the case wave 11 built to
sweep *"every control this address drew"* sweeps two of fifteen.

**Do**

1. **`frontend/e2e/tsconfig.json:12`.** It publishes *"`wc -l frontend/e2e/*.ts`, **5,169** on
   2026-09-12"*; the command answers **5,229**, because the packet that wrote the figure then edited
   `shell.spec.ts`. The command is written beside it, which is the mitigation this project agreed on
   — so either take the figure last, or **drop the figure and keep the command**, which costs nothing
   and cannot go stale. House rule 17, twice amended.
2. **`frontend/README.md:57`.** *"`pnpm typecheck` — `tsc --build`, strict"* over a script that has
   been `tsc --build && tsc --noEmit -p e2e/tsconfig.json` since W11-02. One line. It is owned by
   nobody today and it is yours this wave.
3. **The address sweep, which is the interesting one.** `overview.render.test.tsx`'s case *"offers no
   enabled action this address cannot perform"* mounts `Overview` alone. Its distinguishing half —
   `control.click(); expect(navigated || opened).toBe(true)` — **executes zero times** on the green
   tree: the instrumented inventory is exactly `[["BUTTON","Create topic",null,"true"],["A","Manage
   clusters","/ui/clusters/manage",null]]`, so the button hits `continue` on `aria-disabled` and the
   link hits `continue` on the `A` branch, and `AddressProbe` is mounted and never read. Measured on
   the running product, **the address draws 15 `a, button` controls and the case sweeps 2** — the
   other thirteen are `TopBar` (including three unlabelled and two labelled `Q`/`S`), `EnvRail`,
   `Breadcrumb` and the drawer's links, all on `/ui` exactly as the header action is.
   **Either widen the mount to the frame** — `app.render.test.tsx` already mounts it, and W11-A2's
   composition case is the shape — **or rename the case to what it actually holds.** The vacuity
   guard `expect(controls.length).toBeGreaterThan(1)` is satisfied by exactly those two and will not
   notice either way. Do not leave a case whose name over-claims by seven-fold.
4. **`StatRowProps.range` is read by no entry of `STAT_TILES`**, yet `StatRow` passes
   `range={props.range}` into every `Dynamic`, and the interface comment says *"whether or not it
   reads all of it"* when in fact **none** of the six reads it. Delete it or make the comment true.
5. **`ClusterSelector.tsx` has survived three waves as an undecided decision.** 214 lines, eight
   stories, eight cases, an export from `shell/src/index.ts`, **zero production callers**, and
   `[data-testid="cluster-selector-trigger"]` resolving to zero elements on every screen of a running
   stack. `Overview.tsx:507` and `:550` now both name it in comments as *"the shell's other cluster
   chooser"*. **Decide it.** Deleting it is a decision and keeping it is a decision; leaving it is
   not.
6. **No third skip.** `pnpm -C frontend e2e` is 108/2 and both skips are deployment-shaped. If your
   work adds one, you have not finished.

**Acceptance**
```
pnpm -C frontend test
pnpm -C frontend typecheck
pnpm -C frontend e2e                 # 108 passed, 2 skipped, 0 failed
pnpm -C frontend lint:boundaries
node frontend/scripts/a11y-stories.mjs   # after build-storybook + serve on :6017
```
**Mutation line:** re-enable one control on `/ui` that cannot perform its action and show your widened
sweep red. **And a green one:** report one control on the address your sweep still does not read.

---

## W12-A1 — The hunter

**Owns**
```
libs/filter/**
libs/kafka-auth/**
services/alerts/**
services/consumer/**
services/schema/**
services/connect/**
(test trees only; production edits are behaviour-preserving seams, declared in the first hour)
```

**Contract.** Mutate, measure, close. Everything you find that you cannot close, file with the exact
mutation, the suite command and the closing case, per house rule 18, in
`docs/plan/verification/W12-A1.md`.

**Start from the two rows wave 11 left open**, both filed by W11-A1 with the mutation written out:

1. **`libs/filter/src/kui/filter/CelFilterEngine.scala` — `Sync[F].interruptible` → `Sync[F].blocking`.**
   `./mill --no-daemon libs.filter.test` → 548/548 SUCCESS. Cancelling a browse is supposed to cancel
   the evaluation in flight; nothing asserts it. The deadline beside it **was** closed this wave (the
   old case accepted `Right(_)` as well as `Left(Timeout)`, so it had no failing input) and now asks a
   population of 50 evaluations in both directions — that is the shape.
2. **`libs/kafka-auth/src/kui/kafka/auth/KeyStoreMaterializer.scala` — delete the second
   `Files[F].setPosixPermissions(directory, DirectoryPermissions)`.** `./mill --no-daemon
   libs.kafkaAuth.test` → 395/395 SUCCESS, `KeyStoreMaterializerSuite` 12/12 green. W11-A1 judged it
   *"not portably closable"*; decide that yourself rather than inheriting it, and if it is right, say
   what a non-portable case would look like and file the reason rather than the row.

Then sweep the four services in your `Owns`, **none of which has ever been mutated**, and each of
which holds a filed finding nobody has closed: `alerts`'s `MaxReadMarkers` (TD-031), `consumer`'s
`stateCounts` and `notes` (TD-032), `schema`'s dead `.filter` (TD-033), `connect`'s
`ConnectorFacts.complete` (TD-034).

**Two things measured in wave 11 that will change how you count.** The compiler refused **14 of 73**
mutations under `-Werror` + `-Wunused` with four distinct messages; it fires when a guard's *inputs*
stop being read and never for a changed constant, a flipped boolean, or `&& false` appended to a
condition — so it raises the cost of **deleting** a guard by one edit and the cost of **disabling**
one by nothing, and **10 of those 14 turned out to be gated anyway**. And `Class.forName(name, false,
loader)` → `initialize = true` in `CloudHandlers` **hung** `libs.kafkaAuth.test` for 520s against a
9s baseline with no test progressing. A hang is not a red and is not a green; count it in neither
column and write it down.

**Acceptance**
```
./mill --no-daemon libs.filter.test
./mill --no-daemon libs.kafkaAuth.test
./mill --no-daemon services.alerts.__.test        # one target per invocation — see the house rules
./mill --no-daemon '{libs.filter,libs.kafkaAuth}.checkFormat'
./mill --no-daemon checkArchitecture
```
**Mutation line:** the rate, with the denominator stated and every equivalent mutant argued down in
writing.

---

## W12-A2 — The closer

**Owns**
```
scripts/feature-matrix-check.sh      (section 6, guard-fixtures, on W12-01's freeze)
every test tree in the repository, on each packet's freeze
docs/plan/verification/W12-A2.md
```

**Contract.** Close the rows the five verifiers file, before you hunt anything of your own. House
rule 21, which has now converted 27.5% → 60% → 89% across three waves.

**What is already known to be waiting for you, from wave 11's own verification files:**

* **`build.mill`'s bare-call bypass** is closed and the pattern is now receiver-independent with an
  equality on the body count. **Re-run it** — it is the one gate in this repository that has been
  reopened through a synonym once already.
* **The capability-prose claim site** is closed by a driven fixture plus a ledger read. W11-A2
  recorded that *"the ledger half alone was GREEN under the mutation"* — only the driven half caught
  it. Any fixture you write for W12-01's quotation gate inherits that lesson: **drive the function,
  do not read the ledger.**
* **`verify_service_fact_roster_independence` seeds `declared_service_state[fixture-routed]` and
  never removes it**, so a fixture reading the live roster reads another fixture's residue. W11-A2
  fixed this inside its own new fixture by rebuilding the roster from `service_aliases` before each
  direction. **Every fixture you add does the same**, or section 6 becomes order-dependent.
* **The gate-claim total moves when you add a fixture.** Every fixture is a `claim`, every claim is a
  ledger line, and `gate-claim-total` compares the ledger's length against one integer in
  `docs/overview/README.md`. W11-A2 crossed that edge for one integer and declared it. **Declare it
  again**; `docs/overview/README.md` is W12-01's.

**Acceptance**
```
./scripts/run-tests.sh
./scripts/feature-matrix-check.sh
pnpm -C frontend test
./mill --no-daemon __.checkFormat
```
**Mutation line:** the conversion rate, filed-rows-closed over filed-rows-received, with the rows you
argued down named and their algebra written out.

---

## Where the packets meet

| Edge | What crosses it |
| --- | --- |
| W12-01 ← everybody | **`TECH_DEBT.md` and `docs/FEATURE_MATRIX.md` are W12-01's alone.** Every packet that closes or opens a row tells W12-01; no packet edits either file. |
| W12-01 → W12-02 | **The quotation gate will fire on `deployment/**`.** `kui-quickstart-auth.yaml` and `deployment/quickstart/README.md` both quote other files. W12-01 says which regions it scoped to; W12-02 repairs what that scoping catches. Say when you freeze. |
| W12-02 → W12-04 | **`frontend/package.json` is W12-04's; `build-tests`'s assertion about it is W12-02's.** A change to the `typecheck` script breaks `BuildWiringSuite`. Neither packet is expected to change it; if one does, it tells the other before it lands. |
| W12-03 → W12-A1 | **`services/gateway/api/test/**` is W12-03's until it freezes**, then W12-A2's. W12-A1 does not enter the gateway this wave — W10-A1 and W11-A1 swept it and 19 rules are closed there. |
| W12-04 ↔ W12-03 | `frontend/e2e/**` is **W12-04's**. W12-03 files through it rather than editing a spec. |
| W12-01 → W12-A2 | `scripts/feature-matrix-check.sh`'s section 6 passes to the closer on freeze, and the quotation fixtures live there. |
| every packet → W12-A2 | Test trees pass to the closer on freeze. **Say when you freeze.** |

**And the one that cost wave 11 an hour.** *A mutation sweep and a suite run cannot share one working
tree.* Mill locks `out/`, so two sessions serialise — W11-A1 measured a 105s run taking **11
minutes** — and one packet's mutation is compiled into the other's run. W11-A2's acceptance recorded
one red that was W11-A1 writing a file underneath it, and Mill logged *"Another Mill process with PID
3564529 is running"* into the losing run's own log. **Separate checkouts, or serialise the adversaries.**

---

## The partition, checked

**Backend.** Eleven services. `gateway`'s `StreamProxy.scala`, its `auth/**` and its whole test tree
→ **W12-03**. `alerts`, `consumer`, `schema` and `connect` → **W12-A1**. `cluster`, `topic`,
`message`, `metrics`, `ksql` and `identity` are **owned by nobody and need no edit**; W12-A2 takes
their test trees after freeze.

Thirteen `libs`. `filter` and `kafka-auth` → **W12-A1**. The other eleven are **owned by nobody** —
`config`, `observability` and `security-core` were all repaired in wave 11 and are correct as they
stand.

`build.mill`, `build-tests/**`, `.github/workflows/**` and `deployment/**` → **W12-02**, as one tree
with no exception this wave.

**Frontend.** `frontend/e2e/**`, `frontend/README.md`, `packages/shell/**`, `packages/kernel/**`,
`tsconfig.json`, `package.json` and `playwright.config.ts` → **W12-04**. `packages/api`,
`packages/feature-*` and `frontend/scripts/**` are **owned by nobody** — the scripts all read their
rosters from the filesystem and need no edit for a twelfth package.

**Documents.** `README.md`, `ARCHITECTURE.md`, `docs/overview/**`, `docs/FEATURE_MATRIX.md`,
`TECH_DEBT.md`, `DECISIONS.md`, `docs/adr/**`, `scripts/feature-matrix-check.sh` and
`frontend/packages/api/README.md` → **W12-01**. `docs/operations/**` is **owned by nobody and is
correct** — W11-05 repaired both pages and `MaskingConfigSuite` now compares three of `masking.md`'s
claims against the loader.
`docs/plan/verification/W12-<packet>.md` → **each packet's own**, and no packet's `Owns` contains the
directory. `docs/plan/ROADMAP.md` is the integrator's. **`docs/plan/WAVE-12.md` is this file, and the
wave's closing act deletes it.**

**Nesting checks, done rather than assumed.** `services/` is not owned as a tree. `libs/` is not owned
as a tree. `docs/` is not owned as a tree. `deployment/` is owned as a tree by W12-02 with **no**
exception this wave — the quickstart YAML returns to it, because `diskUsedWarningPercent` is now
asserted by a Scala suite and is no longer a browser decision. `frontend/packages/` is owned as two
named subtrees, not as a tree. `services/gateway/api/` is owned as three named paths, not as a tree.

---

## Files owned by NOBODY

**Unowned and correct as they stand:** `scripts/run-tests.sh`; `frontend/scripts/*.mjs`;
`frontend/packages/api/src/**` except its README; fifty-two of fifty-six ADRs; `docs/api/**`;
`docs/domain/**`; `research/**`; `docs/operations/**`; `libs/config/**`, `libs/observability/**` and
`libs/security-core/**` (all three repaired in wave 11 and each now compared against a document);
`services/gateway`'s `routing/**` and `application/capability/**` (swept by W10-A1, 13 rules closed).

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave.**
Each has a `TECH_DEBT.md` row or gets one through W12-01: `services/metrics`' five unclosed payload
rows under TD-024; `frontend/packages/shell/src/overview/LatencyCard.tsx` and `ThroughputCard.tsx`,
whose hidden-table `ABSENT` em-dash decision is now **written out in prose and deliberately not
changed** — W10-01's original `aria-hidden` prescription would have deleted the only accessible
rendering an unmeasured bucket has, and W11-04 was right to refuse it, but nothing gates the refusal.

**Unowned and deliberately left alone:** `stash@{0}` — six waves old, 46 files, unpoppable without
conflicts, **no packet may drop it**; and `docs/ROADMAP.md` / `docs/ROADMAP-SOLID.md`.

---

## For the integrator, before wave 12 starts

1. **Nothing in wave 11 is committed.** `git status --porcelain` is **60 entries** — 47 modified and
   13 untracked — against `fcd184fe`, and that includes three new `build-tests` sources, two new Scala
   test suites, `frontend/e2e/statTiles.ts` and seven verification reports. **The wave is green and
   unrecorded. Commit it before anything in wave 12 moves.** Every gate in the tree table above was
   run against exactly this working tree at this close.
2. **`docs/plan/verification/` is kept, and the citation reason is unchanged.** It holds
   twenty-four files. The seven `W11-*` are wave 12's input. `W8-04.md` and `W8-07.md` are cited
   **from `docs/FEATURE_MATRIX.md`**, and `W9-03.md` from `frontend/e2e/topics.spec.ts` and
   `brokers.spec.ts`, so deleting them creates dangling references. **W12-01 either moves those three
   citations or says the files stay** — and note that W12-01's own quotation gate is the thing that
   would notice a dangling one, which makes this the first wave where that decision has a mechanism
   behind it. The six discharged `W8-02`/`W9-0*`/`W9-A2` files are cited by nothing and may go.
3. **Check `ss -ltnp` for `:6017`, `:6018`, `:6099` and `:8099`** before you record any figure, and
   `docker ps` for a leftover quickstart holding 8080/8090/9092/8083. One eleven-container stack three
   hours old was found at the wave-11 integration and would have made the last two gates either fail
   on a port bind or — worse — **pass against a stale image**.
4. **Do not trust a cached frontend image layer.** At the wave-11 integration every layer of the
   frontend build reported `CACHED` against an image stamped two hours before some frontend sources
   were last touched. It was honest — the integrator exported the image's bundle and diffed its
   content-hashed asset filenames against a `dist` built from the tree, and they matched, because the
   adversaries' mutate-then-restore-from-bytes cycle changes mtime and not content. **Do the same
   check rather than taking either answer on trust.**
5. **`docs/plan/WAVE-11.md` is deleted in the same commit that creates this file**, per
   `docs/plan/README.md`.

---

## What wave 13 will be, and whether there is one

**There should not be, and this is the fourth time that has been written. Here is why this one is
different, stated so that the next reader can check it rather than believe it.**

Wave 9 argued *"M10 has four things left and each is a command"* — right about all four, and it
missed that two definition-of-done items were not gates at all. Wave 10 argued *"one claim kind and
one per-tile assertion turn both into gates"* — the assertion landed, the claim kind did not, and
**nobody would have known**, because the packet, its verifier and the integration all reported on the
gate's *existence*. Wave 11 argued *"the reproduction at the top of this file is the acceptance
test"* — and **it was, and it went red, and house rule 23 is why that was checked.**

So wave 12's argument is narrower again, and it is the first one that names its own failure mode in
advance: **item 4 is open on one mechanism and seven substitutions.** The mechanism is a quotation
check that is a `grep`, not a parser and not a heuristic — a string attributed to a file either
occurs in that file or it does not, and that is decidable. The seven substitutions are listed in
*"Item 4, the eight things it is open on"* with the command that finds each. **Nothing in this wave
needs a new idea.**

**What would make a wave 13 necessary, in order of likelihood:**

1. **The positive-voice prose rule has a false-positive rate that makes the README unwritable.** This
   is the likeliest failure by a wide margin, and W12-01 is told to decide it in the open. Refusing
   *"an identity beside a state word"* will refuse honest sentences — *"the Connect screen is the
   placeholder for a worker that is not configured"* is true prose about a configured state. **The
   escape hatch is a smaller checked region**, and it is a document change rather than a script
   change. If W12-01 shrinks the block to the three lists and moves the prose out, item 4 can still
   close, and the honest thing is to say the gate reads *lists* and not *prose* rather than to ship a
   rule that fights the next writer.
2. **The quotation gate catches more than four quotations.** This is the good failure. If a `grep`
   over every checked region finds twenty broken attributions rather than the three measured here,
   that is twenty documents wrong and one wave will not repair them. Measure the count **first**,
   before the repairs, and if it is large, W12-01 reports the census and the wave plans around it.
3. **`StatRowProps.range`, `ClusterSelector` or the address sweep turns out to be a product decision
   rather than a cleanup.** Three of W12-04's five items are *"decide it"*, and a decision that needs
   a design answer is not a repair-wave item. Say so rather than deciding it quietly.
4. **W12-A1 finds something structural in `alerts` or `consumer`.** Four services in its `Owns` have
   never been mutated. Wave 10's hunter found 83% ungated in unswept code; wave 11's found **26%** in
   code beside it, which is the first time that number has gone **down** and is the best single piece
   of evidence in this plan that the sweeps are working. If the rate returns to 80% in the four
   unswept services, a finding that needs a new refusal at the edge is a wave-13 item.

**And if none of those fires, the closing act is this.** The integrator runs **the five items**, not
the gate list: opens the product at the address it lands on, reads the first paragraph of `README.md`
against `ls services/`, reads `docs/overview/README.md`'s gate table against the commands it names,
**appends one false sentence to a checked region and requires the script to refuse it — in both
moods**, greps for a quotation attributed to a file that does not contain it and requires zero hits,
writes the wave-12 retrospective into `ROADMAP.md`, folds whatever is still true out of
`docs/plan/verification/` into it, and deletes `docs/plan/WAVE-12.md`. `docs/plan/` is then
`README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and the plan is finished.

**The ratio, for the record.** Wave 10 ran six and two at 3:1 and closed four of five items. Wave 11
ran five and two at 2.5:1, closed its own load-bearing prediction for the first time in this plan's
history, and left item 4 open on a class nobody had named. Wave 12 is **four and two at 2:1** — the
smallest wave here has ever been, because what is left is one `grep`, seven sentences, one `sed`
anchor, eight one-line auth mutations and five decisions somebody has to make out loud.
