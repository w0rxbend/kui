# Closing report: fourteen waves, and why there is no fifteenth

**What this is.** The document that closes the build described in [`ROADMAP.md`](ROADMAP.md). Fourteen
waves ran against that plan; a fifteenth was written and is deliberately not being run. This file
replaces it. It is written for two readers who are not the same person: somebody who has to operate or
extend KUI and needs to know what it is and where it is weak, and somebody who has to run a build like
this one and would rather inherit the measurements than repeat them.

**Every figure below was printed by the command beside it on 2026-09-12, on this working tree, one
command at a time.** Nothing here is read off a packet report or a retrospective without saying so.
Two heavier gates together have exhausted this machine twice, which is why they were run serially and
why the four criteria that need a running stack are cited rather than re-taken — that distinction is
made explicitly in §2 rather than smuggled into a table.

**Why the loop is being stopped rather than continued.** Wave 15 existed, was written, and its subject
was three defects *in the gates* and two sentences. Waves 9 through 14 were each written to be the
last, and each one found a real class of documented fact that nothing in the repository read. That
sequence has ended: a figure, a sentence, a quotation, a roster and a listing all have readers, all
five are driven by fixtures, and both of the last two waves' acceptance predictions are still red on
the shipped bytes (re-confirmed here, §4). What remains is not a missing mechanism. It is a shape rule
that was never driven over the shapes in front of it, a `find` where the house rule says `git
ls-files`, and a table in a document the definition of done does not name. Those are worth an
afternoon, not a wave, and a wave whose subject is its own instrumentation is how a project stops
shipping. The remaining work is named in §5 with the command that finds each.

---

## 1. What was built

KUI is a Kafka management and observability console: Scala 3 on the server, TypeScript and SolidJS in
the browser, shipped as two independent halves that talk over HTTP. The back end is **eleven services**
under `services/` — `gateway` at the edge, `identity` behind its sign-in routes, and nine routed
domain services (`cluster`, `topic`, `message`, `consumer`, `schema`, `metrics`, `alerts`, `connect`,
`ksql`), each in the same six ADR-041 layers with its own contract, image and OpenAPI document. The
browser is **eleven packages** under `frontend/packages/` — `shell`, `kernel`, `api` and eight feature
packages, each loaded by dynamic import through the shell's registry so a feature cannot import a
feature. An operator gets clusters and brokers, topics and their configuration, record browsing and
publishing with a typed filter language, consumer groups with lag and an offset-reset wizard, schema
subjects with versions and compatibility, an alert feed with a live SSE stream and a notification bell,
Kafka Connect connectors with per-task state, ksqlDB objects and statements with push queries, a
metrics Traffic tab drawn from one Prometheus exposition, and cross-entity search. Destructive writes
are plan → token → confirm. The whole thing composes two ways from the same modules — one JVM, or
eleven containers — and the reason the architecture is shaped that way is the one property the product
is actually built around: **a part of it failing must leave the rest usable and must say so**, which is
what `deployment/compose/smoke.sh` exists to prove rather than assert.

### The measured numbers

| Command | What it printed here, 2026-09-12 |
| --- | --- |
| `./scripts/run-tests.sh` | **81 modules (81 with tests), 4,420 test cases, all passing** |
| `./mill __.compile` | 8251/8251 SUCCESS |
| `./mill checkArchitecture` | **195 modules, 10 rules, no layering violations** |
| `./mill __.openApiCheck` | 2544/2544 SUCCESS |
| `./mill __.checkFormat` | 495/495 SUCCESS |
| `./mill __.fix --check` | 10672/10672 SUCCESS |
| `./scripts/feature-matrix-check.sh` | **474 claims over 13 sections, all true**, exit 0 |
| `pnpm -C frontend test` | **1,936 cases over 83 files** |
| `pnpm -C frontend typecheck` | exit 0, over both projects including `frontend/e2e/` |
| `pnpm -C frontend lint:boundaries` | **423 files in 11 packages**, no violations |
| `ls services/ \| wc -l` | **11** |
| `ls frontend/packages \| wc -l` | **11** |
| `git ls-files \| wc -l` | **2,161** |
| `git ls-files '*.scala' \| wc -l` | 1,157 |
| `git ls-files screens \| wc -l` | **23** |
| `git log --oneline \| wc -l` | 540 |

