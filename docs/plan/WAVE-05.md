# Wave 5 — Finish M7, and stop losing three rules for every one closed

**Milestones covered:** the rest of **M7** in [ROADMAP.md](ROADMAP.md) — four endpoints, the cards
that read them, and the one deployment that can prove a measured and an unmeasured cluster on the
same stack. M6 closed in wave 4 and owes nothing on substance; the two things it closed *with* rather
than *without* are carried below as items rather than as milestone obstacles.

**Why this shape.** Wave 4 answered the question three retrospectives could not. Assigning each
packet one named ungated rule to close **worked, ten times out of ten** — every one re-applied by an
independent verifier who watched the named case go red. It is the first mechanism in four waves with
a perfect hit rate, and it is kept unchanged below. It is also **losing three to one**: the same ten
packets shipped thirty-five new ungated rules, thirty-one of which the packet that wrote them did not
disclose, under a house rule that required exactly that disclosure. And a census of 89 rules in code
nobody was editing found 26 ungated — 29%, the standing rate, written long before wave 4.

So one instruction is not repeated a fourth time. **Three of this wave's twelve packets do not build
anything.** They mutate code they did not write, and they land the cases. The evidence for that split
is arithmetic rather than taste: builders found 4 of their own 35 holes and second readers found 31,
on the same code under the same rule. A packet mutates the thing it just wrote and finds the case it
just wrote, because the two were authored to one understanding. Wave 3 was told; wave 4 was told
twice, and disclosed a green mutation in ten reports out of ten while missing thirty-one holes.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Where two packets meet, the brief states
the exact contract both sides code against. Four dependencies are declared, all on a *stated shape*
and none on a diff: W5-03 on W5-01's four wires, W5-04 on the same four, W5-02 on the metric-family
names W5-01 reads, and W5-09 on W5-01 and W5-03 having landed before the merged documents are
regenerated.

**The adversarial packets are scoped to what nobody else owns, and that constraint is load-bearing.**
Wave 4's census lost runs to false reds because siblings were editing the files it was mutating —
contamination that can only manufacture a false *red*, never a false green, but which cost hours and
nearly produced a wrong headline. W5-A1, W5-A2 and W5-A3 therefore attack areas with no building
packet in them at all, and they own the **test** directories of what they attack so that a hole found
is a case landed rather than a paragraph filed.

**The designed intermediate state, again, and it is the same one as last wave.** W5-01 adds four
endpoints to `services/metrics` and regenerates that module's own `services/metrics/api/openapi.json`;
W5-03 adds their gateway routing. `./mill __.openApiCheck` will be **red on `services.gateway.api`
and green on the other six** until W5-09 regenerates `docs/api/openapi.json` and
`docs/api/openapi.browser.json`, because `build.mill:1513` aims the gateway module's check at those
two files. `./scripts/feature-matrix-check.sh` is red for the same reason, and W5-09 turns it green.
Do not repair either by editing a file you do not own.

