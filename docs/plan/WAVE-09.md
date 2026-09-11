# Wave 9 — the closing wave: two screens, one dependency row, one smoke run, and the file that deletes this file

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else. **M9 closed
in wave 8** on a browser suite that is green for the first time in this project's history. This is the
last wave in this plan, and it is deliberately the smallest one since wave 2.

**Why this shape.** Wave 8 was thirteen packets and nineteen agents and was **killed twice**. It lost
no production code to that and it lost most of its evidence: seven of ten verification passes never
wrote a file, the frontend image went fourteen hours stale while the wave's own eleventh feature
package could not be seen in a browser, and two of the gates M10's exit criterion names —
`pnpm -C frontend e2e` and `./deployment/compose/smoke.sh` — were not on the integration list at all.
One of the two was red when it was finally run. **A wave that cannot finish cannot verify itself**, so
this one is **eight packets**, sized to run to the end in one session, and every packet's first act is
to write its verification file as a stub rather than its last act to write it as a report.

**What is actually left.** Four things, each measured at wave 8's integration rather than predicted:

* **Two of the twenty-three screens.** `M08` — the cluster-switch toast — is uncovered, and `M09`'s
  second-cluster clause is unproved, **for the same reason: the quickstart registers one cluster and
  there is nothing to switch to.** `M14` — the plural receipt after a *bulk* topic delete — is partial.
  That is one entry in a YAML file and two browser cases.
* **The dependency-row half of the comparison gate**, which is one line and exits 0. Reproduced at
  wave 8's integration and written out in M10.
* **`./deployment/compose/smoke.sh`**, which has not been run since the eleventh service and the
  eleventh container landed.
* **`docs/plan/` reduced to `README.md` and `ROADMAP.md`**, which is M10's own last bullet and is this
  wave's closing act.

Everything else in this plan is **debt, not definition-of-done**: the masking engine, the scalafix
gate that reaches no test source, and the production seams three adversaries filed. They are here
because they are cheap and because leaving a filed finding unowned is how this project has previously
lost forty-four of them, not because M10 waits on them.

**Parallelism.** `Owns` is disjoint across every packet. Four dependencies are declared, all on a
*stated shape* and none on a diff: W9-03 on W9-01's second registered cluster existing in the
quickstart; W9-03 on W9-04's `data-state` attribute; W9-05 on every other packet having frozen,
because a formatter run over 483 test sources touches files five packets are writing; W9-A2 on the
verification files existing as files.

**The tree you start from is green, and every figure here was re-measured at wave 8's integration on
2026-09-11, by the integrator, one gate at a time.** `./mill __.compile` 8251/8251;
`./scripts/run-tests.sh` **4,254 cases over 81 modules, all 81 carrying tests**;
`./mill checkArchitecture` **195 modules, 10 rules**, no layering violations; `./mill __.openApiCheck`
2629/2629 over **65 paths, 76 operations, 160 schemas**; `./mill __.checkFormat` 252/252;
`./mill __.fix --check` 5353/5353; `pnpm -C frontend test` **1,895 over 82 files**;
`pnpm -C frontend typecheck` exit 0; `pnpm -C frontend build` exit 0 with **eight** dynamic feature
chunks; `node frontend/scripts/boundaries.mjs` **424 files in 11 packages**; `pnpm -C frontend a11y`
**790 stories × 2 themes**, no violations; `./scripts/feature-matrix-check.sh` **283 claims over seven
sections, all true**; and `pnpm -C frontend e2e` **101 passed, 4 skipped, 0 failed**. Anything red is
yours.

**Three things are not green, not flaky, and not secret.**

* **`./deployment/compose/smoke.sh` is unmeasured.** Not red — *unrun*, since before the eleventh
  service existed. It is W9-01's first acceptance line and it may well fail; if it does, that failure
  is the most valuable thing this wave finds.
* **The dependency row.** `claim npm-version "" "$version" "${matched:-not in the cell}"` →
  `… "$version" "$version"` is one line, and with it `DEPENDENCY_MATRIX.md` was made to publish `vite`
  at `9.9.9` against a `frontend/package.json` pinning `8.2.2` while the run printed
  `283 claims checked, all true` and exited 0. It is W9-02's owned rule.
* **`docs/adr/ADR-053-alert-events.md:255` publishes `61 paths, 72 operations and 156 component
  schemas`** about documents that are now 65/76/160. W8-09 measured the correction and could not make
  it — the file was another packet's — and that packet had already frozen. **One line, stale for a
  whole wave because ownership outlived the owner.** W9-02 owns it this time.

**The image rule, restated because wave 8 obeyed the old one exactly and still shipped blind.**
`kui-frontend:0.1.0-SNAPSHOT` was built in W8-10's first hour with the image id published as house
rule 19 requires. The frontend build then broke mid-wave on `feature-ksql`'s missing exports, was
repaired by a different packet, and **nobody rebuilt**: the served image carried seven feature chunks
and no `feature-ksql` fourteen hours later, and `ksql.spec.ts` failed two cases at integration with
`getByText("ksqlDB")` finding no element. One `docker build` turned both green with no source change.
House rule 19 now has a second half and it is in the list below.

**`stash@{0}`, said out loud as wave 8's plan asked.** It holds 46 files and 3,256 insertions of six
packets' wave-5 mid-flight work, created by accident, unpoppable without conflicts, and four waves
stale. Its owners carried that work forward; three integrators have now been told to drop it and none
did, correctly, because *a document asserting that data is disposable is not evidence that it is.*
**It is not on this wave's list and no packet may drop it.** It is a deliberate act for a human:
`git stash show --stat stash@{0}`, confirm nothing in it post-dates `8fc0c77`, then drop it. Wave 9 is
the last wave, so if it survives this wave it survives the plan, and that is an acceptable outcome.

**House rules.** The first nineteen are wave 8's and still apply. Two are amended and one is new; each
is a wave-8 finding.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110, per `.scalafmt.conf`). **Do not reformat a file
you are not otherwise changing** — except W9-05, whose whole packet is that reformatting, and which
runs last for exactly that reason.

1. **No new stylesheet files.** `build-tests`' `CssReferencesSuite` requires every stylesheet on disk
   to be named exactly once in `frontend/packages/kernel/styles/index.css`. This wave adds no feature
   package and needs no new stylesheet.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** The Scala mirror
   in `build-tests/src/kui/build/design/Tokens.scala` is owned by nobody.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte at 31 codes.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it, run
   the acceptance suite, record which case went red, revert it from bytes you saved yourself.
5. **Report a mutation that stayed green.** Alongside the red one, apply at least one mutation to a
   rule you did *not* write the test for and record what happened.
6. **No honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely with
   assertions that something is absent, refused or not configured. Wave 8 retired the Connect
   *not-configured* browser case for exactly this reason and the retirement is recorded in M9.
7. **A card may not be drawn from a figure the design did not name.**
8. **`./mill a.test b.test` runs nothing.** The separator is `+`; Mill aborts the rest of a `+` chain
   on the first failure unless `-k` is passed; a fully-qualified suite name as a second argument
   matches nothing and prints SUCCESS; the glob form `'*SomeSuite*'` runs it. Compare your summed case
   total against your own baseline every time.