Three figures the checker prints about the documents it reads, because they are the ones a reader is
most likely to want and least likely to re-take: `docs/FEATURE_MATRIX.md` is **189 rows, 71 COMPLETE,
178 in scope, 40% delivered**; `docs/api/openapi.json` is **65 paths, 76 operations, 160 component
schemas**, with `X-Kui-Principal` on 59 operations over 48 paths; `DECISIONS.md` is **58 rows** — and
that last one is the subject of a defect in §5, because the gate counts ADRs with `find` and a clone
has 57.

---

## 2. The definition of done, item by item

The five items are at the top of [`ROADMAP.md`](ROADMAP.md). They are judged here as written, not as
anybody would prefer them written.

### Item 1 — every screen in `screens/` renders real data. **MET.**

Re-taken here: `git ls-files screens` is **23**, so the item's own denominator is a fact about the
repository rather than about somebody's working tree — that was first true at the wave-14 close and it
is true here. The browser evidence is **not** re-taken in this session: `pnpm -C frontend e2e` needs a
quickstart brought up from images built from the tree, and the closing measurement is the wave-14
close's, **108 passed, 2 skipped, 0 failed over 110**, with 23 of 23 captures carrying a named case
that ran green in that run and per-spec counts published in the wave-14 retrospective. The two skips
each describe a *different deployment* (no alerts service configured, no ksqlDB) rather than a gap.

The honest qualification, which stands: **nothing reconciles the `M01`…`M23` screen index against the
case list.** It is a hand roster, it is this item's denominator, and a human matches case titles to
screen ids by eye once per close. See §5.

### Item 2 — every backend capability exists as a service or an endpoint. **MET, re-taken here.**

`ls services/` is 11, nine of them routed, and the roster is not trusted: `./scripts/feature-matrix-check.sh`
compares the names `README.md` publishes against the directories on disk *and* against
`ServiceContracts.byService`, in both directions, so a service that exists and is not named fails the
build as loudly as a name with no service. `./mill __.openApiCheck` is 2544/2544 over the committed
documents; `./mill checkArchitecture` is 195 modules and 10 rules with no layering violations.

### Item 3 — tested. **MET**, three of four halves re-taken here.

`./scripts/run-tests.sh` is **4,420 cases over 81 modules, all 81 carrying tests** — the property that
matters more than the total is the parenthesis: since wave 7 no module resolves as a test target and
ships nothing. `pnpm test` is 1,936 over 83 files, `pnpm typecheck` exits 0 including the browser suite's
own tree, `lint:boundaries` is 423 files in 11 packages. The a11y sweep was **not** re-run here — it is
three commands (build Storybook, serve `storybook-static` on `:6017`, sweep) and the wave-14 close
measured it clean over **782 stories × 2 themes**, with the load budget line recorded beside it because
that line is part of the measurement.

### Item 4 — documented. **MET AS WRITTEN**, and the tool that proves it is not yet exhaustive.

Item 4 asks for "README, ARCHITECTURE, ADRs for new decisions, an accurate `docs/FEATURE_MATRIX.md`,
and an overview a newcomer can read". All five exist and, measured against the tree at this close, all
five say what is true. `./scripts/feature-matrix-check.sh` exits 0 at 474 claims over 13 sections, and
that run now compares five kinds of documented fact rather than one: a figure against the thing that
produces it, a sentence about a service against `services/` and the gateway's contract map, a quotation
against the file it quotes, the roster of swept blocks against the markers on disk, and a documented
listing against `git ls-files` in both directions.

**That is the honest verdict and it should not be dressed up in either direction.** The documentation
is accurate; the checker is not exhaustive, and its remaining blind spots are known, named, and two of
them were measured in this session:

* **`listing_is_name` does not recognise an extensionless top-level file.** Measured here: adding
  `├── NOTICE  CODEOWNERS  Makefile` to `ARCHITECTURE.md` §16's fenced tree leaves the run at
  **474 claims checked, all true, exit 0**, while `├── benchmarks/` in the same place is **1
  disagreement, exit 1**. `ARCHITECTURE.md` restored from saved bytes, `md5sum`
  `82871799098e0937f8a356284c29f850` on both sides. §16 itself draws five real names of that shape, so
  the rule is silently unenforced on entries the document already carries — and `├── screens` *without*
  its slash, the defect the whole claim kind was built for, is one character short of accepted.
