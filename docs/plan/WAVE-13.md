# Wave 13 — the roster the gate cannot see, and the figures nobody re-took

**Milestones covered:** the rest of **M10** in [ROADMAP.md](ROADMAP.md), and nothing else.

**Why there is a wave 13, when waves 9, 10, 11 and 12 were each written to be the last.**

Wave 12 did the load-bearing thing it said it would, and it is the second wave in a row that did.
The `quotation` claim kind landed, it is a `grep` and not a parser, and the two false quotations
that kept definition-of-done item 4 open at the wave-11 close are gone from the tree:

```
$ grep -rn "masked value is never longer" . --exclude-dir=.git | grep -v docs/plan/
scripts/feature-matrix-check.sh:3660:    printf 'See `%s`, which says *a masked value is never longer …
```

One hit, and it is the gate's own fixture. The mood hole is closed too — **four of the five
sentences** in `WAVE-12.md`'s table are now refused where three were green at the wave-11 close, and
the two the plan named by name each print one disagreement and exit 1. That was measured at this
close, sentence by sentence, `README.md` restored byte-identical (`md5sum`
`4d4c7b9a3eb912ef228382afd5a2e336`).

**And item 4 is still not met, for a reason that is one measurement long.**

```
$ # appended inside TECH_DEBT.md's <!-- checked: debt-register --> block, nothing else changed:
$ #   See `docs/operations/masking.md`, which says *a masked value is never longer than the
$ #   value it replaced*.
$ ./scripts/feature-matrix-check.sh
feature-matrix-check: 438 claims checked, all true.        # exit 0
```

That is the sentence the whole of wave 12 exists to refuse, inside a `<!-- checked: -->` region,
in the register wave 12 itself put the markers around, and the gate cannot see it. The sweep reads a
**hand-written roster** — `quotation_swept_kinds`, `scripts/feature-matrix-check.sh:3368` — of eight
files and twelve blocks, and nothing reconciles that roster against the repository. The tree carries
**fifteen** `<!-- checked:` markers outside `docs/plan/`; two are `quotations` blocks the quotation
handler reads directly; **thirteen** are blocks of other kinds; **twelve** are in the roster. The
thirteenth is `TECH_DEBT.md`'s.

Two of the script's own shipped sentences are false because of it. Its refusal text at line 3399
says *"House rule 25 is about a checked region and not about one kind of region, so every block in
this repository is read for attributions"* — twelve of thirteen are. Its section-9 census comment at
line 3219 says *"the twelve marked regions this repository carries"* — there are fifteen. The
`blocks == 12` guard beside the claim cannot notice either, because **12 is the size of the roster,
not a count of anything on disk**.

**This is the same shape as every previous close, and it is the fourth time the shape has been
named rather than discovered.** Wave 9's open item was a figure. Wave 10's was a figure and a
sentence. Wave 11's was a quotation. Wave 12's was a quotation the gate could read — and wave 13's
is **the list of places the gate looks**, which is the one input to that gate nobody derived.

**Wave 13 is three building packets and two adversaries.** Smaller than wave 12, which was the
smallest here had ever been. No new service, no new endpoint, no new ADR, no new feature package, no
new screen, and **no new gate mechanism at all**: every item below is a reconciliation, a
re-measurement, or a decision written down in the open. If a packet finds itself writing a
capability, it has misread its brief.

---

## The definition of done, judged item by item at the wave-12 close

Every figure below was printed by the command beside it, on **2026-09-12**, by the closer, one
command at a time, against a quickstart brought up from this tree (`kui-allinone` `2bfe5665f839`,
`kui-frontend` `278908972b32`) and the eleven-container distributed stack built from the same tree.
**Nothing below is read off a packet report; every line was re-run here.**

