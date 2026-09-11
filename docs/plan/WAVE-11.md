# Wave 11 — item 4, and the gate that still does not read a sentence

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 11, when wave 10 was written to be the last one — and wave 9 before it.**
Wave 10 did most of what it said it would. `/ui/` is honest, `formatBytes` is repaired, `README.md`
is accurate for the first time in four milestones, the tenth service's documents exist, `smoke.sh`
asks nine services for a signed read, and the browser suite is 106 green. Every one of fifteen gates
is green, re-run at this close one at a time against images built from this tree.

**The definition of done is still not met, and it is not met on item 4 — the same item wave 10 was
written to close.** It is not met for the same reason it was not met in wave 9: the gate compares
figures and lists, and the false sentences are prose.

Measured at this close, not read off a report:

```
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: 404 claims checked, all true.

$ grep -n '404 claims\|390 claims' docs/overview/README.md
172:| `./scripts/feature-matrix-check.sh` | … | **390 claims over nine sections** |
```

A false figure about this repository's own flagship gate, in the row of the newcomer's overview that
describes that gate, written by the wave that owned it. And:

```
$ grep -c 'checked:' docs/overview/README.md
0
```

The document definition-of-done item 4 names by function — *"an overview a newcomer can read"* —
sits entirely outside every `<!-- checked: -->` region, so **four** of its ten gate-table rows are
stale against the tree they describe: `run-tests.sh` 4,310 against a measured **4,350**; `pnpm test`
1,908 against **1,933**; `pnpm e2e` 105 against **106**; and the 390 above.

**And the mechanism wave 10 built to stop this does not do it.** W10-03's item 7 was *"a claim kind
that compares a sentence against a fact"*, and `ROADMAP.md` says in its own words that if it lands,
*"the two open items become gates and stay closed."* It did not land. Reproduced at this close, by
the closer, on the shipped script — one sentence appended **inside** the new
`<!-- checked: capability-claims -->` block in `README.md`, no script edit, nothing else changed:

```
Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen.
```

```
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: 404 claims checked, all true.
$ echo $?
0
```

That is **the exact sentence** that kept item 4 open for four milestones, sitting inside the region
built to refuse it, with every section count unchanged. `check_capability_region` reads backticked
tokens that follow one of three literal `**<label>:**` markers; `report_unclaimed_figures` refuses
digits and bold number words. Free prose in the region is struck out by nothing and compared by
nothing. `README.md` was restored byte-identical afterwards (`md5sum` 2c5835bfadcb59d02970c6817e92dd1d).

**So this is the fifth wave running in which the packet that hardens this script published a price
for its own gate that measurement does not support.** House rule 17 exists for exactly that, and has
now been broken by the packet that cited it in wave 7, wave 8, wave 9 and wave 10.

**Wave 11 is five building packets and two adversaries. It is a repair wave, smaller than wave 10.**
No new service, no new endpoint, no new ADR, no new feature package, no new stylesheet, and no new
screen. If a packet finds itself writing a capability, it has misread its brief.

---

## The definition of done, judged item by item at the wave-10 close

Every figure below was printed by the command beside it, on 2026-09-12, by the closer, against the
quickstart brought up from this tree (`kui-quickstart-kui` `sha256:85310603f50c`,
`kui-quickstart-frontend` `sha256:a41cedf83308`).

| # | Item | Verdict | Command, and what it printed |
| --- | --- | --- | --- |
| 1 | Every screen renders real data | **MET**, with one defect named below | `pnpm -C frontend e2e` → **106 passed, 3 skipped, 0 failed**; 23 of 23 captures have a green case; `/ui/` and `/dashboard/overview` probed in a browser |
| 2 | Every backend capability exists as a service or endpoint | **MET** | `ls services/` → 11; `./mill __.openApiCheck` → 2544/2544; `./mill checkArchitecture` → 195 modules, 10 rules; the capability roster compared against disk and `ServiceContracts.byService` |
| 3 | Unit and component tests, a11y in both themes, a browser suite | **MET** | `./scripts/run-tests.sh` → **81 modules, 4,350 cases**; `pnpm test` → **83 files, 1,933**; a11y → **790 stories × 2 themes, no violations**; `pnpm e2e` → 106/3/0 |
| 4 | Documented: README, ARCHITECTURE, ADRs, an accurate FEATURE_MATRIX, an overview | **NOT MET** | the two greps above, plus six more below |
| 5 | One command brings the whole product up under `docker compose` | **MET** | `./deployment/quickstart/quickstart.sh` → exit 0, eight containers healthy, *"KUI is running: http://localhost:8090/ui/"*, driven in a browser |

### Item 1, measured rather than assumed

