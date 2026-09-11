# KUI, from the beginning

This is the document to read if you have never seen this repository before. It describes what KUI
is, the eleven services it is made of, the eight feature packages the browser is made of, the two
shapes it deploys in, and the gates that decide whether a change is allowed to land. It is not a
README — `README.md` at the root tells you how to run it — and it is not an ADR index:
`DECISIONS.md` is that, and it lists all fifty-six.

Every figure below was measured on 2026-09-11 against this tree, with the command that measured it
named beside it. Where a figure could not be measured here it says so in a sentence rather than
being rounded to something convenient.

---

## 1. What it is

KUI is a web console for Apache Kafka. An operator opens it in a browser and can look at clusters,
brokers, topics, records, consumer groups, schemas, Kafka Connect connectors and ksqlDB objects, and
can change some of them — create a topic, reset a consumer group's offsets, register a schema,
restart a connector, run a ksqlDB statement.

Two properties shape almost every decision in the codebase, and if you only remember two things
about it, remember these.

**It streams rather than accumulates.** Browsing a topic's records, following a push query, watching
throughput: none of it buffers a whole topic in memory. That is why there is a streaming envelope
(ADR-035) with its own frame types, why the gateway proxies some calls and relays others, and why
"the connection just stopped" is treated as a bug with a named terminal frame rather than as a
network fact.

**It refuses honestly.** When KUI cannot do something — no permission, a cluster registered
read-only, an upstream that is down, a feature the deployment did not configure — the screen says
which of those it is, in a sentence an operator can act on. That is why there is an error envelope
with stable codes (ADR-034, thirty-one codes in `docs/api/error-codes.md`, generated from the
`ErrorCode` enum by `./mill docs.errorCodes`), why capability state is folded and reported rather
than inferred (ADR-039), and why a destructive action is a plan, then a token, then a confirmation
(ADR-045) instead of a button and a shrug.

---

## 2. The back end: eleven services

The Scala side is built with Mill (`build.mill`), on Scala 3, with cats-effect, http4s and Tapir.
`ls services` gives eleven directories:

| Service | What it owns |
| --- | --- |
| `gateway` | The edge. The only port a browser talks to. Authenticates, mints the internal principal header, strips inbound `X-Kui-*`, routes or relays to the other services, folds their capability reports into one answer. |
| `cluster` | Registered clusters, brokers, broker configuration, ACLs, log directories, the cluster administration page's writes. |
| `topic` | Topics, their configuration, partitions, statistics, and the create / alter / grow / delete / empty writes. |
| `message` | Records: the browse stream, produce, resend, the filter language and the track page. |
| `consumer` | Consumer groups, lag, offset resets, group deletion. |
| `schema` | Schema Registry subjects, versions, compatibility settings, registration. |
| `metrics` | Broker and topic metrics read from a Prometheus exposition (ADR-050). |
| `alerts` | Four rules over facts KUI already reads, an event feed, and a per-principal read marker (ADR-053). |
| `connect` | Kafka Connect workers: the connector list with each connector's tasks, and pause / resume / restart (ADR-054). |
| `ksql` | ksqlDB: statements, pull queries and push queries as a stream (ADR-055, ADR-056). |
| `identity` | Principals and the authentication mechanisms. It holds no routed contract **by design**, and must not: a proxied `/login` would answer with a principal in a body and set no cookie. |

**Nine of them are routed.** The gateway's `ServiceContracts.byService` is the single place that
association is declared, and running its own derivation over the file answers `alerts cluster
connect consumer ksql message metrics schema topic`. `deployment/compose/smoke.sh` reads that same
declaration rather than a list of its own, because the two omissions this project actually shipped
were both a literal somebody forgot to extend.

### The six layers, and the rule that is enforced by the build

Every service that owns a domain is six Mill modules, and `ls services/ksql` shows all six:

```
domain           the rules. No Tapir, no Circe, no http4s, no cats-effect IO at the edges of it.
application      the use cases. Owns its own types; ADR-041 forbids it depending on the wire.
infrastructure   the adapters — Kafka, HTTP upstreams, caches.
contract         the Tapir endpoint descriptions and the DTOs. This is the wire.
api              the routes, and the mapping between application types and DTOs (Chimney, ADR-033).
app              the wiring and the main class.
```

