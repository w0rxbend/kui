# Wave 10 — the two definition-of-done items that are still open, measured on a running stack

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 10, when wave 9 was written to be the last one.** Wave 9 did what it said it
would: `smoke.sh` ran, the second cluster landed, `M08` and `M14` got their cases, the dependency row
got its fixture, the masking engine got a caller, and the style gates reached 499 test sources for the
first time. Every gate is green. **The definition of done is still not met, and it is not met on the
two items that cannot be judged from a test suite.**

* **Item 1 — every screen renders real data.** The twenty-three captures do. **The address the
  product actually opens on does not.** `http://localhost:8090/ui/`, measured in a browser against
  the stack this wave built, draws the sentence *"Asking the cluster how it is."* over six stat tiles
  that carry a label and **nothing else** — no figure, no *not measured* sentence — and stays that way
  after `networkidle` plus fifteen seconds, during which the only requests the page makes are
  `/auth/me`, `/auth/settings` and `/capabilities/stream`. **Nothing is being asked.** The sentence is
  false and the tiles are the exact shape item 1 forbids. A second, smaller one on a capture screen:
  the CONSUME stat card on `M01` prints `81.2359955010432 B/s` beside a PRODUCTION card reading
  `1.2 kB/s`.
* **Item 4 — documented, with an accurate feature matrix.** `README.md`'s status banner says
  *"milestones 0 to 5"* and its **What is not built** section says **"No Kafka Connect, no ksqlDB, no
  ACL or quota management"** — against a tree that ships both services, draws both screens and has
  seven passing browser cases and one deployment-shaped skip over them. `docs/FEATURE_MATRIX.md`'s `DM-001` row says the masking engine
  has *"no caller in any service"* and that *"there is no configuration that can define a rule"*; wave
  9 gave it both. `docs/overview/README.md`'s gate table is stale in five rows and carries one clause
  that is now false. `ARCHITECTURE.md:156` names a domain type, `MaskingPolicy`, that
  `grep -rn MaskingPolicy --include='*.scala'` finds **zero** times in this repository.

Items 2, 3 and 5 are met and were re-run at the wave-9 close, one command at a time, on a stack built
from this tree. They are not this wave's subject and no packet here may weaken them.

**The shape of the failure is the same in both, and it is the shape this plan was written against.**
Every false sentence above sits **outside** every `<!-- checked: -->` region, in a document the
348-claim gate reads and does not check. The gate compares figures; it does not read
prose. So `./scripts/feature-matrix-check.sh` printed `348 claims checked, all true` over a README
whose headline claim is four milestones stale. That is `docs/ROADMAP-SOLID.md`'s failure, committed by
the repository that exists to prevent it.

**Wave 10 is six building packets and two adversaries, and it is a repair wave, not a feature wave.**
No new service, no new endpoint, no new ADR, no new feature package, and no new stylesheet. If a
packet finds itself writing a new capability, it has misread its brief.

---

## What is actually left, as commands

| # | DoD item | Open because | Packet |
| --- | --- | --- | --- |
| 1 | Every screen renders real data | `/ui/` draws a progress claim over six empty tiles with no request in flight; `formatBytes` prints raw doubles below 1 kB | **W10-01** |
| 1 | …and a case that actually drives it | `brokers.spec.ts:371` cannot tell the second cluster's screen from the first; `search.spec.ts:133`'s empty-state arm is dead on every deployment the suite runs against; `topics.spec.ts:432`'s bulk-bar count compares a number with itself; `shell.spec.ts:155` still skips for want of one seeded alert | **W10-02** |
| 4 | Documented, accurately | `README.md`, `docs/overview/README.md`, `ARCHITECTURE.md`, `docs/FEATURE_MATRIX.md` each carry a false sentence, and **no gate reads any of them** | **W10-03** |
| 4 | `TECH_DEBT.md` current | TD-027 is closed in the build and open in the row; three rows wave 9 asked for were never written | **W10-03** |
| — | Debt with a named seam | masking is wired and unreachable: no shipped deployment configures a rule, `docs/operations/masking.md` does not exist, the `kui.clusters.<n>.*` table has zero mentions of it, and a `keep` of 20/20 returns a card number in full | **W10-04** |
| — | Deployment | `smoke.sh` reads a document from three of nine routed services and probes the rest; the frontend `Dockerfile`'s manifest roster is ungated; the quickstart README publishes two figures that were wrong the day they were written | **W10-05** |
| — | Filed and unclosed | three W9-A1 rules and four W9-06 rules with the seam named and no owner | **W10-06**, **W10-A2** |

---

## The tree you start from, measured at the wave-9 close

Every figure here was printed by the command beside it, by the closer, on 2026-09-11, one gate at a
time, against images built from this tree.

| Gate | Figure |
| --- | --- |
| `./scripts/run-tests.sh` | **4,310 cases over 81 modules**, all 81 carrying tests |
| `pnpm -C frontend test` | **1,908 over 82 files** |
| `pnpm -C frontend e2e` | **105 passed, 3 skipped, 0 failed** over 108 |
| `./scripts/feature-matrix-check.sh` | **348 claims over eight sections**, all true |
| `./mill __.checkFormat` | 495/495 — **1,145 Scala sources over 162 reporting targets**, test trees included for the first time |
| `./mill --no-daemon resolve '__.fix' \| grep -c '\.test\.fix'` | **81** (was 0) |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2629/2629 over 65 paths, 76 operations, 160 schemas |
| `pnpm -C frontend a11y` | **790 stories × 2 themes, no violations** |
| `deployment/quickstart/quickstart.sh` | nine containers, healthy, two registered clusters |

**Anything red is yours.** Three things are not green, not flaky and not secret:

1. **`/ui/` is broken and no case sees it.** Reproduce before you repair:
   `node` a Playwright script that opens `/ui/`, waits for `networkidle` plus ten seconds, and prints
   `document.querySelectorAll('.kui-stat')`'s text. Six labels, six empty figures, one false sentence.