| # | Item | Verdict | Command, and what it printed |
| --- | --- | --- | --- |
| 1 | Every screen in `screens/` renders real data | **MET** | `ls screens/` → 23 captures; `playwright test --reporter=list` → **108 passed, 2 skipped, 0 failed** over 110; **23 of 23** captures have a named case that ran green in that run (mapping below); the landing page and the cluster dashboard probed in a browser — 6 honest sentences, `Create topic` `aria-disabled=true` and swallowing its press, **0** page errors, **0** three-decimal figures in 1,159 `<td>` cells |
| 2 | Every backend capability exists as a service or an endpoint | **MET** | `ls services/` → **11**; `./mill __.openApiCheck` → **2544/2544**; `./mill checkArchitecture` → **195 modules, 10 rules, no layering violations** |
| 3 | Unit and component tests, a11y in both themes, a browser suite | **MET** | `./scripts/run-tests.sh` → **81 modules (81 with tests), 4,401 cases, all passing**; `pnpm test` → **83 files, 1,935**; `pnpm typecheck` → exit 0 over both projects; `pnpm a11y` → **782 stories × 2 themes, no violations**; `pnpm lint:boundaries` → 423 files in 11 packages; `./mill __.compile` 8251/8251, `__.checkFormat` 495/495, `__.fix --check` 10672/10672 |
| 4 | Documented: README, ARCHITECTURE, ADRs, an accurate FEATURE_MATRIX, an overview | **NOT MET** | the reproduction at the top of this file, plus five more below |
| 5 | One command brings the whole product up under `docker compose` | **MET** | `./deployment/quickstart/quickstart.sh` → exit 0, *"KUI is running: http://localhost:8090/ui/"*, eight containers healthy; `down` → *"Removed. Nothing from the quickstart is left running or stored."*; `./deployment/compose/smoke.sh` → **PASSED**, fault isolation and recovery included |

### Item 1, measured rather than assumed

The twenty-three captures were re-counted against `docs/plan/verification/W8-07.md` §1 — the only
published screen-to-spec index — and against this run's own case list, matched by case title in the
`--reporter=list` output rather than by file:

| Screen | Case that drove it, green in this run |
| --- | --- |
| `M01` | `traffic.spec.ts` *carries the same stat cards as Overview, and its own last row* + `dashboard.spec.ts` (9 green) |
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

**23 of 23, and the two skips are the right two** — `alerts.spec.ts:270` and `ksql.spec.ts:175`,
each describing a *different deployment*. No third skip appeared. `search.spec.ts`'s 8 cases are
over a surface the captures do not picture and are counted nowhere above.

### Item 4, the six things it is open on

Each was measured at this close, by the closer, with the command beside it. **Two of the six are
inside the gate this wave built**, which is why this wave is about the gate's inputs and not about
prose.

1. **The roster the sweep reads is reconciled against nothing.** The reproduction at the top of this
   file: a false quotation inside `TECH_DEBT.md`'s own marked block leaves the script at *438 claims
   checked, all true*, exit 0. Fifteen markers on disk, thirteen non-`quotations` blocks, twelve in
   `quotation_swept_kinds`. Filed by W12-01's own verifier **and** by W12-A2, and deliberately closed
   by neither: the closing claim belongs in section 9, which was W12-01's, and W12-A2 crossed two
   ownership edges already and declined a third. It is the only filed row of eighteen this wave that
   nobody closed.
2. **The gate publishes a figure about itself that does not reproduce.** Section 9's comment says the
   document-wide reader would answer *"1,154 attributions, 1,148 of them absent"* over the 113
   tracked markdown files outside `docs/plan/`. W12-01's verifier lifted `quotation_attributions` and
   `quotation_is_present` verbatim out of the shipped script and ran them four ways — 200/193,
   1,669/1,664, 1,022/1,019, 609/578. **None is 1,154/1,148.** The conclusion the figure supports is
   unaffected (every reading is orders of magnitude above the marked-region answer); the number is
   wrong, it sits in a `#` comment no gate can read, and it was taken against an earlier draft of the
   reader — **the exact amendment to house rule 17 the packet quotes in its own preamble.**
3. **The newcomer's overview publishes a Scala source count that is wrong in three rows.**
   `docs/overview/README.md:207`, `:210` and `:211` each say **1,145 sources**. Measured here by
   summing the per-target counts the named command prints: `./mill __.checkFormat` → **1,152 sources
   over 162 reporting targets**, and `git ls-files '*.scala' | wc -l` is **1,152** on this tree and on
   `74935618`. The **162**, the 8251/8251, the 10672/10672, the 495/495, the 195 modules and the
   2544/2544 in the same table are all correct and were re-run here. The page says in prose that this
   section was not re-measured by the wave-12 documentation pass, which is honest and does not make
   the figure true. **This is one of the five documents item 4 names by name.**
