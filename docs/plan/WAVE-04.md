# Wave 4 — The metrics adapter, the endpoint M6 is missing, and twelve rules that cannot fail

**Milestones covered:** the start of **M7** in [ROADMAP.md](ROADMAP.md) — the adapter that reads a
broker, and the deployment that gives it something to read — plus the two things that stopped **M6**
closing: the one bullet with no backend capability behind it, and the twelve rules wave 3 shipped
that no test can break.

**Why these ten and not others.** Wave 3 closed M2 and M4, both of which had failed twice, and it
built nine of M6's ten bullets and drove them in a browser. It also shipped twelve ungated rules
*after* being told in its own house rules that a gate you cannot make fail is not a gate, and after
every packet ran a mutation and watched a case go red. That is the finding this wave is shaped
around: the mutation a packet plans finds the case the packet just wrote, because the two were
written together. So **every packet here must disclose a mutation that stayed green** — an honest
negative — and the twelve specific holes are assigned by name to the packet that owns the file.
A packet that reports only a red mutation has not finished looking.

The second reason for the shape of this wave is that wave 3's partition held — one gate went red at
integration, and it went red because it was working — while **two of fifteen contracted handoffs
simply did not happen**. The `ARCHITECTURE.md` §9 paragraph that one packet was to write and another
to paste is not in the file, and the schema drawer badge still costs five registry round-trips
because one packet built the cheap count-only mode and the other never changed the call. Both were
one paragraph or one constant. So the rule this wave adds is: **where a handoff is a sentence or a
constant, one packet owns both ends.** That is why W4-04 owns `ARCHITECTURE.md`, and why the
`COUNT_PAGE_SIZE` change is inside W4-06's Do list rather than in a contract row.

**Parallelism.** `Owns` is disjoint across every packet: no two tasks may edit the same file, and no
packet owns a directory containing another packet's file. Where two packets meet, the brief states
the exact contract both sides code against. Three dependencies are declared, all on a *stated shape*
and none on a diff: W4-05 on W4-01's throughput wire, W4-09 on W4-03's register wire, and W4-10 on
both of them having landed before the merged documents are regenerated.

**A consequence of the split you must not "fix", and it is narrower than it looks.** W4-01 and
W4-03 each add endpoints. Each of those two modules has **its own** committed document —
`services/metrics/api/openapi.json` and `services/schema/api/openapi.json`, written by
`./mill services.<svc>.api.openApi` and compared by that module's `openApiCheck` — and each packet
regenerates and commits its own, so its own check is green when it finishes. What goes red is
`./mill services.gateway.api.openApiCheck`, because `build.mill:1513` points the *gateway's*
`openApiTarget` at `docs/api/openapi.json` and `docs/api/openapi.browser.json`, which are W4-10's.
So `./mill __.openApiCheck` will be **red on `services.gateway.api` and green on all six others**
until W4-10 regenerates the merged pair. That is the designed intermediate state, exactly as it was
in waves 2 and 3. Do not repair it by editing a file you do not own.
`./scripts/feature-matrix-check.sh` will also be red for the same reason — the merged document's
path, operation and schema counts are pinned in two `<!-- checked: merged-document -->` regions —
and it is W4-10 that turns it green. Nobody else edits those regions.

**House rules that apply to every packet** — read them before starting.

Backend: Scala 3 + Mill, ADR-041 layering (machine-enforced by `./mill checkArchitecture`), Tapir
endpoints, ADR-034 error envelope, ADR-039 capability fold, ADR-035 streaming, ADR-045
plan→token→confirm for destructive mutations. Frontend: TypeScript + SolidJS 2 + Vite under
`frontend/` (pnpm, not Mill), Storybook-first — a story per state — and browser types generated from
`docs/api/openapi.browser.json`. Comments explain **why**, not what, at roughly the 25% density of
the surrounding code. There is no ESLint or Prettier; the codebase is hand-written at 100 columns
(Scala at 110, per `.scalafmt.conf`). **Do not reformat a file you are not otherwise changing.**

The four rules from wave 3 stand unchanged, and two are new.

1. **No new stylesheet files.** `build-tests`'s `CssReferencesSuite` requires every stylesheet on
   disk to be named exactly once in `frontend/packages/kernel/styles/index.css`, and that file has
   one owner. Extend a stylesheet your package already has. Every package that needs one has one.
2. **No new custom properties in `frontend/packages/kernel/styles/10-tokens.css`.** A Scala mirror of
   that file lives in `build-tests/src/kui/build/design/Tokens.scala`, which no packet owns.
3. **No new `ErrorCode`.** `./mill frontend.apiConstants --check` compares
   `frontend/packages/api/src/constants.generated.ts` byte for byte and neither file is owned this
   wave. The thirty-one that exist cover everything here: a registry that rejects a schema is
   `KUI-VALIDATION` with the registry's own message in `details`, an exporter that will not answer is
   `KUI-UPSTREAM-UNAVAILABLE`, and a scrape that runs out of budget is `KUI-TIMEOUT`.
4. **A gate you cannot make fail is not a gate.** Every packet's acceptance list has a **mutation
   line**: name one change to the shipped code that reverses the packet's headline rule, apply it,
   run the acceptance suite, and record which case went red.
5. **New: report a mutation that stayed green.** Alongside the red one, apply at least one mutation
   to a *different* rule your packet ships — one you did not write the test for — and record what
   happened. If it stayed green, say so in `problems` rather than repairing it silently or omitting
   it. Eleven of wave 3's rules were ungated and not one packet reported it; every one was found by
   somebody else afterwards. A report with no green mutation in it is a report that stopped looking.
6. **New: no honest-refusal-only acceptance.** No packet may satisfy its acceptance list entirely
   with assertions that something is absent, refused or not configured. M7's exit criterion was
   satisfiable by a service containing no adapter for exactly this reason. Every packet that ships a
   capability must assert the capability *working* against something real, and the refusal beside it.

`pnpm` is not on the default PATH in a non-login shell; it lives at `~/.local/share/pnpm/bin/pnpm`,
with node at `~/.nvm/versions/node/v26.8.1/bin`.

**Running a browser suite.** `pnpm -C frontend e2e` drives a stack it does not start.
`deployment/quickstart/quickstart.sh` starts one — and it will happily reuse a container image built
from a tree that no longer exists. So: `./mill deployment.docker.allinone.docker.build` and
`docker build --no-cache -f deployment/frontend/Dockerfile -t kui-frontend:0.1.0-SNAPSHOT .` **before**
`quickstart.sh`, every time the tree has changed. Done in that order at the close of wave 3, the
whole suite is 56 passed over eight spec files; skip it and you will read a bundle from before your
own repair.

---

## The guard files

Everything below asserts a shape, a count or a roster that this wave's work can invalidate. None of
them is owned by the packet most likely to break it — that is the point of listing them. If your
change makes one of these red, it is your change that is unfinished, and the repair goes in the
packet that owns the guard, named through `needsOutsideOwnership` if that is not you.