2. **`README.md` says ksqlDB and Kafka Connect are not built.** `grep -n "No Kafka Connect" README.md`.
3. **`./mill __.openApiCheck` fails roughly one run in four under load** with
   `services.<n>.api.runMain Subprocess failed` and **no stderr at all**, a different module each
   time. Three clean runs followed the one failure at the wave-9 integration, and
   `openApiCheck` is a `Task.Command` that Mill never caches, so those were real re-forks. It is a
   forked-JVM start failure, not a stale document — and a gate that reports a subprocess death with no
   stderr has now cost two waves an hour each. **W10-05 owns making it say what happened.**

---

## House rules

The first twenty are waves 8 and 9's and still apply in full; read them in the git history of
`docs/plan/WAVE-09.md` if you have not. Three are amended and two are new, and each is a wave-9
finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not otherwise
changing** — and note that since W9-05 the formatter and the linter **do** reach test sources, so a
test file you touch must satisfy `./mill <module>.test.checkFormat` before you freeze.

4. **A gate you cannot make fail is not a gate.** Unchanged, and it is the rule that produced every
   finding in this file.
5. **Report a mutation that stayed green.** Unchanged.
13. **A verifier's finding is an owned rule or a case, never a paragraph.** Unchanged, and see the new
    rule 21 for the case it kept failing on.
17. **A claim about your own gate is measured, not asserted.** Unchanged, and W10-03 will need it: the
    claim *"the documents are now gated"* is worth exactly one measured attack on the new claim.
18. **A verification pass writes its file FIRST, as a stub, and fills it as it goes.** This worked:
    **five of five** building packets filed in wave 9 against three of ten in wave 8, and the closer's
    input nearly doubled. Unchanged, and now extended — see rule 22.
19. **AMENDED: the stack is rebuilt twice and the second pair of ids is quoted, and the browser results
    are taken against a stack that has finished its first scrape.** Wave 9's integration lost a case
    (`brokers.spec.ts:157`) on a stack that was ninety seconds old: `/log-dirs` had no capacity yet, so
    a case that branches on `capacityIsReported` took the wrong branch and then timed out waiting for
    a sentence the warm product does not draw. `e2e/globalSetup.ts` checks that the stack is **up**,
    not that it has **scraped**. W10-05 fixes that; until it does, wait two minutes.
21. **NEW: the case belongs to whoever proved the hole.** Wave 9's closer converted **12 of 12** filed
    findings whose closing case lay inside its ownership and **0 of 28** that did not — 27.5% overall,
    and the shortfall was ownership, not capacity or input. So in this wave the adversarial closer owns
    **every test tree**, including `frontend/e2e/**` and the `guard-fixtures` section of
    `scripts/feature-matrix-check.sh`, from the moment the packet that owns the production file
    freezes. A fixture and a `page.waitForRequest` are **cases**. Production sources are still handed
    back; that part was right.