4. **`TECH_DEBT.md` carries three rows marked `open` that describe defects wave 12 repaired**, in the
   present tense, each with an exit condition this tree now meets — measured here, not read off a
   report:
   * **TD-049** — *"the step still prints 11 packages, 11 manifests copied, every name matched and
     exits 0"*. Applied `COPY frontend/packages/api/package.json` → `tsconfig.json` and ran
     `./mill --no-daemon build-tests.test`: **139/139, 1 FAILED**,
     `theInterfaceImageCopiesEachManifestIntoItsOwnPackageDirectory`. Dockerfile restored,
     `md5sum 556767a851c379afc22c70406c124b73`.
   * **TD-052** — *"mutating `frontend/package.json`'s `typecheck` script and watching
     `build-tests.test` go red **without** a preceding `clean`"* is the row's own stated exit. Done
     exactly that: **139/139, 1 FAILED**, `theInterfaceTypeGateStillReadsTheBrowserSuite`, no clean.
     `package.json` restored, `md5sum 1e79a1a85074591739e54dac9a6f14aa`.
   * **TD-050** — *"decide it in the open and say which"*. Decided: `kui-quickstart-auth.yaml:13-41`
     now carries the decision, the RBAC measurement behind it and the condition for revisiting. The
     row still says nobody left a row.
   The register's new `debt-register` block checks id arithmetic and uniqueness and **nothing checks
   whether an `open` row still describes the tree** — the wave's own subject, inside the register
   built to hold it.
5. **A shipped file publishes a runnable command as its evidence, and the command cannot fail.**
   `deployment/quickstart/kui-quickstart-auth.yaml:337-338` publishes
   `diff <(awk '/^      masking:/,/^$/' …) <(awk … )` as the reader's proof that the two masking
   blocks are identical. The range ends at the first blank line and the blank line falls **between**
   the two rules, so both extracts hold only the `kind: mask` rule. Measured: set
   `replacement: "<redacted>"` → `"<name>"` in the auth file and the published command still exits 0
   and prints nothing; `./mill --no-daemon libs.config.test` → **395/395, 1 FAILED**,
   `ShippedConfigurationSuite`. The rules **are** gated; the comment's own evidence is the thing that
   cannot fail. This is wave 12's replacement for the wave-11 *"byte for byte"* claim in the same
   file — **the third distinct form of the same class**, and TD-051 is the row it belongs to.
6. **The positive-voice rule refuses a sentence `WAVE-12.md` bolded as one it must not refuse.**
   Measured: *"The Connect screen is the placeholder for a worker that is not configured."* appended
   inside `README.md`'s `capability-claims` block → **1 disagreement, names `[connect]`, exit 1**.
   The plan's escape hatch — move honest prose out of the markers — was taken, and W12-01 argued it
   at length **in the script**, naming the four-line escape and the rejected alternative. It appears
   in neither the packet's surprises nor its `needsOutsideOwnership`, so an integrator reading the
   report and not the script would not learn that the plan's named honest sentence is now unwritable
   inside the markers. The judgement is defensible; the **reporting** is the defect, and the decision
   still has to be written where a document author will read it.

### What is NOT open, and should not be re-hunted

* **The quotation class is closed for the four quotations it was written against.** `grep -rn
  "masked value is never longer"` outside `docs/plan/` returns the gate's own fixture and nothing
  else. Do not re-litigate it.
* **Both moods are refused.** Four of the five sentences in `WAVE-12.md`'s table are red, including
  the two that were green at the wave-11 close. The fifth — *"KUI has no topic detail page and no
  consumer lag chart."* — is still green and **names no service**: it is outside the rule's declared
  scope, not a hole in it. Say so once in the README's own prose and move on; widening the rule to
  screens is how the false-positive rate becomes unmanageable.
* **TD-042, TD-044, TD-045, TD-046, TD-047's first half and TD-048 are closed**, and the three
  frontend figures the `ClusterSelector` deletion moved (423 files, 782 stories, 1,935 cases) were
  re-measured at this close rather than copied.
* **`build-tests.test` is no longer cached on a stale input set.** Measured above, twice, without a
  `clean`. The house rule saying *clean first* can go.
* **The e2e suite and both deployment shapes are green from images built from this tree.** Do not
  re-run them to prove a documentation repair.

---

## The tree you start from

Every figure printed by the command beside it at the wave-12 close, one gate at a time, by the
closer, on an otherwise idle machine.