| Guard | What it pins | Who breaks it |
| --- | --- | --- |
| `docs/api/openapi.json`, `docs/api/openapi.browser.json` — `./mill services.gateway.api.openApiCheck` | A **byte** comparison against a fresh Tapir render of every service's endpoints. One renamed key fails it. Note that `build.mill:1513` aims the *gateway* module's check at these two files, so the gateway module is the one that goes red for somebody else's endpoint | W4-01 and W4-03; repaired only by W4-10 |
| `services/metrics/api/openapi.json`, `services/schema/api/openapi.json` — each module's own `openApiCheck` | The same byte comparison one layer in. Each is inside its adding packet's boundary, so each packet regenerates its own and stays green | W4-01 and W4-03, each for itself |
| `scripts/feature-matrix-check.sh` + the two `<!-- checked: merged-document -->` regions in `docs/adr/ADR-048-*.md` and `frontend/packages/api/README.md` | `N paths and M schemas`, `X-Kui-Principal on N of its M operations`, `X-Csrf-Token on N` | the same two; repaired only by W4-10 |
| `frontend/packages/api/src/schema.d.ts` + `ci.yml`'s `generate` + `git diff --exit-code` | The browser's types, regenerated from the browser document. **Nothing in Mill checks this**; the only gate is that CI step | W4-10 |
| `services/metrics/contract/test/resources/golden/*.json` and `services/schema/contract/test/resources/golden/*.json` | Each service's own wire, byte for byte | W4-01 and W4-03, each inside its own boundary |
| `libs/contracts-core/test/resources/golden/*.json` + their Scala twins | Cluster and topic DTOs in *two* places per document | nobody this wave — no shared DTO changes. If you are about to, stop |
| `services/gateway/api/test/.../openapi/OpenApiMergeSuite.scala:36-59` | A hard-coded sorted path list over `gatewayDoc + clusterDoc` only, so schema and metrics paths cannot reach it | W4-04 if it adds a gateway path (it must not) |
| `services/gateway/api/test/.../openapi/MergedDocumentShapeSuite.scala` | `writes.size == 3` over `ServiceContracts.proxied(cluster)`; distinct operationIds across the merged document | W4-03's new POST moves the operationId set, not the write count |
| `services/gateway/api/test/.../routing/ServiceContractsSuite.scala` | A hard-coded `Set` of service ids | nobody: no service is added this wave |
| `apps/allinone/test/.../AllInOneWiringSuite.scala` | The startup-log string and the mounted path set | W4-02 if it changes the all-in-one's configuration surface |
| `deployment/compose/smoke.sh` | The **contract** set scraped from `ServiceContracts.byService`, compared against the containers | W4-02 owns both; W4-04 owns the Scala file it scrapes |
| `frontend/packages/*/src/recorded/*.json` + each package's `recorded.test.ts` | A **recorded** gateway answer replayed against the mapping. Re-cut it from a live stack; do not hand-edit it | W4-09 (schemas), W4-05 if it records throughput |
| `frontend/e2e/*.spec.ts` | Screen text, by role and by visible string. Allocated per file below | every frontend packet |
| `docs/FEATURE_MATRIX.md` rows vs. its own prose, and `README.md:96` | 188 rows; 64 COMPLETE; "64 of 177 — 36%" in two files, now compared by `feature-matrix-check.sh` | every packet that finishes a capability; repaired by W4-10 |
| `docs/FEATURE_MATRIX.md:539` | A **second, unmarked** copy of all nine state totals that no checked region covers | W4-10 must bring it inside a marker or date it |
| `build-tests/**` | The token mirror and the stylesheet roster | house rules 1 and 2 forbid it |
| `frontend/packages/api/src/constants.generated.ts` | 31 error codes, byte for byte | house rule 3 forbids it |
| `scripts/run-tests.sh` | 63 modules, 55 with tests, and the eight with no test sources it names out loud | any new module — and none is added this wave |
| `libs/config`'s `SafeUrl` | `http` and `https` only, no exceptions, per `ARCHITECTURE.md` §14. **A JMX service URL cannot be expressed as a `MetricsSourceSettings.url`** | W4-01 if it tries to implement `MetricsSourceKind.Jmx` through this config |

---

## W4-01 — The metrics adapter: something that actually reads a broker

**Owns**
```
services/metrics/**
docs/adr/ADR-050-metrics-source.md                          (new)
```

**Contract.** `services/metrics` is a walking skeleton in all six ADR-041 layers, and the seam is one
method. `ConfiguredClusterSources.source` returns `None` for every cluster and its own scaladoc says
*"adding the adapter later is one class and one line in `MetricsWiring`"*. `MetricsSourcePort` is
declared with one question — `throughput(range, endingAt)` — and it never throws: everything that
went wrong is a `Left[KuiError]`. `ThroughputRange` pairs each window with its own step
(24h/5min = 288 buckets, 7d/1h = 168, 30d/6h = 120) and `bucketCount` is constant per range whatever
was sampled. `SeriesWindow` and `SeriesWindowCell` in `libs/cache` are the retention primitive and
already refuse in the three ways this needs. `MetricsConfig` parses today with `scrapeInterval`,
`retention`, `maxSamplesPerSeries` and a per-cluster `sources` map of
`MetricsSourceSettings(url: SafeUrl, kind, callTimeout)`.

**The plan decides the protocol, so that nobody has to guess.** Implement
`MetricsSourceKind.Prometheus` and only that. Three reasons, and they are structural rather than
preference: `Prometheus` is already the default in `MetricsSourceSettings`; `SafeUrl` is `http`/`https`
only by `ARCHITECTURE.md` §14, so `service:jmx:rmi:///jndi/rmi://kafka:9999/jmxrmi` **cannot be
written into this configuration at all**; and a text parser is a pure function that a test can feed
a captured exposition body, where an RMI client is not. `MetricsSourceKind.Jmx` stays declared and
unimplemented — and a cluster configured with `kind: jmx` must answer a stated refusal naming the
build, not throw and not silently read nothing. Say so in the ADR so the next wave does not "fix" it
by deleting the case.

**Do**
1. A Prometheus text-exposition reader in `infrastructure`, implementing `MetricsSourcePort`. It
   parses the JMX-exporter's Kafka metric families for bytes-in and bytes-out per second, ignores
   families it does not know rather than failing on them, and turns a body it cannot parse into a
   `Left`. Feed it captured exposition text in the test, not a mock.
2. A retention buffer over `SeriesWindow`, keyed by (cluster, metric, bucket), whose step is
   `MetricsConfig.scrapeInterval` and whose horizon is `retention`, bounded by `maxSamplesPerSeries`.
   A never-sampled bucket is **absent**, not zero. A window shorter than the requested range answers
   the buckets it has and marks the rest absent rather than shortening the axis.
3. A scrape loop in `app`: one fibre per configured cluster, at `scrapeInterval`, each scrape bounded
   by `callTimeout`, a failed scrape logged and dropped rather than retried into a pile. A scrape
   that fails must not evict what the buffer already holds — the honest answer to "the exporter died
   ten minutes ago" is the last ten minutes of data, not an empty chart.
4. `ThroughputUseCase` reads the buffer instead of the port directly, and `ConfiguredClusterSources`
   returns a real source for a configured cluster. The `not_configured` answer for a cluster with no
   `sources` entry must survive unchanged; there is a case for it today and it must still pass.
5. **The ungated rule this packet owns.** `ThroughputRange.bucketCount` is constant per range and the
   design rests on it: a quiet hour must draw the same axis as a busy one. Add a case that fails if
   a range whose buffer holds three samples answers three buckets instead of `bucketCount` with the
   rest absent. This is the exact shape of the "never a zero" rule one layer down, and it is the one
   an adapter gets wrong first.
6. The ADR: the protocol choice, why `Jmx` is declared and unimplemented, why the `SafeUrl` policy
   forces the decision, and what a deployment has to run to be measurable.

**Do not** add the other four endpoints. Latency, request-handlers, producers and record-size are
wave 5, and a fifth endpoint built against an adapter nobody has run yet is four more things to
rewrite. One metric, end to end, against a real broker.

**Acceptance**
```
./mill services.metrics.__.test
./mill checkArchitecture
./mill services.metrics.__.checkFormat
./mill services.metrics.api.openApi         # regenerate this module's own document, and commit it
./mill services.metrics.api.openApiCheck    # then green here; the gateway's stays red until W4-10
```
Required cases, by name: a body the exporter served becomes a sample with both rates; a body that
cannot be parsed is a `Left` and no sample; a range with three samples answers `bucketCount` buckets
of which three carry values; a cluster with no `sources` entry answers `not_configured`; a cluster
whose `kind` is `jmx` answers the stated refusal and not an exception; a scrape that fails leaves the
previous samples in place.
**Mutation line:** make the buffer answer only the buckets it holds, so a quiet window draws a short
axis. Name the case that goes red. **And a green one:** mutate the `not_configured` path and report
whether anything notices.

---

## W4-02 — A broker that publishes metrics, a smoke test that cannot pass on nothing, and one guard three waves have owed

**Owns**
```
deployment/**
.github/workflows/ci.yml
apps/allinone/**
frontend/scripts/bundle-shape.mjs                           (new)
frontend/package.json
```
The last two are here, in a packet that is otherwise about deployment, for one reason: they are the
other end of a CI step, and this wave's rule is that where a handoff is a script and the line that
runs it, one packet owns both. See item 7.

**Contract.** M7 cannot be proved on any machine today, because nothing anywhere serves a broker
metric — which is why its exit criterion was satisfiable by a service with no adapter. This packet
makes the quickstart measurable. The broker is `apache/kafka:4.3.1` and the JMX exporter is not in
that image, so the exporter is a **sidecar**: a `prom/jmx-exporter` (or equivalent) container in
httpserver mode against the broker's remote JMX port, published inside the compose network only.
`MetricsSourceSettings.url` is a `SafeUrl`, `http`/`https` only, so the address is
`http://kafka-metrics:5556/metrics` and not a JMX service URL — W4-01's brief explains why that
constraint decides the protocol.

Wave 3 left five things in these files that a reader would take at face value; four are cosmetic and
one is a gate that cannot fail.

**Do**
1. A JMX exporter beside the quickstart broker, with a config that exposes at least
   `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` and `BytesOutPerSec`. Enable remote JMX
   on the broker through `KAFKA_OPTS`. Publish no port to the host; the exporter is for KUI, not for
   a person.
2. `deployment/quickstart/kui-quickstart.yaml` gains `kui.metrics.sources.quickstart` naming that
   address. The distributed stack in `deployment/compose/` gets the same treatment so `smoke.sh`
   and the compose CI job exercise it too. `kui-quickstart-auth.yaml` gets it as well or is stated
   not to need it.