The direction of those dependencies is not a convention here. `./mill checkArchitecture` reads the
module graph and fails the build on an edge that points the wrong way; run on 2026-09-11 it prints
`195 modules, 10 rules, no layering violations`. The figure this sentence used to publish was 233,
which is what Mill prints as its *task* count for the same command and not a count of modules at
all — the same confusion the `openApiCheck` line below carried. ADR-041 is the decision and its §1a
explains the one place the rule bends: the gateway owns no domain, so its `application` may hold
wire types.

### One contract, two documents

`docs/api/openapi.json` is the merged contract of every routed service, rendered from the Tapir
endpoints themselves — **65 paths, 76 operations, 160 component schemas, OpenAPI 3.1.0**.
`docs/api/openapi.browser.json` is the same document with every `X-Kui-*` header parameter removed,
because those headers are minted by the gateway and stripped from anything a browser sends: a
browser client generated from the service-facing document would have types obliging every call site
to send the exact header the security boundary exists to reject. Both are committed and
`./mill __.openApiCheck` re-renders and byte-compares them — **eleven committed documents over ten
`openApiCheck` targets**: one per service that publishes a contract, plus the gateway's merged pair.
This sentence used to say *2629 endpoints*, which is neither: it is what Mill prints as its task
count for that command, it is not a count of anything in the contract, and it answered 2544 on the
next run of the same command against the same tree.

---

## 3. The front end: a shell, a kernel and eight features

The browser build is TypeScript, SolidJS 2 and Vite, under `frontend/`, with pnpm rather than Mill
(ADR-048). `ls frontend/packages` gives eleven packages:

* **`shell`** — the application frame: navigation, routing, the cluster picker, the top-bar search,
  the notification bus, the dashboard, and the feature registry that lazily loads everything else.
* **`kernel`** — everything a feature package is allowed to share: the data layer, the query cache,
  the table components, the toasts, the confirmation dialogs, the capability state.
* **`api`** — `src/schema.d.ts`, generated from `docs/api/openapi.browser.json` by
  `openapi-typescript`, plus the `openapi-fetch` client. No hand-written wire types.
* **Eight feature packages** — `feature-clusters`, `feature-topics`, `feature-messages`,
  `feature-consumers`, `feature-schemas`, `feature-alerts`, `feature-connect` and `feature-ksql`.

A feature package owns its screens and its own wire module and nothing else. It is reached only
through the shell's registry (`frontend/packages/shell/src/features/registry.ts`, eight
registrations), which loads it by dynamic import, so a feature cannot be imported by another
feature and a screen cannot be reached by an address the shell does not publish. The gate on that
is `pnpm lint:boundaries`, which printed `425 files in 11 packages: no boundary violations` on
2026-09-11.

Two conventions are worth knowing before you write any of it. **Storybook first**: a component gets
a story before it gets a screen, because the story is where its states are visible without a
running cluster. And **accessibility is a gate, not a review comment**: `pnpm a11y` runs axe over
the built screens and is green.

---

## 4. The two deployment shapes

The same modules compose two ways, with no code change between them.

**One process.** `./mill dev` builds the all-in-one JVM and serves the whole product on one port.
This is the local loop, and it is the shape most development happens in.

**Eleven containers.** `deployment/compose/docker-compose.yml` runs the gateway, the nine routed
services and the frontend as separate containers, against Kafka, a Schema Registry, a Connect
worker, a ksqlDB server and a metrics exporter. `./mill deployment.docker.__.build` builds the
eleven images (ten services plus the all-in-one). `deployment/compose/smoke.sh` brings that stack
up, stops one container and asserts the other eight carry on — deriving the list from
`ServiceContracts.byService` rather than from a literal.

`deployment/quickstart/` is a third thing and not a third shape: a single-command demo stack with
seeded data, which is what the browser evidence in the feature matrix is usually driven against.

---

## 5. The gates