| Gate | Figure |
| --- | --- |
| `./mill __.compile` | 8251/8251 |
| `./scripts/run-tests.sh` | **4,401 cases over 81 modules**, all 81 carrying tests (wave 11: 4,389) |
| `pnpm -C frontend test` | **1,935 over 83 files** (1,938 over 83 — seven `ClusterSelector` cases deleted, four added) |
| `pnpm -C frontend e2e` | **108 passed, 2 skipped, 0 failed** over 110 |
| `./scripts/feature-matrix-check.sh` | **438 claims over 12 sections**, all true (419 over 10) |
| `./mill __.checkFormat` | 495/495 over **1,152 sources** across 162 reporting targets |
| `./mill __.fix --check` | 10672/10672 |
| `./mill checkArchitecture` | 195 modules, 10 rules, no layering violations |
| `./mill __.openApiCheck` | 2544/2544 |
| `pnpm -C frontend typecheck` | exit 0 over both projects |
| `pnpm -C frontend lint:boundaries` | **423 files in 11 packages** (425) |
| `pnpm -C frontend a11y` | **782 stories × 2 themes, no violations** (790) |
| `./deployment/quickstart/quickstart.sh` | eight containers healthy, two registered clusters |
| `./deployment/compose/smoke.sh` | **PASSED** over eleven containers, SSE `phase` frame included |

`feature-matrix-check.sh`'s section sizes: self-check 7, rows 30, merged-document 50, milestones 49,
adr-index 112, openapi-totals 15, **guard-fixtures 25**, capability-claims 43, **quotations 11**,
**debt-register 3**, gate-table 5, dependencies 88 over 12 named manifests.

**Anything red is yours. Nothing is red.** The six things that are wrong are not gate failures —
one of them is a gate that reports success over a block it never opened.

---

## House rules

The first twenty-five are waves 8 through 12's and still apply in full; read them in the git history
of `docs/plan/WAVE-12.md`. Three are amended and one is new.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first, browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what. No ESLint or Prettier; the
codebase is hand-written at 100 columns (Scala at 110). **Do not reformat a file you are not
otherwise changing.**

5. **Report a mutation that stayed green. Tenth wave, no movement.** Four building packets, four
   disclosures, **thirteen** more found by their verifiers. The rate has not moved in ten waves and
   the mechanism is built around it rather than against it.
17. **AMENDED a third time — a figure about your own gate is re-derived by running the gate's own
    code, not by reasoning about it, and it is taken after the last edit.** Wave 12 published
    *1,154 attributions* from a draft of a reader whose shipped form answers four other numbers.
    **If you cannot re-run the derivation in the report, write the command and no figure.**
21. **Unchanged, and it produced this project's first 100%.** W12-A2 received 13 filed rows and
    closed 13. It hunted nothing while filed rows remained.
23. **Unchanged, and it decided this wave.** `WAVE-12.md` predicted that the `quotation` kind would
    turn item 4 into a gate. The closer tested the prediction **and its inverse** — the sentence
    inside a block the roster does not name — and the inverse is why there is a wave 13.
26. **NEW: a roster is a claim. A hand-written list of the places a gate looks must be reconciled
    against the repository, in the same run, by the same script.** Every gate in this repository that
    derives its subjects from the filesystem has survived four waves of mutation
    (`bundle-shape.mjs`, the compose image list, `reconcile_dependencies`); every hand-written list
    of subjects has eventually been found missing one. The roster is the input nobody mutates because
    it does not look like a rule.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
and `npx --yes pnpm@11.25.0 <script>` works from `frontend/`. Use `./mill --no-daemon` for anything
you record a number from. **The a11y sweep is three commands** — build Storybook, serve
`storybook-static` on `:6017`, sweep — and `npx http-server` leaves two processes; kill the listener
`ss -ltnp` names. `./mill a.test b.test` runs **zero** tests and exits 0: the second word is a munit
name filter. **`clean` before `build-tests.test` is no longer needed** — measured at this close, the
suite now invalidates on `build.mill`, `ci.yml`, `frontend/package.json` and
`deployment/frontend/Dockerfile`.

**And one the wave-12 adversaries paid for twice: the two sweeps must not share a checkout.**
W12-A1's in-flight mutation of `ResetPlanner.scala` turned W12-A2's `__.checkFormat` red for one
run, and W12-A2's `run-tests.sh` ran over a tree carrying another packet's mutation. Both reported
it rather than reverting each other, which was right. **Separate checkouts, or serialise them.**

---