3. **The ungated rule this packet owns.** `smoke.sh`'s image preflight cannot fail on an empty list:
   if `expected_images` returns nothing the `for` loop runs zero times, `missing` stays empty and the
   step passes. It was observed doing exactly that during wave 3's verification, saved only by the
   contract check three lines below. Give it the same `[[ -n … ]] || fail` guard the contract check
   already has, and then prove it — run the script with the derivation broken and record the failure.
4. Then add the metrics assertion the milestone needs: `smoke.sh` asserts that the configured
   cluster's throughput answers `ok` with at least one non-null bucket, **and** that a cluster with no
   source answers `not_configured` — both, because the second alone is what M7's criterion has been
   passing on for two waves.
5. Four things that are wrong in these files and were found by reading them:
   `ci.yml`'s `grep -oE 'image: kui-[a-z]+:'` cannot match a hyphenated image name, so
   `image: kui-schema-registry:` would be silently dropped from the derived build list;
   `smoke.sh`'s contract step prints `$contracts` rather than `$expected`, so with `UNROUTED_CONTRACTS`
   set it announces the deliberately-unrouted service as routed; the `SETTLE_TIMEOUT` comment's
   "twenty-one samples … thirteen more polls" is off by one against `LatencyWindow.percentile`'s
   `ceil(rank/100 * n) - 1`, which puts the boundary at twenty and twelve; and `await_not`'s docstring
   cites ADR-039 §6, which is about business errors not dimming capabilities — the rule it means is
   §1/§2's precedence table.
6. The compose job's `timeout-minutes: 25` was written for five images and now builds seven plus a
   sidecar pull. Measure it and raise it, or record the measurement.
7. **The debt three waves have carried.** ADR-012's promise — a feature's code is fetched only when
   somebody navigates to it — is kept today by convention and by nothing else. `TECH_DEBT.md`'s
   TD-016 was closed on a real `frontend/dist/.vite/manifest.json` showing all five feature packages
   under the entry's `dynamicImports`, and closed with the guard its own exit condition named left
   unbuilt; it was re-filed as TD-022 and has carried since. The old `build-tests` object cannot
   grow the rule — `BundleShape.scala` parses Scala.js linker output, `$c_` symbols and a `main.js`,
   which ADR-048 deleted, and no `checkBundleShape` task remains in `build.mill` — so this is a new,
   Vite-shaped check and not an extra rule on the old one. Write it as TD-022's exit condition
   states: after `pnpm -C frontend build`, read `dist/.vite/manifest.json` and fail when the entry
   chunk names any `frontend/packages/feature-*` module under `imports` instead of `dynamicImports`.
   A `pnpm` script, a step in the `frontend` job, and — because this is house rule 4 — a run against
   a deliberately broken manifest showing it fail. Do not close TD-022 here; W4-10 owns
   `TECH_DEBT.md` and closes the row once this exists, which is stated in the contract table.

**Acceptance**
```
./mill apps.allinone.test
./mill checkArchitecture
./mill apps.allinone.checkFormat
docker compose -f deployment/compose/docker-compose.yml config -q
# images built from the working tree by the derived list, then:
docker compose -f deployment/compose/docker-compose.yml up -d --wait
curl -sf localhost:8090/ui/ >/dev/null && curl -sf localhost:8080/api/v1/health/ready
./deployment/compose/smoke.sh            # three consecutive runs
./deployment/quickstart/quickstart.sh    # then curl the throughput endpoint
```
Also: `pnpm -C frontend build` then the new bundle-shape check, run twice — once against the real
manifest and once against one edited to move a feature package from `dynamicImports` to `imports`.
**Mutation line:** break the image derivation so it yields an empty list, and show the preflight
failing rather than passing. **And a green one:** remove the metrics source from one configuration
file and report which of the seven things above notices.

---

## W4-03 — Register a schema: the one M6 bullet with no endpoint behind it

**Owns**
```
services/schema/**
docs/adr/ADR-051-schema-registration.md                     (new)
```

**Contract.** `Register schema` is drawn on the schema registry screen and is
`aria-disabled="true"` with a true sentence: *"KUI cannot register a schema yet: the gateway serves
no endpoint that writes one."* `docs/api/openapi.json`'s schema paths are `GET subjects`,
`GET/PUT compatibility`, `GET/PUT subject compatibility`, `GET versions`, `GET version` and
`POST version compatibility` — the compatibility *check*. Nothing registers.

The RBAC half already exists and is used by nothing. `Action.SchemaCreate` is declared at
`libs/security-core/src/kui/security/rbac/Vocabulary.scala:146` as
`Action(Resource.Schema, "CREATE", true)` and its only other reference in the entire repository is
its own implication row (`SchemaCreate | SchemaEdit | SchemaDelete => Set(SchemaView)`). It is
altering, so ADR-047's read-only rule refuses it on a read-only cluster for free, and
`RbacLawsSuite` already covers the implication. Wire it; do not widen the vocabulary.

`SchemaMutationEndpoints` already exists and is already in `ServiceContracts.byService`, so the
gateway proxies a new schema write with no gateway change at all. Confirm that before you assume it.

**Do**
1. `POST …/clusters/{clusterId}/schemas/subjects/{subject}/versions` — a schema document and its
   format, answering the registered version and id. Authorized by `Action.SchemaCreate` through
   `SchemaApi.Securing`, the same path the compatibility PUTs take.
2. The registry client call in `RegistryHttp`. A registry that rejects the schema as incompatible is
   a `KUI-VALIDATION` carrying the registry's own message in `details` — not a 500, and not a
   swallowed error that leaves the browser to guess. A registry that will not answer is
   `KUI-UPSTREAM-UNAVAILABLE`.
3. **The ungated rule this packet owns.** `SchemaEndpoints.MaxPageSize = 100` is asserted only against
   itself: `SubjectListRoutesSuite` compares the answer's `pageSize` to `SchemaEndpoints.MaxPageSize`
   and its row count to the same constant, so the value **250** ships green — three-quarters of the
   500-row registry outage the bound exists to prevent, since each row costs three registry GETs.
   Pin the number, or pin `MaxPageSize < PageSize.Max.value` with the arithmetic beside it. A literal
   is fine; a constant compared to itself is not.
4. Two smaller things wave 3 left here. `pageSize < 0` silently became count-only
   (`SchemaMapping.scala:86` is `<= CountOnlyPageSize`), which is a wire behaviour the published
   parameter description does not mention and no case covers — document it and test it, or clamp it.
   And `SchemaMapping.scala:73-74` says the count-only `PageRequest`'s size "is never used to cut
   rows", which is false: `SubjectCatalog.page` calls `Page.of` with it and discards the items
   afterwards. Harmless in effect, wrong as written.
5. The ADR: why registration is an endpoint here rather than a gateway fold, what a rejection looks
   like on the wire, and why `SchemaCreate` was already the right action.

**Acceptance**
```
./mill services.schema.__.test
./mill checkArchitecture
./mill services.schema.__.checkFormat
./mill services.schema.api.openApi          # regenerate this module's own document, and commit it
./mill services.schema.api.openApiCheck     # then green here; the gateway's stays red until W4-10
```
Required cases, by name: a valid schema registers and the answer carries the version; a registry
rejection becomes `KUI-VALIDATION` with the registry's message; a read-only cluster refuses with
`KUI-READ-ONLY`; a principal without `SCHEMA:CREATE` gets 403; a page size of 250 is refused or
clamped to 100.
**Mutation line:** set `MaxPageSize` to 250 and show a case going red — it does not today.
**And a green one:** mutate the count-only short-circuit and report what happens.

---

## W4-04 — The gateway's search, gated; and the paragraph nobody pasted

**Owns**
```
services/gateway/**
ARCHITECTURE.md
```

**Contract.** `GET /api/v1/search` shipped in wave 3, works against a live stack — three topics and a
subject for `?q=orders`, `"partial":[]` — and its fan-out and `partial` behaviour are genuinely
gated. Three things around it are not, and one contracted handoff was never made.

**Do**
1. **The ungated rule this packet owns, first half.** `SearchResultsDto.take(found, query.limit)` in
   `SearchUseCase.scala:143` can be deleted entirely and `SearchSuite` stays 15/15. On one cluster the
   per-source caps hide it; the only multi-cluster case asserts call counts and never reads
   `answer.results.*.size`. Uncapped, a two-cluster deployment answers twenty topics against a
   published `limit` whose schema maximum is 50. Add a multi-cluster case that reads the sizes.
2. **Second half.** All four non-`Ok` branches of `SearchSections.data` — Stale, Unavailable,
   Forbidden, NotConfigured — are executed by no test at all: replacing every one with a `throw`
   leaves the suite green. `SearchRig` only ever answers `Section.Ok`, and `Behaviour.Down`
   short-circuits at the `ServiceClient` before a Section exists. The whole argument the file was
   written for — Stale is used like Ok, and a section carrying no rows becomes a `Left` so the
   service is named in `partial` — is prose. Make the rig able to answer the other four.