A change lands when every one of these is green. They are run one at a time; two of the heavier
ones together have exhausted memory on a developer machine, and a killed subprocess reads as a
failure that is not one.

Every figure in the right-hand column was printed by the command beside it on **2026-09-11**, one
gate at a time. Two of them — the Scala and browser case counts — move on almost every commit, which
is why the column is dated rather than maintained; the rest move only when the shape of the build
does.

| Gate | What it checks | Size, measured 2026-09-11 |
| --- | --- | --- |
| `./mill __.compile` | Scala compilation under `-Werror` | 8251/8251 build tasks over 1,145 Scala sources |
| `./scripts/run-tests.sh` | every Scala suite | **4,310 cases over 81 modules**, all 81 carrying tests |
| `./mill checkArchitecture` | the ADR-041 module dependency direction | **195 modules**, 10 rules |
| `./mill __.checkFormat` | Scalafmt | **495/495 over 1,145 sources across 162 reporting targets** |
| `./mill __.fix --check` | Scalafix, including the stricter no-`var` rule set for `libs` and every `domain` | **10672/10672 over the same 1,145 sources — every test tree included**, with `.scalafix-tests.conf` relaxing four sub-rules for `test/src` only |
| `./mill __.openApiCheck` | the committed OpenAPI documents against a fresh render | 11 documents over 10 targets; the merged one is 65 paths, 76 operations, 160 schemas |
| `pnpm test` | the browser suites | **1,908 cases** |
| `pnpm typecheck`, `pnpm lint:boundaries`, `pnpm a11y` | types, package boundaries, accessibility | 425 files over 11 packages; 790 stories × 2 themes |
| `pnpm e2e` | Playwright against a quickstart built from the tree | 105 passed, 3 skipped, 0 failed |
| `./scripts/feature-matrix-check.sh` | every count and every service claim this repository publishes about itself | **390 claims over nine sections** |

**The `__.fix --check` row carried a clause that was false when it was read again.** It said *5353
sources — and no test source anywhere*. Wave 9 widened both style gates to every test tree: 81
`.test.fix` targets resolve where none did, `./mill resolve '__.fix'` answers 163 targets, and 380
of the repository's 499 test sources had to be reformatted the first time the formatter reached them.
`TECH_DEBT.md` TD-027 is closed on those figures.

The last one is unusual enough to be worth a paragraph, because it is the gate most likely to
surprise you. Several documents here publish figures about the code — how many capability rows are
`COMPLETE`, how many operations carry the CSRF header, which npm dependencies have a row in the
dependency matrix, whether every ADR on disk has a row in `DECISIONS.md`. Each such passage is
wrapped in an HTML comment naming the claims it expects checked, and the script compares every one
of them against the thing it counts. It fails with the figure that would make each sentence true,
so repairing it is a substitution rather than an investigation. It also checks itself first: its own
comparator is driven into both of its states before a document is read, and a whole section drives
each of its refusals over a fixture built for the occasion, because a refusal whose failing case
never arrives in this repository is a line nothing distinguishes from `true`.

**And since 2026-09-11 it compares one thing that is not a figure.** Every gate in this list was
green over a `README.md` whose *What is not built* section said *"No Kafka Connect, no ksqlDB"* —
against a tree that ships both services and routes both through the gateway — because the false
sentence sat outside every marker and nothing here read prose. The `capability-claims` section
compares the service roster that README publishes against `services/` on disk and against
`ServiceContracts.byService`, in both directions: a service named as not built that is built fails,
and so does a service on disk the page does not mention at all.

---

## 6. Where to look next

* `README.md` — how to run it, and the commands above with their flags.
* `ARCHITECTURE.md` — the module structure in detail.
* `DECISIONS.md` — the fifty-six ADRs, one line each, linked.
* `docs/FEATURE_MATRIX.md` — 189 capability rows with their state, and the rule that a row reaches
  `COMPLETE` only when a person has done the thing from a browser against a running KUI.
* `TECH_DEBT.md` — what is known to be wrong and what closing it would take.
* `docs/plan/ROADMAP.md` — how the work has actually gone, wave by wave, including the parts that
  did not work.