`/ui/` — the address that was wave 10's headline — is repaired. Probed in a browser after
`networkidle` + 10s: six stat tiles, **each carrying a sentence** (*"No cluster is selected, so KUI
has not asked how many brokers are online."*), an empty state that names the choice (*"Choose one
from the environment rail on the left … Nothing has been asked until then."*), a voice line that
claims nothing, three API requests, zero page errors. `formatBytes` holds: on
`/clusters/quickstart/dashboard/overview` the CONSUME card reads `199.7 B/s`, and a sweep of the
page found **0** figures with three or more decimals across **1,159** `<td>` cells.

**The screen-to-spec-to-case count, re-taken against `docs/plan/verification/W8-07.md` §1.**
Wave 8 published 21 covered / 1 partial / 1 uncovered. Today it is **23 of 23 covered**, and each
covering case ran green in the run above: `M08`'s cluster-switch toast is `shell.spec.ts:389`,
`M09`'s second cluster is `brokers.spec.ts:371`, `M14`'s plural bulk receipt is
`topics.spec.ts:473`, and `M20` is `ksql.spec.ts`'s three passing cases. **One screen is covered by
half a case**: `M06`'s acknowledgement path, `shell.spec.ts:181`, still **skips**, because
`deployment/quickstart/kui-quickstart.yaml:117` still ships `diskUsedWarningPercent: 80`. W10-02's
brief said *"Kill the third skip"* and its acceptance line said *"skips 3 → 2"*; its own verifier
recorded the packet `confirmed: false` on that ground and the packet reported `done: true` anyway.

**The defect on the repaired screen, measured here, no mutation applied.** `/ui/` with no cluster
draws an **enabled** primary *"Create topic"* button:

```
count: 1   enabled: true
URL after click: http://localhost:8090/ui/
dialogs: 0
```

It navigates nowhere and opens nothing. `App.tsx:581` states the rule it breaks in its own words —
*"a button that navigates nowhere is not [honest]"*. It is not a fabricated **figure**, so item 1 is
judged met; it is a screen claiming an action it cannot perform, nothing gates it, and it is
**W11-04**'s.

### Item 4, the six things it is open on

1. `docs/overview/README.md:172` publishes **390 claims** against a gate that prints **404**, and
   four of its ten gate-table rows are stale. `grep -c 'checked:'` on that file is **0**.
2. **The prose gate does not read prose** — reproduced above, exit 0, over the original defect
   sentence, inside the region built to refuse it.
3. `docs/FEATURE_MATRIX.md:713-722` still publishes *"Repairing them is wave 10's, and this
   paragraph is where the caveat lives until they are."* Two of the three were repaired in wave 10.
   `:484` still says `search.spec.ts` *"drives five cases"*; it drives **seven**.
4. `docs/operations/masking.md:47-49` publishes, as one of *"three things [that] hold for all of
   them"*, that **"A masked value is never longer than the value it replaced"** — and gives it a
   security rationale an operator is invited to rely on. It is false for `kind: replace`:
   `MaskingEngine.scala:202` is `case MaskingKind.Replace(replacement) => Some(Json.fromString(replacement))`,
   with no length bound, so `replacement: "<redacted by policy>"` over a four-character field
   returns a longer value.
5. `deployment/quickstart/kui-quickstart-auth.yaml:3-6` says it is `./kui-quickstart.yaml` *"with
   two blocks filled in … and nothing else changed"*. `grep -c masking` is **0** on it and **6** on
   the file it claims to copy — and the `--with-auth` deployment, the one with a viewer role, is the
   one with no masking.
6. `TECH_DEBT.md:71` marks **TD-037 `open`** and asserts that `smoke.sh` passes over a stack whose
   topics product answers 401. W10-05 closed that hole and never moved the row. Separately, the ids
   have collided: `TD-035` and `TD-036` are taken, the `unreadable` bound W10-06 drafted as TD-035 is
   **already filed as TD-040**, and the next free id is **TD-043**.

### One clause that is now satisfiable, and was measured at this close

`docs/FEATURE_MATRIX.md`'s `DM-001` has stayed `IMPLEMENTING` on one clause: *"no person has watched
a field come back masked in a browser against a running stack."* Done here, on the quickstart, by
opening `/ui/clusters/quickstart/topics/customers.profiles/messages` and pressing **Read**:

```
{"customerId":"CUST-8812","name":"<redacted>","email":"***********************.com",
 "tier":"gold","country":"PL","createdAt":"2025-09-11T22:50:12Z","marketingOptIn":true}
```

`Marta Zielinska` and `marta.zielinska@example.com` — the seed record's real values, in
`deployment/quickstart/seed/data/customers.profiles` — are **absent from the page**, the `mask` rule
preserved the email's length and kept its four-character suffix, and the `replace` rule named itself
rather than disguising itself. The row can move; moving it is **W11-01**'s, and the paragraph above
is the measurement it needs.

---

## The tree you start from

Every figure printed by the command beside it, at the wave-10 close, one gate at a time.

| Gate | Figure |
| --- | --- |
| `./mill __.compile` | 8251/8251 |
| `./scripts/run-tests.sh` | **4,350 cases over 81 modules**, all 81 carrying tests (wave 9: 4,310) |
| `pnpm -C frontend test` | **1,933 over 83 files** (1,908 over 82) |
| `pnpm -C frontend e2e` | **106 passed, 3 skipped, 0 failed** over 109 (105/3 over 108) |
| `./scripts/feature-matrix-check.sh` | **404 claims over nine sections**, all true (348 over eight) |
| `./mill __.checkFormat` | 495/495 |
| `./mill __.fix --check` | 10672/10672 |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2544/2544 |
| `pnpm -C frontend typecheck` | exit 0 — **over a tree that excludes `frontend/e2e/`** |
| `pnpm -C frontend lint:boundaries` | 425 files in 11 packages |
| `pnpm -C frontend a11y` | **790 stories × 2 themes, no violations** |
| `./deployment/quickstart/quickstart.sh` | eight containers, healthy, two registered clusters |

**Anything red is yours. Four things are not green, not flaky and not secret:**

1. **The prose gate, reproduced above.** One sentence, no script edit, exit 0.
2. **`docs/overview/README.md` publishes 390 against a gate that prints 404.** One grep.
3. **`pnpm -C frontend typecheck` does not read `frontend/e2e/`.** `grep -n e2e frontend/tsconfig.json`
   returns nothing; `wc -l frontend/e2e/*.ts` is **4,961**. `npx tsc --noEmit -p e2e/tsconfig.json`
   exits 0 and is run by nothing in this repository.
4. **`build.mill:879` and `build.mill:4192` still call `runMain`.** W10-05 wrote `runDocumentMain`
   so a dead forked JVM says what happened, and applied it to the ten `openApiCheck` targets — but
   `frontend.apiConstants`, which **`.github/workflows/ci.yml:91` runs on every push**, was left on
   the old call and still fails as the bare `Subprocess failed` with no stderr. Two call sites, one
   word each.

---

## House rules

The first twenty-two are waves 8, 9 and 10's and still apply in full; read them in the git history of
`docs/plan/WAVE-10.md`. Two are amended and two are new, and each is a wave-10 finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not
otherwise changing**; test sources are inside `checkFormat` and `fix --check` since wave 9.

4. **A gate you cannot make fail is not a gate.** Unchanged, and it produced the reproduction at the
   top of this file.
5. **Report a mutation that stayed green.** Unchanged, and see the retrospective: eight waves, no
   movement.
17. **AMENDED — a claim about your own gate is measured by somebody who is trying to break it, and
    the measurement is in the packet result before the packet reports.** Wave 10's W10-03 published
    a two-line price for its capability gate; its verifier found a **one-line** attack and a
    **zero-line** one. That is five consecutive waves. From here: a packet that hardens
    `scripts/feature-matrix-check.sh` does not report `done` until it has itself run the cheapest
    attack it can construct on the finished gate and written down what that attack cost.
18. **AMENDED — `docs/plan/verification/<packet>.md` belongs to the packet that the file is about,
    always, and is never inside any other packet's `Owns`.** Wave 9 lost one report to this; wave 10
    lost **three** — W10-01, W10-03 and W10-06 each reasoned the directory was the closer's and each
    left its rows in a packet result, and each verifier then wrote the file itself. Five of eight
    files this wave were written by somebody other than the packet. The directory is nobody's tree;
    each file is its own packet's.
21. **Unchanged, and it worked.** The closer converted **60%** of filed rows against wave 9's 27.5%,
    and **94%** of rows whose case was inside its ownership against 100%. Widening the partition to
    the test trees moved the first number and not the second. Keep it.
23. **NEW: a wave plan's "if this lands, the item becomes a gate" is a prediction, and the closer
    tests it.** `ROADMAP.md` said wave 10's two load-bearing items would turn items 1 and 4 into
    gates. One did — the per-tile assertion is real and `W10-A2` widened it to read the row off the
    DOM. The other did not, and nobody would have known, because the packet, its verifier and the
    integration all reported on the gate's **existence**. From here the closer reproduces the
    prediction, in the shape the plan wrote it, before the wave is recorded.
24. **NEW: an acceptance line that is not met is not `done: true`.** W10-02's acceptance said
    *"skips 3 → 2"*; it measured 3, filed it honestly under `needsOutsideOwnership`, and reported
    `done: true`. Honesty in the report is not a substitute for the line. A packet whose acceptance
    is unmet reports `done: false` with the reason, and the wave plan decides.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`.
Use `./mill --no-daemon` for anything you record a number from. **Running the a11y sweep is three
commands** — build Storybook, serve `storybook-static` on `:6017`, then sweep — and it took 18
minutes at this close under a load average of 8.4 over 16 cores. `npx http-server` leaves **two**
processes; kill the listener `ss -ltnp` names.

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **404 claims over nine sections**: self-check 7, rows 30, merged-document 50, milestones 49, adr-index 112, openapi-totals 15, guard-fixtures 18, capability-claims 35, dependencies 88 over 12 manifests | **W11-01**, which must raise it and not lower it |
| `docs/FEATURE_MATRIX.md` | 189 rows, 70 COMPLETE, 178 in scope, 39% delivered, all inside checked regions | **W11-01** alone |
| `frontend/e2e/**` | **106 passed, 3 skipped, 0 failed.** Two skips are deployment-shaped and correct (`alerts.spec.ts:268`, `ksql.spec.ts:175`); the third, `shell.spec.ts:181`, is **W11-02**'s to kill | **W11-02**; no packet may add a fourth |
| `frontend/packages/kernel/src/components/record.ts` | `formatBytes`, 14 call sites across four packages; its header currently promises an invariant it does not hold | **W11-04** |
| `libs/config/test/.../ShippedConfigurationSuite.scala` | 16 cases, including one asserting that **every shipped file other than `kui-quickstart.yaml` has no masking rule** — so W11-03's repair to the auth file moves this suite too | **W11-03** |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 over every tree including tests | every packet |
| `deployment/compose/smoke.sh` | Nine routed services, each asked for one signed read and required to answer 200 — except the message stream, whose refusal **is** a 200 | **W11-03** |
| `build.mill` | 8251 compile tasks, 2544 openApiCheck, 233 checkArchitecture; `runDocumentMain` at 134-166 is the only thing standing between a dead JVM and a silent gate, and nothing tests it | **W11-03** |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W11-01 — The overview, and a gate that reads a sentence

**Owns**
```
scripts/feature-matrix-check.sh      (except section 6, guard-fixtures, which is W11-A2's on freeze)
docs/overview/**
README.md
ARCHITECTURE.md
docs/FEATURE_MATRIX.md
TECH_DEBT.md
DECISIONS.md
docs/adr/**
frontend/packages/api/README.md
```

**Contract.** Definition-of-done item 4. This is the only packet in the wave that can close it, and
it is the fifth consecutive wave to be given this script. Read the reproduction at the top of this
file before you write a line.

**Do**

1. **`docs/overview/README.md:172`: 390 → 404.** Then re-measure every other figure in that table
   against the tree, not against wave 9's close. Four are stale today: `run-tests.sh` 4,310 → 4,350;
   `pnpm test` 1,908 → 1,933; `pnpm e2e` 105 → 106; the 390. Do not adjust; run the command.
2. **Wrap §5's gate table in `<!-- checked: gate-table -->`** with a claim kind comparing, at
   minimum, the three figures the script derives without another build: its own claim total
   (`count_of` over the whole ledger), `docs/api/openapi.json`'s path/operation/schema triple, and
   the `DECISIONS.md` row count. The five figures that need another process (4,350; 1,933; 495;
   10,672; 8,251) may stay a **dated** snapshot — the figure the script itself produces has no
   excuse for being unread by the script itself. `docs/overview/README.md` holds no marker of any
   kind today; that is why the 390 survived a wave whose subject it was.
3. **The `capability-prose` claim, and it is the load-bearing item of this wave.** Build a
   service-identity alias map out of the block's own three lists (`connect` → *"Kafka Connect"*,
   `ksql` → *"ksqlDB"*, …) held beside `service_labels`, so that a twelfth service forces a new
   alias rather than silently escaping. Then refuse any **residue** sentence — the block's text
   after the three labelled lists are consumed — that names an identity together with a negation
   token (`no`, `not built`, `neither`, `nor`, `without`). Drive it from a fixture in **both**
   directions in section 6's shape.
   **The acceptance is the reproduction at the top of this file going red**, with the count raised
   and every other section unchanged.
4. **Close W10-03's F-1, which is a one-line attack on the gate wave 10 just shipped.** Insert as
   the second line of `service_state_fact` (line 2404):
   `if [[ -n ${declared_service_state[$name]+x} ]]; then printf '%s' "${declared_service_state[$name]}"; return; fi`
   — then move `connect` and `ksql` into **Not built:** in `README.md`. Measured by W10-03's
   verifier: `404 claims checked, all true`, exit 0, every section count unchanged. The cause is
   that `verify_service_fact_independence` runs at line **2592**, *before* `check_marked_file`
   populates the roster at **2595**, so it proves independence only in the state where there is
   nothing to depend on. The closing fixture runs **after** `audit_service_states`, seeds
   `declared_service_state[fixture-routed]='Built and routed'` and asserts the fact still answers
   `Not built but routed`; kind `service-fact-roster-independence`, into `registry[capability-claims]`.
5. **`docs/FEATURE_MATRIX.md`.** `:713-722` — the wave-10 caveat is two-thirds discharged; W10-02
   repaired the `brokers.spec.ts` second-cluster case and the `topics.spec.ts` bulk-bar count, and
   annotated the `search.spec.ts` arm. `:484` — `search.spec.ts` drives **seven** cases, not five,
   and the annotation at `:150` is invisible under the `list` reporter every non-CI run selects.
   And `DM-001`: the last clause is **discharged at the wave-10 close** and the measurement is in
   this file, above, with the record verbatim. Move the row or write down why not.
6. **`TECH_DEBT.md`.** Close **TD-037** — W10-05 landed the signed-read loop and proved it red in
   both directions. Fix the id collision: `TD-035`/`TD-036` are taken, the `unreadable` bound is
   already **TD-040** and should be amended rather than duplicated, and the next free id is
   **TD-043**. File the wave-10 hand-backs that nobody owns: `build.mill`'s two `runMain` sites,
   `frontend/e2e/**` outside the type gate, the duplicate `formatBytes`, and the `masking.md` /
   `configuration.md` pages that no suite reads.
7. **House rule 17, in its amended form.** Before you report, construct the cheapest attack you can
   on your own finished prose gate, run it, and write down what it cost. Four packets have now
   published a price for this script that a verifier halved or quartered within the hour.

**Acceptance**
```
./scripts/feature-matrix-check.sh                    # 404 -> 404+, no section below its floor
grep -c 'checked:' docs/overview/README.md           # 0 -> at least 1
./mill --no-daemon tools.errorCodes.test
./mill --no-daemon frontend.apiConstants --check
```
**Mutation line:** the sentence at the top of this file, appended inside the `capability-claims`
block, with the script exiting non-zero and naming it. **And a green one:** report one false
sentence you can still write inside a checked region.

---

## W11-02 — The third skip, the types, and the arm nothing drives

**Owns**
```
frontend/e2e/**
frontend/tsconfig.json
frontend/package.json
frontend/playwright.config.ts
deployment/quickstart/kui-quickstart.yaml
```

**Contract.** Definition-of-done item 3 asks for *"a browser suite driving the deployed product"*.
It does — 106 green. What it does not do is read its own types, and one of its cases has skipped for
three waves over one line of YAML.

**Do**

1. **Kill the third skip.** `shell.spec.ts:181` — *acknowledging an open event moves the bell* —
   skips because `kui-quickstart.yaml:117` ships `diskUsedWarningPercent: 80` and the feed holds no
   open event. This is `M06`'s acknowledgement path and no browser drives it. W10-02's brief asked
   for this and W10-02 did not do it. **Decide once, in the open**, and say in the file why: either
   the quickstart ships a threshold that seeds one warning permanently, or the case seeds and
   reverts. W10-02's verifier could not reproduce the packet's seeded-stack measurement
   (`KUI_ALERTS_THRESHOLDS_DISKUSEDWARNINGPERCENT=1` opening exactly one event, once per gateway
   process) — so measure it yourself rather than inheriting it.
2. **Put the browser suite inside the type gate.** `pnpm -C frontend typecheck` exits 0 over a tree
   that does not contain `frontend/e2e/` — **4,961 lines**. `frontend/tsconfig.json` references ten
   packages and not `./e2e`; `e2e/tsconfig.json` sets `composite: false`, so it cannot be referenced
   as it stands; `npx tsc --noEmit -p e2e/tsconfig.json` exits 0 and is invoked by nothing in the
   repository. Either `composite: true` plus a reference from the solution file, or a
   `typecheck:e2e` script wired into the frontend CI job beside the roster step. Prove it by
   appending `const x: number = "not a number";` to a spec and watching `pnpm typecheck` go red.
3. **The search arm every deployment takes, in a browser.** `search.ts:181` is
   `searchHitCount(state.answer) === 0 && state.answer.partial.length === 0 ? "empty" : "ready"`.
   Dropping the left conjunct leaves the whole browser suite green (measured by W10-02's verifier:
   106 passed with 23 substitutions served), because `search.spec.ts:196` picks a query that matches
   nothing **and** forces `partial: []`, so both conjuncts agree. W10-A2 closed this in the unit
   tree; the browser arm — everybody answered, something matched — is driven by nothing. Add the
   case over a matching query with `partial` rewritten to `[]`.
4. **Prove the zero-selection bulk bar red.** W10-A2 landed
   `await expect(page.getByTestId("topic-bulk-bar")).toHaveCount(0)` at `topics.spec.ts:396` and
   said plainly that it never built a patched bundle to watch it fail. Build one, or say why the
   unit case at `surfaces.test.tsx:991` is sufficient and delete the browser assertion rather than
   leave one nobody has seen bite.
5. **`search.spec.ts:150`'s annotation reaches nobody.** `test.info().annotations.push` is invisible
   under the `list` reporter that `playwright.config.ts` selects for every non-CI run — including
   the acceptance command that was supposed to display it. Either make it visible or make it a
   comment and stop claiming it informs a reader.

**Acceptance**
```
pnpm -C frontend e2e                # 106 passed, 3 skipped -> 107+, 2 skipped
pnpm -C frontend typecheck          # now reads frontend/e2e/**
pnpm -C frontend test
```
**Mutation line:** re-apply the `search.ts` left-conjunct deletion in the served bundle and show your
new case red. **And a green one:** report one change to a spec file the type gate still does not see.

---

## W11-03 — The gates that cannot say what happened

**Owns**
```
build.mill
build-tests/**
.github/workflows/ci.yml
deployment/compose/smoke.sh
deployment/compose/docker-compose.yml
deployment/frontend/Dockerfile
deployment/quickstart/README.md
deployment/quickstart/kui-quickstart-auth.yaml
```

**Contract.** Four gates in this repository are green in a state their author did not intend, and
each was measured under mutation by a wave-10 verifier. None is a test file; that is why wave 10's
closer could not touch any of them.

**Do**

1. **The two surviving `runMain` sites.** `build.mill:879` (`frontend.apiConstants`) and
   `build.mill:4192` (`docs.errorCodes`). `.github/workflows/ci.yml:91` runs
   `./mill frontend.apiConstants --check` **on every push**, so the exact failure W10-05 was asked
   to eliminate — `Subprocess failed`, no exit code, no stderr, a different module each time, and
   two waves at an hour each — survives on a gate CI runs constantly. `tools.errorCodes` already
   extends `KuiJvmModule`, so the seam exists. Two call sites, one word each.
2. **`runDocumentMain` itself is untested, and one word disarms it.** `build.mill:134-166`:
   `Task.fail(s"$mainClass exited ${ran.exitCode}:\n$said")` → `Task.log.info(...)` and
   `./mill services.topic.api.openApiCheck` answers **629/629 SUCCESS over a genuinely stale
   committed document** (measured by W10-05's verifier, with one path deleted from
   `services/topic/api/openapi.json`). The whole power of ten `openApiCheck` targets routes through
   33 lines in the one file outside `__.checkFormat`, outside `__.fix` and outside every test
   module. Extract the `(exitCode, out, err) -> Either[String, Unit]` decision into a testable
   object and case its three branches in `build-tests`: exit 0 → `Right`; non-zero with text →
   `Left` containing the text; non-zero with silence → `Left` containing *"printed nothing on stdout
   or stderr"*.
3. **`smoke.sh`'s stream read cannot fail.** The `STREAM_READS` arm (~line 913) asserts
   `%{http_code}` only, and an SSE refusal **is** an HTTP 200 with the refusal in the body. Measured
   with a wrong `KUI_PRINCIPAL_KEY` on `kui-message`: `event: error` /
   `data: {"code":"KUI-UPSTREAM-UNAVAILABLE",…}` with the script printing
   *"message answers …/messages/stream?limit=1: 200"* and **PASSED**. The one service whose read had
   to be hand-written is the one the new block cannot fail on. Read the first frame
   (`curl -sN -m 20 "$base$path" | head -c 200`) and fail on `^event: error`, or await
   `event: phase`. Prove it in both directions, which is the whole lesson of TD-037.
4. **`ci.yml:170-208` compares two counts and calls it a roster check.** Two independent mutations
   stay green, both measured: delete the `feature-ksql` COPY and duplicate the kernel line
   (*"11 packages, 11 manifests copied"*, exit 0); or keep the line and misspell only its
   destination (`./packages/feature-kqsl/`, exit 0). The sorted-name diff that would name the
   missing package sits **inside the failure branch** and never runs on an equal count. Make the
   name comparison the exit-status test and keep the count only as the message.
5. **`kui-quickstart-auth.yaml`'s header is false, and the file it describes is the one that
   matters.** `:3-6` claims it is `./kui-quickstart.yaml` *"with two blocks filled in … and nothing
   else changed"*; `grep -c masking` is 0 against 6. `--with-auth` is the deployment **with a viewer
   role** and it is the one with no masking. Note before you start: `ShippedConfigurationSuite`'s
   new case asserts that every shipped file other than `kui-quickstart.yaml` has **no** masking
   rule, so adding the block turns that suite red — which is correct behaviour for a guard and means
   two files move together. `libs/config/test/**` is W11-A2's on freeze; land the seam, say when.
6. **`deployment/quickstart/README.md` publishes a figure that does not reproduce.** It records
   `order-fulfilment … totalLag 10`; W10-05's verifier measured **9** on a cold stack and **11** on
   an older one. Wave 10 replaced two wrong numbers with a third that varies across boots. Either
   publish a bound, or publish what the figure depends on, or drop it. Every other figure in that
   file was checked and is correct.
7. **`globalSetup.ts:77-81`'s measured table names `/health/ready`**, which answers 404 on the
   quickstart; the gateway's path is `/api/v1/health/ready`. The substance reproduces; the path a
   reader copies does not. (`frontend/e2e/**` is W11-02's — file this through it.)

**Acceptance**
```
./mill --no-daemon __.openApiCheck
./mill --no-daemon frontend.apiConstants --check
./mill --no-daemon build-tests.test          # or wherever the extracted decision lands
./deployment/compose/smoke.sh
./scripts/run-tests.sh
```
**Mutation line:** wrong `KUI_PRINCIPAL_KEY` on `kui-message`, `smoke.sh` red. **And a green one:**
report one edit to `ci.yml` or `build.mill` that no gate notices.

---

## W11-04 — The inert action, and two headers that promise what they do not hold

**Owns**
```
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/App.tsx
frontend/packages/kernel/src/components/record.ts
frontend/packages/kernel/src/components/record.test.ts
frontend/packages/feature-topics/src/TopicListPage.tsx
frontend/packages/feature-topics/src/index.tsx
```

**Contract.** Item 1 is met and this packet must not weaken it. Three things sit beside it, each
measured, none gated.

**Do**

1. **The enabled button that does nothing.** `/ui/` with no cluster draws a primary *"Create topic"*:
   `count 1, enabled true`, click leaves the URL at `/ui/` and opens **0** dialogs. Measured at the
   wave-10 close on the shipped stack with no mutation applied. `App.tsx:581` already documents the
   rule — *"a button that navigates nowhere is not"*. Decide: disable it with a reason, remove it,
   or make it open the cluster chooser. Then **gate it**: a case asserting that every enabled
   primary action on `/ui/` either navigates or opens something.
2. **`record.ts`'s header promises an invariant the function does not hold.** It states *"the printed
   figure is always in `[0, 1000)`"*; `units` stops at `TB`, so `formatBytes(1.5e15)` returns
   `"1500.0 TB"` — a four-digit figure under a unit, which is the exact defect the `999.95`
   threshold was introduced to remove. Either add `PB` (the `feature-topics` copy already has it) or
   correct the sentence. `record.test.ts` pins the behaviour either way since W10-A2.
3. **The duplicate `formatBytes`.** `TopicListPage.tsx:771` exports a second one, **re-exported from
   `feature-topics/src/index.tsx:18`** — so it is public surface, not an internal convenience — with
   a units ladder that runs to `PB` where the kernel's stops at `TB`. Two functions, one name, one
   meaning, and they disagree about more than rounding. Wave 10 reported it and was told not to
   repair it; this packet owns both files and repairs it.
4. **Four hand-written rosters of the same six stat tiles** exist: `Overview.tsx`'s `STAT_ORDER`,
   `overview.render.test.tsx`, `e2e/shell.spec.ts:468` and `e2e/traffic.spec.ts:142`. W10-A2's
   DOM-reading case is the only one of the four that can notice a seventh tile. Reduce the count or
   say in the code why four is right. (The two `e2e` files are W11-02's; file through it.)
5. **Pre-existing, recorded by W10-01's verifier and charged to nobody:** 1,144 bare `<td>—</td>`
   cells under `panel-throughput` and `panel-latency` carry no `aria-hidden` and no visually-hidden
   alternative, unlike `feature-topics`' own `Quantity`, which pairs its dash with *"not known"*.
   Deliberate per `ThroughputCard.tsx:19`; the a11y sweep is green because it reads stories, not this
   table. Decide it in the open rather than inheriting it a sixth time.

**Acceptance**
```
pnpm -C frontend test packages/kernel packages/shell packages/feature-topics
pnpm -C frontend typecheck
pnpm -C frontend e2e e2e/shell.spec.ts
pnpm -C frontend a11y                      # three commands; see the house rules
```
**Mutation line:** re-enable the inert button and show your new case red. **And a green one:** report
one change to `Overview.tsx` the browser suite does not notice.

---

## W11-05 — The masking documents, and the adapter the process actually holds

**Owns**
```
docs/operations/**
services/message/**
libs/security-core/**
libs/config/**
libs/observability/**
```

**Contract.** Wave 10 gave masking a configuration, a shipped rule, an operator page and a metric,
and a person watched a field come back masked in a browser at the wave-10 close. Two things it left
are safety-shaped rather than cosmetic.

**Do**

1. **`docs/operations/masking.md:47-49` states a false invariant and gives it a security rationale.**
   *"A masked value is never longer than the value it replaced"* holds for `mask` only;
   `MaskingEngine.scala:202` and `:229` write a `replace` literal verbatim with no length bound.
   `MaskingRule.scala`'s own kind table scopes it correctly to the Mask row — the prose does not.
   Scope the sentence, and note that an operator reading this page is being invited to rely on it.
2. **The running process can be handed the silent metrics adapter with every suite green.**
   `MessageWiring.scala:129`:
   `MaskingMetrics.otel4s[F](meter)` → `MaskingMetrics.noop[F].pure[F]` →
   `./mill -k services.message.__.test` reports **1442/1442 SUCCESS**. `MaskingMetricsSuite` drives
   the real adapter directly, `ConfiguredRecordMaskingSuite` drives a counting fake, and
   `MessageWiringSuite` is about the signing key and the start-up line. Under that one-line,
   `-Werror`-clean edit, `kui.masking.applied` is never emitted in production and the first writer
   of a metric declared in wave 1 has no writer again. W10-A2 landed a stopgap that reads the
   composition root's **source**; the real fix is the seam W10-04 named — widen `resource` (or a
   helper) to publish the constructed `MaskingMetrics`, the same `private[app]` widening
   `describeMasking` already carries — then assert over `OtelJavaTestkit.inMemory` that the process
   holds something that records. **Land the seam before you freeze** (house rule 22).
3. **`masking.md` and `configuration.md` are read by nothing.** Deleting `masking.md` entirely leaves
   `./scripts/run-tests.sh` green at 4,350. W10-A2 closed this for `observability.md` — a
   `MetricNamesSuite` case comparing both set differences against `Nil`, with the one documented
   exception asserted rather than filtered and a >20-row guard so a reader matching nothing cannot
   pass vacuously. Do the same for one of the other two, or say which fact each page could be
   compared against and file it. `MaskingEngine.scala:13` and `MaskingConfig.scala:231` both assert
   in prose that `masking.md` exists — a claim that can become false silently, which is the exact
   shape of the sentence this packet's predecessor replaced.
4. **`observability.md:76-78` overstates its counterparty by one hop.** It says `MetricNamesSuite`
   pins the list *"against ARCHITECTURE.md §13"*; the suite compares `MetricNames.all` to a
   hand-written copy inside itself and names that file only in a comment. W10-A2's new case now does
   read `observability.md` — say which is which.
5. **`MaskingMetrics.all` omits `AggregationSection`**, declared at `MetricNames.scala:42` and
   emitted at `TopicOverviewUseCase.scala:230`. W10-04 reported it and did not own it; this packet
   does.

**Acceptance**
```
./mill --no-daemon -k libs.securityCore.jvm.test + libs.config.test + libs.observability.test + services.message.__.test
./mill --no-daemon checkArchitecture
./scripts/run-tests.sh
```
**Mutation line:** `MaskingMetrics.otel4s` → `.noop` in `MessageWiring`, and your new case red.
**And a green one:** report one change to an operator page no suite notices.

---

## W11-A1 — The hunter

**Owns**
```
services/gateway/api/src/kui/gateway/api/auth/**      (production and test)
services/gateway/api/test/src/kui/gateway/api/GatewayTestServer.scala
services/identity/**
libs/filter/**
libs/kafka-auth/**
```

**Contract.** Rule 4, applied to code nobody has ever mutated. W10-A1 found **20 genuine ungated
rules in 25 mutations** against `gateway/auth`, `gateway/routing`, `gateway/capability` and
`ksql/infrastructure` — 83% of its non-equivalent mutants, the highest rate in this project's
history, and it closed 19. It then named what it could **not** declare finished, and that is this
packet's brief.

**Do**

1. **`AuthRoutes`' sign-in surface has no behavioural case at all.** A grep for `login` / `oidc` over
   `services/gateway/**/test` finds nothing for `login`, `changePassword`, `oidcStart` or
   `oidcCallback`. W10-A1 closed **one** rule out of that file (ADR-019's session-fixation delete)
   and only by lifting `replaceSession` out as a `private[auth]` function — because every suite in
   the tree builds a gateway with `identity = None`, so `/auth/login` answers `KUI-UNSUPPORTED`
   before `signIn` runs. Still unasserted: the OIDC callback's refusal of `PasswordChangeRequired`,
   the password-change path's deliberate absence of a new cookie, and `withIdentity`'s *"this
   deployment has no identity service"* sentence.
2. **The seam that unblocks it, and it is yours this wave:**
   `GatewayTestServer.scala`'s `private def configView` → `private[api]`, or an identity
   `ServiceClient` stub parameter on `GatewayTestServer.resource`. W10-A1 filed it as needing outside
   ownership; nobody owned it.
3. **`K9` — the OAuth refresh margin — needs one `mvnDeps` line.**
   `KsqlCredentials.scala:270` replaces a token `RefreshMargin` (30s) **before** its stated expiry,
   floored at `MinimumLifetime/2`, so it never expires in flight;
   `now.plusMillis((lifetime - RefreshMargin).max(MinimumLifetime/2))` → `now.plusMillis(lifetime)`
   is green. Closing it needs a virtual clock:
   `mvn"org.typelevel::cats-effect-testkit::${Versions.catsEffect}"` on
   `services.ksql.infrastructure.test`. `build.mill` is **W11-03**'s — declare the edge in your
   first hour, house rule 15.
4. **`G10` — the route-table invariant.** W10-A1 argued down one equivalent mutant with the algebra:
   `SessionMiddleware.scala:217`'s `session.fold(PrincipalKind.Anonymous)(...)` → `PrincipalKind.Bearer`
   is a CSRF fail-open that is unreachable **today**, because no endpoint outside `/api/v1` declares
   a non-safe method. It is equivalent by accident of the route table, not by construction. The case
   that makes it stay equivalent: every non-safe-method endpoint the gateway serves sits inside the
   session-minting set.
5. **Three structural compiler gates are worth extending rather than re-discovering.** `-Werror` with
   `-Wunused:privates`, `-Wunused:explicits` and `-Wunused:imports` each **refused a mutation
   outright** in wave 10 and forced a second, visible edit — 3 of 25 attempts. That converts *delete
   the guard, see if it is green* from one edit into a diff that touches a second place. Record where
   it fires and where it does not.
6. **Report `rulesMutated`, genuine ungated, closed, and arguments for every equivalent mutant.**
   Keep equivalents out of the denominator, as both wave-9 and wave-10 hunters did.

**Acceptance**
```
./mill --no-daemon services.gateway.api.test
./mill --no-daemon services.identity.__.test
./mill --no-daemon '{services.gateway.api,services.gateway.api.test}.checkFormat'
./mill --no-daemon checkArchitecture
```
**File `docs/plan/verification/W11-A1.md` first, as a stub** (house rule 18, as amended — the file is
yours, not the closer's; wave 9 and wave 10 each lost reports to that confusion).

---

## W11-A2 — The closer

**Owns**
```
docs/plan/verification/W11-A2.md
every test tree in the repository, from the moment its production packet freezes:
  **/test/src/**, **/*.test.ts, **/*.test.tsx, frontend/e2e/**,
  and scripts/feature-matrix-check.sh's section 6 (guard-fixtures)
```

**Contract.** Rule 21, which worked and is kept unchanged. Wave 10's closer converted **15 of 25**
filed rows (60%, against wave 9's 27.5%) and **15 of 16** whose closing case was inside its
ownership (94%). Nothing that stayed open needed a test file: everything left needed a shell script,
`build.mill`, a CI step, a `Dockerfile`, a compose file or a `tsconfig`. That is why W11-03 exists —
it is the partition moving a second time, and if wave 11 closes the book, this is the variable that
did it.

**Do**

1. **Close from the filed list. Do not hunt.** Your input is `docs/plan/verification/W10-01.md`
   through `W10-06.md`, `W10-A1.md` and `W10-A2.md`'s nine hand-backs, plus W11-A1's file and every
   row this wave's building packets file as they go.
2. **Every closure is a case that fails under its own mutation, applied by you, reverted from bytes
   you saved yourself** — never `git checkout --`, `git restore` or `git stash`.
3. **Argue down equivalent mutants with the algebra shown**, and keep them out of the denominator.
4. **Report two percentages**: closures over all filed rows, and closures over rows whose case was
   inside your ownership.
5. **One specific carry-over, because it is a measurement nobody has made.**
   `StreamProxy.scala:124-133` and `StreamProxySuite.scala:290-297` assert in a **production
   scaladoc**, as fact, that `relay`'s queue always reassembles before `observe` — *"measured, the
   watch sees whole frames here however finely the source is chopped."* W10-06's verifier measured
   `aTerminalEventSplitAcrossChunkBoundariesIsStillSeen` failing in **2 of 4** runs under one
   mutation and **3 of 4** under another. The unmutated suite is stably green over three consecutive
   runs. Either the determinism holds and the flake has another cause, or a production file asserts
   something that is not true. Measure it.

**Acceptance**
```
./scripts/run-tests.sh
pnpm -C frontend test
pnpm -C frontend e2e
./mill --no-daemon __.checkFormat
```

---

## Where the packets meet

| Edge | What crosses it |
| --- | --- |
| W11-A1 → W11-03 | **`mvn"org.typelevel::cats-effect-testkit"` on `services.ksql.infrastructure.test`**, declared in W11-A1's first hour and landed by W11-03 before any of its own work. House rule 15. Without it `K9` has no clock. |
| W11-03 → W11-05 | **`kui-quickstart-auth.yaml`'s masking block moves `ShippedConfigurationSuite`.** W11-03 lands the YAML, W11-05 owns `libs/config/**` and moves the suite. Say when you freeze. |
| W11-03 → everybody | **The stack, twice**, with both pairs of image ids published, the second taken after the first scrape. Every browser result quotes the second pair. |
| W11-02 ↔ W11-04 | `e2e/shell.spec.ts` and `e2e/traffic.spec.ts` are **W11-02's**; the tile roster they duplicate is W11-04's. W11-04 files through W11-02 rather than editing a spec. |
| W11-01 ← everybody | **`TECH_DEBT.md` and `docs/FEATURE_MATRIX.md` are W11-01's alone.** Every packet that closes or opens a row tells W11-01; no packet edits either file. |
| W11-01 → W11-A2 | `scripts/feature-matrix-check.sh`'s section 6 passes to the closer on freeze, and the new `capability-prose` fixture lives there. |
| every packet → W11-A2 | Test trees pass to the closer on freeze. Say when you freeze. |

---

## The partition, checked

**Backend.** Eleven services. `message` → W11-05. `gateway`'s `auth/**` and `GatewayTestServer`, and
`identity` → W11-A1. The other nine services are **owned by nobody and need no edit**; W11-A2 takes
their test trees after freeze.

Thirteen `libs`. `security-core`, `config` and `observability` → W11-05. `filter` and `kafka-auth` →
W11-A1 (they hold TD-029 and TD-030 and have never been mutated). The other eight are **owned by
nobody**.

`build.mill`, `build-tests/**`, `.github/workflows/**` and `deployment/**` → **W11-03**, which lands
the one declared module edge first. `deployment/quickstart/kui-quickstart.yaml` is the exception:
it is **W11-02's**, because the third skip is a browser decision.

**Frontend.** `kernel/src/components/record.ts`, the shell's `overview/` and `App.tsx`, and
`feature-topics`' two files → W11-04. `frontend/e2e/**`, `tsconfig.json`, `package.json` and
`playwright.config.ts` → W11-02. Every other package and every other shell file is **owned by
nobody**.

**Documents.** `README.md`, `ARCHITECTURE.md`, `docs/overview/**`, `docs/FEATURE_MATRIX.md`,
`TECH_DEBT.md`, `DECISIONS.md`, `docs/adr/**`, `scripts/feature-matrix-check.sh` and
`frontend/packages/api/README.md` → W11-01. `docs/operations/**` → W11-05.
`docs/plan/verification/W11-<packet>.md` → **each packet's own**, and no packet's `Owns` contains
the directory. `docs/plan/ROADMAP.md` is the integrator's. **`docs/plan/WAVE-11.md` is this file, and
the wave's closing act deletes it.**

**Nesting checks, done rather than assumed.** `services/` is not owned as a tree. `libs/` is not
owned as a tree. `docs/` is not owned as a tree. `deployment/` is owned as a tree by W11-03 **except
one named file**. `frontend/packages/kernel/` is not owned as a tree. `frontend/e2e/` is owned as a
tree by W11-02 for the first time, because the type gate is a whole-directory change.

---

## Files owned by NOBODY

**Unowned and correct as they stand:** `scripts/run-tests.sh`; `frontend/scripts/*.mjs` (all read
their rosters from the filesystem); `frontend/packages/api/src/**` except its README; fifty-two of
fifty-six ADRs; `docs/api/**`; `docs/domain/**`; `research/**`; `frontend/README.md`;
`services/gateway`'s `routing/**` and `application/capability/**` (swept by W10-A1, 13 rules closed).

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave.**
Each has a `TECH_DEBT.md` row or gets one through W11-01: `services/alerts`'s `MaxReadMarkers`
(TD-031); `services/consumer`'s `stateCounts` and `notes` (TD-032); `services/schema`'s dead
`.filter` (TD-033); `services/connect`'s `ConnectorFacts.complete` (TD-034);
`frontend/packages/shell/src/chrome/ClusterSelector.tsx` — 214 lines, eight stories, eight cases, an
export from `shell/src/index.ts`, **zero production callers**, and
`[data-testid="cluster-selector-trigger"]` resolving to zero elements on every screen of a running
stack. Deleting it is a decision, not a chore, and it has now survived three waves as one.

**Unowned and deliberately left alone:** `stash@{0}` — five waves old, 46 files, unpoppable without
conflicts, **no packet may drop it**; and `docs/ROADMAP.md` / `docs/ROADMAP-SOLID.md`.

---

## For the integrator, before wave 11 starts

1. **Nothing in wave 10 is committed.** `git status --porcelain` is **69 entries** — 58 modified and
   11 untracked — against `ee81a440`, and that includes two new production test files, the masking
   operator page and eight verification reports. The wave is green and unrecorded. **Commit it
   before anything in wave 11 moves.**
2. **`docs/plan/verification/` is kept, and this time the reason is a citation rather than a
   backlog.** It holds seventeen files. The eight `W10-*` are wave 11's input. `W8-04.md` and
   `W8-07.md` are cited **from `docs/FEATURE_MATRIX.md`**, and `W9-03.md` from
   `frontend/e2e/topics.spec.ts` and `brokers.spec.ts`, so deleting them creates dangling references
   — which is the drift this repository exists to catch. **W11-01 either moves those citations or
   says the files stay**; `W8-02.md`, `W9-01.md`, `W9-02.md`, `W9-04.md`, `W9-06.md` and `W9-A2.md`
   are discharged and cited by nothing but `WAVE-10.md`, which is deleted with this file's creation,
   and may go.
3. **Check `ss -ltnp` for `:6017`, `:6018`, `:6099` and `:8099`** before you record any figure, and
   `docker ps` for a leftover quickstart holding 8080/8090/9092 — one was found at the wave-10
   integration and it would have made `smoke.sh` unrunnable on a port collision.
4. **`docs/plan/WAVE-10.md` is deleted in the same commit that creates this file**, per
   `docs/plan/README.md`.

---

## What wave 12 will be, and whether there is one

**There should not be, and for the first time the argument does not rest on a mechanism that has
never worked.** Wave 9 argued *"M10 has four things left and each is a command"* and was right about
all four while missing that two definition-of-done items were not gates. Wave 10 argued *"one claim
kind and one per-tile assertion turn both into gates"*; the per-tile assertion landed and is real,
and the claim kind did not — reproduced at the top of this file, exit 0, over the original sentence.

So wave 11's argument is narrower than either: **item 4 is open on six named documents, five of the
six are a substitution, and the sixth is a claim kind whose exact shape is written out in W11-01
item 3 with the failing input already in hand.** The reproduction at the top of this file *is* the
acceptance test. That is the difference from wave 10, where the gate was specified as an intention
and nobody had a sentence it had to refuse.

**What would make a wave 12 necessary, in order of likelihood:**

1. **The prose gate turns out to need a parser.** Refusing *"names an identity together with a
   negation token"* is a heuristic, and a heuristic that is too loose fails on honest prose while one
   that is too tight is defeated by a synonym. If the first honest draft has a false positive rate
   that makes the README unwritable, the right answer is a **smaller checked region** — a block that
   is nothing but the three lists — and that is a document change, not a script change. Decide it
   in W11-01 and say so.
2. **The third skip needs a permanent threshold nobody wants to ship.** `diskUsedWarningPercent: 1`
   in the shipped quickstart means every reader who runs it sees a warning on first boot. If the
   honest answer is that the case seeds and reverts, it needs the gateway restart W10-02's verifier
   could not reproduce, and that is a harness item.
3. **`build.mill` is outside every style and test gate (TD-036), and W11-03 is asked to put logic in
   it.** Extracting the `runDocumentMain` decision into `build-tests` is the right shape and nobody
   has done it. If the seam does not exist, say so rather than adding a fifth ungated branch.
4. **W11-A1 finds something structural in `AuthRoutes`.** It is the one production file in this
   repository with a whole public surface and no behavioural case, and wave 10's hunter found 83%
   ungated in code beside it. A finding that needs a new refusal at the edge is a wave-12 item.

**And if none of those fires, the closing act is this.** The integrator runs **the five items**, not
the gate list: opens the product at the address it lands on, reads the first paragraph of
`README.md` against `ls services/`, reads `docs/overview/README.md`'s gate table against the
commands it names, **appends one false sentence to a checked region and requires the script to
refuse it**, writes the wave-11 retrospective into `ROADMAP.md`, folds whatever is still true out of
`docs/plan/verification/` into it, and deletes `docs/plan/WAVE-11.md` and `docs/plan/verification/`.
`docs/plan/` is then `README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and the plan is finished.

**The ratio, for the record.** Wave 10 ran six building packets and two adversaries at 3:1 and closed
four of the five definition-of-done items; the one it did not close is the one whose mechanism was
predicted rather than demonstrated. Wave 11 is **five and two** at 2.5:1 — smaller, because what is
left is six documents, four gates that cannot fail, one inert button and one file with no test.
Nothing here needs a new idea. It needs the sentence at the top of this file to go red.