**House rules that apply to every packet** — read them before starting.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of the
surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns (Scala
at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

The six rules from wave 4 stand. Four are new, and every one of them is a wave-4 finding rather than
a preference.

1. **No new stylesheet files.** `build-tests`'s `CssReferencesSuite` requires every stylesheet on disk
   to be named exactly once in `frontend/packages/kernel/styles/index.css`. Extend a stylesheet your
   package already has.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror
   lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte. The thirty-one that exist cover
   everything here: an exporter that will not answer is `KUI-UPSTREAM-UNAVAILABLE`, a scrape out of
   budget is `KUI-TIMEOUT`.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it, run
   the acceptance suite, record which case went red, revert it.
5. **Report a mutation that stayed green.** Alongside the red one, apply at least one mutation to a
   rule you did *not* write the test for and record what happened. This rule did not work last wave —
   ten reports carried a green mutation and thirty-one holes went unreported — so it is kept as a
   floor and not as the defence. The defence is packets A1, A2 and A3.
6. **No honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely with
   assertions that something is absent, refused or not configured. Every packet that ships a
   capability must assert the capability *working* against something real, and the refusal beside it.
7. **New: a card may not be drawn from a figure the design did not name.** Three of this wave's cards
   ask for numbers a Kafka broker does not publish — a purgatory *percentage*, a per-`client.id` byte
   rate, and a record-size *distribution*. Redrawing a card as what can be measured is a decision
   taken in an ADR, in the open. Deriving something close and labelling it with the design's word is
   definition-of-done rule 1 broken in the one service that exists to keep it.
8. **New: `./mill a.test b.test` runs nothing.** Mill parses the second task path as a **vararg to the
   first**, MUnit takes it as a test-name filter, it matches nothing, and every suite reports
   `0 failed, 1 ignored, 0 total` while Mill exits `SUCCESS`. A wave-4 census agent was handed exactly
   that command and would have measured an empty suite. The separator is `+`:
   `./mill services.cluster.__.test + services.topic.__.test`. Check every multi-target line you are
   given, including the ones in this file.
9. **New: a root cause is not established until it has been run in both directions.** The distributed
   stack's smoke failure was reported with a cause — a scrape loop that dies on its first failed pass
   — and a fix that made it pass. The cause is false; the loop recovers in one interval, proved twice.
   If you patch something, the report says what you observed with the patch **and without it**, and if
   the two do not explain each other you say so instead of closing the item.
10. **New: state the scope of a measurement, and expect the next reader to re-run it.** Wave 4's
   census reported a fail-open authorization guard as its worst finding and correctly noted it had run
   only `./mill libs.__.test`. Run against the suite that exercises it, that branch is gated; the
   branch beside it is not. The scoping note was honest and the headline was still wrong.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`.

**Running a browser suite.** `pnpm -C frontend e2e` drives a stack it does not start.
`deployment/quickstart/quickstart.sh` starts one — and it will happily reuse a container image built
from a tree that no longer exists. So: `./mill deployment.docker.allinone.docker.build` and
`docker build --no-cache -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .` **before**
`quickstart.sh`, every time the tree has changed. Two further things, both learned in wave 4: the
metrics buffer is **in memory**, so a restarted KUI has an empty series and a card that needs data
needs a minute; and a container restarted underneath a running suite will fail an unrelated case that
passes in isolation, so a single red in a 70-case run is re-run before it is reported.

**Running the a11y sweep** is three commands, not one — build Storybook, serve `storybook-static` on
`:6017`, then sweep. Two wave-4 packets saw it abort on an arbitrary story with *"asked for the dark
theme and got none"* under load, and each named a different story; the same sweep run twice at
integration was clean both times. It is a race in the harness, it is W5-05's item 6, and a single
abort with no axe violation printed is not an a11y failure — re-run the named story alone before you
report one.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None is
owned by the packet most likely to break it — that is the point of listing them. If your change makes
one red, it is your change that is unfinished, and the repair goes in the packet that owns the guard,
named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill services.gateway.api.openApiCheck` | A **byte** comparison against a fresh Tapir render of every service's endpoints. `build.mill:1513` aims the *gateway* module's check at these, so the gateway module goes red for somebody else's endpoint | W5-01 and W5-03; repaired only by W5-09 |
| `services/metrics/api/openapi.json` — that module's own `openApiCheck` | The same comparison one layer in, inside W5-01's boundary. W5-01 regenerates it and stays green | W5-01, for itself |
| `scripts/feature-matrix-check.sh` + the two `<!-- checked: merged-document -->` regions in `docs/adr/ADR-048-*.md` and `frontend/packages/api/README.md` | `N paths and M schemas`, `X-Kui-Principal on N of its M operations`, `X-Csrf-Token on N`. Today: 50 paths, 61 operations, 146 schemas, principal on 46 over 35, csrf on 20 | W5-01 and W5-03; repaired only by W5-09 |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml:217-220`'s regenerate-then-`git diff --exit-code` | The browser's types. **Nothing in Mill checks this**; that CI step is the only gate | W5-09 |
| `services/metrics/contract/test/resources/golden/*.json` | The metrics wire, byte for byte, inside W5-01's boundary | W5-01, for itself |
| `libs/contracts-core/test/resources/golden/*.json` + their Scala twins | Cluster and topic DTOs in *two* places per document | nobody this wave — no shared DTO changes. If you are about to, stop |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala:36-59` | A hard-coded sorted path list over `gatewayDoc + clusterDoc` only, so metrics paths cannot reach it | W5-03 if it adds a *gateway* path (it must not; it adds proxy routes) |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size == 3` over `ServiceContracts.proxied(cluster)`; distinct operationIds across the merged document. Four new **reads** move the operationId set and not the write count | W5-03 |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A hard-coded `Set` of service ids | nobody: no service is added this wave |
| `services/gateway/api/src/.../routing/ServiceContracts.scala:57-61` | A prose comment counting the schema service's bodied endpoints. It says three and it has been four since wave 4 | W5-03 owns the file and repairs the count |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted path set | W5-02 if it changes the all-in-one's configuration surface |
| `libs/config/test/src/kui/config/ShippedConfigurationSuite.scala:33-45` | A **hand-written** list of shipped configuration files, loaded through the real loader. It omits `deployment/compose/kui-service.yaml` — the file four service containers mount — and `kui-quickstart-auth.yaml`. Carved out of W5-A1 and given to W5-02, because W5-02 is the packet that edits those files | W5-02 owns both ends |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService`, compared against the containers | W5-02 owns the script; W5-03 owns the Scala file it scrapes |
| `deployment/metrics/kafka-jmx-exporter.yml` | Six metric names that are a stated contract with `PrometheusExposition`. Only two of the six are asserted by anything — `smoke.sh` greps the two byte-rate families and both healthchecks grep one | W5-02 owns the file and the assertion gap |
| `frontend/packages/*/src/recorded/*.json` + each package's `recorded.test.ts` | A **recorded** gateway answer replayed against the mapping. Re-cut it from a live stack; do not hand-edit it | W5-04 if it records the four new documents; W5-A2 (schemas, clusters) |
| `frontend/e2e/*.spec.ts` | Screen text, by role and by visible string. Allocated per file below | every frontend packet |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md` | 189 rows; 68 COMPLETE; 38% in two files, compared by `feature-matrix-check.sh`. `BR-002` still publishes 340 broker config rows and a live broker answers **341** | every packet that finishes a capability; repaired by W5-09 |
| `docs/FEATURE_MATRIX.md:503-517` | The **milestone** table — eleven rows, three columns and a 189/74/72 total — recomputed by wave 4 and sitting outside every `<!-- checked: -->` marker | W5-09 must bring it inside one |
| `build-tests/**` | The token mirror and the stylesheet roster | house rules 1 and 2 forbid it |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `scripts/run-tests.sh` | 63 modules, 56 with tests, and the seven with no test sources it names out loud. It derives its list from `./mill resolve __.test`, so nothing is hard-coded | any new module — and none is added this wave |
| `libs/config`'s `SafeUrl` | `http` and `https` only, per `ARCHITECTURE.md` §14. **A JMX service URL cannot be a `MetricsSourceSettings.url`** — this is why ADR-050 chose Prometheus and why MT-001 is not closed by deleting a case | W5-01 if it tries to reach JMX directly |
| `libs/config`'s `checkMetricsRules` (`KuiConfigSource.scala:1276-1315`) | `retention >= scrapeInterval` and `callTimeout < scrapeInterval`, both refused at load | W5-01 — see its item 6, which is a clamp for a configuration the loader forbids |
| `./mill __.checkFormat` and `./mill __.fix --check` | **Both are red today and neither is this wave's doing.** `libs.config` has six misformatted files and `tools/error-codes/.../BrowserConstants.scala` fails `DisableSyntax.throw` plus an import reorder. Both are byte-identical to `debd783`; the configs are unchanged. They are assigned below rather than carried a fourth time | W5-02 (`libs/config`) and W5-09 (`tools/error-codes`) |

---

## W5-01 — The three endpoints a broker can answer, and the one it cannot

**Owns**
```
services/metrics/**
docs/adr/ADR-052-metrics-endpoints.md                       (new)
```

**Contract.** Wave 4 built the adapter and it works: `PrometheusExposition` parses a text exposition,
`PrometheusThroughputScrape` fetches it inside `callTimeout`, `ThroughputBuffer` holds a
`SeriesWindow` per cluster whose never-sampled buckets are absent rather than zero, and
`ThroughputScrapeLoop` runs one fibre per configured cluster. Measured on the running quickstart:
`throughput?range=24h` answers `"status":"ok"` with **288 buckets**, of which 46 carry a rate. The
loop's recovery was tested by breaking it — one WARN per interval, then `open`, `halfopen`, `closed`,
and the series resumes; and a cold start whose first scrape fails fills a bucket 26 s after the
exporter returns. **You are extending working code, not rescuing it.**

**What a broker actually publishes, which decides three of the four endpoints.** The exporter beside
the quickstart serves six lines today because its ruleset whitelists three families; W5-02 widens it.
What widening reaches is Kafka's own JMX surface, and it answers some of §4's questions and not
others. Check this yourself against a real broker before you build — that is item 1 — but the expected
answer is:

* `kafka.network:type=RequestMetrics, name=TotalTimeMs, request=Produce|FetchConsumer` publishes
  `50thPercentile`, `99thPercentile`, `Mean`, `Max` and `Count`. **The p99 line is ordinary work.**
* `kafka.server:type=KafkaRequestHandlerPool, name=RequestHandlerAvgIdlePercent` is a meter whose
  `OneMinuteRate` is a ratio in 0..1, and
  `kafka.network:type=SocketServer, name=NetworkProcessorAvgIdlePercent` is a gauge in 0..1. **Two of
  §3.4's three ring gauges are ordinary work.**
* `kafka.server:type=DelayedOperationPurgatory, name=PurgatorySize` is a **queue length**. §3.4 draws
  "38% PURGATORY". There is no purgatory percentage to read, and a length divided by an invented
  ceiling is a fabricated percentage.
* §4 draws **Top producers by `client.id`**, and a broker publishes no per-`client.id` byte rate
  unless quotas are configured. It publishes a per-**topic** `BytesInPerSec`, which is a real and
  useful number and is not the number the design named.
* §3.5 draws a **twelve-bucket record-size histogram** with `p50 · 1.1 KB`, `p99 · 18 KB`,
  `max · 0.9 MB` chips. **Kafka publishes no record-size distribution at all** — only a mean, as
  bytes-in over messages-in.

House rule 7 is about exactly these three. Decide each in ADR-052 *before* writing the endpoint.

**Do**
1. **Measure first, and write it down.** Point a JMX exporter with the *stock* ruleset at the
   quickstart broker once, capture the exposition, and record in ADR-052 which families exist and
   which of §3.4/§3.5/§4's figures they can answer. This is the evidence the refusals rest on, and
   without it a `NotMeasured` sentence is indistinguishable from an endpoint nobody wrote — which is
   the trap M7's criterion sat in for two waves. Commit a trimmed capture as a test fixture.
2. `GET …/metrics/latency?window=` — a `Section`-wrapped series of p99 request latency, produce and
   fetch as two series, over the same range vocabulary `ThroughputRange` already spells. Same rules as
   throughput: `bucketCount` buckets for the window whatever was sampled, a never-sampled bucket
   **absent** and never zero, a failed scrape evicting nothing.
3. `GET …/metrics/request-handlers` — the current idle ratios, as ratios and not as pre-formatted
   percentages. Two of them if item 1 confirms purgatory is a length; if you ship a third reading it
   is a **count** with its own unit and the card says so.
4. `GET …/metrics/producers?top=` — whatever item 1 says is answerable. If it is topics rather than
   clients, ADR-052 says so, the field is named for what it holds, and W5-04 redraws the card's title
   to match. A field called `clientId` carrying a topic name is the defect this wave's rule 7 exists
   to stop.
5. `GET …/metrics/record-size` — the same decision. A mean record size is real; a distribution is not,
   and a twelve-bucket histogram assembled from a mean is a drawing of an assumption.
6. **The ungated rules this packet owns, and they have one cause.** Wave 4's verification found six
   rules decided in `MetricsWiring.scala` that no suite executes, because **no test constructs
   `MetricsWiring`**: the scrape interval (`ThroughputScrapeLoop.scala:56`, `.andWait(interval)` can
   become `interval * 60`), the call-timeout budget (`callTimeout * 100`), the bulkhead width and
   retry count (`MaxConcurrentPerExporter` 1 → 16, `MaxRetries` 0 → 5, both defended by a paragraph
   apiece), the buffer step (`scrapeInterval * 12`) and the sample ceiling
   (`maxSamplesPerSeries` → 1000000). Each is green today under the full suite.
   `ThroughputScrapeLoopSuite`'s own header admits it assembles the collector *"the way MetricsWiring
   assembles it"* — by hand, in the test. **Give `MetricsWiring` a suite that constructs it**, and
   the six close together. Two more beside them: `ThroughputScrapeLoop.resource` is entirely untested
   (only `pass` is exercised — nothing asserts the loop repeats, that it repeats at `interval`, that
   the first pass is immediate, or that it runs in the background, all four of which the file's own
   scaladoc argues for at length), and `PrometheusExposition.scala:156`'s `!value.isInfinite` half of
   the guard has no fixture, so an exporter serving `+Inf` files Infinity as a measured rate.
7. **Three comments that describe code that is not there, in files this packet owns.**
   `domain/Ports.scala` still heads a section *"There is no implementation of this port yet, and that
   is the point"* — there is one. `PrometheusExposition.scala:44-49` still describes the abandoned
   rule (*"a sample carrying a `topic` label is skipped… the same is true of the `partition` label"*)
   while the code applies `labels.keySet.forall(_ == AggregateLabel)`, and the scaladoc twenty lines
   below argues at length that a list of known slice labels is the wrong shape. And
   `PrometheusExpositionSuite.scala:52` states *"a parser that summed every match would answer
   348601.0"*; the three matching fixture lines are 98000.0, 26800.5 and 124800.5 and sum to
   **249601.0**. The sentence beside it is right; only the figure is invented.
8. **A clamp for a configuration that cannot exist.** `ThroughputBuffer.scala:83` passes
   `retention.max(step)` and its `@param retention` scaladoc says the two keys are *"bounded
   independently"* and that *"the loader accepts `scrapeInterval: 1h` beside `retention: 1m`"*. It
   does not: `KuiConfigSource.scala:1276-1285` raises a `ConfigProblem` for exactly that pair, and
   both values are inside their own Min/Max bounds so the rule fires rather than being skipped. So the
   clamp is unreachable, the paragraph justifying it is false, and `ThroughputBufferSuite`'s *"a
   retention shorter than one scrape interval builds a window rather than refusing to start"* asserts
   behaviour for a file no operator can write. Delete the clamp and the case, or keep them and rewrite
   the paragraph to say the invariant is defended twice on purpose — but the three must agree.
9. ADR-052: the three design decisions from item 1, the range vocabulary the four endpoints share, and
   why each `Section` is separate rather than one document — one dead family costing one card is the
   whole reason `Section` exists.

**Do not** widen `MetricsSourceSettings` or add a key to `kui.metrics`. If a metric name or a JMX
object name is about to be typed into YAML, stop: that is the adapter's business and
`MetricsConfig`'s own scaladoc says so. And do not implement `MetricsSourceKind.Jmx` — ADR-050's
reasoning has not changed and `SafeUrl` still forbids the address.

**Acceptance**
```
./mill services.metrics.__.test
./mill checkArchitecture
./mill services.metrics.__.checkFormat
./mill services.metrics.api.openApi         # regenerate this module's own document, and commit it
./mill services.metrics.api.openApiCheck    # then green here; the gateway's stays red until W5-09
```
Required cases, by name: a captured exposition becomes a latency sample carrying produce and fetch
p99; a window with three samples answers `bucketCount` buckets of which three carry values; an idle
ratio arrives as a ratio and is not pre-formatted; a family the exporter does not serve is a stated
refusal and not a zero; **a wiring built by `MetricsWiring.make` scrapes at the configured interval**;
**a wiring built by `MetricsWiring.make` bounds a scrape by the configured `callTimeout`**; the loop
runs more than once and its first pass is immediate; an exposition line of `+Inf` is not a sample.
**Mutation line:** `MetricsWiring.MaxRetries` from `0` to `5`. Name the case that goes red — there is
none today. **And a green one:** mutate the latency endpoint's window vocabulary and report whether
anything notices.

---

## W5-02 — The exporter's other families, and the smoke test nobody has explained

**Owns**
```
deployment/**
.github/workflows/ci.yml
apps/allinone/**
libs/config/**                                              (see item 6 and the note below)
libs/config/test/src/kui/config/ShippedConfigurationSuite.scala
```
`libs/config/**` — both `src/kui/config/` and `test/src/kui/config/`, including their `store/`
subdirectories — is carved out of W5-A1's block whole, so that one packet holds it and A1 mutates
nothing under it. Two things are done with it and nothing else: a formatter run (item 6) and two rows
added to `ShippedConfigurationSuite` (item 4). The suite is a hand-written registry of shipped
configuration files and this is the packet that edits those files; where a handoff is two rows in a
list, one packet owns both ends. **No behaviour in `libs/config` changes.**

**Contract.** `deployment/metrics/kafka-jmx-exporter.yml` whitelists three families and the live
exposition is six lines — checked. W5-01 needs more of them and will tell you which; the names are a
stated contract with `PrometheusExposition` and the file's own comment says so.

**The headline, and read it before you touch the compose file.** `./deployment/compose/smoke.sh`
fails, deterministically, at `FAILED: buckets carrying a measured rate was 'no' after 90s` — the one
assertion M7 exists to add. Two independent runs from a torn-down stack failed identically. Every step
before it passes, including `the exporter serves both byte-rate families` and `the measured cluster's
throughput: ok`. `.github/workflows/ci.yml:342` runs the script, so the compose job is red as shipped.

**The reported cause is false and the reported fix works, which is why this is item 1 rather than a
one-line patch.** It was reported that `kui-metrics` starts before its exporter — true, it has no
`depends_on` while `kafka-metrics` waits on the broker's health — and that the scrape loop then
"does not survive its first failed pass". That second half is wrong. Verified on the running
quickstart: stopping the exporter under a live KUI produces one WARN per interval, then
`circuit … is now open`, `halfopen`, `closed`, and the series resumes; and restarting KUI with **no
exporter at all**, so its first scrape fails exactly as it does in compose, fills a bucket **26
seconds** after the exporter comes back. The loop retries and the breaker recovers. Meanwhile the
compose stack seeds no traffic, so its exposition reads `0.0` — a *measured zero*, which is not null,
and `smoke.sh` asserts `bytesInPerSecond != null`. House rule 9 applies: run it with the patch and
without it, and if the two do not explain each other, say so rather than closing the item.

**Do**
1. **Reproduce and explain, then fix.** Build the seven images from the working tree by the derived
   list, `up -d --wait`, run `smoke.sh` from a torn-down stack. Record the container start times, every
   line `kui-metrics` logs, and what `throughput?range=24h` answers at 30 s intervals for five
   minutes. Then state the mechanism in one paragraph in `deployment/compose/README.md` and fix it.
   `depends_on` may well be the right fix; it is not the right fix *because* of a claim that is false.
2. The exporter families W5-01 asks for, in the one ruleset both stacks mount, with the comment's
   name list extended in the same edit — it is a contract and it is written down as one.
3. **The ungated rule this packet owns.** Of the six metric names that comment calls *"a CONTRACT with
   services/metrics's Prometheus reader"*, **two are asserted by anything at all**: `smoke.sh:347`
   greps the two byte-rate `oneminuterate` families, and both stacks' healthchecks grep one. Rename
   `MessagesInPerSec` or any of the three `_total` rules and everything stays green while
   `recordsPerSecond` is null on every bucket for ever — and `smoke.sh`'s own bucket assertion only
   tests `bytesInPerSecond != null`, so it cannot see it. Assert every name the reader reads, and the
   new ones with them. Prove it: rename one and show the failure.
4. **Two shipped configuration files that no suite loads.** `ShippedConfigurationSuite:33-45` names
   `deployment/compose/kui.yaml`, `kui-cluster.yaml`, `kui-allinone.yaml` and
   `deployment/quickstart/kui-quickstart.yaml` — and not `deployment/compose/kui-service.yaml`, which
   four of the six service containers mount, nor `kui-quickstart-auth.yaml`, which no CI job runs at
   all. Wave 4 demonstrated the cost: setting `callTimeout: "60s"` beside the default 30 s
   `scrapeInterval` in both files left `./mill libs.config.test` at 395/395 and
   `docker compose config -q` at exit 0, while the process refuses to boot with
   `kui-metrics cannot start; the configuration has problems: …callTimeout (60 seconds) must be
   shorter than kui.metrics.scrapeInterval`. Two rows close it. Add `./mill libs.config.test` to your
   acceptance list, which wave 4 shipped edits to that registry without running.
5. Two gates that pass for a reason unrelated to what they check. `ci.yml`'s
   `grep -oE 'image: kui-[a-z-]+:'` was widened for hyphenated names and the derived list is
   byte-identical today because every image is one word, so the fix is unobservable until somebody
   adds `kui-schema-registry`. And `smoke.sh`'s `$expected` vs `$contracts` print is unobservable
   because `readonly UNROUTED_CONTRACTS=""` makes the two lists the same. Either force the situation
   each exists for — a fixture image name, a non-empty `UNROUTED_CONTRACTS` in a dry-run mode — or say
   in the comment that the rule is unasserted and why. A comment claiming a defence nothing makes is
   the defect this wave counts.
6. **`./mill __.checkFormat` is red and has been since before wave 4.** `libs.config` has six
   misformatted files — `KuiConfig`, `KuiConfigSource`, `UpstreamAuthConfig`, `KsqlSettings`,
   `MetricsConfig`, `AlertsConfig` — all byte-identical to `debd783` with `.scalafmt.conf` unchanged.
   Run `./mill libs.config.reformat`, commit it **alone**, change no behaviour, and note in the commit
   that it is a formatter run. Nobody has owned these files for four waves, which is why it carried.
7. `docker-compose.auth.yml` is run by no CI job, so the `kui.metrics.sources` block in
   `kui-quickstart-auth.yaml` is exercised by nothing. Either give it a job, or state in the file why
   it is exercised only by hand.

**Acceptance**
```
./mill apps.allinone.test
./mill libs.config.test
./mill checkArchitecture
./mill apps.allinone.checkFormat
./mill libs.config.checkFormat
docker compose -f deployment/compose/docker-compose.yml config -q
# images built from the working tree by the derived list, then:
docker compose -f deployment/compose/docker-compose.yml up -d --wait
curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready
./deployment/compose/smoke.sh            # three consecutive runs, from a torn-down stack each time
./deployment/quickstart/quickstart.sh    # then curl every metrics endpoint
```
**Mutation line:** rename one metric name in `kafka-jmx-exporter.yml` and show the assertion that
catches it. **And a green one:** remove `kui.metrics.sources` from one configuration file and report
which of the seven things above notices.

---

## W5-03 — The gateway routes four more reads, and a permission check no test constructs

**Owns**
```
services/gateway/**
ARCHITECTURE.md
```

**Contract.** W5-01's four endpoints are `Section`-wrapped reads under
`/api/v1/clusters/{clusterId}/metrics/…`. The gateway proxies them the way it already proxies
`throughput`: a contract row, a capability fold entry, no new *gateway* path. `OpenApiMergeSuite`
holds a hard-coded path list over `gatewayDoc + clusterDoc` only, so a metrics path cannot reach it —
adding a gateway path of your own would, and you must not.

**Do**
1. Route the four. One dead exporter costs one card and not the tab, which is what `Section` is for
   and what `CapabilityFold`'s precedence table already gets right.
2. **The ungated rule this packet owns, and it closes four holes with one fixture change.**
   `SearchRig.Call` is `Call(operation, cluster: Option[ClusterId], q: Option[String])` — it records
   what was asked and never what was asked *for*. So the fan-out **cost** bound is ungated in all
   three search sources and the state filter with them: `GroupSearchSource.scala:62` and
   `SubjectSearchSource.scala:54` can both take `pageSize = 100` instead of `query.limit`,
   `TopicSearchSource.scala:59` can drop `.take(query.limit)` entirely, and
   `GroupSearchSource.scala:57` can send `states = Set(GroupState.Stable)` instead of `Set.empty` —
   all four green. `SubjectSearchSource`'s own comment argues the rule hardest of any in the fold:
   *"asking for ten rows is ten enrichments and asking for the contract's maximum would be five
   hundred. A search must never be the most expensive request in the product."* **Add `pageSize` and
   `states` to `Call`** and all four close at once. Note that wave 4 made the topic one *harder* to
   see: the new fold-level `SearchResultsDto.take(fairAcrossClusters(...), limit)` cuts every kind to
   `limit` at any cluster count, so a source that stopped capping cannot show through in the response
   body at all — and `interleave`'s new scaladoc then cites that very cap as its bound on recursion
   depth.
3. **The gateway's real permission check is constructed by no test.**
   `PolicyRbacPreCheck.scala`'s `Decision.Denied` branch can be made to log the denial and then return
   `().asRight` — the request proceeds to the upstream service — with the whole gateway suite green.
   `grep -rn "PolicyRbacPreCheck" services/gateway --include=*.scala | grep -i test` returns nothing:
   every routing suite injects `RbacPreCheck.allowAll` or `denyAll`, which proves the *seam* is
   consulted and says nothing about the class that decides the answer. Construct it.
4. `BrowserProjection.keep` strips every `X-Kui-*` header parameter from the document the browser's
   client is generated from (ADR-040, ADR-048 §3 — *"a browser must never send one"*). Breaking the
   prefix test so every reserved header is retained leaves the suite green, because
   `BrowserProjectionSuite` deliberately asserts against the **committed** file rather than a fresh
   projection — its header explains why, and the reasoning is sound. The consequence is that the
   projection *function* is gated by nothing in this module. Gate the function without giving up the
   committed-file assertion; they answer different questions.
5. **Three comments in files this packet owns that describe code that is not there.**
   `routing/ServiceContracts.scala:57-61` says the schema service publishes *"its **three** bodied
   endpoints"* from the second list and that the list holds *"the **two** compatibility writes and the
   compatibility check"* — since wave 4 it is four and three. `TopicOverviewDtoSuite.scala:16-21`
   still says *"Cross-compiled deliberately… These assertions run on the JVM and under Node"*, and
   `./mill resolve services.gateway.contract._` names one module, `.jvm`; wave 4 fixed the identical
   claim in `SearchDtoSuite`'s header and renamed a test three lines below this one without reading
   it. And `ARCHITECTURE.md:209` says A7 *"is enforced by the bundle-shape assertion in BUILD-006"* —
   BUILD-006 is the dead Scala `BundleShape.scala` that parses Scala.js output ADR-048 deleted; A7 is
   now enforced by `frontend/scripts/bundle-shape.mjs` through `pnpm bundle-shape` in the frontend
   job.
6. **Settle the `partial` question in ADR-049 rather than leaving it in a comment.** Wave 4 made a
   deployment with no clusters distinguishable from a search that matched nothing by naming topics,
   consumers and schemas in `partial` — which the browser renders as *"Topics, Consumer groups, Schema
   Registry"* under `unavailableServices`. ADR-049 §2 defines `partial` as the services the gateway
   **could not ask**, via four enumerated causes, and this is not one of them: with no clusters all
   three are routed and perfectly askable, there is simply nothing to ask about. It errs safely and
   the sentence a user reads is not literally true. Either add the fifth cause to §2 or give the
   answer a field of its own; a decision, either way, written down.
7. `GatewayWiringSuite.theGatewayStartsWhenEveryUpstreamIsUnreachable` failed on one of three full-suite
   runs during wave 4's verification and passed in isolation and on the other two. It is not this
   packet's doing — wave 4's only edit to that file was a comment — but it is a flake in the command
   this packet is graded on. Find it or quarantine it with a stated reason.

**Acceptance**
```
./mill services.gateway.__.test       # three consecutive runs, because of item 7
./mill checkArchitecture
./mill services.gateway.__.checkFormat
./mill services.gateway.api.openApiCheck   # red against docs/api/** until W5-09; that is designed
```
Required cases, by name: a metrics endpoint the gateway routes reaches the service and its Section
survives; one unavailable metrics family costs one card and not the tab; **a search asks each source
for the caller's `limit` and no more**; **a group search asks for every state**; a request the policy
denies does not reach the upstream service; a reserved header parameter never reaches the browser
projection.
**Mutation line:** `SubjectSearchSource`'s `pageSize = query.limit` → `pageSize = 100`. Name the case
that goes red — there is none today. **And a green one:** mutate the capability fold's `since` and
report what happens.

---

## W5-04 — The Traffic tab's last row, and two cards that must keep their sentence

**Owns**
```
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/pages/**
frontend/packages/shell/styles/32-shell-dashboard.css
frontend/packages/shell/styles/37-overview.css
frontend/e2e/traffic.spec.ts
```

**Contract, stated so this packet does not wait on W5-01's diff.** Each of the four new endpoints
answers a `Section`-wrapped document with `status` in `ok | stale | unavailable | not_configured` and,
when `ok`, `data` in the shape ADR-052 states. The rules are the ones the throughput card already
keeps and they are not negotiable: **a never-sampled bucket is a gap, never a zero and never
interpolated**; a `not_configured` cluster draws the `NotMeasured` sentence and no axis; a bucket
count is a property of the range and not of what was sampled.

**Read W5-01's item 1 before you draw anything.** Three of §4's figures may not exist. If ADR-052 says
a card cannot be measured from the configured source, **the card keeps its sentence on both clusters**
and this packet asserts that — which is not a refusal-only acceptance under house rule 6, because the
two cards beside it assert real series on the same screen.

**Do**
1. The p99 latency card: two series, produce and fetch, the current value in each legend chip, over
   the same range selector the throughput card puts in the address.
2. The ring gauges. §3.4 needs an explicit `goodDirection` — 64% idle is green and 38% purgatory is
   amber in the same card, so `Donut`'s `warnBelow`/`criticalBelow` bakes in "higher is better" and is
   wrong here. `RingGauge` exists in the kernel; W5-05 owns it, so any change to it is a
   `needsOutsideOwnership` row and not an edit. An unmeasured gauge draws the plain track and an em
   dash, never a full ring.
3. Top producers, and this is where `Monogram` finally gets a caller four waves after it was built —
   **if** ADR-052 says the figure exists. If the answer is topics rather than clients, the card's
   title says topics. A tile labelled `client.id` over a topic name is the defect house rule 7 names.
4. The message-size histogram, on the same condition, and the condition is likely to be no.
5. The four stat-card sparklines.
6. **The ungated rules this packet owns, both of them sentences the screen tells the operator.**
   `Overview.tsx`'s `trafficLede` chooses between *"KUI measures the first of those."* and *"Nothing
   has been sampled in this window yet."* on `hasMeasuredBucket`; collapsing it to the first string
   leaves 162 cases green, so a cluster whose exporter is reachable and has sampled nothing gets a
   cheerful line over 288 blank steps — precisely what the function's own eight-line comment says it
   exists to prevent. There is a fixture (`THROUGHPUT_ALL_ABSENT`) and a story
   (`screens-traffic--nothing-sampled`) and neither is asserted. And `ThroughputCard`'s `captionOf`
   stale branch can be replaced with `return chart?.caption` — 162 green — because **nothing anywhere
   renders the card in the `stale` state**: no fixture, no story, no render case. Last-known-good data
   then draws as though it were current, with no badge (deliberately) and now no sentence either,
   which is the same defect class the brokers screen was repaired for in the same wave.
7. Three dead exports and one incorrect comment, in files this packet owns.
   `NOT_CONFIGURED_SENTENCE`, `FORBIDDEN_SENTENCE` and `NO_SAMPLES_SENTENCE` are exported from
   `ThroughputCard.tsx` and referenced by nothing outside it, while the render tests and
   `traffic.spec.ts` retype fragments of them — exactly the drift this packet avoided for
   `UNMEASURED_DISK` by importing it. And `throughput.ts`'s `DEFAULT_THROUGHPUT_RANGE` doc says *"the
   agreement is asserted rather than assumed: see `throughput.test.ts`"* and that a disagreement would
   make the card *"ask for one window and label it with another"*. Nothing in the tree compares the
   browser default with the Scala one, and the stated failure cannot occur because `fetchThroughput`
   always sends the resolved range in `?range=`. Both halves are wrong.
8. The two new wave-4 cards render `NotMeasured why={… ? props.model.topProducers.why : ""}` — an
   empty sentence on the branch that should be impossible. It is inherited from the latency card
   rather than invented, and it is now in three places on this tab. Make the impossible branch
   structurally impossible or make it say something.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/overview packages/shell/src/pages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-overview|screens-traffic|screens-settings'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: a latency range with a null bucket draws a gap and not a zero; a ring gauge
with no reading draws the track and an em dash; **a reachable exporter that has sampled nothing does
not claim to be measuring**; **a stale answer says so in the caption**; a card ADR-052 calls
unmeasurable draws its sentence on a cluster that *is* measured; the range selector puts the range in
the address.
**Mutation line:** make a null latency bucket render as `0`. Name the case that goes red.
**And a green one:** mutate a legend chip's current-value derivation and report what happens.

---

## W5-05 — The kernel, which has had no owner since M3, and six rules to prove it

**Owns**
```
frontend/packages/kernel/**
frontend/scripts/a11y-stories.mjs
```

**Contract.** Every screen in the product is drawn with these primitives and **no packet has owned
this tree for three waves** — wave 4's partition said in as many words that `frontend/packages/kernel/**`
*"is unowned and needs no edit"*. Wave 4's census then mutated 23 rules here and **six survived**,
and every one is live code on a path the suite executes. That is the cost of an unowned tree, and this
packet is the correction rather than a feature.

**Do — the six, each with the mutation that proves it.**
1. `numbers.ts`'s `share(value, of)`: replacing the whole body with `return value / of` leaves 1324
   tests green. `0/0` is `NaN` and `5/0` is `Infinity`, and both reach `MagnitudeBar`'s width through
   `feature-clusters/src/BrokerDetail.tsx:172` — the comment argues exactly this, that NaN slips
   through `Math.min`/`Math.max` and lands in the stylesheet as a width. Note the shape of the gap
   before you fix it: `charts/format.ts`'s `fraction()` states the same rule, has a test file beside
   it, and caught its mutation. Two modules state one rule and one of them is checked.
2. `numbers.ts`'s `formatDelta`: replacing the signed format with `formatCount(value)` leaves 1324
   green, so `+4,212` and `-4,212` render alike in the offset-reset preview
   (`feature-consumers/src/ResetWizard.tsx:498`) — and "this rewinds 4212 records" against "this skips
   4212 records" is the only thing that preview decides.
3. `data/capabilities/store.ts`'s polling-episode guard: replacing all three
   `if (!polling || current !== episode) return;` with `if (!polling) return;` leaves 1324 green. The
   comment states the failure exactly — a stream that flaps twice inside one poll interval leaves two
   independent chains polling and re-opening the stream on their own timers, for ever.
4. The same file's `stop()`: deleting `episode += 1;` leaves 1324 green, so a poll scheduled before
   `stop()` still fires and re-opens the stream against a gateway the user may no longer be
   authenticated to. With 3 this is one mechanism gated at neither end.
5. `components/overlay.ts`: restoring `document.body.style.overflow = ""` instead of the previous
   value leaves 1324 green — the nested-overlay defect the comment was written to prevent, where the
   inner overlay closing unlocks the page under the outer one.
6. `data/sse/stream.ts`: deleting `if (transport.aborted()) return;` from the send rejection handler
   leaves 1324 green, so every client-initiated `close()` reports a transport error to the subscriber
   and ends with *"the connection could not be established"* instead of *"closed by the client"*.

   The shape is worth naming in whatever comment you leave: four of the six are **teardown** — what
   happens on the way out, reached only by close/stop/unmount sequences no test drives — and two are
   formatting helpers whose twin in another module has a test file and is gated.
7. **The a11y harness has a race, and it is this packet's because every packet's acceptance depends on
   it.** Two wave-4 packets saw `frontend/scripts/a11y-stories.mjs` abort on an arbitrary story with
   *"asked for the <theme> theme and got none. Not checking a theme twice."* — a different story each
   time, each passing when run alone, with **no axe violation printed in any run** — under a load
   average of 21 on 16 cores. The same sweep run twice at integration was clean. It is a 10 s
   `data-theme` wait losing to load, not an accessibility failure, and it will fail CI
   nondeterministically. Make the wait adaptive or retry the theme switch before giving up, and make
   the failure message distinguish "the harness could not set the theme" from "this story has a
   violation", because as written they read the same.
8. `icon.tsx`'s `star` shape has had no caller since wave 4 removed the favourites branch —
   `grep -rn '"star"' frontend/` is empty. `iconNames` is derived from `SHAPES` and
   `IconTile.stories.tsx` still renders it, so nothing breaks either way. Delete it or say in the file
   why an icon with no caller is kept.

**Do not** add a custom property to `10-tokens.css` or a stylesheet file — house rules 1 and 2 —
because `build-tests/**` mirrors both and is owned by nobody. And do not change `RingGauge`'s
signature without a `needsOutsideOwnership` row: W5-04 draws three of them this wave and needs
`goodDirection`.

**Acceptance**
```
pnpm -C frontend test packages/kernel
pnpm -C frontend test                      # the whole suite: the kernel's invariants are load-bearing
pnpm -C frontend typecheck                 # in eight packages downstream
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs     # the unfiltered sweep, twice, and both must be clean
```
Required cases, by name, one per rule above: an unknown share draws an empty bar and not a full one; a
positive delta carries its sign; a stream that flaps twice inside one poll interval leaves one poll
chain; a store that has been stopped does not re-open its stream; a nested overlay closing does not
unlock the page beneath it; a client-initiated close is not reported as a transport failure.
**Mutation line:** any one of the six above, re-applied. Name the case that now goes red.
**And a green one:** mutate a primitive nobody asked you to touch — `charts/window.ts`'s scroll clamp,
`query/cache.ts`'s eviction rule — and report whether the suite notices.

---

## W5-06 — The frame: a defence that moved and is deletable at both ends

**Owns**
```
frontend/packages/shell/src/App.tsx
frontend/packages/shell/src/app.render.test.tsx
frontend/packages/shell/src/shell.test.tsx
frontend/packages/shell/src/chrome/**
frontend/packages/shell/src/nav/**
frontend/packages/shell/src/data/**
frontend/packages/shell/src/routing/**
frontend/packages/shell/src/index.ts
frontend/packages/shell/styles/30-shell.css
frontend/packages/shell/styles/31-shell-nav.css
frontend/packages/shell/styles/33-chrome-drawer.css
frontend/packages/shell/styles/34-chrome-topbar.css
frontend/packages/shell/styles/35-chrome-navigation.css
frontend/packages/shell/styles/36-frame.css
frontend/packages/shell/styles/38-chrome-rail.css
frontend/e2e/shell.spec.ts
frontend/e2e/dashboard.spec.ts
frontend/e2e/search.spec.ts
```

**Contract.** The shell's route table is **not** widened: a feature reaches its own pages through
`kui.paths.*`, `KuiPaths` gains no member, and no feature edits `routing/**`. `DashboardTab` and
`shellPaths.dashboard` must keep agreeing; `tabs.ts` says so and it is W5-04's, so this packet adds no
dashboard address.

**Do**
1. **The ungated rule this packet owns, and it is wave 4's item 6 come back worse.** That item said:
   *"Keep the guard or drop it, but do not leave a comment claiming a defence that something else is
   making."* Wave 4 kept `App.tsx`'s `names.length === 0` clause and rewrote **both** comments to move
   the claim onto `NavItem` — *"the renderer, which defends itself"*, and *"a row is a branch when it
   has children, so an empty array gets no disclosure. A chevron that opens onto nothing is a control
   that appears broken."* Both ends are deletable with 223 cases green: `NavItem.tsx:69`'s
   `children().length > 0` can become `props.destination.children !== undefined` (an empty array now
   draws a chevron opening onto nothing) and `App.tsx:715`'s `names.length === 0` clause can go, and
   together a cluster with zero topics renders an expandable Topics row over an empty subtree with
   nothing red. One unverifiable comment became two. Close both, with a case that constructs a
   `NavDestination` carrying `children: []` — no case in `chrome.test.tsx` does.
2. `SearchField.tsx:167`'s `onBlur={() => window.setTimeout(() => setFocused(false), 120)}` can become
   `onBlur={() => setFocused(false)}` with 223 green. It is pre-existing rather than shipped last
   wave, it was disclosed, and the 120 ms is the whole reason a click on a result lands before the
   panel closes.
3. `nav/topicTree.ts`'s `TopicTreeInput` is referenced only by its own declaration, its own function
   signature and the barrel that re-exports it: `App.tsx:716` calls `topicTree` with an inline object
   literal and never names the type, and `topicTree.test.ts` does the same. Give it a use or stop
   exporting it.
4. `research/design/SCREENS-V4.md` §2.2 item 4 draws *"Two starred favourites by exact name, then
   prefix groups with counts, then a padlocked internal"*, and the shipped drawer no longer matches
   after wave 4 removed the favourites branch. Wave 4 sanctioned either outcome and `topicTree.ts`'s
   header records what bringing it back costs, so this is a recorded decision rather than a regression
   — but it is recorded **only in a source comment**. Put it where a designer will find it, or build
   it.
5. `docs/plan/ROADMAP.md` said M4 shipped a favourites branch that *"is still not reachable"*; three
   of the four things it enumerates no longer exist. That paragraph is a milestone record and this
   file has now corrected it; nothing is owed. Named here so it is not re-filed.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/chrome packages/shell/src/nav packages/shell/src/data packages/shell/src/routing packages/shell/src/app.render.test.tsx packages/shell/src/shell.test.tsx
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'chrome-|shell-'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: a destination with an empty children array draws no disclosure; a memo over
an empty topic list yields no subtree; a click on a search result lands before the panel closes.
**Mutation line:** `NavItem`'s `branch` predicate → `props.destination.children !== undefined`. Name
the case that goes red — there is none today. **And a green one:** mutate the cluster store's
`COUNT_PAGE_SIZE` and report what happens.

---

## W5-07 — Consumers and messages: a toast that reports the wrong number, and a browse that can duplicate a page

**Owns**
```
frontend/packages/feature-consumers/**
frontend/packages/feature-messages/**
frontend/e2e/consumers.spec.ts
frontend/e2e/messages.spec.ts
```

**Contract.** These two packages were rewritten in wave 4 and gated unusually well — 20 of 22
mutations went red. What survived is concentrated and each one is a sentence or a number an operator
acts on.

**Do**
1. **The ungated rule this packet owns, and it is the packet's own required case defeated by its own
   fixture.** `MessagesRoute.tsx:506` reports a copy as `written of read`, and the comment three lines
   above states the rule verbatim: *"`written`, not `requested`: the request said how many records to
   try for and the answer says how many arrived, and on a range that retention has eaten those are
   different numbers. Reporting the first would be reporting the intention."* Changing `written` to
   `read` in that template leaves 150 cases green. The case named for the rule — *"a copy that moved
   records raises a success toast carrying both figures"* — has a fixture of `{read: 3, written: 3}`
   and asserts `"3 of 3"`, and the only other resend case is `{read: 0, written: 0}`. **No case in
   either package has `read != written`**, which is the one state the rule exists for: retention ate
   part of the range, and the operator is told twelve records moved when three did.
2. `GroupList.tsx:134-136`'s filtered-list voice. Guarding the branch off leaves 85 green, and the
   sentence `No consumer group on this cluster is named like ${term}.` appears in exactly one place in
   the repository — that line. With it gone the fall-through reaches the *other* sentence wave 4 added,
   *"No consumer groups on this cluster."*, so a filter that matched nothing tells the operator the
   cluster has no consumer groups at all. Both halves of the voice were added together and only the
   counted-zero half got a test.
3. **Five rules the census found here, in code wave 4 did not write.** `session.ts`'s
   `canLoadMore` requires `!running()` — dropping it offers Load-more while a browse is still
   streaming; `loadMore()`'s `cursorNow === undefined` short-circuit — dropping it re-runs the last
   query and appends a duplicate page, *"a button that scrolled the user back to where they began"* in
   the comment's words; and `run()`'s `cursorNow = undefined; setCursor(undefined)` — dropping it
   leaves a new browse inheriting the previous page's continuation. Those three mask each other, so no
   single-point mutation is caught and a stale cursor is reachable when two are gone. Then
   `detail.ts`'s `targetOption` exhaustiveness throw, and — the one that matters most — `write.ts`'s
   reset plan `current: typeof payload.current === "number" ? … : null`, whose comment says *"null,
   never 0. A group that has never committed on this partition and a group sitting at offset zero are
   different facts… Rendering both as 0 tells an operator a group has consumed the first record when
   it has consumed nothing."* All five are live code — probed by making each throw, which reddened the
   suite every time — and all five are unasserted.
4. Three more in the same files: `lag.ts:177`'s `Math.max(advised, MIN_POLL_MS)` floor, whose docblock
   argues at length that a `0` from a bug or a truncated body *"must not turn this into a request loop
   that describes groups as fast as the browser can ask"*; `lag.ts:175`'s `payload.token !== ""`
   guard, so an empty-string token is adopted as a real one; and `GroupList.tsx:197`'s
   `mayHaveMore`, which mutated to `=> true` leaves 85 green — the paginator's Next enablement is
   asserted nowhere.
5. `MessagesRoute.tsx:364`'s `notify("Filter saved", …)` is the sixth `notify` call in these two
   packages and the only one nothing asserts; `"Filter saved"` appears nowhere else in `frontend/`.
6. `ResendDialog.tsx:285` reads `describePartitions(props.partitionCount ?? 0)` inside a
   `<Show when={props.partitionCount !== undefined}>`, so the `?? 0` is unreachable today — but it is a
   pre-written *"has 0 partitions"*, the exact sentence this file's comment, this packet's brief and
   an e2e assertion all exist to prevent. A keyed `<Show>` child or a narrowed local makes the guard
   structural instead of relying on a sibling condition staying in sync.
7. `feature-consumers/src/write.ts:177`'s `deleteOffsets` has no caller anywhere outside its own
   declaration, and the control it belongs to — `Forget this group's offsets on one topic` — appears
   nowhere in `frontend/`. `docs/FEATURE_MATRIX.md`'s CG-005 moved to `IMPLEMENTING` for this reason
   and nobody owns the code. Build the control or delete the function.

**Acceptance**
```
pnpm -C frontend test packages/feature-consumers packages/feature-messages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-consumer|consumers-|messages-|screens-message'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: **a copy whose source was partly eaten by retention reports both figures and
they differ**; a filter that matched nothing says so and does not say the cluster is empty; Load-more
is not offered while a browse is running; a load-more with no cursor does nothing; a new browse does
not inherit the previous page's cursor; a reset plan for a group that never committed shows no offset
and not a zero; an advised poll interval of zero is floored.
**Mutation line:** `written` → `read` in the resend toast. Name the case that goes red — there is none
today. **And a green one:** mutate the lag merge and report what happens.

---

## W5-08 — Topics: a live effect nothing exercises, and a parameter spelling the wire does not have

**Owns**
```
frontend/packages/feature-topics/**
frontend/e2e/topics.spec.ts
```

**Do**
1. **The ungated rule this packet owns.** `TopicsRoute.tsx:346-353`'s address-following
   `createEffect` can be deleted outright — 127 unit cases and 70 browser cases still green. It is
   **live code**, confirmed by probe, carrying a three-sentence comment that claims a defence:
   *"The address keeps being read, not read once… clicking a second one while this screen is already
   on does not remount the route."* Nothing exercises that: both unit address cases mount fresh, so
   the seed answers them, and the dashboard prefix-row case routes in and remounts. Note that wave 4's
   report has this backwards — it says the seed mutation stayed green and the effect rescued it; the
   seed is the **gated** half (`queryFromAddress(listLocation.search)` → `queryFromAddress("")`
   reddens both address cases) and the effect is the ungated one. Closing it costs one case: mount at
   `…/topics?q=orders.`, push a second search string onto the location, assert a second topics request
   carrying the new `q`.
2. `TopicListPage.tsx:192`'s `params.get("showInternal") === "true"` can be loosened to `!== null`
   with 127 green, so `?showInternal=1` and `?showInternal=false` would both light the Internal chip
   and ask the server for bookkeeping topics. The comment above it states the rule as deliberate —
   *"Only `true`. A `?showInternal=1` this screen decided to honour would be a parameter spelling the
   wire does not have, invented in the browser"* — which is a claimed defence with no case behind it.
   One line in the existing case closes it.
3. `TopicListPage.tsx:189`: dropping `.trim()` from `(params.get("q") ?? "").trim()` leaves 127 green,
   so `?q=%20orders.%20` reaches the server as a search nothing matches. No comment claims this one,
   which is why it ranks below the other two.
4. Two pre-existing holes wave 4 disclosed and did not close, both in this packet's files: the
   statistics `unavailableReason` spread at `TopicsRoute.tsx:490-495` can be replaced with `{...{}}`,
   and the purge toast's `tone:` at `TopicsRoute.tsx:885` can be hard-coded to `"success"`, each with
   127 green. Neither is code wave 4 shipped and the report is the only record they exist.
5. `TopicListPage.tsx`'s bulk bar `onDismiss={() => props.onSelectionChange?.(new Set())}` can become
   `() => undefined` — the bar's dismiss stops clearing the selection — with 127 green.
6. Two of wave 4's own required case **names** were not used, and the wave doc greps for names:
   `a ?q= in the address filters the list` shipped as *"a ?q= in the address arrives at a filtered
   list"*, and the partition-total case kept its old name. Both assert the right thing. Use the names
   this file asks for, or say in the report which name you shipped and why.
7. Two stories changed rendering silently: the short-table notice used to need 500 partition rows and
   now fires on any shortfall, so `TopicOverview` and `TopicOverviewNotMeasured` draw
   *"This table shows 6 of 12 partitions."* and *"3 of 6"* where they previously drew nothing. The
   behaviour is honest; both doc comments still describe the old rendering.

**Acceptance**
```
pnpm -C frontend test packages/feature-topics
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-topic|topics-'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: **a second address change on a mounted route reaches the server**; a
`?showInternal=1` is not honoured; a `?q=` with surrounding space is trimmed before it is sent; the
statistics region names why a figure is unavailable; a purge that partly refused raises a warning
toast.
**Mutation line:** delete the address-following `createEffect`. Name the case that goes red — there is
none today. **And a green one:** mutate the facet bar's chip ordering and report what happens.

---

## W5-09 — The published documents, the counts, and a gate that measures its own liveness

**Owns**
```
docs/api/**
frontend/packages/api/src/schema.d.ts
frontend/packages/api/README.md
docs/FEATURE_MATRIX.md
README.md
TECH_DEBT.md
DECISIONS.md
docs/adr/ADR-012-microfrontend-loading-strategy.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
scripts/feature-matrix-check.sh
tools/error-codes/**
```
**depends on W5-01 and W5-03.**

**Contract.** Two packets add endpoints, so the merged documents and the browser's generated types
move and `./scripts/feature-matrix-check.sh` is red on arrival. Regenerate; write no Scala and no
TypeScript by hand. `./mill services.gateway.api.openApi` renders both merged documents,
`pnpm --filter @kui/api run generate` renders `schema.d.ts`, both are byte-reproducible, and a hand
edit anywhere breaks the very check this packet exists to make green. Today's figures, measured here:
50 paths, 61 operations, 146 schemas, `X-Kui-Principal` on 46 operations over 35 paths,
`X-Csrf-Token` on 20.

**Do**
1. Regenerate the three documents and update both `<!-- checked: merged-document -->` regions to the
   counts the regenerated files actually carry. Derive them with `jq`, not from a packet's note.
2. **The ungated rule this packet owns, and it is last wave's rule reopened one line over.** Wave 4
   closed the glob — the manifests are a named array and disk is reconciled against it, both halves
   proven able to fail. But `close_section` floors each section at `counted > 0` and not at a count,
   so handing `jq` `"${manifests[0]}"` instead of `"${manifests[@]}"` prints
   `feature-matrix-check: 45 claims checked, all true.` and exits 0 — **byte for byte the output the
   broken glob produced**. Three more assertions can be deleted the same way: narrowing the
   `dependencies` selector to one key drops twenty of twenty-five claims and still exits 0; deleting
   the `X-Csrf-Token` branch from `check_document_region` drops the csrf claim ADR-048 publishes; and
   deleting `check_rows_region`'s "a state exists in the rows and is named nowhere in the prose" loop
   changes the printed total by **nothing at all**, because that loop increments `failures` and never
   `assertions`. Give every section a per-section **count**, not a floor — the number it published
   last time — and prove all four.
3. `docs/FEATURE_MATRIX.md:503-517`'s milestone table — eleven rows, three columns, a 189/74/72 total,
   recomputed by wave 4 — sits outside every checked marker. Moving one row's Milestone cell leaves the
   table wrong and the checker green. Wave 4 brought the second copy of the **state** totals inside a
   marker and left the **milestone** totals, in the same file, two paragraphs above.
4. **Move the rows M7 finishes**, and correct one that was transcribed rather than measured: `BR-002`
   publishes 340 broker config rows at 61,531 bytes; a live broker answers **341** on both stacks.
   Wave 4's brief carried the 340 and the packet copied it from the brief — which is the failure that
   Do item existed to end.
5. `TECH_DEBT.md`: TD-023 records the ungated-rule class and lists nine of wave 4's holes as open, on
   the two packets that had been verified when it was written. **The real number is thirty-five**, and
   a census of code nobody was editing found twenty-six more in 89 rules — 29%. Update the row with
   both figures, name the packet each is assigned to in this wave, and record the correction that
   matters: the fail-open authorization branch reported as the census's worst finding **is gated**, by
   `services/cluster/api`'s `ServiceRbacGuardSuite` — two named cases go red when it is made to fail
   open, run here — while the branch beside it, refusing an endpoint the policy cannot decide at all,
   is gated by nothing and leaves the cluster-api suite at 752/752 green. TD-017 through TD-021 are
   unchanged; TD-020's exit condition (*"confirm no feature package calls `history.pushState`"*) is
   one grep and can be settled either way.
6. `ADR-012:65` says *"BUILD-006 still fails the build if a real class reference leaks"*. It does not:
   `BundleShape.scala` parses Scala.js linker output ADR-048 deleted and no `checkBundleShape` task
   remains. `ARCHITECTURE.md`'s twin sentence is W5-03's; this one is yours.
7. `./mill __.fix --check` is red on `tools/error-codes/src/kui/tools/BrowserConstants.scala:228`
   (`DisableSyntax.throw`) plus an import reordering, byte-identical to `debd783` with the scalafix
   config unchanged. It has carried through four waves because nobody owned the file. Repair it, and
   change no generated output — `./mill frontend.apiConstants --check` must still print
   `is up to date (31 codes)`.

**Acceptance**
```
./mill __.openApiCheck                     # green, and it is this packet that makes it so
./mill __.fix --check                      # green, and likewise
pnpm --filter @kui/api run generate        # twice; the second run must change nothing
rm -rf frontend/.tsbuild && pnpm -C frontend typecheck
./mill frontend.apiConstants --check
./scripts/feature-matrix-check.sh
```
Note on the last line of wave 4's list: `git diff --exit-code schema.d.ts` after a regenerate **cannot
pass on an uncommitted wave**, because the wave itself is the diff. The property it stands for is that
a second regenerate changes nothing, which the line above asserts, and the real gate is
`ci.yml:217-220`. Do not report a red exit for it; report the regenerate.
**Mutation line:** hand `jq` one manifest instead of all nine and show the run failing rather than
printing `45 claims checked, all true.` **And a green one:** move a row's Milestone cell and report
whether anything notices.

---

## W5-A1 — Adversarial: `libs/` and the five services nobody is editing

**Owns**
```
libs/cache/test/**   libs/contracts-core/test/**   libs/filter/test/**
libs/http/test/**    libs/kernel/test/**           libs/observability/test/**
libs/security-core/test/**   libs/serde/test/**    libs/testkit/test/**
                          — every libs test tree EXCEPT libs/config/**, which is W5-02's whole
services/cluster/*/test/**
services/topic/*/test/**
services/consumer/*/test/**
services/message/*/test/**
services/identity/*/test/**
```
It owns **no production source anywhere**. It mutates production code and reverts it; a repair it
believes is needed goes in `needsOutsideOwnership` with the mutation that proves it.
`libs/config/**` is excluded entirely — W5-02 is reformatting it and editing its suite.

**Contract.** This packet builds nothing. Its deliverable is **cases**, and its report is a
measurement. Wave 4's census mutated 22 rules in `libs/` and 16 across cluster/topic/consumer and
found 13 ungated; this packet starts from those, confirms or refutes each **against the suite that
actually exercises it** rather than the nearest one, and then goes looking for more.

**Do**
1. **Re-run the thirteen, correctly scoped.** Two of the five `libs/` findings were measured with
   `./mill libs.__.test` alone and the scope changes the answer. `RbacGuard.fromPolicy`'s
   `Decision.Denied` branch made to return `().asRight` is **caught** — `services/cluster/api`'s
   `ServiceRbacGuardSuite`, two named cases, run here. Its `Left(problem)` branch — the fail-closed
   refusal of an endpoint the authorization declaration cannot cover — made to fail open leaves
   `./mill services.cluster.api.test` at **752/752 SUCCESS**, and nothing else in the repository pins
   it either. That is the severe one and it is yours. `PrincipalVerification.reject` returning a
   distinguishable 401 per reason — the file's scaladoc argues at length that a distinguishable 401 is
   an oracle an attacker uses to fix a forged token one field at a time — needs the same treatment.
2. The rest of the census's list, each confirmed or refuted with the suite named:
   `LogbackSelection.apply` overwriting an operator's `-Dlogback.configurationFile`;
   `AuditPrincipal.render` dropping *"(authentication is not enabled)"* so a trail has two names for
   one absence; `ClusterMapping` emitting `version: 0` for a static cluster and dropping the log
   directory's biggest-first replica sort; `ClusterSnapshots` publishing a failed sweep's stale
   partition counts inside a topology stamped with the current instant, and recording a failed scrape
   as `uptime.record(now, false)` so KUI's own outage is reported as the cluster's;
   `CapabilityMapping`'s `not_configured` branch, which ADR-032 is explicit is not a failure;
   `MutationGuard` auditing a **cancelled** mutation as `Succeeded` rather than `Unknown`, which is
   the outcome that tells an operator to go and look, and which is green under
   `./mill services.consumer.__.test` too.
3. **Land the cases.** Every survivor you can close with a case in a test directory you own, you
   close. Every survivor you cannot — because the rule is only observable from a file you do not own —
   is a `needsOutsideOwnership` row naming the file, the mutation, and the suite that should hold it.
4. **Then go looking.** Mutate at least **thirty** further rules in these areas, chosen by reading the
   comments: a rule with a paragraph defending it and no test is this packet's whole subject.
   `KafkaPartitionSweeper.sweep` has had no test since M5 and produces M5's headline numbers;
   `KafkaTopicAdmin.cleanupPolicies`'s batching and per-key failure isolation are argued at length and
   asserted by a hand-built snapshot. Both are named in the M5 record as carried risk.
5. **Report the rate, not just the list.** N rules mutated, M survived, and where the survivors
   cluster. Wave 4's census found the shape: a rule gets a test when a consumer **inside its own
   module** exercises it and goes untested when its only consumer is somewhere else. Confirm or refute
   that, because it is the finding that tells wave 6 where to put its packets.

**Method, and it is not optional.** One mutation at a time, applied to source, full suite run, reverted
before the next. `-Werror` will reject some mutations as unused-parameter or unreachable-case rather
than failing a test; **a compile failure is not a red** — re-cut the mutation so it compiles and only
then score it. Finish with `git status --porcelain` over every path you touched, empty, and a clean
suite run recorded.

**Acceptance**
```
./mill libs.__.test + services.cluster.__.test + services.topic.__.test + services.consumer.__.test + services.message.__.test + services.identity.__.test
./mill checkArchitecture
./scripts/run-tests.sh
```
Note the `+` — house rule 8. Required: a case named for each rule closed, and a table of every
mutation with its verdict and the suite that produced it.
**Mutation line:** `RbacGuard`'s `Left(problem)` branch made to fail open. Name the case that goes red
after your work; today there is none in the repository.
**And a green one:** this packet is all green ones. Report the ratio.

---

## W5-A2 — Adversarial: schemas and clusters, six ungated rules in one wave's output

**Owns**
```
frontend/packages/feature-schemas/**
frontend/packages/feature-clusters/**
frontend/e2e/features.spec.ts
frontend/e2e/brokers.spec.ts
```
This packet owns source as well as tests, because the rules it is closing are in the source and the
packet that wrote them is not in this wave. Its brief is still adversarial: **mutate first, then
close.** Build no feature.

**Contract.** Wave 4 rebuilt both packages and closed the one rule it was assigned. Verification then
found **six more in its own output**, three undisclosed. That ratio — one closed, six shipped — is the
sharpest single instance of the finding this wave is shaped around, and it is why these two packages
get an adversarial packet rather than a building one.

**Do**
1. **The schema-text guard is wired into nothing.** `RegisterSchemaDialog.tsx:63`'s
   `createMemo(() => proposedSchemaProblem(schemaType(), definition()))` can be replaced with a memo
   returning `undefined`, or the narrower `problem() === undefined` clause dropped from line 70, and
   147 cases stay green either way. With either applied the Register button submits `{ not json` to the
   registry, the `role="alert"` paragraph never appears, and `blockedReason()`'s fallback is dead.
   `proposedSchemaProblem` **itself** is gated — four cases in `recorded.test.ts:337-354` — and it is
   the *wiring into the dialog* that nothing asserts, which is character for character the failure
   shape wave 4 assigned this packet's predecessor to close. The file's own header spends a section on
   it.
2. **The never-invent-a-number rule is ungated at both layers, on the one wire wave 4 added.**
   `data.ts`'s `registerSchema` return can become `version: Number(answer.value.version ?? 0)`, and
   `SchemasRoute.tsx:185`'s `notifyRegistered` can push `` `version ${registered.version ?? 0}` `` —
   147 green either way, and either puts *"The registry accepted it as version 0"* in the success
   toast on the exact branch `RegisteredVersionDto`'s docstring says is real: the version is a second
   call that can fail after the registration succeeded, which is why it is `Option[Int]`. Both the
   data-layer comment (*"Never coerced to a number the answer did not carry… rather than printing a
   zero"*) and `notifyRegistered`'s docstring state the rule. This is the milestone's bare-zero rule,
   in a sentence wave 4 wrote, on the wire wave 4 added.
3. `BrokerList.tsx:109`'s `waiting` memo: dropping `&& props.brokers.length === 0` leaves 147 green.
   The stated reason for leaving it open — that the state *"could not be reached"* — is wrong: the
   packet ships a story that constructs it (`BrokersRefetching`, `<BrokerList brokers={SAMPLE_BROKERS}
   loading …>`), and the case named for the rule goes through `BrokersScreen`, where `useQuery` never
   re-enters `loading`, so it passes trivially. A `BrokerList`-level case over the story's own props
   closes it in three lines.
4. Three more wave 4 disclosed and left: `closeOnScrimClick={false}` deletable from
   `RegisterSchemaDialog.tsx:92`; `register.reset()` deletable from the dialog's `onClose`; and the
   three-branch `help=` sentence on the Subject field collapsible to its first string, which also
   makes `knownSubjects` dead.
5. A pre-existing one on the tile wave 4 rebuilt, which its own five-prop audit walked past:
   `BrokerList.tsx:133`'s `if (props.brokers.some(b => b.leaderPartitions === null)) return undefined;`
   deleted leaves 147 green, and `totalLeaders` then sums an unreadable broker as zero and renders a
   silently-short total with no chip.
6. `docs/FEATURE_MATRIX.md`'s `SR-005` is `REVIEW` because only the *display* of compatibility has been
   pressed in a browser: the two writes and the compatibility check have recorded-response tests and
   seven stories and **no browser evidence**. `features.spec.ts` is yours. Drive them, or say in the
   report what stopped you — the row's state is W5-09's to move and it will ask.
7. **Then go looking.** At least fifteen further mutations across these two packages, chosen by
   reading the comments rather than the tests. Report the ratio.

**Acceptance**
```
pnpm -C frontend test packages/feature-schemas packages/feature-clusters
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'schemas-|screens-schema|clusters-|screens-broker'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: **a schema document that is not valid JSON is refused by the dialog before it
reaches the registry**; **a registration the registry accepted without naming a version says so and
does not say version 0**; a refetch keeps the figures on screen; a broker whose leaderships could not
be read is not summed as zero.
**Mutation line:** the dialog's `problem()` clause. Name the case that goes red — there is none today.
**And a green one:** report the ratio for all fifteen-plus.

---

## W5-A3 — Adversarial: the schema service, and a bound measured against itself

**Owns**
```
services/schema/**
```
Same brief as W5-A2: mutate first, then close. Add no endpoint.

**Contract.** `services/schema` gained registration in wave 4 and is in no other packet this wave.
Verification found two ungated rules and three stale or wrong statements in files the packet owned.

**Do**
1. **A bound asserted against itself, which is the anti-pattern wave 4 assigned that packet to
   eliminate.** `CompatibilityUseCases.scala:45`'s `MaxDefinitionBytes = 1024 * 1024` can become
   `1024 * 1024 * 1024` with 1186 cases green. `RegisterSchemaUseCase.MaxDefinitionBytes` aliases it,
   and the packet's own new case at `SchemaRegistrationSuite:143` builds its oversized document as
   `"x" * (RegisterSchemaUseCase.MaxDefinitionBytes + 1)` — the constant measured against itself. The
   packet closed exactly this pattern for `MaxPageSize` and re-created it one file over; the only other
   reader, `RegistryAbsenceSuite:214`, does the same. Consequence: a 1 GB schema document is buffered
   in this service and forwarded to a single-writer registry JVM.
2. `SchemaMapping.toRegister` can set `references = Nil` with 1186 green, so a Protobuf or Avro schema
   with imports is registered without them — the registry either rejects it with a message about an
   unresolvable type or stores a schema no consumer can resolve. `references` is a published field of
   `RegisterSchemaRequest`. The same expression also silently discards any reference whose version
   fails `SchemaVersion.from`, via `.toOption`, which is likewise uncovered. The packet closed this
   hole for `schemaType` — *"is not assumed to be Avro"* — and not for the third field of the same
   request. `SchemaMapping.proposed` carries the identical block.
3. **A behaviour change on read paths that one case covers.** `RegistryHttp.errorFrom` now folds
   `StatusCode.Conflict` into the KUI-VALIDATION branch for **every** call the file makes — `GET
   /subjects`, `GET …/versions`, `GET …/versions/{v}`, `GET/PUT /config` — not only the registration.
   A 409 on a read now answers 400 KUI-VALIDATION with `details[0].field == null` where it used to be
   KUI-UPSTREAM-UNAVAILABLE, and only the registration 409 is tested. The same edit changed the
   pre-existing 422 path from `details: []` to a one-entry `FieldError`, and the case that covers it
   asserts only `error.message`, so an envelope shape change in a service whose error bodies the
   browser reads is unasserted. Decide whether the read paths were meant to move; assert whichever
   answer.
4. `RegistryHttp.scala:395-400`'s scaladoc on `errorFrom` still reads *"A non-404 failure status… `422`
   with `error_code` 42203 is the one worth naming"* and says nothing about the 409 branch directly
   beneath it or the `details` array it now emits.
5. `RegisterSchemaUseCase`'s header lists *"the three refusals, in the order they are decided"* as
   unknown cluster (404) → read-only (KUI-READ-ONLY) → no registry (KUI-UNSUPPORTED), and `register`
   calls `validate(proposed)` **first**. An empty or oversized document at an unknown cluster answers
   400 rather than 404, and at a read-only cluster 400 rather than 405. No rule is broken — nothing
   leaves the process on either path, so ADR-047 §2 holds — and no case covers the combination. Fix
   the order or fix the sentence.
6. The published `RegisterSchemaRequest` schema lists `schemaType` in `required` while the decoder uses
   `getOrElse("AVRO")` and the comment beside it says an absent `schemaType` means Avro. It is the
   shape `CompatibilityCheckRequest` already ships, so it is consistent rather than new — and the
   browser is being told to read the published document.
7. **Then go looking.** At least fifteen further mutations across this service. Report the ratio.

**Acceptance**
```
./mill services.schema.__.test
./mill checkArchitecture
./mill services.schema.__.checkFormat
./mill services.schema.api.openApi
./mill services.schema.api.openApiCheck
```
Required cases, by name: **a schema document larger than the bound is refused by a case that does not
compute its input from the bound**; a registration carrying references passes them to the port; a 409
on a read is not reported as a validation failure.
**Mutation line:** `MaxDefinitionBytes` × 1024. Name the case that goes red — there is none today.
**And a green one:** report the ratio for all fifteen-plus.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff.

| Pair | The contract both sides code against |
| --- | --- |
| W5-01 ↔ W5-04 | The four wires. Each is `Section`-wrapped, `status` in `ok\|stale\|unavailable\|not_configured`, and where a series is answered it carries **exactly `bucketCount` entries for the range** with `null` for buckets never sampled. W5-04 codes the cards against that shape and owns `traffic.spec.ts`; W5-01 owns the endpoints and their Scala tests. **Which of the four exist at all is decided in ADR-052 by W5-01's item 1, before either side builds** — W5-04 reads the ADR, not the diff. |
| W5-01 ↔ W5-02 | The metric names. `deployment/metrics/kafka-jmx-exporter.yml` is one ruleset mounted by both stacks, and its comment calls the names a contract with `PrometheusExposition`. W5-01 states which families it parses; W5-02 publishes exactly those and asserts every one. Neither adds a name the other does not know about. |
| W5-01 ↔ W5-03 | Four `Section`-wrapped reads under `/api/v1/clusters/{clusterId}/metrics/…`, proxied the way `throughput` already is. W5-03 adds **no gateway path of its own**: `OpenApiMergeSuite` pins a hard-coded list over `gatewayDoc + clusterDoc`, and a new gateway path would move it. |
| W5-01, W5-03 ↔ W5-09 | Each adding packet regenerates **only** its own module's `services/<svc>/api/openapi.json` and leaves `docs/api/**` and `schema.d.ts` alone. `./mill services.gateway.api.openApiCheck` is red until W5-09 regenerates the merged pair, because `build.mill:1513` aims the gateway's check at `docs/api/**`. Designed intermediate state, not a breakage. |
| W5-02 ↔ nobody | `ShippedConfigurationSuite`. Wave 4's rule was that where a handoff is two rows in a list, one packet owns both ends; this is that, so the suite is carved out of W5-A1's block and given to the packet that edits the files it should be listing. |
| W5-02 ↔ W5-A1 | `libs/config/**` is **entirely** W5-02's this wave — the formatter run and the one suite. W5-A1 mutates nothing under it. This is the only overlap in the two blocks and it is resolved by exclusion, not by sequencing. |
| W5-03 ↔ W5-02 | `ServiceContracts.byService`. W5-03 owns the Scala file; `smoke.sh` scrapes it with `sed`/`grep` to derive the contract set. W5-03 adds no service and moves no entry, so the scrape does not move — and if it ever reformats that map, `smoke.sh` fails loudly and in the safe direction. |
| W5-04 ↔ W5-05 | `RingGauge` needs an explicit `goodDirection`: §3.4 puts 64% idle green and 38% purgatory amber in one card, and `Donut`'s `warnBelow` bakes in "higher is better". The component is W5-05's. W5-04 raises it as `needsOutsideOwnership` and does not edit the kernel; W5-05 ships the prop whether or not W5-04 asks, because the design already names the requirement. |
| W5-04 ↔ W5-06 | `frontend/packages/shell/src/` is divided by named subdirectory and `styles/` by named file. W5-04 has `overview/`, `pages/`, `32-shell-dashboard.css` and `37-overview.css`; W5-06 has everything else it is listed as owning. `DashboardTab` and `shellPaths.dashboard` must keep agreeing: `tabs.ts` says so and it is W5-04's, so W5-06 adds no dashboard address. |
| W5-04 ↔ W5-07, W5-08, W5-A2 | No feature packet fills a metrics card from data it happens to hold. A rate computed from a browse is not a broker metric, and a card whose ADR says it cannot be measured keeps its sentence in every package that draws one. |
| W5-06 ↔ every feature packet | The shell's route table is **not** widened. A feature reaches its own pages through `kui.paths.*`; no feature edits `routing/**`; `KuiPaths` gains no member. |
| W5-09 ↔ W5-A2 | `SR-005`'s state. W5-A2 drives the two compatibility writes and the check in a browser or reports what stopped it; W5-09 moves the row on that evidence and not on the existence of the controls. A row moved on stories is how this matrix drifted before. |
| W5-09 ↔ W5-A1, W5-A2, W5-A3 | The three adversarial reports are the input to TD-023. Each hands W5-09 a table — rule, mutation, verdict, suite — and W5-09 records the totals and the ratio rather than a list of twelve that a deleted wave plan takes with it. |
| W5-09 ↔ W5-03 | `ARCHITECTURE.md:209` and `ADR-012:65` are the same stale sentence about BUILD-006 in two documents. W5-03 owns the first, W5-09 the second, and both say the same corrected thing: A7 is enforced by `frontend/scripts/bundle-shape.mjs`. |
| every packet ↔ the guard files | House rules 1, 2 and 3 keep `build-tests/**`, `10-tokens.css` and `constants.generated.ts` out of reach. If your change needs one of them, your change is shaped wrongly, and that has been true for four waves. |

## The partition, checked

**Frontend.** Inside `frontend/packages/shell/`, no packet owns `**`. `src/overview/`, `src/pages/`,
`styles/32-shell-dashboard.css` and `styles/37-overview.css` are W5-04's; `src/App.tsx`, its two test
files, `src/chrome/`, `src/nav/`, `src/data/`, `src/routing/`, `src/index.ts` and the other seven
stylesheets — `30`, `31`, `33`, `34`, `35`, `36`, `38`, named one by one in its `Owns` block rather
than as a directory with an exception — are W5-06's. `src/features/`, `src/messages.ts`, `src/bootstrap.ts`, `src/health.ts` and
`src/index.tsx` are **owned by nobody and need no edit** — `features/registry.ts` enumerates the five
feature registrations and this wave adds no feature, only cards inside a tab that already exists; the
other four are the boot path, which nothing here changes.

`frontend/packages/kernel/**` **has an owner for the first time since M3**, and that is the change
rather than an oversight: three waves of "unowned and needs no edit" is how six ungated rules
accumulated in the tree every screen is drawn with. W5-05 has it. Two known facts stay as they are:
`controls.test.tsx`'s top-level `readFileSync` of `27-primitives-v3.css` is a fragility nobody is
asked to touch, and `vitest.config.ts` deliberately loads no CSS, which is a settled decision that has
shaped three waves rather than something to reverse.

The five `frontend/packages/feature-*/` trees are one packet each, styles included: `feature-topics` →
W5-08, `feature-consumers` and `feature-messages` → W5-07, `feature-schemas` and `feature-clusters` →
W5-A2. `frontend/e2e/` is allocated **per file**: `traffic.spec.ts` → W5-04;
`shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts` → W5-06;
`consumers.spec.ts`/`messages.spec.ts` → W5-07; `topics.spec.ts` → W5-08;
`features.spec.ts`/`brokers.spec.ts` → W5-A2. `fixtures.ts`, `globalSetup.ts`, `tsconfig.json` and
`playwright.config.ts` are **unowned and need no edit**: the fixtures already expose `CLUSTER` and an
`api` fixture, and the config already points at 8090/8080 and refuses to run against a stack that is
not there.

`frontend/packages/api/src/constants.generated.ts`, `src/index.ts`, `src/probes.ts` and
`src/types.test.ts` are **unowned and need no edit** — house rule 3 forbids a new error code, and the
type-level regression file pins two named bugs rather than a roster. `frontend/packages/api/`'s
`src/schema.d.ts` and `README.md` are W5-09's, so that directory is not owned as a tree.
`frontend/vitest.config.ts`, `frontend/vite.config.ts` and `frontend/package.json` are **unowned and
need no edit**: no dependency is added, so no lockfile moves and `DEPENDENCY_MATRIX.md` stays true, and
`vite.config.ts:26-31` already emits the manifest `bundle-shape.mjs` reads.
`frontend/scripts/a11y-stories.mjs` is **W5-05's**, for the theme race and nothing else; every other
file under `frontend/scripts/` — `boundaries.mjs`, `bundle-shape.mjs` — is unowned and needs no edit,
both having been shown to fail against deliberately broken input in wave 4.
`frontend/packages/*/package.json` and `tsconfig.json` are unowned and need no edit; note that
`frontend/tsconfig.json` references seven of the eight packages and has since `debd783`, so
`pnpm typecheck` does not see `feature-schemas` — W5-A2 runs
`npx tsc --build packages/feature-schemas` explicitly, and whether to add the reference is a
`needsOutsideOwnership` question rather than an edit.

**Backend.** `services/metrics` is W5-01's, `services/gateway` is W5-03's, `services/schema` is
W5-A3's. `services/cluster`, `services/topic`, `services/consumer`, `services/message` and
`services/identity` have **their test trees owned by W5-A1 and their production source owned by
nobody**, deliberately: A1's whole method is to mutate source and revert it, and a repair it finds
necessary is a `needsOutsideOwnership` row rather than a commit. If that produces a real bug with no
owner, that is the wave telling wave 6 where a packet belongs.

`libs/**` is owned by nobody except the nine test trees listed in W5-A1's block and `libs/config/**`,
which W5-02 holds whole for a formatter run and two suite rows.
`libs/cache`'s `SeriesWindow` and `SeriesWindowCell` are complete and W5-01 *uses* them; adding a
fourth refusal is a sign the buffer is being written in the wrong layer. `libs/security-core`'s
`Vocabulary` already declares every action this wave needs. `libs/http`'s `RbacGuard` and
`PrincipalVerification` are **read by W5-A1 and edited by nobody** — that is the point of the packet,
and the fail-closed branch it will find has no owner today, which is itself the finding.

`build-tests/**` is unowned and must not be edited: it was wave 1's only two-sided collision and house
rules 1 and 2 exist to keep it that way. Its `BundleShape.scala` and `BundleShapeSuite.scala` remain a
dead pair reachable from no build task, and `TECH_DEBT.md` says so — leave them.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W5-02's alone. `build.mill` is **unowned and needs no edit**: no Mill module is added, every image this
wave needs already has a `deployment.docker.*` target, and wave 4's metrics dependency block is already
in place. `scripts/run-tests.sh` is unowned and needs no edit — it derives its module list from
`./mill resolve __.test` and computes its counts at runtime, so nothing in it is hard-coded and the
module count of 63 does not move. `scripts/feature-matrix-check.sh` is W5-09's; there is no other file
in `scripts/`.

**Documents.** `docs/api/**`, `schema.d.ts` and six named documents are W5-09's; `ARCHITECTURE.md` is
W5-03's; ADR-052 is W5-01's new file. Every other ADR, `DEPENDENCY_MATRIX.md`, `docs/ROADMAP.md`,
`docs/ROADMAP-SOLID.md`, `docs/testing.md`, `docs/api/error-codes.md`, `docs/operations/**` and every
file under `docs/domain/` are **unowned and need no edit** — no dependency is pinned or moved, no error
code is added, and no domain vocabulary changes. `docs/api/error-codes.md` is generated and its
generator (`tools/error-codes/**`) is W5-09's for a scalafix repair that must leave the output byte
for byte identical. `docs/ROADMAP-SOLID.md` is explicitly a record rather than a claim, which its own
line 30 says; leave it. `docs/plan/ROADMAP.md` is **unowned this wave**: it is edited when a
milestone's *shape* changes, and finishing M7 does not change M7's shape.
`docs/plan/WAVE-05.md` is this file; the wave's closing act deletes it.

**Three nesting checks, done rather than assumed.** `docs/` is not owned as a tree — W5-09 owns
`docs/api/**` and eight named documents, W5-03 owns one, W5-01 owns one new ADR, and nothing above
them is claimed. `frontend/packages/shell/` is not owned as a tree — W5-04 and W5-06 divide `src/` by
named subdirectory and `styles/` by named file, with five `src/` files left over that are named above
rather than left to inference. And `services/cluster/` and its four siblings are not owned as trees:
W5-A1 holds `*/test/**` and nothing else, so `services/cluster/api/src/` belongs to nobody and A1 may
not commit to it. The only two blocks that could have overlapped — W5-A1's `libs/*/test/**` and
W5-02's `libs/config/test/.../ShippedConfigurationSuite.scala` — are resolved by excluding
`libs/config/**` from A1 entirely, stated in both packets. No packet's `Owns` block contains another
packet's file, and no two blocks name the same path.

## What wave 6 will be, so nobody builds it here

Wave 6 opens **M8** — `services/alerts`, a genuinely new service in the ADR-041 shape. Its RBAC
resource, its `kui.alerts` config section with the thresholds its rules read, and its retention window
all shipped in wave 1, so what it costs is the rules, the event store, the feed endpoint and the
ADR-035 stream — plus one decision that should be taken there and not inherited:
`AlertsAcknowledge` is marked altering, and `isAlter` answers both the audit question and the
read-only question with one field, so acknowledging an alert is refused on a read-only cluster
although it writes to KUI's own store and never to Kafka.

It also carries whichever of M7's cards this wave's ADR-052 rules unmeasurable. If Top producers needs
a per-`client.id` byte rate, that is client quotas configured on the broker or a different source
entirely, and it is a milestone decision rather than a card.

Do not stub an alert, and do not stub a metric that this wave decided cannot be measured. A card that
says it cannot measure something is the correct rendering and the whole design rests on it staying
true — and since wave 4 the refusal must be provable *against a deployment where something else can*,
because otherwise it is indistinguishable from the absence of the code.

**And keep the ratio.** Wave 5 runs three adversarial packets against nine building ones because
builders found 4 of their own 35 holes and second readers found 31. If wave 5's three report a
materially lower rate than wave 4's census found — 26 ungated in 89 — then the mechanism is working
and the ratio can fall. If they report the same rate or higher, it does not fall, and the conclusion
is not that people need telling again. They have been told three times.