## The guard files

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `scripts/feature-matrix-check.sh` | **438 claims over 12 sections**; `quotation_swept_kinds` (8 files, 12 blocks); `close_section` literals 25/43/11/3/5 | **W13-01**, which must raise the total and not lower it |
| `TECH_DEBT.md` | 52 rows, highest `TD-052`, the block publishing `TD-053` as next free, ids unique | **W13-02** alone |
| `docs/overview/README.md` | the `gate-table` block's script-derived figures, and the build-shape table whose Scala source count is wrong in three rows | **W13-03** alone |
| `docs/FEATURE_MATRIX.md` | 189 rows, 71 COMPLETE, 178 in scope, 40% delivered, every self-count inside a checked region | **W13-03**; W13-01 may add markers, not figures |
| `frontend/e2e/**` | **108 passed, 2 skipped, 0 failed.** Both skips are deployment-shaped; **no packet may add a third** | nobody this wave without saying so |
| `libs/config/test/.../ShippedConfigurationSuite.scala` | 16 cases including both quickstarts' masking rules — the gate that actually compares them | **W13-02** may add, not weaken |
| `build-tests/test/.../BuildWiringSuite.scala` | the job/step roster, `readDeclared`, the manifest pairing | **W13-02**; W13-A1 may mutate, not edit |
| `./mill __.checkFormat` / `__.fix --check` | 495/495 and 10672/10672 over every tree including tests | every packet |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids moving it |

---

## W13-01 — The roster, and the gate's own figures