* **`adr_files` is built with `find`, so the gate cannot see an untracked ADR.** Measured here:
  `ls docs/adr/ADR-*.md | wc -l` is **58**, `git ls-files 'docs/adr/ADR-*.md' | wc -l` is **57**,
  `DECISIONS.md` carries **58** rows, and the checker prints *58 rows over 58 ADRs* and exits 0. The
  untracked file is `docs/adr/ADR-058-design-captures-are-tracked.md`, and `DECISIONS.md:65` links it.
  This is house rule 27 — *derive a fact about the repository with `git ls-files`, never with `ls` or
  `find`* — violated inside section 5 of the script that enforces house rule 27. **Whoever commits this
  work must `git add` ADR-058 with it**, or a clone gets a decisions index pointing at a file it does
  not have.
* **One published property of `docs/frontend/README.md` is a human's one-time sweep, not a check.**
  Its banner asserts that every path the page names is resolved against `git ls-files`; nothing
  re-resolves them, and three component names in its *"names the file that draws it"* table resolve to
  nothing (§5).

So: item 4 is met as written, the documents item 4 names are accurate, and the sentence a reader should
carry away is that **a green run means every claim the readers can read is true — not that every
sentence in those documents is checked.** That distinction is itself published, in `README.md`'s
*Editing the checked blocks above* and in §5 of the newcomer's overview, which is the right place for
it.

### Item 5 — one command brings the whole product up. **MET**, cited rather than re-taken.

`deployment/quickstart/quickstart.sh` is that command. It was not run in this session (it builds images
and starts containers). At the wave-14 close it exited 0 with *"KUI is running:
http://localhost:8090/ui/"*, `connect-seed` exited 0, `down` removed everything, and
`./deployment/compose/smoke.sh` **PASSED** over the eleven-container distributed stack with fault
isolation and recovery.

---

## 3. What the mechanisms measured

This is the section worth more than the product, because the product is one Kafka console and the
measurements are about how work of this shape actually behaves. Every number below is from a
retrospective in `ROADMAP.md`; the sampling discipline that produced them is the same one applied here,
and two of the claims were re-verified in this session rather than trusted — those are marked.

**1. Assigning each packet exactly one named ungated rule to close worked every time it was tried.**
Wave 4 introduced it after three waves of telling packets to "write tests that can fail" produced
nothing measurable. The record published at the wave-13 close is **57 of 57** owned rules closed across
thirteen waves — wave 14 published no update to the streak — each re-applied by
an independent verifier who watched the named case go red and then restored the tree from saved bytes.
The denominator moved exactly once, in wave 13, when a packet that added no mechanism correctly shipped
no owned rule and its verifier recorded `ownedRuleGenuinelyClosed: false` *"by construction, not by
failure"*. It is the only mechanism in this project with a perfect record, and the reason it works is
that it names a *seam* rather than a behaviour: the packet that owns the file is also the packet that
can see whether the rule it just wrote has anything holding it.

**2. Asking a builder to disclose its own ungated rules never worked, and it is not a discipline
problem.** House rule 5 required every packet to report a mutation that stayed green. The disclosure
rate ran **11% → 18% → 20% → 14%** across waves 4 to 7 under instruction that got more explicit each
time, and wave 7 — the first wave in which every builder was told in its own plan that an adversary
would sweep its frozen code afterwards — is the **14%**. Pre-announcing the audit made it worse. From
wave 8 the rate stopped being computable and started being flat: every building packet disclosed
exactly the one its brief demanded and never more, while its verifier found between three and nine
further holes in the same code. Twelve waves, no movement, and never once improved by being measured.
The explanation is structural rather than moral: **a packet mutates the thing it just wrote and finds
the case it just wrote, because the two were authored to one understanding.** The correct response is
not a thirteenth instruction. It is to budget the verifier as a permanent role and stop treating the
gap as a defect in the packets.