9. **A root cause is not established until it has been run in both directions.** Wave 8 discharged the
   standing example and it is worth reading as a model: the axe `Test timed out in 5000ms` flake was
   measured at 385/553/610 ms for one case run alone and 898/4478/881/**5541** ms for the same case in
   the full 82-file run, with a sibling at 336/281/**7221** ms — *a case does not get slow, its
   neighbours do* — and the fix is `testTimeout: 20_000` in `frontend/vitest.config.ts` with the
   measurement written above it. **The new standing example is `smoke.sh`,** which nobody has run.
10. **State the scope of a measurement, and expect the next reader to re-run it.**
11. **A comment that names a figure must name a figure something in the tree can check.**
12. **Two sides of one wire are one packet, and the binding case decodes the encoder's own output.**
    The hole wave 7 found in this rule — `frontend/e2e/` — was closed in wave 8 for Connect and ksqlDB;
    `connect.spec.ts` and `ksql.spec.ts` both read the service's golden off disk now. **The residue is
    the *words*:** `connect.spec.ts`'s `wordFor` still transcribes five kernel labels by hand. W9-03
    and W9-04 kill it between them.
13. **A verifier's finding is an owned rule or a case, never a paragraph.**
14. **Adversarial packets run in a `git worktree`**, revert by restoring bytes they saved themselves —
    never `git checkout --`, `git restore --source=HEAD` or `git stash` — and namespace their scratch
    directory.
15. **An edge has an owner.** Whoever owns `build.mill` lands every module-dependency edge this plan
    declares first, before any of its own work. This wave declares none, which is what a closing wave
    should look like.
16. **A stream ships its relay.** `KsqlStreamRoutes` and `AlertsStreamRoutes` both exist and both are
    hand-written because `ContractRouting.derive` re-encodes JSON and cannot carry SSE. No new stream
    this wave.
17. **A claim about your own gate is measured, not asserted.** Wave 8 obeyed this for the first time
    and the result was a published price *lower* than the plan's: W8-09 found the inherited gate cost
    **one** line where three waves had published two, four and five. **If your packet claims a gate is
    now harder to defeat, defeat it, and publish the cheapest attack you found and its cost.**
18. **AMENDED: a verification pass writes its file FIRST, as a stub, and fills it as it goes.** Wave 8
    ran house rule 18 at **three of ten**, not because anybody disagreed with it but because the file
    was the last act of a pass and seven passes were killed. So: the first thing a verification pass
    does is create `docs/plan/verification/W9-<packet>.md` containing the list of mutations it intends
    to apply, one per row, with the columns empty. A row is filled the moment its mutation is run. A
    pass killed halfway then leaves a partial file naming what it did and did not reach, which is
    strictly more than nothing — and nothing is what wave 8's closer was handed seven times.
19. **AMENDED: the stack is rebuilt twice — first hour and last hour — by the same owner.** W8-10
    published its image ids in hour one exactly as instructed and the tree moved out from under them.
    W9-01 therefore builds `kui-allinone` and `kui-frontend` from the tree **in the first hour and
    again after the last building packet freezes**, publishes both pairs of image ids, and **every
    browser criterion in this wave is run against the second pair.** A packet quoting the first pair
    for a browser result has measured an image, not the product.
20. **NEW: a packet that spawns load traps its own kill, and a run that executed nothing is not a
    result.** W8-A2's sixteen busy loops outlived their `kill %1..%16` and forty-nine of them were
    still spinning ninety-seven minutes later at a load average of 62 on 16 cores. The damage was not
    slowness: `./mill __.openApiCheck` failed a *different random subset* of its ten api modules per
    run with `Subprocess failed` and no stderr — indistinguishable from a stale committed document —
    and `pnpm -C frontend test` exited non-zero having executed **zero** test files, with 82
    `[vitest-pool]: Timeout starting forks runner` errors. **A `pnpm test` run whose `Test Files` line
    does not report a number is not a red suite; it is not a measurement.** Trap your kill
    (`trap 'kill $(jobs -p) 2>/dev/null' EXIT`), and check `uptime` before recording any figure.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`. Mill's shared daemon is contended when several
packets run at once and dies with `Worker wire broken, worker likely crashed`; **use
`./mill --no-daemon`** for anything you intend to record a number from.

**Running the a11y sweep** is three commands, not one — build Storybook, serve `storybook-static` on
`:6017`, then sweep. `frontend/storybook-static/` is a **shared output directory**: a failed build
leaves it half-written and the next packet's sweep reads the wreckage. Kill the server you started;
wave 8's integration found three `http-server` processes on `:6017`, `:6018` and `:6099`, two of them
belonging to sessions that had ended.

**Seeding.** One open alert: `diskUsedWarningPercent: 1` in
`deployment/quickstart/kui-quickstart.yaml`, restart `kui-quickstart-kui`, revert the file afterwards
— that opens exactly one `storage` event and is what `shell.spec.ts:127` skips for want of. One SSE
frame: hold `curl -N …/alerts/stream` open and `POST` an acknowledgement three seconds in. One
connector and one ksqlDB stream: `deployment/quickstart/seed/connect-seed.sh` and `ksql-seed.sh`,
which W8-10 shipped and which are why wave 8's browser suite went green.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None is
owned by the packet most likely to break it — that is the point of listing them. If your change makes
one red, it is your change that is unfinished, and the repair goes in the packet that owns the guard,
named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` §6 `guard-fixtures` | Eight fixtures driving the **shipped** `claim`, `close_section`, `header_fact_from_document` and the marked-block refusals over input written for the occasion — because every one of those refusals was green under mutation for a whole wave, a refusal being exercised only by input this repository does not contain. **It covers the marked-document machinery and not the `dependencies` section**, which is precisely the hole | **W9-02**, which must add the ninth fixture without weakening the eight |
| `scripts/feature-matrix-check.sh` + the `<!-- checked: -->` regions | **65 paths, 76 operations, 160 schemas; `X-Kui-Principal` on 59 operations over 48 paths; `X-Csrf-Token` on 26; `If-Match` on 2.** 283 claims over seven sections: self-check 7, rows 18, merged-document 50, milestones 49, adr-index 112, guard-fixtures 10, dependencies 37 over 12 named manifests | **W9-02**; and anybody who adds an ADR or a package manifest |
| `docs/adr/ADR-052`, `ADR-053`, `ADR-054` | Three documents publishing OpenAPI and store figures that **no gate reads**: `grep -c 'checked:'` over all three is **0**. ADR-053:255 has been stale for a wave | **W9-02**, both the correction and the gate |
| `DECISIONS.md` vs `docs/adr/ADR-*.md` | 56 rows over 56 ADRs, machine-compared in both directions | any packet adding an ADR; this wave should add none |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService` with `sed` against the containers the stack runs; a contracted service that is not a container fails the preflight before a container starts. Eleven services now | **W9-01**, and it is unrun |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted-path set, including the alerts feed, the public alerts stream and the ksql stream | **W9-01** |
| `libs/config/test/.../ShippedConfigurationSuite.scala` | A list of shipped configuration files reconciled against disk in both directions, plus `widenedExclusionProbes`. **A second registered cluster in the quickstart YAML adds no file, so this should not move** — if it does, something is wrong with the shape and not with the roster | **W9-01** adds the cluster; **W9-06** adds the masking section |
| `frontend/scripts/bundle-shape.mjs` | Every `frontend/packages/feature-*` package present as a **dynamic** entry in the Vite manifest — **eight** today. Roster read from the filesystem. **It reads `frontend/dist/.vite/manifest.json`, so it is stale until somebody runs `pnpm -C frontend build`** | nobody adds a feature package this wave; **W9-01** re-runs it before the second image build |
| `frontend/packages/kernel/styles/index.css` + `build-tests`' `CssReferencesSuite` | Every stylesheet on disk named exactly once | nobody; house rule 1 forbids it |
| `frontend/vitest.config.ts` | `testTimeout: 20_000`, with the measurement that justifies it in the header. **Lowering it reopens the flake two waves paid to close** | **W9-04** owns the file and must not |
| `frontend/e2e/**` | **101 passed, 4 skipped, 0 failed** against `kui-allinone` `c575438c5bfc` and `kui-frontend` `e9fadeecc330`. The four skips are all *deployment-shaped*: `alerts.spec.ts:268`, `ksql.spec.ts:175`, `shell.spec.ts:127` and `traffic.spec.ts:536` | **W9-03**, which adds three cases and must not add a fifth skip |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `docs/FEATURE_MATRIX.md` | **189 rows; 70 COMPLETE; 178 in scope; 39% delivered**, all inside checked regions. Seven rows moved out of `RESEARCHING` in wave 8 and **none into `COMPLETE`**; `KC-005` and `KC-006` carry sub-capabilities that do not exist | **W9-02** records; every packet that finishes a capability |
| `./mill __.fix --check` and `./mill __.checkFormat` | 5353/5353 and 252/252 — **over `src` only.** `./mill resolve '__.fix'` returns two test-bearing targets in the whole repository and neither is a service's test module: **483 `.scala` files under `test/` are outside both gates**, which is TD-027 | **W9-05**, and it is the only packet allowed to reformat |
| `build-tests/**`, `scripts/run-tests.sh`, `frontend/vite.config.ts`, `frontend/scripts/boundaries.mjs` | Rosters derived from the filesystem or from `./mill resolve __.test`; nothing here needs an edit | nobody |

---

## W9-01 — The stack: a second cluster, eleven containers, and the rebuild that happens last

**Owns**
```
deployment/**
apps/allinone/**
.github/workflows/ci.yml
```

**Contract.** This packet owns the two facts every other packet's browser acceptance depends on, and
it owns the one gate nobody has run since the tenth service. It has **two first-hour obligations and
one last-hour obligation**, and the last one is new because wave 8 proved the first two are not enough.

**Do — the first hour**
1. **Build `kui-allinone` and `kui-frontend` from the tree and publish both image ids in your first
   report.** House rule 19, unchanged. Recreate the quickstart on them and run both seed scripts, so
   the Connect worker carries `quickstart-file-source` and the ksqlDB server carries
   `QUICKSTART_ORDERS` — those two are why wave 8's browser suite went green and a stack without them
   makes six cases skip.
2. **Register a second cluster in `deployment/quickstart/kui-quickstart.yaml`.** This is the whole
   blocker for two of the twenty-three screens and it has been the blocker for two waves. `M08` is the
   cluster-switch toast and no case has ever opened the selector's menu because there is nothing to
   switch to; `M09` is the second cluster's brokers screen; `traffic.spec.ts:536` carries a
   `test.skip(other === undefined, …)` for the same reason. **The design's own capture names it
   `staging-eu-01`** (`SCREENS-V4.md` §1, `M08`: *"Switched to staging-eu-01"*), so use that id and
   that display name — a browser case asserting a toast has to know the word. It may point at the same
   broker: what the screens need is a *second registered profile*, not a second Kafka. Say in your
   report whether it is a second profile over one broker or a second broker, because the next reader
   will otherwise assume the harder one.
3. Announce both of the above to the wave before you do anything else. W9-03 is blocked on item 2.

**Do — the gate nobody has run**
4. **`./deployment/compose/smoke.sh`, three consecutive times, on the distributed eleven-container
   stack**, with the images built from the tree by the derivation CI uses. This has not been run since
   before `services/connect` existed. The preflight scrapes `ServiceContracts.byService` with `sed`
   and refuses to start a container if a contracted service is not one, so the eleventh service is
   exactly the shape it was written to catch. **If it fails, that failure is the most valuable thing
   this wave finds** — do not repair it by relaxing the script; repair the stack, and if the defect is
   in another packet's Scala, file it.
5. `AllInOneWiringSuite`'s startup-log string and mounted-path set now carry the ksql stream. Confirm,
   do not assume.

**Do — the last hour, and this is the new one**
6. **Rebuild both images from the frozen tree and publish the second pair of ids.** Wave 8 obeyed
   house rule 19 to the letter and still shipped an image with seven feature chunks in it while the
   tree built eight, because the frontend build broke after the images were made and was repaired by
   somebody else. Run `pnpm -C frontend build && node frontend/scripts/bundle-shape.mjs` immediately
   before the rebuild and put the chunk count in your report. **Every browser result in this wave is
   quoted against the second pair.**

**Acceptance**
```
docker compose -f deployment/compose/docker-compose.yml up -d --wait
curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready
./deployment/compose/smoke.sh          # three consecutive runs, output quoted
./mill --no-daemon apps.allinone.__.test
curl -s localhost:8080/api/v1/clusters | jq -r '.clusters[].id'    # two ids, not one
pnpm -C frontend build && node frontend/scripts/bundle-shape.mjs   # before the second image build
```
**Mutation line:** delete one contracted service's container from `docker-compose.yml` together with
its `depends_on` and its gateway address, and show the preflight refusing before a container starts.
**And a green one:** report one mutation to the stack that `smoke.sh` does not notice.

---

## W9-02 — The comparison gate's other half, and three ADR figures nothing reads

**Owns**
```
scripts/feature-matrix-check.sh
DEPENDENCY_MATRIX.md
docs/FEATURE_MATRIX.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
docs/adr/ADR-052-metrics-endpoints.md
docs/adr/ADR-053-alert-events.md
docs/adr/ADR-054-connect-endpoints.md
frontend/packages/api/README.md
DECISIONS.md
TECH_DEBT.md
tools/**
```

**Contract.** M10's exit criterion asks the checker to fail *"when any single comparison is weakened
while its claim, its marker and its count all stay standing — demonstrated on the `csrf-operations`
claim **and on a dependency row**"*. Wave 8 met the first half and the measurement is in M10. **The
second half is open and it is one line.** This packet closes it and gates three ADR figures that
nothing reads, and it is the fourth consecutive packet pointed at this script — which is the reason
its acceptance list starts with an attack rather than ending with one.

**Do**
1. **Close the dependency row.** Reproduced at wave 8's integration, on the shipped script:
   ```
   claim npm-version "" "$version" "${matched:-not in the cell}"   ->   ... "$version" "$version"
   ```
   With that one edit, `DEPENDENCY_MATRIX.md` publishing `vite 9.9.9` against a `package.json` pinning
   `8.2.2` leaves the run printing `283 claims checked, all true`, exit 0 — claim, marker, section
   count and the manifest reconciliation all standing. The shape of the fix is already in the file and
   was built for the other half: a **guard fixture** driving the shipped `npm-version` comparison over
   a known-bad pair, and a **second independent read** of `DEPENDENCY_MATRIX.md`'s own rows in the
   shape `audit_document_facts` has for the marked blocks. Do not add a ninth count.
2. **The eight fixtures in §6 cover the marked-document machinery and none of them covers section 3.**
   Read why they exist before you add to them: each closed a refusal that was green under mutation for
   a whole wave, because a refusal is only exercised by input this repository does not contain. Your
   ninth fixture is the same argument applied to the one section that was skipped.
3. **`docs/adr/ADR-053-alert-events.md:255` says `61 paths, 72 operations and 156 component
   schemas`.** It is `65`, `76` and `160`. W8-09 measured that correction and could not make it,
   because the file belonged to a packet that had already frozen — **one line, stale for a whole wave,
   for no reason but ownership.** Correct it.
4. **Then gate all three.** `grep -c 'checked:'` over `ADR-052`, `ADR-053` and `ADR-054` is **0**
   today. The mechanism exists: wrap each figure in a
   `<!-- checked: openapi-totals -- claims: merged-paths, merged-operations, merged-schemas -->` block
   and add the extractor. This is W8-A3's F5 and it is the reason item 3 was possible at all.
5. `TECH_DEBT.md`: **TD-027** — the scalafix gate covers no test source — is W9-05's to close; move the
   row's exit to name that packet. **TD-024** is re-measured and its figure is 25 named `: unknown;`
   properties of 177 lines, 152 being the index signature; leave it open and say why.
6. **File the six wave-8 findings that need a production seam in a tree nobody owns this wave**, one
   row each, with the exact edit and an owner. They are already written out in their reports and none
   of them is a paragraph: `Pbkdf2PasswordHasher.constantTimeEquals` (the recommended edit is to
   **delete** the hand-written loop and call `java.security.MessageDigest.isEqual`, which the JDK
   documents as time-constant and which `PurgeToken.verify` next door already uses for the same
   reason); `CelFilterEngine.warm` (widen to `private[filter]` returning whether the trial
   compile-and-evaluate completed); `KeyStoreMaterializer`'s re-apply of 700 (**no unit case can gate
   it honestly** — it needs an integration check under a hostile umask, which is a deployment
   decision); `InMemoryAlertStore.MaxReadMarkers` (expose the marker cache's `stats`, then assert
   `estimatedSize <= MaxReadMarkers * slack`); `GroupListView.stateCounts` and `notes`, which reach no
   response — *render them on the list document or delete the two fields*; and
   `RegistryCredentials.MinimumLifetime`'s `.filter`, which is **provably dead** — for every input the
   clamp two lines below yields the same value — so one of the two guards is decoration and a reader
   has to solve the algebra to find out which. A finding that ends this wave unclosed is a row with an
   owner or it is not carried.
6. `docs/FEATURE_MATRIX.md`: seven rows are at `TESTING` after wave 8 and none moved to `COMPLETE`.
   With W9-01's second cluster and W9-03's cases landing in this wave, **re-assess `KC-001` and
   `KS-001/002/003` against the row's own wording** and move only what the wording supports. `KC-005`
   and `KC-006` carry sub-capabilities that do not exist; say so in the file rather than moving them.
   And one residue W8-09 disclosed rather than fixed: the MFE key now says nine of twelve packages
   exist, and **`feature-alerts` exists with no row naming it.** Either give it a row or correct the
   key — a roster that omits a package it counts is the same defect the checker exists to catch.

**Acceptance**
```
./scripts/feature-matrix-check.sh                       # exits 0, count published per section
# then, in order, and quoted in the report:
#   (a) reproduce the one-line dependency attack above against the SHIPPED script -> must be RED
#   (b) re-run the csrf one-line attack from M10                                   -> must stay RED
#   (c) apply your own cheapest attack on the finished gate and publish its cost
./mill --no-daemon tools.errorCodes.test
./mill --no-daemon frontend.apiConstants --check
```
**Mutation line:** item 1's own line, applied to the finished script with a false `DEPENDENCY_MATRIX`
row, naming the fixture that goes red. **And house rule 17 has teeth here:** publish the **measured**
cheapest attack on the gate as you leave it. Wave 8's packet found the inherited gate cost one line
where three waves had published two, four and five, and said so. If your number is bigger than the
attack you actually found, write the one you found.

---

## W9-03 — The last two screens, and the words that are still hand-copied

**Owns**
```
frontend/e2e/**
```

**Contract.** The twenty-three-screen mapping was published for the first time in wave 8
(`docs/plan/verification/W8-07.md` §1) and it says **21 covered, 1 partial, 1 uncovered**. This packet
closes the remaining two and removes the last hand-copied wire in the browser suite. **Do not copy the
mapping into another document** — read it, close the gaps, and report the new count with its scope.

**Depends on** W9-01's second registered cluster (items 1 and 2) and W9-04's `data-state` attribute
(item 4). Both are stated shapes; neither is a diff.

**Do**
1. **`M08` — the cluster-switch toast.** *"Switched to staging-eu-01"*. Open the cluster selector's
   menu, choose the second cluster, assert the toast names it and that the frame follows. No case has
   ever opened that menu.
2. **`M09` — the second cluster's brokers screen.** The screen is covered; what is unproved is that
   the frame follows a cluster change into it. One case in `brokers.spec.ts`.
3. **`M14` — the plural receipt.** Select two scratch topics, delete them, assert `.kui-notice`
   carries the count. Single deletion is covered and the toast machinery is proved; this specific
   receipt is not.
4. **`connect.spec.ts:214` asserts nothing on this stack, and the reason is a locator.** `card` is the
   whole panel, which contains both the pill and `connector-tasks`; for `quickstart-file-source`
   (RUNNING, 1/1) the panel text carries *"1 of 1 tasks running."*, so
   `toContainText(wordFor("RUNNING"))` is satisfied by the **task sentence** whatever the pill says. A
   regression drawing an unreported state as `running`, or drawing no pill at all, passes it today.
   Scope it to the pill and assert `toHaveText`.
5. **Delete `wordFor`.** `connect.spec.ts:257` transcribes `connectorChip`'s five labels by hand, in a
   file whose whole thesis after W8-04's rewrite is that copies are read off disk. It is correct today
   — all five were compared in wave 8 — and that is not a gate. W9-04 gives the pill a stable
   `data-state`; compare the drawn state to the wire's state word and transcribe nothing.
6. **The four skips are all deployment-shaped and you may not add a fifth.** `alerts.spec.ts:268`,
   `ksql.spec.ts:175` and `traffic.spec.ts:536` assert what a deployment *without* a service draws;
   `shell.spec.ts:127` needs a seeded open alert (`diskUsedWarningPercent: 1`, restart, revert). If
   you can un-skip the fourth with the seed, do; if not, say so. **The not-configured browser path is
   covered by no case in any deployment and M9 records that as deliberate** — if you want it back it
   is a second Playwright project against a stack with those addresses unset, which is a deployment
   decision and not a `test.skip`.

**Acceptance**
```
pnpm -C frontend e2e                     # against W9-01's SECOND image pair, ids quoted
pnpm -C frontend e2e e2e/connect.spec.ts # the rewritten pill assertion, alone
```
Report the twenty-three-screen count with its scope, in the shape W8-07 used. **Mutation line:** break
`ConnectorCard`'s pill state in the served bundle and show item 4's rewritten assertion going red
where today's passes. **And a green one:** report one product change the browser suite does not notice.

---

## W9-04 — The kernel's pill, the shell's crumb table, and two structural holes a case cannot close

**Owns**
```
frontend/packages/kernel/**
frontend/packages/shell/src/App.tsx
frontend/packages/shell/src/app.render.test.tsx
frontend/packages/shell/src/shell.test.tsx
frontend/vitest.config.ts
```

**Contract.** Three filed findings whose *case* halves wave 8's closer landed and whose *structural*
halves are production edits it did not own. Each is the same shape: a case pins today's behaviour, and
a type or an attribute would make tomorrow's mistake impossible. **Prefer the type.**

**Do**
1. **Give `ConnectorCard`'s `StatusPill` a stable `data-state` attribute** carrying the wire's state
   word, so a browser case can compare the drawn state to the API's without knowing any label. W8-A3
   closed the cheap half — `packages/kernel/src/components/connect.test.tsx` now pins all five
   `connectorChip` labels by literal, so a rename reddens in the language that owns the words, in a
   second, rather than in a Playwright run. This is the other half, and W9-03 item 5 consumes it.
2. **`App.tsx:1089`'s `LABELS` is typed `Record<string, string>`, so a missing feature id yields no
   crumb at all** — and the trail that results is the *dashboard's* trail over a different page, which
   is a correct-looking answer for the wrong screen rather than a blank an eye would catch. The ninth
   feature will forget it the way the eighth did. Type it `Record<FeatureId | "settings" | "overview",
   string>` and make the omission a compile error, the way `landingFor`'s exhaustive switch made
   `ksql` impossible to forget.
3. **`frontend/vitest.config.ts` is yours and the answer in it is already right.** `testTimeout:
   20_000` closes a flake two waves paid for, with the measurement in the header: the same axe case at
   385/553/610 ms alone and 898/4478/881/**5541** ms in company, a sibling at 336/281/**7221** ms.
   **Do not lower it and do not lower the worker count instead** — the header argues why, and the
   argument is that a wall-clock deadline is the wrong instrument for CPU-bound work on a shared
   machine. If you disagree, measure it in both directions per house rule 9 before you touch it.
4. `Alerts.lastReadAt` and `Alerts.connection` were callerless at wave 8's freeze and their fate was
   supposed to be a recorded decision rather than an assumption. **Decide it and record it.** Do not
   delete `Alerts.unreadCount`: the drawer badge calls it.

**Acceptance**
```
pnpm -C frontend test packages/kernel packages/shell
pnpm -C frontend typecheck
pnpm -C frontend build-storybook && (serve :6017) && pnpm -C frontend a11y
```
**Mutation line:** delete one key from `LABELS` and show `typecheck` failing where today it is silent.
**And a green one:** report one kernel change `pnpm -C frontend test` does not notice.

---

## W9-05 — The two gates that reach no test source  ·  RUNS LAST

**Owns**
```
build.mill
.scalafix.conf
.scalafmt.conf
```

**Contract.** TD-027, filed by W8-09 and confirmed by both adversaries independently. `./mill resolve
'__.fix'` returns **two** test-bearing targets in the whole repository — `build-tests.fix` and
`libs.testkit.jvm.fix` — and neither is a service's test module. `./mill __.checkFormat` checks `src`
and not `test/src`. **483 `.scala` files across 81 test modules are outside both gates**, which every
acceptance list in three waves has named as if it covered them. Wave 8 added 31 test files hand-held
to the column rule by their authors, and both adversaries said so in their reports.

**This packet runs last, after every other packet has frozen**, and that sequencing is the whole of
why it is a separate packet: a formatter run over 483 files touches trees five packets are writing.
It is the only ownership in this plan that moves during the wave, and it is the only packet permitted
to reformat a file it is not otherwise changing.

**Do**
1. Mix `ScalafixModule` into `KuiTests` (the seam W8-09 named) with semanticdb for test compilation,
   so `__.fix` resolves a `.test.fix` target per module, and widen `checkFormat` to `test/src`.
2. **Run the repair as its own commit, separate from the build change**, so the next reader can see
   the gate arrive without 483 files of noise on top of it. Report the number of files the formatter
   actually moved; if it is large, that is the measurement, not a problem.
3. **A rule that fires on 483 files nobody has ever run it against will find things that are not
   formatting.** If scalafix wants a semantic change — an unused import that is actually load-bearing
   under `-Werror`, a rule that rewrites a test's meaning — **stop and file it**; do not take the
   rewrite because the tool offered it.
4. Close TD-027 in the row W9-02 re-pointed at you, with the resolved target count quoted.

**Acceptance**
```
./mill --no-daemon resolve '__.fix' | grep -c '\.test\.fix'   # was 0, quote what it is now
./mill --no-daemon __.fix --check
./mill --no-daemon __.checkFormat
./scripts/run-tests.sh                                         # 4,254 cases or more, unchanged pass
```
**Mutation line:** mis-format one line in one test source and show `__.checkFormat` failing where
today it is silent. **And a green one:** report one thing scalafix still does not see.

---

## W9-06 — The masking engine, wired

**Owns**
```
libs/security-core/**
libs/config/**
services/message/**
```

**Contract.** `libs/security-core`'s `MaskingEngine` is an orphan: **DM-001 is a P1 `IMPLEMENTING` row
in `docs/FEATURE_MATRIX.md:361` behind ADR-023**, the engine has two live anchors outside its own
module — `StoreSection.Masking` in `libs/config/.../StoreKey.scala:14` with `StoreKeySuite` asserting
it, and `MetricNames.MaskingApplied` = `kui.masking.applied` `{cluster, topic, target}` in
`libs/observability`, declared and unused since wave 1 — and **no production caller anywhere.**
W8-A2 took the decision deliberately and recorded it: **wire it, do not delete it.** Deleting would
retire a roadmap row unilaterally from a packet owning neither the roadmap nor the consumer. This
packet is that wiring, and it is the only feature work in a closing wave.

**It starts from a hardened engine.** W8-A2 closed two rules in it from its own tree before handing it
on, so you cannot wire it against a silently wrong header scope or a number that comes back unmasked:
a `topicKeysPattern` rule does **not** reach headers (a header belongs to the record, not to its key or
its value), and a masked scalar is **always a JSON string** (masking a number and keeping it a number
either changes its magnitude or fails to hide it).

**Do**
1. **`libs/config`** — add `MaskingConfig` under `kui.clusters[].masking[]` with `kind`, `fields`,
   `fieldsNamePattern`, `topicKeysPattern` and `topicValuesPattern`. **Refuse an uncompilable regex at
   start-up**: `MaskingEngine`'s own scaladoc already says that is where it is caught, so this is
   honouring a promise the code has been making. Add the file to `ShippedConfigurationSuite`. Every
   field defaults, so an existing YAML still boots unchanged — that is the standing rule for every
   config section this project has added and it has never been broken.
2. **`services/message`** — in the browse use case's value rendering, **after deserialization and
   before any DTO leaves the service**, call `MaskingEngine.applies` → `maskJson`/`maskText` and
   `maskHeaders`, **including on `originalValue`** (ADR-023 is explicit), and increment
   `MetricNames.MaskingApplied`. **Not on produce**: `ProduceUseCase.scala:42` already states that rule
   and it is right — masking a value on the way in destroys it.
3. **What is yours out of wave 8's filed seams is exactly this one**: `MetricNames.MaskingApplied`
   finally has a writer and the masking rules finally have a caller. The other three production seams
   W8-A1 filed — `Pbkdf2PasswordHasher`'s constant-time loop, `CelFilterEngine.warm`, and
   `KeyStoreMaterializer`'s umask re-apply — are in `services/identity`, `libs/filter` and
   `libs/kafka-auth`, **none of which is owned by anybody this wave**. They go to `TECH_DEBT.md` with
   owners, through W9-02, and the exact edits are already written out in W8-A1's report.
4. Move `DM-001` only as far as its wording supports, and tell W9-02 what you moved so it lands in
   `docs/FEATURE_MATRIX.md` rather than in two places.

**Acceptance**
```
./mill --no-daemon -k libs.securityCore.jvm.test + libs.config.test + services.message.__.test
./mill --no-daemon checkArchitecture
./mill --no-daemon __.openApiCheck
./scripts/run-tests.sh
```
**Mutation line:** make one masking rule's scope reach a target it must not, and name the case that
goes red. **And a green one:** report one mutation in `libs/security-core` that stays green — and read
W8-A2's note first: `Permission.covers`' `case _ => false` was one character from a fail-open and
inverting it reddened **not one case** in eleven services and thirteen libs until a case was written
for it.

---

## W9-A1 — Adversarial: the three surfaces nobody has ever mutated

**Owns** — production and test, whole trees, because the seams may be production edits
```
services/ksql/**
services/gateway/**
frontend/packages/feature-ksql/**
```

**Contract.** These are wave 8's largest new surfaces and **not one of them has ever been mutated by
anybody.** `services/ksql` is 41 Scala sources across six ADR-041 layers. `services/gateway` grew
`KsqlStreamRoutes`, a hand-written SSE relay that `ContractRouting.derive` cannot generate — the exact
category of code the ninth service shipped without and the tenth shipped with. `feature-ksql` is 20
files and roughly 3,900 lines. **All three packets that built them were killed before filing a
verification file**, so this is not a second sweep of swept code: it is the first sweep of the newest
code in the repository.

**Your output is a filed file, not a set of closures.** This is the change wave 8's evidence argues
for: a hunter spends three to four mutations to find one hole, a closer spends one to close it, and
the binding resource is filed findings rather than closing capacity. So **hunt wide and file
everything**, in W9-A2's shape — file, exact mutation, suite command, the case that would close it —
and close only what is cheap enough not to slow the hunt. W9-A2 closes the rest.

**Method, stated because both of wave 8's hunters proved it.**

* **A1's clue fired eleven times out of seventeen and was right every time**, and it is not spent:
  *a rule ungated in a file that already has a suite is ungated because the fixture could not express
  the failing input, or because no assertion ever read that field.* Grep for the shape, not the rule:
  a hard-coded `Nil`, a fixture whose every input is already sorted, a stub that drops the flag it was
  handed, a local helper that can only produce one side of a boundary. Wave 8's sharpest instance:
  `MutationAuditSuite`'s `toKui` could only produce 4xx codes, so `httpStatus < 500` had never been
  asked about a status on the other side of its own boundary and the guard's whole `Left(error)` arm
  had **no case at all.**
* **Argue equivalent mutants down rather than counting them.** A2 found three and excluded them from
  its own denominator with the algebra shown. A hole you cannot state as a failing input is not a hole.
* **A rule gated only at a distance is gated, and it is still worth a local case.**
  `PrincipalInterceptor.isPrincipalHeader` inverted left all 258 cases of its own module green and
  reddened only when three services' route suites ran.
* **A green mutation in a stream relay is the finding to look hardest for.** House rule 16's criterion
  is `curl -N` through the gateway with the frame quoted, and a relay that silently drops a terminal
  frame passes every JSON suite in the repository.

**Acceptance**
```
./mill --no-daemon -k services.ksql.__.test + services.gateway.__.test
pnpm -C frontend test packages/feature-ksql
./mill --no-daemon checkArchitecture
./mill --no-daemon __.checkFormat
./scripts/run-tests.sh
```
Report your rate with its denominator — mutations applied and screened, not rules read — and say which
modules you would and would **not** declare finished being hunted, with the evidence for each. House
rule 20: trap your kill, and check `uptime` before recording a figure.

---

## W9-A2 — Adversarial: the closer, and the last verification the plan will ask for

**Owns**
```
docs/plan/verification/**
the building packets' unit and component test trees, AFTER they freeze
```

**Contract.** Wave 8 ran this role properly for the first time and the answer was unambiguous:
**every one of its seventeen closures came from a filed finding, it never hunted once, and it stopped
because the filed list ran out.** Per mutation applied it converted 17 of 17, against 42.5% and 32%
for the two hunters and 25% for wave 7's blind closer. The pre-commitment asked for thirty and the
wave produced twenty-three findings from three surviving verification passes, so **the number thirty
measured house rule 18's compliance rate and not the closer.** The role is kept and made permanent.

**The re-stated pre-commitment, and it is about the input.** *A closer that lands a verified-red case
for **80% or more of the findings filed to it** is doing its job, whatever the absolute number.* Wave
8 hit 74% with two findings argued down as non-defects and six correctly refused as production edits
it did not own — which is 100% of what it could legitimately close. **Your denominator is the filed
findings, and you publish it.**

**Do**
1. **Consume every filed file**: this wave's `docs/plan/verification/W9-*.md`, plus the three wave-8
   files still on disk (`W8-02.md`, `W8-04.md`, `W8-07.md`) for anything their owners left open.
2. **Land a case per finding, verified red** — apply the finding's own mutation, watch the named case
   fail, restore from bytes you saved yourself. Never `git checkout --`, `git restore` or `git stash`.
3. **Argue down what is not a defect, with the reasoning shown.** Wave 8 did this twice and one of the
   two corrected a false gate claim a previous wave's adversary had written into a case comment. An
   equivalent mutant is a finding closed, not a finding dodged.
4. **Hand back what needs a production seam**, naming the exact edit and the packet that owns the file.
   Six of wave 8's went back this way and that was right.
5. **Two fixture corrections from wave 8 are worth carrying, because both cost a run to find and both
   are the same lesson in two languages.** A case aimed at the wrong nesting level is
   indistinguishable from a gated rule — a stale-worker fixture made the *outer* section stale where
   the code reads the *inner* one and stayed green under its own mutation. And a decoy placed under
   the wrong family makes a case fail against **correct** code rather than against the mutation.
6. **The closing act of this wave is yours to prepare and the integrator's to perform.** M10's last
   bullet is `docs/plan/` reduced to `README.md` and `ROADMAP.md`. Everything in
   `docs/plan/verification/` that is still true at the end belongs in the wave-9 retrospective in
   `ROADMAP.md` — **fold it, then the directory goes.** A finding that survives this wave unclosed is
   not a file; it is a row in `TECH_DEBT.md` with an owner, or it is not carried.

**Acceptance**
```
./scripts/run-tests.sh
pnpm -C frontend test
./mill --no-daemon checkArchitecture
./mill --no-daemon __.checkFormat
```
Publish, in this order: findings filed to you, findings closed, findings argued down with the
argument, findings handed back with the seam named, and the percentage. **And say plainly whether the
mechanism should outlive this plan**, because you are the last packet that will ever run it here.

---

## Where the packets meet

| Edge | What crosses it |
| --- | --- |
| W9-01 → everybody | **The stack, twice.** `kui-allinone` and `kui-frontend` built from the tree in the **first** hour with both ids published, the quickstart recreated on them with both seed scripts run — and **rebuilt again after the last building packet freezes**, with the second pair of ids published. House rule 19, amended. Every browser result in this wave quotes the **second** pair. Wave 8 obeyed the first half exactly and shipped an image with seven feature chunks against a tree that built eight. |
| W9-01 → W9-03 | **The second registered cluster**, `staging-eu-01`, in `deployment/quickstart/kui-quickstart.yaml`, announced in the first hour. It is the whole blocker for `M08` and `M09` and the reason `traffic.spec.ts:536` skips. W9-03 writes no case against it until W9-01 says the id and says whether it is a second profile over one broker or a second broker. |
| W9-01 ↔ W9-06 | `libs/config` is **W9-06's** and `deployment/**` is W9-01's. A second *registered cluster* adds no shipped configuration **file**, so `ShippedConfigurationSuite` should not move for it; a `masking` **section** does move `libs/config`'s own suites. If either sees the other's change in its gate, the shape is wrong and not the roster — say so rather than repairing across the line. |
| W9-02 ↔ W9-01 | `smoke.sh` scrapes `ServiceContracts.byService`; `feature-matrix-check.sh` reconciles twelve named npm manifests and 56 ADR rows. Neither reads the other, and **neither has ever been run in the same hour as the other.** Run yours; do not repair the other's. |
| W9-02 ↔ W9-06 | `docs/FEATURE_MATRIX.md` is W9-02's alone. W9-06 moves `DM-001`'s *capability* and **tells** W9-02 what moved; it does not edit the matrix. Wave 8 lost a one-line ADR correction for a whole wave to exactly this shape, so the telling is the obligation, not the editing. |
| W9-03 ↔ W9-04 | **`data-state` on `ConnectorCard`'s pill.** W9-04 ships the attribute; W9-03 deletes `wordFor` and compares the drawn state to the wire's. Until the attribute lands, W9-03's item 4 (scoping the locator to the pill) is independently doable and should go first. |
| W9-04 ↔ W9-03 | `frontend/packages/shell/src/App.tsx` and its two test files are **W9-04's**; every other shell file and all of `frontend/e2e/**` is W9-03's or nobody's. The crumb table is a type change in `App.tsx` and a browser assertion in `shell.spec.ts`, which is the line between them. |
| W9-05 → everybody | **It runs last.** A formatter run over 483 test sources touches trees five packets are writing. W9-05 does not start until every other packet has frozen, and lands the build change and the repair as **two commits**. This is the only ownership in this plan that moves during the wave. |
| W9-A1 → W9-A2 | **The hunter's output is a filed file, not a closure.** `services/ksql`, `services/gateway` and `feature-ksql` have never been mutated and their builders filed nothing; W9-A1 hunts wide and files in W9-A2's shape, and W9-A2 closes. Wave 8 proved the binding resource is filed findings and not closing capacity: the closer converted 17 of 17 and stopped for want of input. |
| W9-A2 ↔ every building packet | A3 owns their unit and component test trees **after they freeze**, and not before — and it owns `docs/plan/verification/**` throughout. **A verification pass writes its file FIRST, as a stub naming the mutations it intends, and fills rows as it runs them** (house rule 18, amended). Wave 8 ran this at three of ten because the file was the last act of a pass and seven passes were killed. |
| W9-A1, W9-A2 ↔ W9-05 | Both adversaries write test sources; W9-05's gate is the first one that will ever check them. Expect W9-05's formatter run to move adversary files, and expect that to be fine — but W9-05 runs after both have frozen, so neither is editing while it runs. |

---

## The partition, checked

**Backend.** Eleven services. `ksql` and `gateway` → **W9-A1 whole**, production and test, because the
seams its findings need may be production edits and because nobody has ever swept them. `message` →
W9-06. The other eight — `cluster`, `topic`, `consumer`, `schema`, `metrics`, `alerts`, `connect`,
`identity` — are **owned by nobody and need no edit**; wave 8's two hunters swept all eight and their
open findings are filed with the seam each needs. W9-A2 takes their **test trees only, after the
building packets freeze**, and only to close a filed finding.

Thirteen `libs`. `security-core` and `config` → W9-06. The other eleven are **owned by nobody**;
`libs/observability`'s `MetricNames.MaskingApplied` is read by W9-06 and not edited by it.

`build.mill`, `.scalafix.conf` and `.scalafmt.conf` are **W9-05's alone**, and it runs last. No packet
declares a module-dependency edge this wave, which is what a closing wave should look like.

**Frontend.** `frontend/packages/kernel/**` is W9-04's for the fifth wave running. Inside
`frontend/packages/shell/`, **no packet owns `**`**: `src/App.tsx`, `src/app.render.test.tsx` and
`src/shell.test.tsx` are W9-04's and named one by one; everything else under `shell/src/` —
`chrome/`, `nav/`, `data/`, `routing/`, `overview/`, `pages/`, `features/`, `messages.ts`,
`bootstrap.ts`, `health.ts`, `index.ts`, `index.tsx` — and `shell/styles/**` are **owned by nobody and
need no edit.** `frontend/packages/feature-ksql/**` is W9-A1's. The other seven `feature-*` packages
are **owned by nobody**; W9-A2 takes their test trees after freeze, to close filed findings only.
`frontend/packages/api/**` is unowned except `README.md`, which is W9-02's.

`frontend/e2e/**` is **W9-03's whole**, which is a simplification on wave 8's per-file allocation and
is possible because one packet now owns every browser case. `frontend/vitest.config.ts` is W9-04's.
`frontend/tsconfig.json`, `frontend/vite.config.ts`, `frontend/package.json`,
`frontend/scripts/**` and `frontend/storybook-static/` are unowned and need no edit — the scripts read
their rosters from the filesystem.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W9-01's alone. `scripts/run-tests.sh` is unowned and derives its module list from
`./mill resolve __.test`. `scripts/feature-matrix-check.sh` is W9-02's. `build-tests/**` is unowned.
`tools/**` is W9-02's.

**Documents.** `DECISIONS.md`, `TECH_DEBT.md`, `DEPENDENCY_MATRIX.md`, `docs/FEATURE_MATRIX.md`,
`frontend/packages/api/README.md` and ADRs **048, 052, 053 and 054** are W9-02's.
`docs/plan/verification/**` is W9-A2's. **Every other ADR** — 001 through 047, 049 through 051, and
055 and 056 — `README.md`, `frontend/README.md`, `ARCHITECTURE.md`, `docs/overview/**`, `docs/api/**`,
`docs/ROADMAP.md`, `docs/ROADMAP-SOLID.md`, `docs/testing.md`, `docs/operations/**`, `docs/domain/**`
and `research/**` are **owned by nobody and need no edit**: wave 8 re-measured every figure in them and
this wave adds no endpoint, no service and no ADR. `docs/plan/ROADMAP.md` is the integrator's, for the
wave-9 retrospective. `docs/plan/WAVE-09.md` is this file; **the wave's closing act deletes it, and
`docs/plan/verification/` with it.**

**Five nesting checks, done rather than assumed.** `services/` is not owned as a tree — three of
eleven go to two packets and eight are unowned. `libs/` is not owned as a tree — two of thirteen.
`docs/` is not owned as a tree. `frontend/packages/shell/` is not owned as a tree: three files are
W9-04's by name and the rest is unowned. And W9-A2's ownership of the building packets' test trees is
a **sequenced** claim, beginning when they freeze; W9-05's licence to reformat is sequenced the same
way and after it.

---

## Files owned by NOBODY

Listed because an unowned file that needs an edit is how a wave ends with a gate red and no owner, and
because wave 8's one stale line — `ADR-053:255` — was stale for a whole wave precisely because its
owner had frozen and nobody else could touch it.

**Unowned and correct as they stand:**

* `build-tests/**` — the design-token mirror and the stylesheet roster. House rules 1 and 2 forbid the
  changes that would move them.
* `scripts/run-tests.sh` — derives its module list from `./mill resolve __.test`; an eleventh service
  appeared in it with no edit and nothing this wave adds a module.
* `frontend/scripts/bundle-shape.mjs`, `frontend/scripts/boundaries.mjs`,
  `frontend/scripts/a11y-stories.mjs` — all three read their rosters from the filesystem.
* `frontend/packages/api/src/constants.generated.ts`, `src/index.ts`, `src/probes.ts`,
  `src/types.test.ts`, and **`src/schema.d.ts`** — the last of these moves only when an endpoint moves,
  and this wave adds none.
* `frontend/e2e/fixtures.ts`, `globalSetup.ts`, `tsconfig.json`, `playwright.config.ts` — the harness,
  not the cases. (The cases are W9-03's.)
* Eight of eleven services, eleven of thirteen `libs`, seven of nine `feature-*` packages, and every
  shell file except three — all swept or unchanged, and all reachable by W9-A2 **after freeze** to
  close a filed finding and for nothing else.
* Fifty-two of the fifty-six ADRs, `ARCHITECTURE.md`, `README.md`, `frontend/README.md`,
  `docs/overview/README.md`, `docs/api/**`, `docs/operations/**`, `docs/domain/**`, `research/**`.

**Unowned, and each holds a filed finding whose fix is a production edit nobody is doing this wave.**
They are here so that "unowned" is a decision and not an oversight; every one becomes a `TECH_DEBT.md`
row with an owner through W9-02 item 6, with the exact edit already written in a wave-8 report:

* `services/identity/.../Pbkdf2PasswordHasher.scala` — the hand-written constant-time loop.
* `libs/filter/.../CelFilterEngine.scala` — the warm-up seam.
* `libs/kafka-auth/.../KeyStoreMaterializer.scala` — the umask re-apply, which needs an environment
  rather than a seam.
* `services/alerts/.../InMemoryAlertStore.scala` — `MaxReadMarkers` has no behavioural upper bound.
* `services/consumer/.../GroupListUseCase.scala` — `stateCounts` and `notes` reach no response.
* `services/schema/.../RegistryCredentials.scala` — a provably dead `.filter`.
* `services/connect/.../Connectors.scala` — `ConnectorFacts.complete` has seven test callers and zero
  production callers. It is a hygiene decision for the file's owner, not an ungated rule, and no case
  can close it.

**Unowned and deliberately left alone:**

* **`stash@{0}`.** Four waves old, 46 files, 3,256 insertions, unpoppable without conflicts. No packet
  may drop it. It is a human's deliberate act and the procedure is in the preamble.
* **`docs/ROADMAP.md` and `docs/ROADMAP-SOLID.md`** — the historical M0–M8 record and a superseded
  plan. Neither is this plan and neither is corrected here.

---

## What wave 10 will be, and whether there is one

**There should not be, and this is the first wave in this plan where that sentence is defensible
rather than optimistic.** M9 is closed. M10 has four things left and each is a command: a YAML entry
and three browser cases, one fixture and one re-read in a shell script, a `smoke.sh` run, and deleting
two paths under `docs/plan/`. The two backend items in this wave — the masking wiring and the scalafix
widening — are debt this plan has been carrying since wave 1 and wave 8, and **neither is in the
definition of done.** If they slip, they slip into `TECH_DEBT.md` with owners, which is where debt
belongs, and the milestone still closes.

**What would make a wave 10 necessary, in order of likelihood:**

1. **`smoke.sh` fails.** It has not been run since before the tenth service and it is the only gate in
   M10's exit criterion that nobody in wave 8 executed. Eleven containers, a contract set scraped with
   `sed`, twelve exporter line shapes and four alerts assertions. If the eleventh service broke it,
   that is a real repair in a tree W9-01 may not own.
2. **The second cluster is harder than one YAML entry.** Two registered profiles over one broker is
   the cheap shape and it is what the screens need; if the cluster store, the capability fold or the
   readiness poller turns out to assume one profile in a way nobody has noticed — and nothing in this
   repository has ever run with two — the three browser cases wait on a fix.
3. **W9-A1 finds something structural in the eleventh service or the relay.** Forty-one Scala sources
   and a hand-written SSE relay that nobody has mutated. Wave 8's hunters found 32–42% ungated on code
   that *had* been swept once. A finding that needs a production seam in `services/ksql` is a wave-10
   item, not a W9-A2 closure.
4. **W9-05's formatter run wants a semantic change.** 483 files, a rule that has never run against
   them, `-Werror` underneath. Item 3 of that packet says stop and file; filing is a wave-10 item.

**And if none of those fires, the closing act is this.** The integrator runs M10's exit criterion end
to end from a clean checkout with every image built from it — `run-tests.sh`, `pnpm -C frontend test`,
the a11y sweep in its three commands, `pnpm -C frontend e2e`, `__.openApiCheck`, `checkArchitecture`,
`smoke.sh`, and `feature-matrix-check.sh` with both weakening demonstrations reproduced — writes the
wave-9 retrospective into `ROADMAP.md`, folds whatever is still true out of `docs/plan/verification/`
into it, and **deletes `docs/plan/WAVE-09.md` and `docs/plan/verification/`**. `docs/plan/` is then
`README.md` and `ROADMAP.md`, which is M10's last bullet, and the plan is finished.

**The ratio, for the record, since this is the last time it will be set.** Wave 8 ended with 42 rules
closed by three adversaries against a number opened that **cannot be computed**, because seven of ten
verification passes were killed before they could file. That missing denominator is the wave's real
finding and it is why house rule 18 is amended to write the file first. Wave 9 runs **six building
packets and two adversaries at 3:1**, with the adversarial pair split as **one hunter and one closer**
rather than two hunters and a closer — because the closer converted 17 of 17 filed findings while both
hunters ran at a third of that per mutation, and the thing that was scarce was never closing capacity.