**Owns:** `scripts/feature-matrix-check.sh` entire, and the `<!-- checked: -->` markers in any file
it needs to add one to (markers only — no prose inside another packet's document).

**The wave's load-bearing item, and it is one claim.**

1. **Reconcile `quotation_swept_kinds` against the repository, in the same run.** Derive the set of
   `<!-- checked: <kind> -->` markers on disk outside `docs/plan/` — the same way
   `reconcile_dependencies` derives manifests — and `claim` the roster against it in both
   directions: a marked block of a kind/file pair the roster does not name is a disagreement, and a
   roster entry naming a file that carries no such block is a disagreement. `quotations` blocks are
   read by `check_quotation_region` and are excluded **by name in one place**, not by two lists that
   can drift.
2. **Then put `TECH_DEBT.md`'s `debt-register` block in the sweep** — which by item 1 happens by
   itself — and re-run the reproduction at the top of this file. **The wave's acceptance is that
   sentence going red inside that block.** It is a prediction; house rule 23 says the closer tests
   it.
3. **Repair the two false sentences the script ships about itself** (lines 3219 and 3399) and
   **re-take or delete the census figure** (item 4.2). Re-taking means running the shipped
   `quotation_attributions`/`quotation_is_present` over the 113 files and publishing what they
   answer, with the command beside it; deleting means saying the reader was measured to be useless
   document-wide without a figure nobody can reproduce. **Either is acceptable. Publishing the old
   number is not.**
4. **Four ungated rules W12-01's verifier filed and W12-A2 closed are now fixtures; do not touch
   them.** Section 6 is at 25 fixtures and `close_section guard-fixtures 25` is a guard file.
5. **Disclose one rule of your own that survives mutation with the run green**, in the shape house
   rule 5 asks for. The script is 3,700 lines and the last three waves each found one in it within
   the hour.

**Acceptance:** `./scripts/feature-matrix-check.sh` exits 0 with a total **above 438**; the
reproduction at the top of this file exits 1 and names `TECH_DEBT.md`; deleting any one file from
`quotation_swept_kinds` is a disagreement; adding a `<!-- checked: rows -->` marker to a scratch file
is a disagreement; and the four controls in `docs/plan/verification/W12-01.md` §ACCEPTANCE still
reproduce.

---

## W13-02 — The register that does not describe the tree, and the evidence that cannot fail

**Owns:** `TECH_DEBT.md` entire; `deployment/**` entire (`quickstart.sh`, the two quickstart YAMLs,
`docker-compose*.yml`, `seed/**`, `smoke.sh`, `deployment/frontend/Dockerfile`);
`libs/config/test/**` for additions only.

1. **Close TD-049, TD-050 and TD-052 on the measurements in item 4.4**, with the command and its
   output written into the row — not "closed by wave 12". Re-run each yourself first; a row closed on
   somebody else's transcript is how TD-047 came to publish a false measurement for a whole wave.
2. **Amend the three rows W12-A1 filed and could not reach:** TD-032 (the `notes` half is now gated;
   the row should say which half), TD-033 (the identical dead guard pair exists at
   `services/connect/infrastructure/.../ConnectCredentials.scala:241` and the row names only the
   schema copy), TD-031 (the mutation is caught by an `assertEquals` on a literal and not
   behaviourally; `BoundedCache.stats.size` is now upstream, so the behavioural exit is one
   delegating method and one case).
3. **Repair the evidence command in `kui-quickstart-auth.yaml`** (item 4.5): end the `awk` range at
   the next key rather than the first blank line, re-measure the `2,4c2` figure over the corrected
   range, and **cite `ShippedConfigurationSuite` as the thing that actually compares the rules**. A
   comment that publishes a command publishes a gate, and this one has now been wrong in two
   different ways in two consecutive waves.
4. **File and fix `connect-seed.sh`.** Measured by the wave-12 integrator during a clean e2e
   bring-up: Compose printed *"connect-seed didn't complete successfully: exit 1"* with the
   container's own log ending cleanly and no error line. Under `set -euo pipefail`, the `RUNNING`
   wait loop's assignment `running="$(… | grep -o '"state":"RUNNING"' | wc -l …)"` fails whenever the
   first poll has no `RUNNING` state yet: `grep` exits 1, `pipefail` carries it out of the command
   substitution, and `set -e` kills the script before its own `die()` can say anything. It survives
   only when the very first poll is already green. **It cost no gate this time and will skip the two
   positive Connect e2e cases on a slower machine** — the *"fails with no stderr"* shape wave 8 lost
   four gates to. One row, one fix, and one control: make the first poll return no connector and
   require the script to say so and keep waiting.
5. **Say in the register, in prose, what is not gateable.** Nothing mechanically checks that an
   `open` row still describes the tree. What the `debt-register` block does check is id arithmetic
   and uniqueness; what W13-01's roster change adds is that a **quotation inside a row** is compared
   against the file it names. The honest statement is that the register's staleness is caught by a
   human reading exit conditions at a wave close, and that every row therefore carries a command.
6. **Disclose one ungated rule** in `deployment/**`. `smoke.sh` is 900 lines and only its SSE arm has
   ever been mutated.

**Acceptance:** `./scripts/feature-matrix-check.sh` green with the register's block intact;
`./mill --no-daemon libs.config.test` green, and **red** when the second masking rule is mutated;
the corrected `awk` range printed in the report beside the mutation that now changes its output;
`./deployment/quickstart/quickstart.sh up` with `connect-seed` exiting 0 on a cold machine, and the
first-poll control red.

---

## W13-03 — The figures in the documents item 4 names

**Owns:** `docs/overview/README.md`, `docs/FEATURE_MATRIX.md` (prose and figures; W13-01 owns its
markers), `docs/frontend/README.md`, `frontend/README.md`, `README.md`, `ARCHITECTURE.md`.

1. **Re-take every figure in `docs/overview/README.md`'s build-shape table, or replace it with the
   command that prints it.** Three rows publish **1,145 Scala sources** against a measured **1,152**
   (item 4.3). The wave-12 pass converted three moving figures to commands and left the rest dated;
   the dated ones are the ones that were wrong. **A figure about the tree lives inside a checked
   region or is written as a command — there is no third option left after five waves of this.**
2. **Read the whole page against the commands it names**, not just the table: `ls services/`, the
   eight feature packages, the eleven images, the two deployment shapes. It is the document a
   newcomer is pointed at by `README.md:66`.
3. **Write the prose-mood decision where a document author will read it** (item 4.6): the
   `capability-claims` block refuses any sentence naming a service beside a state word, **including
   honest ones**, and the answer is that state belongs in the three lists and prose belongs outside
   the markers. That is a real constraint on anybody editing `README.md` and it currently exists only
   in a comment inside a 3,700-line shell script.
4. **Say once, in the README's own prose, what the rule does not read** — a false sentence about a
   *screen* rather than a *service* is not refused, measured at this close. A reader who knows the
   gate's scope is not misled by it; a reader who thinks every sentence in the block is checked is.
5. **Do not add a claim kind.** Every repair here is a substitution, and if one of them needs a new
   mechanism, it is a wave-14 item and you say so.

**Acceptance:** every figure in the page printed by the command beside it in the report, in the same
session, after the last edit (house rule 17, third form); `./scripts/feature-matrix-check.sh` green;
no figure in any of the six files that a command in this repository contradicts.

---

## W13-A1 — The hunter

**Owns for mutation:** `services/cluster/**`, `services/topic/**`, `services/message/**`,
`frontend/packages/shell/src/**` and `frontend/packages/kernel/src/**`. **Owns for editing:** the
test trees of those, and nothing else.

**Where to aim, and it is not where the last four hunters aimed.** W12-A1's rate was **12.5%** (5 of
40) against wave 11's 26% and wave 10's 83%, and the four services it swept that had *never* been
swept returned **8.3%** — `services/alerts` returned **zero of twelve**. *"Never swept"* has stopped
predicting *"ungated"*. All three of its genuine findings were the same shape: **a guard that exists
and is tested one notch coarser than it is written** — a binding asserted for a different cluster but
not a similar one (`PlanToken`'s `startsWith` → `contains`, which turns ADR-045's cluster binding
into a substring search and stayed green over 1,390 tasks), a field asserted where it is computed but
not at the seam that carries it, an ordering asserted through a reader that discards order.
**Mutate strictness, not presence.**