**3. Adversarial packets — agents that mutate code they did not write — found several times what
verification passes found, and closed most of what they found.** Wave 5 introduced them at 3:1. Three
adversarial packets scored 162 mutations, found 72 ungated rules and **closed 65**; wave 6's three
scored 130, found 75 and closed **all 75**; wave 7's scored 113, found 51, closed 44; wave 8's scored
94, found 45, closed 42. Over the same waves, nine or ten verification passes per wave found 33, 46 and
44 holes respectively and closed **zero** — not from laziness but because a verifier runs while the
packet it verifies still owns that test tree. Per packet the difference is stark: an adversary closed
14–25 rules, a building packet opened 3.5–4.6, and a verification pass closed none. The costing that
settled it is worth keeping: the three adversarial packets cost 25% of wave 5's capacity and produced
two-thirds of its gating.

**4. House rule 18 — a verification pass writes a FILE, which the closer reads — turned a closer with
zero overlap into one that converts most of what it is handed.** Wave 7's closer was written to consume
verification reports, never received them, hunted blind, and produced eleven findings with **zero
overlap** with the forty-four its verifiers had already made. The same code held at least 55 ungated
rules and two methods found disjoint sets. Wave 8 wrote the reports into the tree; the closer landed
**17 of 17** filed findings and stopped only because the list ran out. The series since, all from
filed reports: **11 of 40 (wave 9), 15 of 25 (wave 10), 16 of 18 (wave 11), 13 of 13 (wave 12), 11 of
22 (wave 13), 15 of 27 plus two halves (wave 14)**. Two secondary findings came with it. First, the
file must be a pass's *first* act, written as a stub and filled as mutations run — wave 8 lost seven of
ten files to interruptions because the file was the last act. Second, **when the conversion rate fell
it was partition, never capacity**: waves 13 and 14 both left ten or eleven rows unclosed and eight to
nine of them were blocked on a document or a script the closer did not own, while it closed everything
inside its ownership. The measurable artefact of the rule is on disk: `docs/plan/verification/` holds
38 files — 3 for wave 8, 6 for wave 9, 8 for wave 10, 7 for wave 11, 6 for wave 12, 4 for wave 13 and
4 for wave 14.

**5. A file-level ownership partition does not partition the EDGES between files, nor the SHAPES that
unowned files assert. Both cost a wave.** `services/alerts` reached a full integration pass complete,
tested, imaged, documented — and unroutable, because the one line joining it to the gateway
(`alerts.contract.jvm` on `services.gateway.api`'s `moduleDeps`) was in no packet's `Owns`. Two packets
each did their half correctly; the half that was nobody's was the edge, and no gate in the repository
can see an edge with no owner. The other half of the lesson is older: wave 2 had a disjoint partition
and collided seven times, every collision in a file *no packet owned* — shared golden documents,
hard-coded endpoint rosters in test suites, a hand-written interface mirroring a wire shape another
packet widened. The rules that came out of it are cheap and they held: a service that ships a stream
ships its gateway relay in the same packet as its routing; **one packet owns the DTO, the decoder and
the golden between them, and the binding case decodes the encoder's own output** (house rule 12, the
only prose-contract replacement that ever worked — two waves of stating a wire in prose on both sides
produced two mismatched wires and a card that lied about its source).

**6. Roughly 29% of load-bearing rules were ungated in code nobody was editing when it was first
measured, and the number rose with the quality of the hunt rather than falling.** Wave 4's census
mutated 89 rules at random across `libs/`, five untouched services, the gateway, the kernel and three
feature packages: 26 survived, **29%**. Wave 5's adversaries chose targets by reading comments for *"a
rule with a paragraph defending it and no test"* and found **46%**. Wave 6 aimed at composition roots,
teardown paths, mapping code whose only consumer is a route, and modules declaring a test module with
no test source, and found **58%**. That is not the code degrading — it is three samples by three
methods, and the trend is a property of the hunt. The later series is the same finding read the other
way: 42.5% and 32% (wave 8), 70% (wave 9), 83% on never-swept gateway trees (wave 10), then **26%**
(wave 11), **12.5%** (wave 12) and 19.4% (wave 13) as the sweeps caught up with the code. Wave 12's
fall is the most informative: four services that had *never* been swept returned 3 of 36, and
`services/alerts` returned 0 of 12, so *"never swept"* had stopped predicting *"ungated"* — and all
three of its findings were a new shape, **a guard tested one notch coarser than it is written** rather
than a guard with no test at all. The instruction that followed (*"mutate strictness, not presence"*)
moved the next wave's rate back up and every one of its findings was that shape. **A hunting heuristic
has a half-life, and the measurement that matters is which clue is still paying.**