3. Two answers that are indistinguishable and should not be. A deployment with **no clusters at all**
   answers `{"results":{…empty…},"partial":[]}` — byte-identical to "all three services were asked
   and nothing matched" — because `fanOut(Nil, …)` issues no calls and adds no `partial` entry. That
   is the exact rule ADR-049 §2 states it must not do. And `limit` is applied after the per-cluster
   results are concatenated in cluster-list order, so on a multi-cluster deployment a term with
   `limit` matches on the first cluster makes the second cluster's topics unreachable. Neither is
   documented and neither has a test. Fix the first; document or fix the second, and say which.
4. Two comments that describe code that is not there. `GatewayWiring.scala:386-388` says the `match`
   is written so that "the compiler, rather than a reader, is what notices when the two lists stop
   agreeing" — it cannot: `searchSourceOf` matches an opaque `ServiceId` and ends in `case _ => None`,
   so a fourth id added to `SearchUseCase.Services` compiles clean and is reported in `partial`
   forever. And `SearchDtoSuite.scala:10-14` says the golden "decodes on both platforms" and is
   "cross-compiled"; `./mill resolve services.gateway.contract._` names one module, `.jvm`. Both
   claims were copied from neighbouring files, which is how they spread.
5. `SearchRoutes.endpoints` has one reader in the whole tree — its own test. `DocsRoutes` reads
   `SearchEndpoints.all` directly. So the case named `theEndpointIsPublishedForTheMergedOpenApiDocument`
   asserts a value production does not consume. It is the pre-existing shape of two sibling route
   files, so either make all three real or state in one place that the roster is a test seam.
6. **The handoff wave 3 did not make.** `ARCHITECTURE.md` §9 (line 697) still describes search as
   only "an in-memory prefix/substring/trigram index inside each snapshot (`libs/kernel` `NameIndex`)"
   and says nothing about the gateway-level cross-entity fold that now ships. One paragraph, in §9,
   citing ADR-049 and naming what `partial` means. **This packet owns both the sentence and the file**
   — that is the whole reason the two were merged.

**Acceptance**
```
./mill services.gateway.__.test
./mill checkArchitecture
./mill services.gateway.__.checkFormat
./mill services.gateway.api.openApiCheck   # RED while W4-01 and W4-03 are in flight; see the preamble
```
Required cases, by name: two clusters with ten matches each answer `limit` of each kind and not
twenty; a section that is Stale contributes rows and does not name its service in `partial`; a
section that is Unavailable names its service and contributes nothing; a deployment with no clusters
is distinguishable from a search that matched nothing.
**Mutation line:** delete the `take` cap and show a case going red — it does not today.
**And a green one:** mutate the `partial` fold's ordering and report whether anything notices.

---

## W4-05 — The dashboard's Traffic tab, and a Throughput card with a series in it

**Owns**
```
frontend/packages/shell/src/overview/**
frontend/packages/shell/src/pages/**
frontend/packages/shell/styles/32-shell-dashboard.css
frontend/packages/shell/styles/37-overview.css
frontend/e2e/traffic.spec.ts                                (new)
```

**Contract, stated so this packet does not wait on W4-01's diff.**
`GET /api/v1/clusters/{clusterId}/metrics/throughput?range=24h|7d|30d` answers a `Section`-wrapped
document. `status` is one of `ok | stale | unavailable | not_configured`. When `ok`, `data.buckets`
is an array of exactly `bucketCount` entries for the range — 288 for `24h`, 168 for `7d`, 120 for
`30d` — each `{at, bytesInPerSecond, bytesOutPerSecond}` where the two rates are `number | null` and
**null means that bucket was never sampled**, which is a gap in the line and never a zero. A cluster
with no configured source answers `not_configured`, which is the common and fully supported case and
must render as the `NotMeasured` sentence rather than an empty axis. That is ADR-032's rule and this
screen is where it is most visible.

`Overview.tsx`'s tab dispatch is already an exhaustive `switch` over `DashboardTab` and its header
says in as many words that a third tab is a compile error in that file rather than a silently-empty
body. Adding `traffic` to `DASHBOARD_TABS` will therefore fail to compile until both `ledeFor` and
`bodyFor` handle it. That is the design working; do not add a default case.

**Do**
1. The `Traffic` tab: `DASHBOARD_TABS`, its voice line
   (`Throughput, latency and who is producing all of it.`), its body. §4.2 says the stat cards and
   rows 2 and 3 are tab-invariant and only the last row changes — Top producers, Message size
   distribution, Request handlers, of which all three are wave 5's endpoints and therefore all three
   keep their `NotMeasured` sentence. Build the tab and the composition; do not stub a figure.
2. The Throughput card, for real: the `24h | 7d | 30d` selector §4.1 draws, the paired produce/consume
   bars, the legend chips and the axis. The selector is in the address so a colleague can be sent one.
   `Overview.tsx:324` currently carries a comment explaining why there is no range selector —
   "a control over data that does not exist is a control whose every setting produces the same
   nothing". Delete the comment when you delete the condition, not before.
3. A null bucket is a **gap**, drawn as a gap, and never a zero and never interpolated. This is the
   whole product's rule arriving in a chart for the first time, and a chart is where it is easiest to
   get wrong: a bar of height zero and a bar that was never measured look identical unless you decide
   they must not.
4. **The ungated rule this packet owns.** `overview/overview.test.ts`'s case
   *"still lands the other four readings, because they are separate requests"* cannot fail for the
   reason its own header gives. Its stub answers the same non-envelope body from all five endpoints,
   so brokers, logDirs, groups and topicCount are `unknown` under the correct implementation too;
   collapsing the whole model into one failure keeps it green. Make the stub answer the four other
   endpoints well and assert those readings are `value`.
5. Two smaller ones, in files this packet owns. `SettingsPage.tsx`'s accent and density
   `<Help of={appearanceHelp(…)} />` calls can both be deleted with `packages/shell/src/pages` green;
   only the theme one is asserted. And the `ZERO_BYTE_DISKS` rendering draws `disk—this broker
   reported a zero-byte disk` — the em dash is `formatPercent(undefined)` in the kernel and is not
   *bare*, since the sentence is directly beneath it, but it reads as one and this packet owns the
   fixture that produces it.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/overview packages/shell/src/pages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-overview|screens-traffic|screens-settings'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: a range with a null bucket draws a gap and not a zero; a cluster whose
throughput is `not_configured` draws the sentence and no axis; the range selector puts the range in
the address; the Traffic tab draws the same stat cards as Overview; the four other readings survive a
failed cluster summary.
**Mutation line:** make a null bucket render as `0`. Name the case that goes red.
**And a green one:** mutate the tab strip's voice-line dispatch and report what happens.

---

## W4-06 — The frame: the guard nothing can see, and two exports with no caller

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

**Contract.** M4 closed on this packet's territory and the frame is real. What is left is the gap
between what the frame's comments claim and what its tests can see, plus one live cost.

**Do**
1. **The ungated rule this packet owns, and it is the wave's worst.** `App.tsx:261`'s
   `if (episode !== searchEpisode) return;` — the out-of-order guard whose three-sentence comment
   argues that "the loser landing last would put the results for `ord` under a box that says
   `orders`" — can be **deleted outright** and `pnpm -C frontend test packages/shell` prints 333
   passed. Verified twice, once by deletion and once by neutering it to `if (episode < 0) return;` so
   that `--noUnusedLocals` stays happy. The same is true of `searchEpisode += 1` in the empty-box
   branch at `:283`, and of the `report("shell", …)` call at `:263`. The reason none of them is gated
   is that both existing search cases stub `fetch` to resolve synchronously, so two searches are never
   in flight and nothing can race. Write the case that puts two answers in flight and resolves them
   out of order.
2. Two exports with no caller, both created last wave. `data/search.ts:257`'s `searchFailure` has
   exactly one reference in the repository — its own definition; `App.tsx` inlines `userMessage` at
   both places it needs the sentence. `data/search.ts:93`'s `NO_RESULTS` is referenced only by its own
   test file, and its docstring describes a code path that does not exist ("the answer an empty query
   is given without asking anybody" — the empty-box branch sets `{kind:"idle"}` and builds no
   `SearchAnswer`). Delete both or give them the caller their docstrings assume. Do not leave a third
   wave's worth.
3. **The live cost this packet owns.** `data/clusterStore.ts:81` is still `const COUNT_PAGE_SIZE = 1;`
   and `:158` still sends it, so the schema drawer badge costs five registry round-trips on every
   refetch. W3-10 shipped a `pageSize=0` count-only mode for exactly this and it is confirmed live —
   `{"items":[],…}` with a real `totalItems`. Change the constant to 0 for the subjects call and
   assert the request that goes out, not just the badge that comes back. This is the handoff that did
   not happen last wave; it is inside one packet this time.