**And one class is named and open.** W12-04's address sweep mounts a fresh frame per press by design,
so **no toggle's second press is asserted anywhere in the frontend.** W12-A2 closed the bell; the
theme glyph, the appearance popover and every disclosure control are open to the identical one-line
mutation (`setX(!x())` → `setX(true)`). Start there: it is a whole class, it is one file, and the
closing case shape is already written.

**Acceptance:** one target per invocation; every mutation reverted from bytes saved before it, never
with `git checkout`; the rate published as *ungated / non-equivalent mutants*, with equivalent
mutants argued in algebra rather than asserted.

---

## W13-A2 — The closer

**Owns:** every test tree in the repository, for additions; no production file except as a declared,
minimal, behaviour-preserving seam.

House rule 21 unchanged: **close before you hunt.** W12-A2 received 13 rows and closed 13 — this
project's first 100% — and the one row it declined was declined for ownership and is now W13-01's
item 1. Expect a similar shape: the verifiers file, you close, and you cross an ownership edge only
when the alternative is leaving a row open, declaring it in `needsOutsideOwnership` with the exact
diff.

**One standing instruction, from wave 12's own measurement:** when a filed row's closing case needs a
seam, add the seam **and then re-apply the original mutation in a form the compiler accepts** —
W12-A1 extracted `KeyStoreMaterializer.secureDirectory` and the extraction made the original deletion
uncompilable, so the close would otherwise have been measured against a compile error rather than
against the suite.

---

## Where the packets meet

* **W13-01 and W13-02 meet at `TECH_DEBT.md`.** W13-01 may add or move a `<!-- checked: -->` marker
  in it and may not touch a row; W13-02 owns every row and may not touch the markers. If the roster
  change needs the block moved, W13-01 says so in its report and W13-02 lands it.
* **W13-01 and W13-03 meet at `docs/FEATURE_MATRIX.md` and `docs/overview/README.md`.** Markers and
  claim kinds are W13-01's; figures and prose are W13-03's. A figure that has to move inside a
  checked region is W13-03's edit and W13-01's re-run.
* **W13-02 and W13-A1 meet at nothing** by construction: the hunter's services are `cluster`,
  `topic` and `message`, none of which W13-02 edits.
* **W13-A1 and W13-A2 must not share a checkout.** Measured twice in wave 12; see the house rules.
* **Nobody owns `services/gateway/**` this wave.** W12-03 and W12-A2 closed five rules in it and the
  tree is fresh.

## The partition, checked

| Path | Owner |
| --- | --- |
| `scripts/feature-matrix-check.sh` | W13-01 |
| `TECH_DEBT.md` | W13-02 (rows), W13-01 (markers only) |
| `deployment/**` | W13-02 |
| `libs/config/test/**` | W13-02 (additions), W13-A2 (additions) |
| `docs/overview/**`, `docs/FEATURE_MATRIX.md`, `README.md`, `ARCHITECTURE.md`, `docs/frontend/README.md`, `frontend/README.md` | W13-03 |
| `services/{cluster,topic,message}/**` | W13-A1 (mutation), its test trees W13-A1 + W13-A2 |
| `frontend/packages/{shell,kernel}/src/**` | W13-A1 (mutation only), test files W13-A1 + W13-A2 |
| every other test tree | W13-A2 |
| `docs/plan/verification/W13-*.md` | the packet it is about, and nobody else (house rule 18) |

## Files owned by NOBODY

**Unowned and correct as they stand:** `scripts/run-tests.sh`; `frontend/scripts/*.mjs`;
`frontend/packages/api/src/**`; fifty-two of fifty-six ADRs; `docs/api/**`; `docs/domain/**`;
`research/**`; `docs/operations/**`; `libs/observability/**`, `libs/security-core/**`;
`services/gateway/**`; `.github/workflows/ci.yml` (its job and step roster is now asserted by
`BuildWiringSuite`, so a deletion is a red case).