22. **NEW: a filed finding that needs one word of production visibility is filed as its own row, with
    the word.** Six of wave 9's forty filed rows could not be closed by anybody because the case needed
    `private` → `private[app]`, or one `mvnDeps` line, or a refusal that does not exist yet. A row that
    names only the assertion is not closeable; a row that names the seam is. The owner of the file
    lands the seam **before it freezes**, the same way house rule 15 treats a module edge.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`.
Use `./mill --no-daemon` for anything you record a number from. **Running the a11y sweep is three
commands** — build Storybook, serve `storybook-static` on `:6017`, then sweep — and `npx http-server`
leaves **two** processes, so `kill $!` reaps the wrapper and leaves the socket bound; kill the listener
`ss -ltnp` names.

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **348 claims over eight sections**: self-check 7, rows 18, merged-document 50, milestones 49, adr-index 112, openapi-totals 11, guard-fixtures 13, dependencies 88 over 12 manifests | **W10-03**, which must raise it and not lower it |
| `docs/FEATURE_MATRIX.md` | 189 rows, 70 COMPLETE, 178 in scope, 39% delivered, all inside checked regions | **W10-03**; **W10-04** moves `DM-001`'s capability and *tells* W10-03 |
| `frontend/e2e/**` | **105 passed, 3 skipped, 0 failed.** The three skips are `alerts.spec.ts:268` and `ksql.spec.ts:175` (deployment-shaped, correct) and `shell.spec.ts:155` (a seeded open alert, and W10-02 kills it) | **W10-01**, **W10-02**; no packet may add a fourth |
| `frontend/packages/kernel/src/components/record.ts` | `formatBytes` has 14 call sites across four packages; changing its output below 1 kB moves broker cards, topic tables and the two rate cards at once | **W10-01** |
| `libs/config/test/.../ShippedConfigurationSuite.scala` | 16 cases: the shipped-file roster, the two cluster ids, their two names, one bootstrap server and one metrics source | **W10-04** |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 **over 1,145 sources including every test tree**, with `.scalafix-tests.conf` relaxing `noNulls`/`noThrows`/`noAsInstanceOf`/`noReturns` for `test/src` only | every packet, now that test sources are gated |
| `deployment/compose/smoke.sh` | The contract set scraped from `ServiceContracts.byService` against the running containers; eleven services | **W10-05** |
| `frontend/scripts/bundle-shape.mjs` | Eight `feature-*` packages, all dynamically imported; reads `frontend/dist/.vite/manifest.json`, so it is stale until `pnpm -C frontend build` runs | nobody adds a package this wave |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W10-01 — The screen the product opens on

**Owns**
```
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/App.tsx
frontend/packages/shell/src/app.render.test.tsx
frontend/packages/shell/src/shell.test.tsx
frontend/packages/kernel/src/components/record.ts
frontend/packages/kernel/src/components/record.test.ts
frontend/e2e/shell.spec.ts
```

**Contract.** Definition-of-done item 1: *"Every screen in `screens/` renders real data. No fixtures,
no fabricated figures. Where a figure genuinely cannot be measured the UI says so; it never shows a
zero."* The twenty-three captures obey it. The address the product opens on does not, and the case
that used to notice was rewritten in wave 9.

**Do**

1. **`/ui/` with no cluster selected.** Measured on the shipped product, after `networkidle` + 15s:
   the voice line reads *"Asking the cluster how it is."*, six stat tiles (BROKERS ONLINE, TOPICS,
   PARTITIONS IN SYNC, PRODUCTION, CONSUME, CONSUMER LAG) draw a label and nothing else, every card
   below them (Throughput, Broker health, Partition health, Top consumer lag, Latency · p99, Storage
   by broker, Alerts & events) draws a heading and nothing else, the drawer head says `no cluster`,
   and the only API requests in flight are `/auth/me`, `/auth/settings` and `/capabilities/stream`.
   **Decide what this address is** — the choices are a redirect to the first registered cluster, a
   cluster chooser, or the same dashboard with every tile carrying its own *not measured* sentence —
   and say in the code why. What it may not be is a progress claim over six blanks.
2. **The case that would have caught it, and did until wave 9.** `shell.spec.ts:30` asserted
   `getByText("Quickstart")` on `/ui/` and was rewritten to assert the environment rail, which is
   chrome and is drawn from a roster that arrives before any cluster is chosen. W9-03 filed this
   against itself, which is the disclosure the house rules ask for; nobody owned the repair. In the
   same case, over `/ui/`, assert **per tile** that it carries either a figure or a sentence —
   `await expect(tile).toHaveText(/\S/)` over each of the six — and assert that the voice line is not
   a progress claim while no request is in flight.
3. **`formatBytes` prints raw doubles below 1 kB.**
   `frontend/packages/kernel/src/components/record.ts:140` reads
   `${unit === 0 ? value : value.toFixed(1)} ${units[unit] ?? "B"}`. It was written for whole-byte
   record sizes, and rates are fractional: the CONSUME stat card on the cluster dashboard prints
   `81.2359955010432 B/s`, and the accessible data table under the Throughput card prints
   `214.77853092686576 B/s`, `20.149754341786714 B/s` and `0.30493676815166676 B/s` in three of its
   288 rows. Fix it where the product decides — **not** at the two call sites — and mind that 14 call
   sites across four packages read it, three of which pass integers that must keep printing as
   integers (`147 B`, not `147.0 B`).
4. **`frontend/packages/feature-topics/src/TopicListPage.ts:771` exports a second `formatBytes`.**
   Two functions with one name and one meaning, in a workspace whose kernel already exports it.
   Report it; do not repair it, because `feature-topics` is not this packet's.
5. **The two structural holes W9-04 left standing, both with their closing case already written.**
   `StatusPill`'s `data-state` on the `<button>` branch has no caller anywhere in the workspace, and a
   pill drawn from no reported state can be made to publish `data-state="RUNNING"` with every one of
   1,088 cases green. Both are in `docs/plan/verification/W9-04.md` with the exact mount to add.

**Acceptance**
```
pnpm -C frontend test packages/kernel packages/shell     # baseline 1,008 over 44 files
pnpm -C frontend typecheck
pnpm -C frontend e2e e2e/shell.spec.ts
pnpm -C frontend e2e                                     # 105 passed, 3 skipped -> 105+, still 3 skipped at most
```
**Mutation line:** re-introduce the blank tile — return `undefined` from whatever your repair makes the
tile read — and show the new per-tile assertion going red. **And a green one:** report one change to
`Overview.tsx` that the browser suite does not notice.

---

## W10-02 — The four browser cases that pass without asking anything

**Owns**
```
frontend/e2e/**   (except shell.spec.ts, which is W10-01's)
```

**Contract.** Definition-of-done item 3 asks for *"a browser suite driving the deployed product"*. The
suite is green and four of its cases do not drive what their headers say they drive. Each was proved
green under a mutation by W9-03's verifier; the mutations are in `docs/plan/verification/W9-03.md`
with the exact bundle edit, and **you should re-apply each one before you repair it**, because a case
you cannot make fail is not a gate.

**Do**

1. **`brokers.spec.ts:371` cannot tell the second cluster's brokers screen from the first.** Its header
   claims it *"reddens on the identity assertions whatever the figures say"*. Measured: pinning all
   three loaders of the brokers route to `quickstart` while leaving their reactive keys alone leaves
   the file **11 passed, green**, and a browser confirms
   `/ui/clusters/staging-eu-01/brokers` then issues `GET /api/v1/clusters/quickstart/brokers` and draws
   that answer. Both clusters sit on the same `kafka:9092` with identical summaries, so every figure
   on the screen agrees. **Observe the request**, which is the only thing that differs:
   `const asked = page.waitForRequest((r) => r.url().includes(`/clusters/${other.id}/brokers`));`
   before the `goto`, awaited after — the shape `topics.spec.ts` already uses for `q=`.
2. **`search.spec.ts:133`'s empty-state arm is dead on every deployment this suite runs against.**
   `/api/v1/search` is global; `staging-eu-01` has no schema registry; so **every** query answers
   `partial: ["schema"]` — measured on `?q=zzz-nothing-matches-this-zzz` as well as `?q=orders` — and
   the `partial.length === 0` branch never executes. Replacing the *"Nothing matches …"* sentence in
   the served bundle with a bare `0` leaves the file **6 passed**. Make the empty arm reachable (a
   cluster-scoped query, or a route intercept that produces a partial-free response) and assert the
   sentence there. At minimum annotate the unexecuted arm so a green run stops reading as coverage.
3. **`topics.spec.ts:432`'s bulk bar compares a number with itself.** The case filters the list on the
   server to exactly the two topics it then ticks, so *rows drawn* and *rows selected* are the same
   number; switching the bar's count from the selected set to the rows on the page leaves both this
   case and `:396` green. Tick **one** of the two filtered rows and assert `1 topic selected` — which
   also drives the singular, exercised nowhere in a browser — then tick the second and assert
   `2 topics selected`. Two lines inside the existing case.
4. **Kill the third skip.** `shell.spec.ts:155` skips for want of an open alert and has done for three
   waves. The seed is one line: `diskUsedWarningPercent: 1` in
   `deployment/quickstart/kui-quickstart.yaml`, restart `kui-quickstart-kui`, revert. Coordinate with
   **W10-05**, which owns that file this wave; the case is yours, the seed is a stack decision. If the
   answer is that the quickstart should ship the low threshold permanently, say so and let W10-05 land
   it. **This is the case that drives `M06`'s acknowledgement path, which no browser has ever run.**
5. **Do not add a fourth skip**, and do not repair a case by widening its timeout.

**Acceptance**
```
pnpm -C frontend e2e                       # 105 passed -> 105+, skips 3 -> 2
pnpm -C frontend e2e e2e/brokers.spec.ts
pnpm -C frontend e2e e2e/search.spec.ts
```
**Mutation line:** re-apply W9-03's bundle mutation for item 1 and show your rewritten case red where
today's is green. Quote the image ids you ran against.

---

## W10-03 — The documents that are wrong, and the gate that reads figures and not sentences

**Owns**
```
README.md
ARCHITECTURE.md
docs/overview/**
docs/FEATURE_MATRIX.md
TECH_DEBT.md
DECISIONS.md
scripts/feature-matrix-check.sh
docs/adr/ADR-048-*.md  docs/adr/ADR-052-*.md  docs/adr/ADR-053-*.md  docs/adr/ADR-054-*.md
frontend/packages/api/README.md
```

**Contract.** Definition-of-done item 4. This is the largest packet in the wave and the only one that
can close a DoD item on its own. Its danger is that it owns the gate that every count in this
repository passes through: **the eight sections and 348 claims may not go down.**

**Do**

1. **`README.md` is four milestones stale in the first paragraph a newcomer reads.** The banner says
   *"milestones 0 to 5"* and names *"Kafka Connect, ksqlDB, … and metrics"* among what is not built;
   the **What is not built** list then says **"No Kafka Connect, no ksqlDB, no ACL or quota
   management."** Connect and ksqlDB are shipped services with their own images, screens, contracts
   and browser cases. Re-measure the whole section rather than editing the two sentences — the
   *What works today* table has no row for Connect, ksqlDB or the dashboard's Traffic tab either. ACL
   and quota management genuinely are not built; say that and only that.
2. **`docs/overview/README.md` §5's gate table is stale in five rows**, and one clause in it is now a
   lie: `__.fix --check` *"5353 sources — and no test source anywhere"*. Measured: 10672/10672 over
   1,145 sources including 499 test sources. `run-tests.sh` 4,205 → **4,310**; `pnpm test` 1,884 →
   **1,908**; `__.checkFormat` 252 → **495**; `feature-matrix-check.sh` 283 → **348**.
3. **`docs/FEATURE_MATRIX.md` `DM-001` is false in two of its three clauses.** *"`MaskingEngine` and
   `MaskingRule` are built and unit-tested with no caller in any service"* — `BrowseUseCase`,
   `TrackUseCase` and `MessageWiring` all call it since wave 9. *"there is no configuration that can
   define a rule"* — `kui.clusters.<n>.masking` decodes ten keys. *"`docs/operations/masking.md` does
   not exist"* — **still true**, and W10-04 is writing it. Move the row with W10-04, not ahead of it.
4. **The twenty-three-screen paragraph is stale.** It still reads *"21 of 23 covered, 1 partial, 1
   uncovered"* and still names `M08` uncovered and `M14` partial. Both have passing cases:
   `shell.spec.ts` *"switching cluster names where you have arrived, and takes the frame with it"* and
   `topics.spec.ts:432` *"a bulk delete says how many it deleted, in the plural"*, both confirmed in a
   full run at the wave-9 close. Re-count with the scope stated, and read `docs/plan/verification/`
   for the caveat that three of the twenty-three covering cases do not discriminate (W10-02 repairs
   them; the count is over screens, and it should say so).
5. **`ARCHITECTURE.md:156` names `MaskingPolicy`**, which is in no `.scala` file in this repository,
   and omits `RecordMasking[F]`, the port wave 9 actually shipped. Correct the row. Then ask the
   sharper question, which W9-06's verifier asked and nobody answered: `ArchitectureDocumentSuite`
   walks `services/<n>/domain/src` only, so a port declared in `application` is invisible to it and a
   type named in the table that exists nowhere is invisible to it in the other direction. **One of the
   two directions is cheap** — every name in the table's *Domain types* column must resolve to a
   `.scala` declaration somewhere under that service. Land it or file it with the seam.
6. **`TECH_DEBT.md`.** Close **TD-027** with W9-05's measured figures (81 `.test.fix` targets, 163
   resolved, 1,145 sources over 162 reporting targets, 380 of 499 test sources reformatted, and the
   `.scalafix-tests.conf` relaxation with its four site counts). Correct the row's own stale numbers
   while you are there — it says 83 test modules and 485 files; it is 81 and 499. Then write the rows
   wave 9 asked for and nobody wrote: scalafmt's non-idempotent `try`/`catch` argument versus
   scalafix's parser (the repository's only `// format: off`); `build.mill` and `mill-build/build.mill`
   — 4,291 lines — outside both style gates; `smoke.sh`'s unsigned reads; and the masking rows W10-04
   hands you. Fix TD-034's arithmetic: *"seven test callers"* is eight call sites in seven files, two
   of which are rigs.
7. **Then make the gate able to see a sentence.** This is the item that stops wave 11 existing for the
   same reason. Every false clause above sits outside every marker. Add a claim kind that compares a
   **named prose claim** against something measurable — the simplest honest version is a
   `<!-- checked: capability-claims -->` region in `README.md` whose sentences name services, compared
   against `ServiceContracts.byService` or against `ls services/`. Publish its cost: apply your own
   cheapest attack on the finished gate and say what it cost, per house rule 17.
8. **Ten fixtures are already written for you.** `docs/plan/verification/W9-02.md` carries ten
   green-under-mutation findings in this script with the exact `drive` shape for each, and
   `docs/plan/verification/W9-A2.md` names them as the single largest block of un-closeable filed
   work in wave 9 — ten of twenty-eight hand-backs, all in this one file, all because the closer did
   not own it. You own it. Close them, and note that rule 21 now gives the **closer** the
   `guard-fixtures` section after you freeze, so file what you cannot finish.

**Acceptance**
```
./scripts/feature-matrix-check.sh                  # 348 -> more, exit 0, per-section counts all up
./mill --no-daemon tools.errorCodes.test
./mill --no-daemon frontend.apiConstants --check
```
**Mutation line:** make one sentence of `README.md`'s repaired capability section false and show the
new claim going red. **And a green one:** the cheapest attack you found on the finished gate, with its
cost in lines.

---

## W10-04 — Masking: reachable, documented, and not defeated by writing 20 twice

**Owns**
```
libs/config/src/kui/config/MaskingConfig.scala
libs/config/src/kui/config/KuiConfigSource.scala
libs/config/test/**
libs/security-core/**
services/message/**
deployment/quickstart/kui-quickstart.yaml
docs/operations/masking.md          (new)
docs/operations/configuration.md
docs/operations/observability.md
```

**Contract.** Wave 9 wired the masking engine into the message service and gated the wiring. What it
did not do is make the feature **reachable by an operator** or **safe when it is**. Five findings are
filed against it with the seam named; four are still open.

**Do**

1. **`keep: {prefix: 20, suffix: 20}` returns a sixteen-digit card number in full**, and the
   configuration loads. `MaxKeep` is enforced **per end**, so two legal ends together reveal the whole
   field; `maskKeepingEnds`' `if maskedCount <= 0 then text` hands the payload back. Verified
   empirically in wave 9 with a throw-away suite. The packet's own stated reason for the cap —
   *"suffix: 44 on a card number returns it unmasked"* — is defeated by writing 20 twice. Refuse
   `keepPrefix + keepSuffix > MaxKeep` at load, **or** make the engine mask the whole field when the
   ends leave nothing, which is the fail-safe direction and matches `maskPayload`'s own stated rule
   that *masking too much is recoverable and masking nothing is not*. Case:
   `MaskingConfigSuite` — *"a keep whose two ends together reveal the field is refused"*.
2. **The start-up line can print the rules.** `MessageWiring.scala:372` prints
   `${cluster.masking.rules.size} rule(s)`; changing `.size` to the list prints every masked field name
   and topic pattern into `docker logs`, and **both the scoped suite and `run-tests.sh` stay green**.
   `MaskingConfigSuite` asserts exactly this discipline for `ClusterConfig.toString`; the second place
   that prints the same fact has no case. Widen `private def describeMasking` to `private[app]` — that
   is the visibility seam rule 22 is about — and assert it in `MessageWiringSuite` over a captured
   `StructuredLogger`.
3. **`kui.masking.applied` has no case as a writer.** `ConfiguredRecordMaskingSuite` counts through a
   fake, so the real otel4s adapter — the only thing that decides what `{cluster, topic, target}`
   actually contain — is untested, and swapping `topic.value` for `cluster.value` in
   `MaskingMetrics.scala:53` is green everywhere. The seam is a build edge, so **land it first**:
   `services.message.infrastructure.test` needs
   `mvn"org.typelevel::otel4s-oteljava-testkit::${Versions.otel4s}"` in `build.mill`, which
   **W10-05 owns and lands in its first hour** (house rule 15). Then `MaskingMetricsSuite` over an
   in-memory meter, the shape `services/cluster/api/test/.../ClusterTestServer.scala:125` already uses.
4. **Decide about `decodeErrors`.** `ConfiguredRecordMasking.maskWith` copies key, value and headers;
   `DecodeError.cause` crosses the wire in `MessageDto` and `JsonSerde.describeFirst` puts the
   payload's **first printable character** into it. One character of a payload the serde refused —
   which is precisely the shape a whole-value text rule exists to hide. Decide, write the decision in
   the port's scaladoc, and pin whichever answer you choose with a case.
5. **Nothing an operator reads says the feature exists.** `docs/operations/configuration.md`'s
   key-by-key `kui.clusters.<n>.*` table has **zero** mentions of masking (`grep -c masking` → 0) while
   `serde.*`, `connect.*`, `ksql.*` and `schemaRegistry.*` each have a row per key; ten new
   operator-facing keys are undocumented. `docs/operations/observability.md`'s metric table lists
   fifteen metrics and not `kui.masking.applied`. `docs/operations/masking.md` does not exist. Write
   all three, and **configure one rule in the quickstart** so that the feature has a deployment a
   person can look at — the seed topics carry an `orders.*` payload with fields worth masking.
6. **`maskingCharsReplacement` is modelled, decoded, listed in `UnknownKeys.Known`, named in the
   scaladoc, and appears in no test in this repository.** Wave 9's closer added that case; check it is
   still there after your edits and that `UnknownKeys.Known` still carries the line, because deleting
   it turns a documented key into a start-up refusal.
7. **Tell W10-03 what moved** — `DM-001`'s state and the sentence that is still true. Do not edit
   `docs/FEATURE_MATRIX.md`; wave 8 lost a one-line correction for a whole wave to exactly that shape.

**Acceptance**
```
./mill --no-daemon -k libs.securityCore.jvm.test + libs.config.test + services.message.__.test
./mill --no-daemon checkArchitecture
./scripts/run-tests.sh
```
**Mutation line:** apply item 1's two-ended keep to a card number and show the new refusal red.
**And a green one:** one masking rule you can still break with every suite green.

---

## W10-05 — The deployment, the build edge, and the gate that dies without saying so

**Owns**
```
deployment/**   (except deployment/quickstart/kui-quickstart.yaml, which is W10-04's)
build.mill
.github/workflows/ci.yml
apps/allinone/**
frontend/e2e/globalSetup.ts
frontend/e2e/fixtures.ts
```

**Do**

1. **Land W10-04's build edge in your first hour** — `otel4s-oteljava-testkit` on
   `services.message.infrastructure.test`'s `mvnDeps` — and say so, before any of your own work.
   House rule 15; wave 6 lost a service to an edge that was nobody's.
2. **`smoke.sh` passes over a stack whose topics product answers 401.** Measured in wave 9, both
   directions: change `KUI_PRINCIPAL_KEY` on `kui-topic` in `docker-compose.yml` and the script prints
   `PASSED` in 107s with *"topic capability: available"* twice, while
   `GET /api/v1/clusters/measured/topics` answers **HTTP 401 KUI-UNAUTHENTICATED** and
   `/consumer-groups` answers 200. Six of the nine routed services are covered only by a capability
   probe, which is a health call carrying no signed principal. Add a `for` over `contracts` asking the
   gateway for each service's published read path and requiring a 2xx — the machinery exists and is
   used once, for Connect.
3. **The frontend `Dockerfile`'s manifest roster is ungated and the drift is visible in the build
   log.** Wave 9 repaired three missing `COPY frontend/packages/*/package.json` lines; deleting one
   again builds an image that is byte-indistinguishable (eight feature chunks, `feature-ksql` among
   them) because `pnpm --frozen-lockfile` resolves a 10-of-11 workspace without failing — it only
   prints `Scope: all 11 workspace projects` instead of `all 12`. Gate it: four lines comparing
   `ls frontend/packages | wc -l` with `grep -c '^COPY frontend/packages/' deployment/frontend/Dockerfile`,
   in `build-tests` or in the CI job.
4. **`deployment/quickstart/README.md` publishes two figures that were wrong the day they were
   written.** *"12 topics (18 counting the internal ones …)"* — it is **16**, confirmed against
   `kafka-topics.sh --list`; and *"4 consumer groups, one of them genuinely live and behind"* — on this
   stack `analytics-indexer` is the live one with `totalLag 0`, and `order-fulfilment` carries the lag
   and is `EMPTY` with 0 members. **No group is both live and behind.** Fix both, and put the section
   inside a `<!-- checked: -->` region if you can find a claim kind that fits — the file has none, and
   the section's own heading is *"honestly"*.
5. **`e2e/globalSetup.ts` waits for the stack to be up and not for it to have scraped.** A suite
   started ninety seconds after `--wait` returns loses `brokers.spec.ts:157`, because `/log-dirs`
   answers no capacity and a case that branches on `capacityIsReported` then waits fifteen seconds for
   a sentence the warm product does not draw. Poll for the first successful `/log-dirs` **and**
   `/brokers` scrape before the suite starts. This is one line of visibility and it belongs to the
   harness, which is yours.
6. **`./mill __.openApiCheck` reports a dead subprocess with no stderr.** One run in four under load
   fails as `services.<n>.api.runMain Subprocess failed`, a different module each time, with nothing
   else in the log — indistinguishable from a stale committed document, and it has cost two waves an
   hour each. `runMain(...)()` swallows the child's stderr. Make the task say what the child said.
7. **Rebuild the stack twice** (house rule 19) and publish both pairs of image ids. Every browser
   result in this wave quotes the second pair, taken against a stack that has finished its first
   scrape.

**Acceptance**
```
./deployment/compose/smoke.sh
docker compose -f deployment/compose/docker-compose.yml up -d --wait
./mill --no-daemon __.openApiCheck
./mill --no-daemon __.compile
deployment/quickstart/quickstart.sh   # then quickstart.sh down
```
**Mutation line:** re-apply the wrong `KUI_PRINCIPAL_KEY` on `kui-topic` and show your new read loop
failing where today's `smoke.sh` prints PASSED. **And a green one:** one deployment change eleven
containers and a smoke run do not notice.

---

## W10-06 — The three rules wave 9's hunter could not close, and the ones nobody owned

**Owns**
```
frontend/packages/kernel/src/components/Dialog.tsx
frontend/packages/kernel/src/components/controls.test.tsx
frontend/packages/kernel/src/components/dialog.test.tsx
frontend/packages/feature-ksql/**
services/gateway/api/src/kui/gateway/api/StreamProxy.scala
services/gateway/api/test/**
```

**Contract.** `docs/plan/verification/W9-A1.md` was never written — its findings are in this file
instead, because the directory belonged to another packet and its own instructions forbade the file.
That is a mechanism failure, not a packet failure, and rule 21 fixes it. Three of its sixteen ungated
rules are open; all three have a named seam.

**Do**

1. **A stray click on the veil dismisses the one dialogue between a typed statement and a deleted
   Kafka topic.** `ConfirmStatement`'s `closeOnScrimClick={false}` is an opt-out against `Dialog`'s
   closing default, and flipping it to `true` leaves `pnpm -C frontend test` at **1,904 green**. The
   hunter wrote a case clicking `.kui-modal-scrim` — both `element.click()` and a bubbling
   `MouseEvent` — and it stayed green under the mutation, because Solid's delegated scrim handler is
   not reachable from that jsdom harness; it **deleted the case rather than ship a decoy**, which is
   the right call and is why this row exists. Close it in `Dialog`'s own suite, which owns the scrim
   and can drive it directly.
2. **The confirm button is never busy.** `busy={apply.busy()}` → `busy={false}` on `ConfirmStatement`
   leaves 1,904 green, and a destructive statement can be sent twice.
3. **`StreamProxy`'s `TerminalWatch` carry is exercised by no case at all** — including the one named
   `aTerminalEventSplitAcrossChunkBoundariesIsStillSeen`. That case feeds `.chunkLimit(1)`, and
   `relay()`'s bounded queue re-chunks before the watch sees anything, so the bytes never arrive split.
   Resetting the carry and treating an incomplete trailing line as a complete one leaves the suite
   **13/13 green**. Drive `observe()` directly, at the nesting level the bytes reach.
4. **`KsqlObjects.of` bounds `items` at 500 and leaves `unreadable` unbounded** — a ksqlDB answering
   10,000 undescribable rows produces a 10,000-entry list in one document and a banner naming every
   one of them. That is a **missing** rule rather than a deleted one; file it in `TECH_DEBT.md`
   through W10-03 with an owner, or bound it here and say which.
5. **`statement-plan-push-query.json` is the one fixture in `feature-ksql` that no encoder produced**,
   and it carries `"warnings": []` where `KsqlUseCases.plan` returns one warning for a push query. It
   is not a lie — `ksql.test.tsx` says it is hand-made — and it is what house rule 12 exists to
   eliminate. Commit a service golden or generate it.

**Acceptance**
```
pnpm -C frontend test packages/kernel packages/feature-ksql
./mill --no-daemon services.gateway.api.test
./scripts/run-tests.sh
```
**Mutation line:** flip `closeOnScrimClick` and show the new `Dialog` case red.

---

## W10-A1 — Adversarial: the gateway's edge, which nobody has mutated

**Owns (production and test, whole)**
```
services/gateway/api/src/kui/gateway/api/auth/**
services/gateway/api/src/kui/gateway/api/routing/**
services/gateway/application/src/kui/gateway/application/capability/**
services/ksql/infrastructure/**
```

**Contract.** Hunt. File in W10-A2's shape — rule, file, exact mutation, suite command, closing case —
and **write the file first, as a stub** (`docs/plan/verification/W10-A1.md`). Wave 9's hunter filed
sixteen genuine ungated rules from 23 mutations and closed thirteen; its own report says which modules
it would **not** declare finished, and this brief is that list.

**Where to look, in the hunter's own words.** `services/gateway` is 117 Scala sources and wave 9
mutated seven points in it. `CsrfCheck`'s constant-time loop, `SessionMiddleware`, `EdgeHeaders`,
`CapabilityRegistry` (419 lines), `ReadinessPoller` (336) and `ContractRouting` (324) **were read and
not mutated**. In `services/ksql/infrastructure`, `KsqlHttp` is 400 lines of hand-written parsing and
four points were screened; `statusFrom`'s three-way fallback, `frameOf`'s two error shapes and
`KsqlCredentials`' OAuth path are unswept.

**Carry the clue.** *A rule ungated in a file that already has a suite is ungated because the fixture
could not express the failing input, or because no assertion ever read that field.* It has fired in
three consecutive waves and it is not spent.

**Record the compiler gate.** Wave 9 found that `-Werror` with `-Wunused` refused two of its
mutations outright — an unused pattern binding and an unused parameter — which forces an attacker into
a second, visible edit. That is a real structural gate nobody has published; if you find more of them,
say so.

**Acceptance:** a filed file with one row per mutation, the denominator stated, and equivalent mutants
argued down with the algebra rather than counted.

---

## W10-A2 — Adversarial: the closer, and it owns the test trees this time

**Owns**
```
docs/plan/verification/**
every test tree in the repository, from the moment its production packet freezes:
  **/test/src/**, **/*.test.ts, **/*.test.tsx, frontend/e2e/**,
  and scripts/feature-matrix-check.sh's section 6 (guard-fixtures)
```

**Contract.** Rule 21. Wave 9's closer converted **12 of 12** findings whose closing case was inside
its ownership and **0 of 28** that were not; it filed twenty-eight hand-backs, ten of them in one
shell script and four in the browser tree, and named the partition as the binding constraint. This
wave removes the constraint. Production sources are still handed back — that part was right six times
in wave 8 and five times in wave 9.

**Do**

1. **Close from the filed list. Do not hunt.** Your input is `docs/plan/verification/W9-01.md`,
   `W9-02.md`, `W9-03.md`, `W9-04.md`, `W9-06.md`, `W9-A2.md`'s twenty-eight hand-backs, W10-A1's file,
   and every row the building packets of this wave file as they go.
2. **Every closure is a case that fails under its own mutation, applied by you, reverted from bytes
   you saved yourself** — never `git checkout --`, `git restore` or `git stash`.
3. **Argue down equivalent mutants with the algebra shown**, and keep them out of the denominator.
   Wave 9's closer did this once and its hunter twice; both are in their reports and both are right.
4. **Report two percentages**: closures over all filed rows, and closures over rows whose case was
   inside your ownership. If rule 21 worked, the first should move toward the second.

**Acceptance**
```
./scripts/run-tests.sh
pnpm -C frontend test
pnpm -C frontend e2e
./mill --no-daemon __.checkFormat      # your test files are gated now
```

---

## Where the packets meet

| Edge | What crosses it |
| --- | --- |
| W10-05 → W10-04 | **The `otel4s-oteljava-testkit` dependency on `services.message.infrastructure.test`**, landed in W10-05's first hour, before any of its own work. House rule 15. Without it `MaskingMetricsSuite` has no module to live in. |
| W10-05 → everybody | **The stack, twice**, with both pairs of image ids published, and the second pair taken after the first scrape has completed. Every browser result quotes the second pair. |
| W10-01 ↔ W10-02 | `frontend/e2e/shell.spec.ts` is **W10-01's**, because the case that stopped seeing the landing screen is the same edit as the repair. Every other spec file is W10-02's. Named one by one so the tree is disjoint. |
| W10-02 ↔ W10-05 | **The seeded open alert.** The case is W10-02's, `kui-quickstart.yaml` is W10-04's and the restart is W10-05's stack. Decide once, in the open: either the quickstart ships `diskUsedWarningPercent: 1` permanently or the case seeds and reverts. |
| W10-04 → W10-03 | **`DM-001` moves and W10-04 does not edit the matrix.** It tells W10-03 what moved and which clause is still true. |
| W10-03 ↔ W10-06 | `TECH_DEBT.md` is W10-03's alone. W10-06's `KsqlObjects` bound is filed **through** W10-03 if it is not landed. |
| W10-A1 → W10-A2 | The hunter's output is a **filed file**, not a closure, and it is written first as a stub. |
| every packet → W10-A2 | Test trees pass to the closer on freeze. Say when you freeze. |

---

## The partition, checked

**Backend.** Eleven services. `message` → W10-04. `gateway`'s auth/routing/capability and
`ksql/infrastructure` → W10-A1, production and test. `gateway/api`'s `StreamProxy` and its test tree →
W10-06. The other eight services are **owned by nobody and need no edit**; W10-A2 takes their test
trees after freeze, to close a filed finding and for nothing else.

Thirteen `libs`. `config` and `security-core` → W10-04. The other eleven are **owned by nobody**.

`build.mill`, `.scalafix*.conf` and `.scalafmt.conf` → **W10-05**, which lands the one declared module
edge first.

**Frontend.** `kernel/src/components/record.ts` and the shell's `overview/`, `App.tsx` and two test
files → W10-01. `kernel/src/components/Dialog.tsx` and `feature-ksql/**` → W10-06. `frontend/e2e/` is
split by file between W10-01 (`shell.spec.ts`), W10-05 (`globalSetup.ts`, `fixtures.ts`) and W10-02
(everything else). Every other `feature-*` package and every other shell file is **owned by nobody**.

**Documents.** `README.md`, `ARCHITECTURE.md`, `docs/overview/**`, `docs/FEATURE_MATRIX.md`,
`TECH_DEBT.md`, `DECISIONS.md`, `scripts/feature-matrix-check.sh`, ADRs 048/052/053/054 and
`frontend/packages/api/README.md` → W10-03. `docs/operations/**` → W10-04.
`docs/plan/verification/**` → W10-A2. `docs/plan/ROADMAP.md` is the integrator's.
**`docs/plan/WAVE-10.md` is this file, and the wave's closing act deletes it.**

**Nesting checks, done rather than assumed.** `services/` is not owned as a tree. `libs/` is not owned
as a tree. `docs/` is not owned as a tree. `frontend/packages/kernel/` is **not** owned as a tree this
wave — two files by name to two packets — which is a change from four waves of W-04 owning it whole,
and is the reason the two files are named rather than globbed. `frontend/e2e/` is not owned as a tree.

---

## Files owned by NOBODY

**Unowned and correct as they stand:** `build-tests/**`; `scripts/run-tests.sh`;
`frontend/scripts/*.mjs` (all read their rosters from the filesystem);
`frontend/packages/api/src/**` except its README; fifty-two of fifty-six ADRs; `docs/api/**`;
`docs/domain/**`; `research/**`; `frontend/README.md`.

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave** —
listed so that *unowned* is a decision and not an oversight. Each already has a `TECH_DEBT.md` row or
gets one through W10-03:

* `services/identity/.../Pbkdf2PasswordHasher.scala` — the hand-written constant-time loop (TD-028).
* `libs/filter/.../CelFilterEngine.scala` — the warm-up seam (TD-029).
* `libs/kafka-auth/.../KeyStoreMaterializer.scala` — the umask re-apply, which needs an environment
  rather than a seam (TD-030).
* `services/alerts/.../InMemoryAlertStore.scala` — `MaxReadMarkers` has no behavioural upper bound
  (TD-031).
* `services/consumer/.../GroupListUseCase.scala` — `stateCounts` and `notes` reach no response
  (TD-032).
* `services/schema/.../RegistryCredentials.scala` — a provably dead `.filter` (TD-033).
* `services/connect/.../Connectors.scala` — `ConnectorFacts.complete` has no production caller
  (TD-034).
* `frontend/packages/feature-topics/src/TopicListPage.ts:771` — a second `formatBytes`.
* `frontend/packages/shell/src/chrome/ClusterSelector.tsx` — 214 lines, eight stories, eight cases, an
  export from `shell/src/index.ts`, and **zero production callers**; `[data-testid="cluster-selector-trigger"]`
  resolves to zero elements on every screen of a running stack. Deleting it is a decision, not a chore.

**Unowned and deliberately left alone:** `stash@{0}` — four waves old, 46 files, 3,256 insertions,
unpoppable without conflicts, **no packet may drop it**; and `docs/ROADMAP.md` /
`docs/ROADMAP-SOLID.md`, the historical record and a superseded plan.

---

## For the integrator, before wave 10 starts

1. **Nothing in wave 9 is committed.** `git status --porcelain` is **424 entries** — 410 modified and
   14 untracked — against `a134d74c`, and that includes seven new production sources (the masking
   engine's config, port and adapter), `.scalafix-tests.conf`, and 380 reformatted test files. The
   wave is green and unrecorded. **Commit it before anything in wave 10 moves**, in the two commits
   W9-05 separated for you: the build change and `.scalafix-tests.conf` first, the 380-file
   reformatting second, with the production work before both.
2. **Three leaked static servers and one stale stack** were killed at the wave-9 integration; check
   `ss -ltnp` for `:6017`, `:6018`, `:6099` and `:8099` before you record any figure.
3. **`docs/plan/WAVE-09.md` is deleted in the same commit that creates this file**, per
   `docs/plan/README.md`. `docs/plan/verification/` is **kept** — nine files, and they are W10-A2's
   input. They are deleted with `WAVE-10.md`.

---

## What wave 11 will be, and whether there is one

**There should not be, and the argument is narrower than wave 9's was.** Wave 9's argument was *"M10
has four things left and each is a command"*, and it was true — all four were done. What it missed is
that two definition-of-done items are not gates, and nothing in this repository was reading them: item
1 is judged by opening the product, and item 4 is judged by reading the first paragraph of the README.
**Both failures were invisible to a tree in which every one of fourteen gates is green.**

So the thing that closes the book is not another packet. It is **W10-03 item 7** — a claim kind that
compares a sentence against something measurable — and **W10-01's per-tile assertion**, which is the
first case in this project that asserts a screen says *something* rather than asserting it says a
particular thing. If those two land, the two open items become gates and stay closed. If they do not,
wave 11 exists and it will be the same wave again.

**What would make a wave 11 necessary, in order of likelihood:**

1. **The `/ui/` decision is a design decision and not a repair.** What that address should be —
   redirect, chooser, or honest empty dashboard — is a question `SCREENS-V4.md` does not answer,
   because the design's twenty-three captures all have a cluster. If the answer needs a new screen, it
   is a wave-11 item and not W10-01's.
2. **The prose gate is harder than one claim kind.** Comparing a sentence against a fact is the thing
   this repository has never done, and the four previous attempts at hardening this script all
   published a price that measurement did not support. House rule 17 exists for exactly this.
3. **W10-A1 finds something structural at the gateway's edge.** `CsrfCheck`, `SessionMiddleware` and
   `ContractRouting` have never been mutated, and wave 8's hunters found 32–42% ungated in code that
   *had* been swept once. A finding that needs a production seam in the edge is a wave-11 item.
4. **The masking `keep` repair changes a shipped default.** Refusing `prefix + suffix > MaxKeep` is a
   configuration that used to load and no longer does. If a deployment exists that relies on it, the
   honest answer is a migration note, not a silent refusal.

**And if none of those fires, the closing act is this.** The integrator runs the definition of done
end to end — not the gate list, **the five items** — opens the product in a browser and looks at the
address it lands on, reads the first paragraph of `README.md` against `ls services/`, writes the
wave-10 retrospective into `ROADMAP.md`, folds whatever is still true out of
`docs/plan/verification/` into it, and deletes `docs/plan/WAVE-10.md` and `docs/plan/verification/`.
`docs/plan/` is then `README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and the plan is finished.

**The ratio, for the record.** Wave 9 ran six building packets and two adversaries at 3:1, and the
adversarial split into one hunter and one closer was right: the hunter found 16 genuine ungated rules
in 23 mutations (**70%**, the highest rate in this project's history, against code nobody had ever
swept) and closed 13; the closer converted 12 of 12 findings it could reach. Wave 10 keeps the split
and changes the partition instead — which is the one variable four waves of this experiment have never
moved.