4. `topicTree`'s favourites branch — `TopicTreeInput.favourites`, `rank: "favourite"`, the star icon,
   `NavItem`'s rank-0 case — is reachable only from a test, a fixture and a story, because the one
   production call site at `App.tsx:714` passes no favourites and nothing in the product records one.
   Either build the store that records a favourite, or delete the branch and the rank with it. It has
   now survived two waves as the same kind of orphan the wave before it was written to remove.
5. The drawer's prefix rows link to `…/topics?q=<prefix>` and land on an unfiltered list, because
   `feature-topics` seeds its query from a default and reads the address only for `?tab=`. This
   packet owns the link and W4-07 owns the reading; the contract row states the shape. Assert the
   destination here, not just the `href` string — `dashboard.spec.ts:87`'s `toHaveAttribute("href", new RegExp(`/clusters/${CLUSTER}/topics`))`
   does not include the query at all, and its own comment says the href is asserted "rather than the
   navigation".
6. `App.tsx:706-708`'s `names.length === 0` clause is inert — `NavItem` already draws an empty
   children array as a leaf — and its stated reason ("a chevron that opens onto nothing is a control
   that appears broken") describes a case the renderer prevents. Keep the guard or drop it, but do not
   leave a comment claiming a defence that something else is making.

**Acceptance**
```
pnpm -C frontend test packages/shell/src/chrome packages/shell/src/nav packages/shell/src/data packages/shell/src/routing
pnpm -C frontend test packages/shell
pnpm -C frontend typecheck
node frontend/scripts/boundaries.mjs
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'chrome-|shell-'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: a slow answer for an old query does not replace the results of a newer one;
emptying the box drops an answer already in flight; the subjects count request asks for `pageSize=0`;
a drawer prefix row lands on a filtered topics list.
**Mutation line:** delete `if (episode !== searchEpisode) return;`. Name the case that goes red — none
does today. **And a green one:** mutate something in `chrome/` and report what happens.

---

## W4-07 — Topics: three rules that can be deleted, and five functions with no caller

**Owns**
```
frontend/packages/feature-topics/**
frontend/e2e/topics.spec.ts
```

**Contract.** This screen shipped whole last wave and is driven by seventeen browser cases. Three of
its rules cannot fail, one browser case cannot fail for its stated reason, and one file carries five
exported functions that nothing calls.

**Do**
1. **The ungated rule this packet owns.** Row selection and card selection sharing **one** set is a
   named requirement of M6 and the case that claims it — `topics.test.tsx:152`, "row selection and
   card selection share one set" — builds its own signal and hands it to a bare `<TopicListPage>` and
   a bare `<TopicCards>` side by side, so it asserts the arrangement the case itself made. Replacing
   the cards branch's forwarded selection with a private `createSignal`, so the table and the cards
   the product actually renders hold two different sets, leaves all 118 cases green. `harness.tsx`'s
   own header names this exact rule as the example of what a hand-composed case cannot see. Six other
   route-level cases go through the harness; this one must too.
2. Two more of the same family. `data.ts`'s `consumerGroups: groupCount` can be changed to
   `groupCount ?? 0` with the suite green, so the Overview tab's CONSUMER GROUPS tile would draw `0`
   where the file's own comment says *"That is not the same as nothing reading it"* — the never-zero
   rule, in a tile this package built last wave, with no case feeding the overview a refused section.
   And the bulk toast's `tone: outcome.value.failed.length === 0 ? "success" : "warning"` can be
   hard-coded to `"success"` with the suite green; only the pure `bulkSentence` helper is asserted.
3. A browser case that cannot fail on what its name claims. `topics.spec.ts:205`, "the partition total
   is the cluster's and not the page's", says in its comment that a page of eight topics cannot sum to
   the cluster's 86 — and its only assertion is `not.toContainText("not measured")`. It never reads
   the number. A tile that summed the page would pass it.
4. Five exported functions in `topicList.ts` with exactly one reference each, their own declaration:
   `totals`, `cleanupSplit`, `topBy`, `visibleTopics`, `pageOf`. (`isOutOfSync` is reachable only
   through `matchesFilter` inside the same file.) The file was a 224-line orphan with zero importers
   before this wave and is now a partial orphan; finish it either way. Also `kui-topic-cards__item` is
   written in `TopicCards.tsx` and has no rule in `51-topic-screens.css`.
5. Read the address. `TopicsRoute.tsx:274` seeds `createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY)`
   and reads the address only for `?tab=`, so `…/topics?q=orders` and `…/topics?showInternal=true`
   both land unfiltered — and the drawer sends people there. W4-06 owns the link; this packet owns the
   reading.
6. Four comments this package wrote that are wrong. `TopicStatisticsRegion.tsx:75-76` says "all four
   tiles" where there are three (the fourth is the one the same file explains it deliberately did not
   build). `TopicConsumers`'s EmptyState is described as distinguishing four kinds and distinguishes
   three. `OVERVIEW_PARTITION_CAP = 500` is a hand-copy of `TopicDetailResponse.EmbeddedPartitionLimit`
   with nothing comparing them, and its notice fires on `>= 500`, so a topic with exactly 500
   partitions and no truncation is told its table is short. And `SORT_OPTIONS` and `SORT_FIELDS` are
   two hard-coded lists in two files that must agree, where removing a mapping is caught and *adding*
   an option with no mapping is not — which ships a Sort menu item that silently sorts by nothing.

**Acceptance**
```
pnpm -C frontend test packages/feature-topics
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-topic|topics-'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: the table and the cards the route renders share one selection set; a refused
consumer-group count renders the sentence and not `0`; a bulk action that partly refused raises a
warning toast; the statistics tile's partition total is read and compared, not merely non-empty; a
`?q=` in the address filters the list.
**Mutation line:** give the cards branch its own selection signal. Name the case that goes red — none
does today. **And a green one:** mutate the Export path and report what happens.

---

## W4-08 — Consumers and messages: the toasts nothing sees, and the paging that defeats the protocol

**Owns**
```
frontend/packages/feature-consumers/**
frontend/packages/feature-messages/**
frontend/e2e/consumers.spec.ts
frontend/e2e/messages.spec.ts
```

**Contract.** Both packages shipped their screens and both left their *toasts* — a named M6 bullet —
with no gate at all. Two packages in one packet because the work is the same work and the trees are
disjoint.

**Do**
1. **The ungated rule this packet owns.** Deleting **both** `notify(…)` calls in
   `feature-consumers`'s `GroupRoute.tsx` leaves 68/68 green; suppressing the produce `notify` in
   `feature-messages`'s `MessagesRoute.tsx` leaves 142/142 green; suppressing the resend `notify`
   leaves 142/142 green and has no browser case either. "A toast on every destructive success" is an
   M6 bullet that can be deleted from three packages without anything in the repository noticing,
   including the deliberate `tone: "warning"` for a copy that moved nothing. Gate all of them, in the
   unit suite, at the route.
2. `feature-consumers`: the coordinator fallback. `data.ts:113`'s list mapping is properly gated, but
   restoring the `` `broker ${coordinatorId}` `` fallback in the **detail** mapping at `data.ts:310`
   leaves the suite green, because the detail assertion reads a recording that carries both fields.
   Strip them from the detail recording too.
3. `feature-consumers`: paging silently defeats CG-006's incremental lag protocol, and nothing says
   so. `fetchLagDelta` sends no page scoping and the endpoint answers cluster-wide;
   `applyLagDelta` returns `needs-full-list` for any changed group not in `rows`, and after last wave
   `rows` is one page. On a cluster with more groups than a page holds, every poll takes the refusal
   branch and the delta protocol never engages. No wrong data is produced. Either scope the delta
   request to the page, or say plainly in the code that the optimisation is off above one page and
   why that is acceptable.
4. `feature-consumers`: five existing stories now advertise the refusal as the screen's normal voice.
   `TheScreenshot` — the canonical SCREENS-V4 §4.12 reproduction — reads *"6 groups on this page, of
   an unstated total"* where the design's line is *"14 groups. One is rebalancing again."*, and
   `Empty`, `FilteredOut` and `Loading` all read the same sentence over states it does not describe.
   The `Loading` one matters beyond the gallery: `ConsumersRoute` starts `total` at `null`, so the
   real screen's first paint states a confident sentence about a cluster it has not asked.
5. `feature-consumers`, smaller: `GroupList.tsx:270`'s `pageSize={props.pageSize ?? props.rows.length}`
   is literally the array's length, which is the thing this package's own doc four lines above forbids;
   `lag.ts:152-167` has a doc comment orphaned onto the wrong declaration by an insertion; and
   `ListingFigures` is exported and imported by nothing.
6. `feature-messages`: `topic.ts`'s `typeof detail.row?.partitionCount === "number"` branch — the one
   its own comment argues hardest for, because `?? 0` "would turn that sentence into the claim the
   whole screen exists to avoid" — has no test: the suite covers the refused-section path and the
   happy path and never a section that is `ok` with a null count. And `describePredicate`
   (`predicates.ts:166`) is exported from the barrel with **zero** callers and zero tests anywhere in
   the repository, its docstring naming a chip that was never built. `celString`, `conjunctsOf`,
   `PREDICATE_PARAM`, `MAX_PRESETS`, `presetsKey` and the `TopicFacts` type are barrel-only.
7. `feature-messages`, three wrong statements. `MessagesRoute.tsx`'s `topic` docblock says this query
   and the topic page's "share one answer and one request" — different key, different endpoint, two of
   each, and `topic.ts` says so twice in its own prose. The `§3.12 five controls` enumeration in
   `predicates.ts`, `index.tsx` and `presets.ts` splits one item in two and silently drops the status
   facet, leaving a reader believing row 2 is complete. And `ResendDialog` renders the *known*
   sentence with `class="kui-resend__unknown"` and says "has 1 partitions".

**Acceptance**
```
pnpm -C frontend test packages/feature-consumers packages/feature-messages
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'screens-consumer|consumers-|messages-|screens-message'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: a successful offset reset raises a toast; a successful group delete raises a
toast; a produce raises a toast; a resend that moved nothing raises a **warning** toast; a detail
answer carrying an id and no address leaves the coordinator absent; a topic whose `partitionCount` is
null renders the sentence and not `0`.
**Mutation line:** delete every `notify(…)` call in both packages. Name the cases that go red — none
do today. **And a green one:** mutate the lag delta's merge and report what happens.

---

## W4-09 — Schemas and brokers: the register control, and a loading state that lies

**Owns**
```
frontend/packages/feature-schemas/**
frontend/packages/feature-clusters/**
frontend/e2e/features.spec.ts
frontend/e2e/brokers.spec.ts
```

**Contract, stated so this packet does not wait on W4-03's diff.**
`POST /api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions` takes the schema document and
its format and answers the registered version and id. A registry that rejects it is a 400
`KUI-VALIDATION` whose `details` carry the registry's own message — render that message, do not
paraphrase it. A read-only cluster answers `KUI-READ-ONLY`, and a principal without `SCHEMA:CREATE`
answers 403; both are already the shapes every other write on this screen produces.

**Do**
1. `Register schema` becomes a real control: the dialog, the write, the toast, the list refresh, and
   the three refusals above rendered as sentences. Delete `REGISTER_UNAVAILABLE_REASON` and the
   `aria-disabled` with it. This closes M6's last bullet.
2. **The ungated rule this packet owns, and its root cause.** No test in `feature-schemas` mounts
   `SchemasRoute.tsx` — 357 lines carrying every non-pure thing the package was asked for. Three
   mutations survive because of it: the sort control's `direction` can be hard-coded so the listbox is
   wired to nothing (`SchemasRoute.tsx:169`); both `notifyLevelSet(…)` calls can be deleted, which is
   the whole of M6's toast bullet for this package; and `subjectCount={result().page.totalItems}` can
   be changed to `result().subjects.length`, which is character-for-character the defect the consumer
   groups screen was rewritten to remove. The package already built the harness for this — `testing.ts`'s
   `testContext` — and then never used it: it is exported and imported by nothing, and its 25-line
   docstring argues, verbatim and about a component in a different package, for the route test that
   was not written.
3. `feature-schemas`, three things on screen. While the subjects request is in flight the workspace
   header renders *"The registry did not say how many subjects it holds."* — a claim about an answer
   that has not arrived, from a `subjectCount` with no loading state, in a package whose `model.ts:13`
   forbids drawing an absence as a default. `SchemasRoute.tsx:95` invents the error code `KUI-STALE`
   and hands it to `Banner`, whose `code` prop is documented "the stable code, for whoever the operator
   escalates to" — it exists in no generated constant, no `docs/api/error-codes.md`, nowhere. And the
   same line paints `stale` as `tone="danger"`, against this repository's written rule that
   *"data that is real and out of date is still the best answer anybody has"* — which this file's own
   `optional()` helper exists to enforce.
4. `feature-schemas`, smaller: `rowCaption` in `model.ts` is called by nothing but its own test while
   `SubjectList.tsx` builds the identical string inline; `features.spec.ts:42`'s
   `toContainText(/inherited from the registry's global level|set on this subject/)` is a disjunction
   over the only two strings the code can produce and cannot fail either way; the badge sits inside
   the `<h2>` and inside the `<a>` so a screen reader hears `AVROorders.avro-value`; and two comments
   count wrong ("the fourth copy" of a helper that is the seventh, "why these four live together" over
   a file exporting ten).
5. **`feature-clusters`: the live defect, and it is the worst rendering in the product.**
   `BrokerList.tsx:39`'s `loading` prop is declared and read nowhere, and `BrokersScreen.tsx:193`
   feeds it. With the brokers request delayed, the screen states *"The cluster is not answering. Last
   successful check was 24s ago"*, `TOTAL LEADERS 0`, `DISK USED — no broker answered` and *"No
   brokers. KUI reached the cluster and it reported no brokers, which should not happen while it is
   running."* — four claims it cannot make, two of which contradict each other, a bare zero where the
   milestone's rule demands a sentence, and a fabricated timestamp that last wave's new `observedAgo`
   turned from an honest "KUI has never reached it" into a specific lie. The published
   `screens-clusters-and-brokers--brokers-loading` story draws exactly this copy and the a11y sweep
   passes it. Draw a loading state, or remove the prop; do not leave a wire that looks connected.
6. `feature-clusters`, five more ungated props on the same screen, all deletable with 77 cases, the
   a11y sweep and the browser suite green: `versionFor` and `controllerKind` (together, the whole
   `v4.3 · KRaft` tag), `configsMoreFor`, `observedAgo`, and `diskFigure`'s held-bytes branch — the
   last of which silently reverts the DISK USED tile to `unknown` on a cluster that reported its usage
   perfectly, which the comment above it says was the bug being fixed. The screen's `failure` prop is
   asserted only through hand-composed `BrokerList` props and never through the screen, which is the
   composition blind spot `BrokersScreen.tsx`'s own header says the file exists to close.
7. `feature-clusters`, two comments and one field. `BrokerCard.tsx` and `BrokerList.tsx` say a broker
   has "around two hundred" settings; measured live it is 340 rows and 61,531 bytes, which the same
   packet's other three comments get right. And `data.ts:108`'s `leaderSkewPercent` is declared in a
   hand-written wire interface and read by nothing — the cluster service now fills it, so either draw
   it or delete the field and say why in one place.

**Acceptance**
```
pnpm -C frontend test packages/feature-schemas packages/feature-clusters
pnpm -C frontend typecheck
pnpm -C frontend build-storybook          # then serve :6017 and:
node frontend/scripts/a11y-stories.mjs 'schemas-|screens-schema|clusters-|screens-broker'
pnpm -C frontend e2e                       # against images built from this tree
```
Required cases, by name: registering a schema adds the subject to the list and raises a toast; a
registry rejection shows the registry's own message; the sort direction reaches the request, asserted
at the route; the subject count is the registry's total and not the page's row count; the brokers
screen while loading draws neither a zero nor a claim about the cluster; the version tag is drawn
from the summary.
**Mutation line:** hard-code `SchemasRoute`'s `direction` to `"asc"`. Name the case that goes red —
none does today. **And a green one:** mutate a `feature-clusters` prop wiring and report what happens.

---

## W4-10 — The published documents, the counts, and the two ADRs  ·  depends on W4-01 and W4-03

**Owns**
```
docs/api/**
frontend/packages/api/src/schema.d.ts
frontend/packages/api/README.md
docs/FEATURE_MATRIX.md
README.md
TECH_DEBT.md
DECISIONS.md
docs/adr/ADR-048-solidjs-typescript-vite-frontend.md
scripts/feature-matrix-check.sh
```

**Contract.** Two packets add endpoints, so the merged documents and the browser's generated types
move and `./scripts/feature-matrix-check.sh` is red on arrival. Regenerate; write no Scala and no
TypeScript by hand. `./mill services.gateway.api.openApi` renders both merged documents,
`pnpm --filter @kui/api run generate` renders `schema.d.ts`, and both are byte-reproducible — a hand
edit anywhere breaks the very check this packet exists to make green.

**Do**
1. Regenerate `docs/api/openapi.json`, `docs/api/openapi.browser.json` and `schema.d.ts`, and update
   the two `<!-- checked: merged-document -->` regions to the counts the regenerated documents
   actually carry. Derive the numbers with `jq` from the rendered files. Do not copy them from a
   packet's closing note: last wave's note said four new schemas where the document gained five.
2. **The ungated rule this packet owns.** `scripts/feature-matrix-check.sh:333` feeds `jq` a glob —
   `frontend/packages/*/package.json` — and there is no per-section assertion floor, only a global
   `assertions == 0` that sections 1 and 2 always satisfy. Point that glob at a directory that does
   not exist and the run drops from 49 assertions to 45 and still prints "all true" and exits 0. The
   script's own comment at line 66 says *"Every input is named rather than globbed: a glob that
   matches nothing checks nothing and says so to nobody, which is the failure this script was written
   to end."* Make that sentence true, or make the sentence honest and add the floor — but the gate
   must be able to fail, which is the one thing this file exists to guarantee about every other file.
3. `docs/FEATURE_MATRIX.md:539` publishes a second copy of all nine state totals outside every checked
   region, reading as current. Bring it inside a marker or date it as a record the way ADR-048's
   evidence items were dated.
4. **Move the rows.** M6 finished nine capabilities and the matrix still describes the screens as
   they were: last wave's document packet explicitly did not touch a State cell
   (`git diff … | grep -c '^[-+]| [A-Z][A-Z]-'` was 0). Topics statistics and facets, consumer-group
   paging and the coordinator address, the schema workspace and registration, the message browser's
   predicates and partition count, the brokers screen, cross-entity search — each row that is now
   COMPLETE, with the recount and the two published percentages moved to match. This is what the
   comparing script is for, and it has never once been used in the direction it was written for.
   Correct CG-005 while you are there: its note describes a control (`Forget this group's offsets on
   one topic`) that does not exist in the shipped screen and whose function is an orphan.
5. `DECISIONS.md` gains rows for ADR-050 and ADR-051. `ARCHITECTURE.md:4` states the rule the index
   exists for and wave 3 shipped ADR-049 without one, which nobody's guard list caught.
6. `TECH_DEBT.md`: TD-022 is filed between TD-020 and TD-021 in an id-ordered register — move it.
   Close it **only if** W4-02's bundle-shape check exists and has been shown to fail against a
   broken manifest; if it has not, leave the row open and say why, because TD-016 became TD-022 by
   being closed on a check nobody had run. And add the twelve ungated rules of wave 3 as one row
   with a pointer here, so that if a packet in this wave does not close its own, the debt is recorded
   rather than forgotten a third time.

**Acceptance**
```
./mill __.openApiCheck                     # green, and it is this packet that makes it so
pnpm --filter @kui/api run generate        # twice; the second run must change nothing
rm -rf frontend/.tsbuild && pnpm -C frontend typecheck
./mill frontend.apiConstants --check
./scripts/feature-matrix-check.sh
git diff --exit-code frontend/packages/api/src/schema.d.ts   # after a regenerate
```
**Mutation line:** change one description string in a Tapir endpoint definition, re-run
`./mill __.openApiCheck`, and show it naming the file that drifted. **And a green one:** point the
`package.json` glob at nothing and show whether the script still says "all true" — it does today, and
that is item 2.

---

## Where the packets meet

Every pair below shares a boundary. The contract is stated on both sides so that neither has to read
the other's diff. Two of last wave's fifteen handoffs did not happen; where a handoff is a sentence
or a constant this time, one packet owns both ends, and those rows say so rather than appearing here.

| Pair | The contract both sides code against |
| --- | --- |
| W4-01 ↔ W4-05 | The throughput wire. `GET …/metrics/throughput?range=24h\|7d\|30d` answers a `Section`-wrapped `{status, data:{buckets:[{at, bytesInPerSecond, bytesOutPerSecond}]}}` with **exactly `bucketCount` buckets** for the range (288 / 168 / 120) and `null` rates for buckets never sampled. `not_configured` for a cluster with no source. W4-05 codes the card against that shape and owns `traffic.spec.ts`; W4-01 owns the endpoint and its Scala tests. Neither waits on the other's diff. |
| W4-01 ↔ W4-02 | The protocol and the address. W4-01 implements `MetricsSourceKind.Prometheus` only, because `SafeUrl` is `http`/`https` and a JMX service URL cannot be written into `MetricsSourceSettings.url` at all. W4-02 supplies an exporter at an `http://` address and names it in `kui.metrics.sources.<cluster>`. `Jmx` stays declared, unimplemented and answering a stated refusal; neither packet deletes the case. |
| W4-01 ↔ W4-10, and W4-03 ↔ W4-10 | Each adding packet regenerates **only** its own module's `services/<svc>/api/openapi.json` and leaves `docs/api/**` and `schema.d.ts` alone. Their own `openApiCheck`s are green when they finish; `./mill services.gateway.api.openApiCheck` is red until W4-10 regenerates the merged pair, because `build.mill:1513` aims the gateway's check at `docs/api/**`. That is the designed intermediate state, not a breakage. |
| W4-03 ↔ W4-09 | The register wire. `POST …/schemas/subjects/{subject}/versions` takes the document and its format and answers the version and id; a rejection is a 400 `KUI-VALIDATION` carrying the registry's own message in `details[0]`; a read-only cluster is `KUI-READ-ONLY`; a missing permission is 403. W4-09 renders the registry's message rather than paraphrasing it. |
| W4-03 ↔ W4-06 | The count-only page size. W4-03 keeps `pageSize=0` answering the total with no rows and no enrichment; W4-06 changes `clusterStore.ts`'s `COUNT_PAGE_SIZE` from 1 to 0 and asserts the request. This was last wave's failed handoff; both halves are now in Do lists rather than in a note. |
| W4-04 ↔ W4-02 | `ServiceContracts.byService`. W4-04 owns the Scala file; `smoke.sh` scrapes it with `sed`/`grep` to derive the contract set. W4-04 adds no service and moves no entry, so the scrape does not move. If W4-04 ever reformats that map, `smoke.sh` fails loudly and in the safe direction — the contract set shrinks and the check fails — which is why the scrape is acceptable. |
| W4-04 ↔ nobody | `ARCHITECTURE.md` §9. Last wave this was a two-packet handoff and the paragraph was never written. It is now one packet's Do item, in a file that packet owns. |
| W4-05 ↔ W4-06 | `frontend/packages/shell/src/` is divided by named subdirectory and `styles/` by named file. W4-05 has `overview/`, `pages/`, `32-shell-dashboard.css` and `37-overview.css`; W4-06 has everything else it is listed as owning. `DashboardTab` and `shellPaths.dashboard` must keep agreeing: `tabs.ts` says so and it is W4-05's, so W4-06 adds no dashboard address. |
| W4-06 ↔ W4-07 | The drawer's prefix link. `…/topics?q=<prefix>` must arrive at a filtered list. W4-06 owns the `href` and asserts the destination in `dashboard.spec.ts`; W4-07 owns `TopicsRoute`'s reading of the address. Today the link is honest and the destination is not, and neither side's test can see it. |
| W4-06 ↔ every feature packet | The shell's route table is **not** widened. A feature reaches its own pages through `kui.paths.*`; no feature edits `routing/**`. `KuiPaths` gains no member this wave. |
| W4-05 ↔ W4-07, W4-08, W4-09 | The `NotMeasured` sentences for latency, request handlers, top producers and message size stay until wave 5's endpoints exist. No feature packet fills a metrics card from data it happens to hold; a rate computed from a browse is not a broker metric. |
| W4-09 ↔ W4-01 | `leaderSkewPercent`. The cluster service fills it and no screen draws it. W4-09 decides — draw it or delete the hand-declared field — and W4-01 does not touch the cluster service to make either easier. |
| W4-10 ↔ everyone | Nobody hand-declares a wire shape, and nobody hand-edits a generated document. If a generated type breaks your file, widen your file. `docs/api/**` and `schema.d.ts` are byte-reproducible from their generators and W4-10's whole acceptance rests on that. |
| W4-10 ↔ W4-02 | `ci.yml` and TD-022. W4-02 owns the workflow; W4-10 touches no workflow. The `generated` job already runs `feature-matrix-check.sh` — wave 3 landed that step — so neither packet has to add it, and W4-10's item 2 changes the script the step already calls. The one thing that crosses: W4-02 builds the bundle-shape check and runs it in CI, and W4-10 closes TD-022 in `TECH_DEBT.md` **only if that check exists and has been shown to fail**. If W4-02 does not deliver it, the row stays open and W4-10 says so; a debt closed on a check nobody ran is exactly how TD-016 became TD-022. |
| every packet ↔ the guard files | House rules 1, 2 and 3 keep `build-tests/**`, `10-tokens.css` and `constants.generated.ts` out of reach. If your change needs one of them, the answer is that your change is shaped wrongly, and that has been true for three waves. |

## The partition, checked

**Frontend.** Inside `frontend/packages/shell/`, no packet owns `**`. `src/overview/`, `src/pages/`,
`styles/32-shell-dashboard.css` and `styles/37-overview.css` are W4-05's; `src/App.tsx`, its two test
files, `src/chrome/`, `src/nav/`, `src/data/`, `src/routing/`, `src/index.ts` and the other seven
stylesheets are W4-06's. `src/features/`, `src/messages.ts`, `src/bootstrap.ts`, `src/health.ts` and
`src/index.tsx` are **owned by nobody and need no edit** — `features/registry.ts` enumerates the five
feature registrations and this wave adds no feature, only a tab inside one of them; the other four
are the boot path, which nothing here changes. `package.json` and `tsconfig.json` **inside**
`frontend/packages/*/` are unowned and need no edit: no dependency is added and no project reference
moves. The root `frontend/package.json` is a different file and is W4-02's, for one `"scripts"` line;
see the note below.

`frontend/packages/kernel/**` is **unowned and needs no edit**, which is worth stating because the
Traffic tab is a chart screen. Every primitive it draws with — `Sparkline`, `RingGauge`, `Histogram`,
`StackedBar`, `StatCard`'s visual slot — was built in M3, is storied, and is clean under axe in both
themes. W4-05 composes them and changes none of them. Two known kernel facts stay as they are:
`Monogram` remains an orphan until the Top-producers card exists, and that card needs wave 5's
`…/metrics/producers` endpoint; and `controls.test.tsx`'s top-level `readFileSync` of
`27-primitives-v3.css` is a fragility nobody is asked to touch.

The five `frontend/packages/feature-*/` trees are one packet each, styles included: `feature-topics`
→ W4-07, `feature-consumers` and `feature-messages` → W4-08, `feature-schemas` and `feature-clusters`
→ W4-09. `frontend/e2e/` is allocated **per file**: `topics.spec.ts` → W4-07,
`consumers.spec.ts`/`messages.spec.ts` → W4-08, `features.spec.ts`/`brokers.spec.ts` → W4-09,
`shell.spec.ts`/`dashboard.spec.ts`/`search.spec.ts` → W4-06, and one new file, `traffic.spec.ts`,
for W4-05. `fixtures.ts`, `globalSetup.ts`, `tsconfig.json` and `playwright.config.ts` are unowned and
need no edit: the fixtures already expose `CLUSTER` and an `api` fixture, and the config already
points at 8090/8080 and refuses to run against a stack that is not there.

`frontend/packages/api/src/constants.generated.ts`, `src/index.ts`, `src/probes.ts` and
`src/types.test.ts` are unowned and need no edit — house rule 3 forbids a new error code, and the
type-level regression file pins two named bugs rather than a roster. `frontend/vitest.config.ts` and
`frontend/vite.config.ts` are unowned and need no edit: the deliberate decision that vitest loads no
CSS is a settled one that has already shaped two waves' work rather than something to reverse, and
`vite.config.ts:26-31` already emits the manifest W4-02's new check reads. `frontend/package.json`
and the new `frontend/scripts/bundle-shape.mjs` are **W4-02's**, for the bundle-shape guard and for
nothing else — a `"scripts"` line and one new file; no dependency is added, so no lockfile moves and
`DEPENDENCY_MATRIX.md` stays true. Every other file under `frontend/scripts/` is unowned and needs no
edit.

**Backend.** `services/metrics` is W4-01's, `services/schema` is W4-03's, `services/gateway` is
W4-04's. `services/cluster`, `services/topic`, `services/consumer`, `services/message` and
`services/identity` are **unowned and need no edit**: no DTO widens, no adapter changes, and
`leaderSkewPercent` — the one field a feature packet might be tempted to chase into the cluster
service — is decided in the browser by W4-09 and not on the wire.

`libs/**` is unowned and needs no edit, and three of them are worth naming because a packet will be
tempted. `libs/cache`'s `SeriesWindow` and `SeriesWindowCell` are the retention primitive W4-01
*uses*; they are complete, their three refusals are the three M7 needs, and adding a fourth is a
sign the buffer is being written in the wrong layer. `libs/config`'s `MetricsConfig`,
`MetricsSourceSettings` and `MetricsSourceKind` are complete for both protocols and W4-01 adds no
key — if a metric name or a JMX object name is about to be typed into YAML, stop: that is the
adapter's business and `MetricsConfig`'s own scaladoc says so. And `libs/security-core`'s
`Vocabulary` already declares `Action.SchemaCreate`; W4-03 *uses* it and widens nothing.

`build-tests/**` is unowned and must not be edited. It was wave 1's only two-sided collision and
house rules 1 and 2 exist to keep it that way: no new stylesheet file, so `CssReferencesSuite` cannot
move; no new custom property in `10-tokens.css`, so `Tokens.scala` cannot drift. `tools/error-codes`
is unowned for the same reason under house rule 3.

**One consequence of that, and how it is worked around rather than carried.** The bundle-shape guard
— the thing `TECH_DEBT.md` closed TD-016 without, re-filed as TD-022, and carried through two waves —
cannot grow out of `build-tests`, because `BundleShape.scala` parses Scala.js linker output (`$c_`
symbols, a `main.js`) that ADR-048 deleted and no `checkBundleShape` task survives in `build.mill`.
It is therefore built somewhere else entirely: as a Node script over the Vite manifest, which is what
TD-022's own exit condition asks for, and it is W4-02's item 7. `build-tests/**` and `build.mill`
stay unowned and untouched, and the old object stays where it is — dead, exercised only by its own
suite, and W4-10 says so in `TECH_DEBT.md` when it closes the row. Wave 3 said this would carry
unless a packet finished it, and no packet owned it, so it carried; this time it is somebody's item
with a number on it.

**Build and deployment.** `.github/workflows/ci.yml`, `deployment/**` and `apps/allinone/**` are
W4-02's alone. `build.mill` and `scripts/run-tests.sh` are unowned and need no edit: no Mill module
is added — every image this wave needs already has a `deployment.docker.*` target and all seven were
built here — so the runner's module count of 63 does not move. `scripts/feature-matrix-check.sh` is
W4-10's; every other file in `scripts/` is unowned and needs no edit.

**Documents.** `docs/api/**` and `schema.d.ts` are W4-10's alone, along with `docs/FEATURE_MATRIX.md`,
`README.md`, `TECH_DEBT.md`, `DECISIONS.md`, `frontend/packages/api/README.md` and ADR-048.
`ARCHITECTURE.md` is W4-04's. ADR-050 is W4-01's new file and ADR-051 is W4-03's. Every other ADR,
`DEPENDENCY_MATRIX.md`, `docs/ROADMAP.md`, `docs/ROADMAP-SOLID.md`, `docs/testing.md`,
`docs/api/error-codes.md` and every file under `docs/domain/` are **unowned and need no edit** — no
dependency is pinned or moved, no error code is added, and no domain vocabulary changes.
`docs/ROADMAP-SOLID.md` carries two sentences that wave 3 made stale (its `useFetch` count and its
monogram paragraph) and it is explicitly a *record* rather than a claim, which its own line 30 says;
leave it. `docs/plan/ROADMAP.md` is unowned this wave: it is edited when a milestone's *shape*
changes, and finishing M6 and starting M7 do not change either's shape. `docs/plan/WAVE-04.md` is
this file; the wave's closing act deletes it.

**Three nesting checks, done rather than assumed.** `docs/` is not owned as a tree — W4-10 owns
`docs/api/**` and five named documents, W4-04 owns one, W4-01 and W4-03 own one new ADR each, and
nothing above them is claimed. `frontend/packages/shell/` is not owned as a tree either: W4-05 and
W4-06 divide `src/` by named subdirectory and `styles/` by named file, with five `src/` files left
over that are named in the paragraph above rather than left to inference. And `frontend/packages/api/`
is not owned as a tree: W4-10 holds `src/schema.d.ts` and `README.md`, and the other three files there
are named as unowned. No packet's `Owns` block contains another packet's file, and no two blocks
name the same path.

## What wave 5 will be, so nobody builds it here

Wave 5 finishes **M7** and opens **M8**.

M7's remainder is the four endpoints this wave deliberately did not build —
`…/metrics/latency?window=`, `…/metrics/request-handlers`, `…/metrics/producers?top=`,
`…/metrics/record-size` — each `Section`-wrapped so one dead exporter costs one card, and then the
cards: the p99 line with the current value in each legend chip, the two ring gauges, Top producers
(which is what finally gives `Monogram` a caller, three waves after it was built), the message-size
histogram, and the four stat-card sparklines. Every one of them should be cheap once this wave's
adapter, buffer and scrape loop exist and have been run against a real broker — and if they are not
cheap, that is the most useful thing wave 4 can report.

M8 is `services/alerts`, a genuinely new service in the ADR-041 shape. Its RBAC resource, its
`kui.alerts` config section with the thresholds its rules read, and its retention window all shipped
in wave 1, so what it costs is the rules, the event store, the feed endpoint and the ADR-035 stream —
plus one decision that should be taken there and not inherited: `AlertsAcknowledge` is marked
altering, and `isAlter` answers both the audit question and the read-only question with one field, so
acknowledging an alert is refused on a read-only cluster although it writes to KUI's own store and
never to Kafka.

Do not stub a metric in wave 4, and do not stub an alert in wave 5. A card that says it cannot measure
something is the correct rendering and the whole design rests on it staying true. The one thing that
has changed is that "it cannot measure it" must now be provable *against a deployment where something
else can* — otherwise the refusal is indistinguishable from the absence of the code, which is the trap
M7's exit criterion sat in for two waves.