**Unowned, each holding a filed finding whose fix is a production edit nobody is doing this wave:**
`services/metrics`' five unclosed payload rows under TD-024; `frontend/packages/shell/src/overview/
LatencyCard.tsx` and `ThroughputCard.tsx`'s hidden-table `ABSENT` decision, written out in prose and
deliberately unchanged.

**Unowned and deliberately left alone:** `stash@{0}` — seven waves old, 46 files, unpoppable without
conflicts, **no packet may drop it**; `docs/ROADMAP.md` and `docs/ROADMAP-SOLID.md`.

---

## For the integrator, before wave 13 starts

1. **Wave 12 is unrecorded.** `git status --porcelain` is **48 entries** against `74935618` — 38
   modified, 3 deleted (`docs/plan/WAVE-12.md` and the two `ClusterSelector` files) and 7 untracked
   (this file and wave 12's six verification reports). Every gate in the tree table above was run
   against exactly this working tree at this close. **Commit it before anything in wave 13 moves.**
2. **`docs/plan/` is `README.md`, `ROADMAP.md`, this file and `verification/`.** The thirty
   `verification/` files include wave 12's six. `W8-04.md` and `W8-07.md` are cited from
   `docs/FEATURE_MATRIX.md`, and `W9-03.md` from two e2e specs, so deleting them creates dangling
   references — and after W13-01's roster change, a dangling one inside a checked region is a gate
   failure rather than a broken link.
3. **Check `ss -ltn` for `:6017`, `:8080`, `:8090` and `docker ps` before you record a figure.**
   Wave 12 lost one acceptance run to two sessions holding the same compose project.
4. **Do not trust a cached frontend image layer** — export the image's `ui/assets` and compare the
   content-hashed filenames against a `dist` built from the tree. The adversaries' mutate-and-restore
   cycle changes mtime and not content, so BuildKit's `CACHED` is honest and uninformative.
5. **`clean` before `build-tests.test` is no longer required.** Measured at this close.

---

## What wave 14 will be, and whether there is one

**There should not be, and this is the fifth time that has been written. Here is what is different,
stated so the next reader can check it rather than believe it.**

Wave 12 is the second consecutive wave whose load-bearing prediction was reproduced rather than
believed, and the first in which **every** filed ungated rule was closed — eighteen found, eighteen
closed. What kept item 4 open is not a mechanism that failed; it is **one input to a mechanism that
nobody derived**, and deriving it is the same edit `reconcile_dependencies` already is. Four of the
six open things are substitutions with the command that finds each written beside them.

**What would make a wave 14 necessary, in order of likelihood:**

1. **The roster reconciliation catches more than one block.** This is the good failure and it is the
   likeliest. If `TECH_DEBT.md`'s block is the only unswept one, item 4 closes; if the derivation
   finds that half the repository's marked blocks carry attributions nobody has read, that is a
   census and W13-01 reports it before repairing anything. **Measure the count first.**
2. **The re-taken census figure is unreproducible in a third way.** Item 4.2 is now the tenth
   consecutive wave in which a packet published a figure about its own gate that a second reader
   could not reproduce. If W13-01 cannot re-derive one either, the honest answer is to delete the
   figure, and a wave that cannot state its own gate's size is a wave whose gate needs rewriting
   rather than re-measuring.
3. **`connect-seed.sh`'s race is not a race.** If the `RUNNING` loop turns out to be one instance of
   a `set -euo pipefail` pattern repeated across `deployment/**`, that is a class and not a fix.
4. **The second-press class is larger than the frontend.** W12-04's sweep design means *"pressed once
   from a clean frame"* is the only thing asserted about every control the product draws. If W13-A1
   finds the same shape in the kernel's disclosure primitives, the closing case is a sweep rather
   than five cases, and a sweep is a wave item.

**And if none of those fires, the closing act is this.** The integrator runs **the five items**, not
the gate list: opens the product at the address it lands on, appends a false sentence to a checked
region **and a false quotation to a block the roster was not written against**, requires both to be
refused, greps for a quotation attributed to a file that does not contain it and requires zero hits
outside the gate's own fixtures, re-runs every figure the newcomer's overview publishes, writes the
wave-13 retrospective into `ROADMAP.md`, folds whatever is still true out of
`docs/plan/verification/` into it, writes `CLOSING-REPORT.md`, and deletes `docs/plan/WAVE-13.md`.
`docs/plan/` is then `README.md`, `ROADMAP.md` and `CLOSING-REPORT.md`, and the plan is finished.

**The ratio, for the record.** Wave 10 ran six and two at 3:1 and closed four of five items. Wave 11
ran five and two at 2.5:1. Wave 12 ran four and two at 2:1 and closed eighteen of eighteen filed
rules. Wave 13 is **three and two at 1.5:1** — one reconciliation, one register, one document, and
two sweeps — because what is left is a list the gate reads, three rows that say `open` about work
that is done, three figures in one table, and one `awk` range.