**Two of the above were re-verified here rather than trusted.** A closure filed by wave 14's closer was
re-applied to the shipped script — `listing_is_name`'s extension arm replaced by `return 1` — and the
fixture that was written to catch it went red with the token printed (*"`listing_is_name` called
[.npmrc] the names of …"*), 1 disagreement over 474 claims, exit 1; the script was restored from a byte
copy, `md5sum e48cdddaec70ddab3e0dcad4e06b26b5` on both sides. And the gate's own headline prediction
still holds in both directions, §4.

---

## 4. The documentation gate's own story

One gate, `./scripts/feature-matrix-check.sh`, went from 49 claims (wave 3) to **474 over 13 sections**
(measured here). It grew because five waves each found a *kind of documented fact that nothing in this
repository read*, and the five are strictly narrowing:

1. **A stale figure.** A count in prose disagreeing with the thing it counts. Closed first, and for
   four waves it was the only thing compared — which is why a repository that failed the build on a
   figure printed *"404 claims checked, all true"* over a README whose headline said *"milestones 0 to
   5"* and *"no Kafka Connect, no ksqlDB"* against a tree that shipped both.
2. **A false sentence.** Wave 10 built a claim kind for sentences and it did not land; the exact
   sentence above, appended *inside* the new checked block, left the run green. Wave 11 made it red,
   and then found the gate read one grammatical mood: *"Kafka Connect remains unimplemented"* was still
   accepted where *"neither is built"* was refused.
3. **A quotation of a file that no longer says what is quoted.** Wave 11's own repair scoped a sentence
   in `docs/operations/masking.md` to one masking kind, and two of the documents item 4 names went on
   quoting the old sentence as a live defect. Nothing had ever compared a quotation of one file against
   that file. Wave 12 made a quotation inside a checked block a `grep`, not a parser.
4. **The roster of what gets swept.** Wave 12's own new checked region in `TECH_DEBT.md` was invisible
   to wave 12's own quotation reader, because the list of blocks the reader walked was hand-written and
   twelve names long while the tree carried fifteen markers. Wave 13 replaced the roster with a
   derivation from the markers on disk.
5. **A documented listing compared against `git ls-files`.** Wave 13 rewrote `ARCHITECTURE.md` §16 —
   the section whose entire subject is directories that are not on disk — by walking the working tree
   with `ls`, which cannot tell a tracked directory from an ignored one, and added a fifth phantom to
   the four it removed. Wave 14 built the listing claim, in both directions.

**Each class was found by a gate the previous wave built, and the last two were introduced by the
previous wave's own repair.** That pattern is the most useful thing in this report. It is not
whack-a-mole: each class is strictly smaller than the one before, the instrument that finds the next
defect is the one just built, and the defect is most often created by the act of building it — because
the only person looking closely enough at a document to break it in a new way is the person repairing
it. The corollary is house rule 26 and house rule 27 together: **every gate whose subjects are derived
from the filesystem has survived repeated mutation; every hand-written list of subjects has eventually
been found missing one** — and a fact about the repository is derived with `git ls-files`, never with
`ls` or `find`, because the two answer different questions and only one of them is true in a clone.

Both of the last two waves' acceptance predictions are still red on the shipped bytes, re-confirmed in
this session: a false quotation inside `TECH_DEBT.md`'s `debt-register` block, and a phantom
`├── benchmarks/` in `ARCHITECTURE.md` §16, are each **1 disagreement over 474 compared claims, exit
1**. House rule 23 — *a wave plan's "if this lands, the item becomes a gate" is a prediction, and the
closer tests it, and its inverse* — is why that is known rather than believed.

---

## 5. What is honestly still weak

Nothing in this list is red. That is precisely why it is a list: each of these is green today and would
mislead somebody tomorrow.

**In the gates themselves.**

1. **`listing_is_name` cannot see an extensionless top-level file.** Measured here (§2): `NOTICE`,
   `CODEOWNERS` and `Makefile` drawn in `ARCHITECTURE.md` §16's tree are accepted as real names, and so
   is `screens` without its trailing slash. The obstacle is real and was filed rather than hidden: §16
   separates its two columns with eight spaces on one line and one space on another, so no purely
   lexical rule can tell `NOTICE` from `the`. **The close is the document's shape and the rule
   together**, not the rule alone.
2. **`adr_files` is built with `find docs/adr`.** Measured here: 58 files on disk, **57 tracked**, 58
   rows in `DECISIONS.md`, and the gate prints *58 rows over 58 ADRs* and exits 0.
   `docs/adr/ADR-058-design-captures-are-tracked.md` is untracked and `DECISIONS.md:65` links it; it
   must be added in the same commit as this report.
3. **`docs/frontend/README.md` promises a property it does not have.** Its banner asserts that every
   path the page names is resolved against `git ls-files`; it is a one-time manual sweep. Three
   component names in its *"names the file that draws it"* table — `Sidebar`, `CapabilityBanner`,
   `FeatureFallbackPanel` — return **0** from `git ls-files | grep -ic`, measured here, under a
   sentence promising a wrong pixel can be traced to a line without a search. The document is outside
   the five item 4 names, which is why item 4 is met and this is still wrong.
4. **Item 1's denominator is a hand roster.** The `M01`…`M23` screen index is reconciled with the
   browser suite by a human matching case titles to screen ids, once per close, six closes running. It
   is the one remaining subject of a definition-of-done item that nothing derives.

**In the build and the deployment.**

5. **`./mill deployment.docker.__.build` reports SUCCESS from cache and will not notice that you
   deleted the image.** Recorded at the wave-14 integration after it cost an hour; not re-measured
   here, because measuring it means building images. A run that trusts that SUCCESS drives whatever the
   daemon happens to hold — which is exactly how the eleventh service's screens were rendered by a
   fourteen-hour-old bundle with every gate green. **`./mill clean deployment.docker` first, every time
   you are about to record a figure from a container.**
6. **`deployment/quickstart/seed/connect-seed.sh` had a silent `set -euo pipefail` exit**, and the
   class it belongs to is worth more than the instance. `grep` exits 1 when it matches nothing, which
   was the *normal* first poll; `pipefail` carried that out of a command substitution and `set -e`
   killed the script before its own `die()`, before the timeout message, before anything was printed.
   It survived only on a warm machine. **Fixed and gated in wave 13** and verified here: the script
   carries four `{ … || true; }` wrappings and `libs/config/test/src/kui/config/ShippedScriptsSuite.scala`
   walks `deployment/` for `*.sh` — a derived roster, not a written one. The residual risk is scope: a
   script of that shape outside `deployment/` is read by nothing.
7. **`.github/workflows/**` is exercised almost entirely by nothing local**, and `ci.yml:301` derives
   the frontend package roster with `ls` on the runner's working tree (TD-057) — house rule 27 broken
   in CI itself.

**From `TECH_DEBT.md`, which has 36 open rows.** The ones a reader should actually know about:

| Row | Why it matters to somebody running this |
| --- | --- |
| **TD-023** | The standing register of rules no test can break. It is a *class*, not a list, and it is the reason the adversarial packets exist. It does not close. |
| **TD-024** | `Section[A]` is documented with `Schema.any`, so 25 named properties across the aggregated responses are typed `unknown` in `frontend/packages/api/src/schema.d.ts` and the browser hand-writes those payload shapes. None of the five metrics payloads has closed. This is the one place the "two halves are one contract" promise is weakest. |
| **TD-003** | Sessions are in memory: **one gateway replica only**. A second replica silently loses sign-ins. |
| **TD-010** | Key-store bytes travel inline in `ClusterProfile` over the inter-service channel. |
| **TD-001 / TD-002** | A release-candidate Chimney and an `-alpha` OpenTelemetry Prometheus exporter in production modules. |
| **TD-036** | `build.mill` and `mill-build/build.mill` are outside **both** style gates, because a Mill build script is not a module of the build it defines — `wc -l` on the two answers **4,428** here, against the 4,374 the row records. The largest Scala in the repository is the least linted. |
| **TD-051** | Prose under `deployment/**` states measured figures and no gate reads any of it; the quickstart README has published one consumer-lag figure wrongly five waves running. |
| **TD-057 / TD-058** | `ls` in CI for a repository fact; the repository-root walk copied fourteen times under a comment that says four. |

**And one that is a design decision rather than debt, stated because it is the one that can hurt
somebody.** `kui.auth.type` defaults to `disabled`. Sign-in, sessions, CSRF and role-based
authorization at the edge are all built and all off until configured, so an unconfigured deployment
lets anyone who can reach the port delete topics. `README.md` says this in its banner; it is repeated
here because a closing report that omitted it would be the same kind of document this project spent
five waves removing.

---

## 6. What a newcomer should do first

```
deployment/quickstart/quickstart.sh
```

One command, Docker the only prerequisite. It starts a single-node Kafka 4.3.1 in KRaft mode, waits
until the broker can genuinely serve metadata rather than until the container has started, seeds
topics, JSON records, a consumer group that is behind, a schema registry, a Connect worker with a
connector on it and a ksqlDB server, starts KUI pointed at all of it, and prints
`http://localhost:8090/ui/`. `quickstart.sh down` removes everything, volumes included. If you have run
KUI before, build the image first (`./mill deployment.docker.allinone.docker.build`) — the script
reuses whatever `kui-allinone` image is on the machine.

**Then read, in this order:** `docs/overview/README.md` — the one document written for somebody who has
never opened this repository, covering the eleven services, the eight feature packages, the two
deployment shapes and the gates. Then `ARCHITECTURE.md` for the module layout, and `docs/adr/` for why
any particular thing is the way it is; `DECISIONS.md` is the index.

**Where the screens are.** `screens/` holds the 23 design captures (tracked, ADR-058), the product
itself is at `/ui/` on the quickstart, and the browser suite that drives every one of those screens is
`frontend/e2e/` — twelve spec files, run with `pnpm -C frontend e2e` against a quickstart built from
the tree.

**Where the plan lived.** `docs/plan/` — [`ROADMAP.md`](ROADMAP.md) is the milestone order, the
definition of done and a retrospective per wave (the retrospectives are the part worth reading);
[`README.md`](README.md) explains how the directory worked; `verification/` holds the 38 filed
verification reports, which are the raw material behind §3. Wave files were deleted at each close by
design — a plan directory that accumulates finished waves becomes an archive somebody has to read
before they can find the current one.

**How to run each gate**, one at a time, because two of the heavy ones together have exhausted a
developer machine and a killed subprocess reads as a failure that is not one:

| Gate | Command |
| --- | --- |
| Compilation, `-Werror` everywhere | `./mill __.compile` |
| Every Scala suite | `./scripts/run-tests.sh` (not `./mill __.test`; and never `./mill a.test b.test`, which runs **zero** tests and exits 0 — the second word is a MUnit name filter) |
| ADR-041 layering | `./mill checkArchitecture` |
| Formatting, lint | `./mill __.checkFormat` then `./mill __.fix --check` |
| Committed OpenAPI documents | `./mill __.openApiCheck` |
| Every count and service sentence this repository publishes about itself | `./scripts/feature-matrix-check.sh` |
| Component and unit suites, types, boundaries | `pnpm -C frontend test`, `pnpm typecheck`, `pnpm lint:boundaries` |
| Accessibility | three commands: `pnpm -C frontend build-storybook`, serve `storybook-static` on `:6017`, then `pnpm -C frontend a11y` |
| The browser suite | `deployment/quickstart/quickstart.sh` with images built from the tree, then `pnpm -C frontend e2e` |
| The distributed stack surviving a service dying | `./deployment/compose/smoke.sh` |

`pnpm` is not on the default PATH in a non-login shell; it is at `~/.local/share/pnpm/bin/pnpm`, and
`npx --yes pnpm@11.25.0 <script>` works from `frontend/`.

---

**One closing observation, since this document is the last thing the plan produces.** The thing that
made this build measurable was never a rule about effort. It was that every criterion had to be a
command somebody else could run, and that the commands were repeatedly found to run without testing
their claim — an `a11y` line that needs three commands, a `jq` path the registry API does not have, a
`grep` whose answer can never be empty, a `./mill a.test b.test` that runs nothing and exits 0, a smoke
test passing over a stack whose entire topics product answered 401. Six exit criteria in `ROADMAP.md`
were corrected for exactly that, some of them three times. **A command that runs is not a command that
tests what it says**, and the only reliable way to tell the difference is to make the thing fail on
purpose and watch.
