# KUI, from the beginning

This is the document to read if you have never seen this repository before. It describes what KUI
is, the eleven services it is made of, the eight feature packages the browser is made of, the two
shapes it deploys in, and the gates that decide whether a change is allowed to land. It is not a
README — `README.md` at the root tells you how to run it — and it is not an ADR index:
`DECISIONS.md` is that, and it lists all fifty-six.

Every figure below is one of two things, and there is no third: a figure a gate re-reads out of this
page on every run — section 5 marks those, inside an HTML comment naming the claims the checker
compares — or a figure with the command that prints it written beside it, which a reader re-takes in
the time it takes to run. There **used** to be a third kind, a snapshot of the build's **shape**
carrying the date it was taken on, and it is gone because it is the kind that was wrong: three rows
of section 5's gate table published *1,145 Scala sources* while `git ls-files '*.scala' | wc -l`
answered **1,152** on 2026-09-12, and the date beside them was the only part of the row still true.
A date is not a check, and a source count moves with every file added, so the table below names the
command rather than carrying an eighth figure somebody has to remember to re-take. Where something
could not be measured here it says so in a sentence rather than being rounded to something
convenient.

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

Every service that owns a domain is these six Mill modules, and `ls services/ksql` shows all six:

```
domain           the rules. No Tapir, no Circe, no http4s, no cats-effect IO at the edges of it.
application      the use cases. Owns its own types; ADR-041 forbids it depending on the wire.
infrastructure   the adapters — Kafka, HTTP upstreams, caches.
contract         the Tapir endpoint descriptions and the DTOs. This is the wire.
api              the routes, and the mapping between application types and DTOs (Chimney, ADR-033).
app              the wiring and the main class.
```

Six is the floor rather than the shape of every service: `ls services/cluster` shows a seventh
module, `client`, the typed reader the other Kafka-facing services use to turn a cluster id into a
live connection. ADR-041 rule A11 admits `contract` and `client` across a service boundary and
nothing else, and it names `client` so that a second module of this kind has to be argued in the
commit that adds it.

The direction of those dependencies is not a convention here. `./mill checkArchitecture` reads the
module graph and fails the build on an edge that points the wrong way, **and it prints what it
read** — the module count, the rule count, and either the violations or `no layering violations` —
so this page names the command and repeats none of it. The figure this sentence used to publish was
233, which is what Mill prints as its *task* count for the same command and not a count of modules
at all — the same confusion the `openApiCheck` line below carried, and the reason no figure out of
either command survives on this page. ADR-041 is the decision and its §1a explains the one place the
rule bends: the gateway owns no domain, so its `application` may hold wire types.

### One contract, two documents

`docs/api/openapi.json` is the merged contract of every routed service, rendered from the Tapir
endpoints themselves, and it declares OpenAPI 3.1.0. Its size — paths, operations and component
schemas — is in the checked block in section 5 and nowhere else on this page, because it is a figure
`./scripts/feature-matrix-check.sh` re-reads out of that block on every run and a second copy here
is a copy that can drift from it.

`docs/api/openapi.browser.json` is the same document with every `X-Kui-*` header parameter removed,
because those headers are minted by the gateway and stripped from anything a browser sends: a
browser client generated from the service-facing document would have types obliging every call site
to send the exact header the security boundary exists to reject. Both are committed and
`./mill __.openApiCheck` re-renders and byte-compares them, over **eleven committed documents**
(`git ls-files '*openapi*.json'`) and **ten `openApiCheck` targets** (`./mill resolve
'__.openApiCheck'`): one per service that publishes a contract, plus the gateway's merged pair.
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
through the shell's registry (`frontend/packages/shell/src/features/registry.ts`), which loads it by
dynamic import, so a feature cannot be imported by another feature and a screen cannot be reached by
an address the shell does not publish. The length of that array is not written here either:
`FEATURE_COUNT`, in the same file, is the number and `registry.test.ts` asserts the array is that
long — the prose that used to state it had been wrong by inheritance twice, because the render test
iterates the registry rather than sizing it and nothing in the workspace could see that the two had
parted company. The gate on the boundaries is `pnpm lint:boundaries`, which prints the files and
packages it read and names any violation.

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
services and the frontend as separate containers — `grep -c 'image: kui-'` over that file counts
them — against Kafka, a Schema Registry, a Connect worker, a ksqlDB server and a metrics exporter.
`./mill resolve 'deployment.docker.__.build'` also answers **eleven**, and **they are not the same
eleven**: Mill builds the gateway, the nine routed services and the all-in-one, while the frontend
image is built from `deployment/frontend/` by Docker, because Mill does not build the browser half
at all (ADR-048). Mill's eleven holds the all-in-one and not the frontend image; the stack's eleven
holds the frontend image and not the all-in-one, and the coincidence is spelled out here rather than
left for a reader to reconcile.
`deployment/compose/smoke.sh` brings that stack up, stops one container and asserts the other eight
carry on — deriving the list from `ServiceContracts.byService` rather than from a literal.

`deployment/quickstart/` is a third thing and not a third shape: a single-command demo stack with
seeded data, which is what the browser evidence in the feature matrix is usually driven against.

---

## 5. The gates

A change lands when every one of these is green. They are run one at a time; two of the heavier
ones together have exhausted memory on a developer machine, and a killed subprocess reads as a
failure that is not one.

**One kind of figure is left in this section, and the difference is the point of it.** The block
below is re-read by `./scripts/feature-matrix-check.sh` on every run, out of the same ledger and the
same documents the gate itself reads, so this page cannot be wrong about it for longer than one run.
Everything else needs another process — a Scala test run, a Mill task graph, a browser suite — and
every one of those commands prints its own size as it finishes, so the table below names the command
and carries no number of its own. That is the repair and not an omission: the snapshot that used to
sit there was wrong in three rows, and a date beside a figure had been the only thing a reader had
to go on.

That distinction is new on 2026-09-12, and it exists because of what this page did without it.
Until then it carried no `<!-- checked: -->` marker of any kind, and the row describing the checker
published *390 claims over nine sections* against a run that printed **404** — a false figure about
this repository's own flagship gate, in the row of this page that describes that gate, published by
the wave that owned it. Four of that table's ten rows were stale against the tree they described.

### The figures this page is not trusted about

<!-- checked: gate-table -- verified by ./scripts/feature-matrix-check.sh -- claims: gate-claim-total, gate-section-count, gate-openapi-document, gate-decisions-rows, residue -->
| What this page publishes | Read back by | Size |
| --- | --- | --- |
| the checker's own size | its own ledger, as the run finishes | **444 claims** over **12 sections** |
| `docs/api/openapi.json`, the merged contract | the checker's `merged-document` section | **65 paths**, **76 operations** and **160 component schemas** |
| `DECISIONS.md` against `docs/adr/` | the checker's `adr-index` section | **56 rows**, one per ADR on disk |
<!-- /checked -->

A figure in that block that stops being true fails `./scripts/feature-matrix-check.sh` and names the
figure that would make it true, so repairing this page is a substitution rather than an
investigation — which is what every other document in this repository that publishes a count has
had since 2026-09-10, and what this one did not.

### The gates, and the size each one prints for itself

**Every row below now carries a command where a figure used to be.** Wave 12 did this for three of
them — the Scala case count, the component case count and the browser suite's tally, each of which
had been wrong on this page within hours of being written down, twice, by the waves that wrote them.
It left the other six as a dated snapshot of the build's **shape**, on the argument that a module
count and a source count move only when a module or a source tree is added. The argument is true and
it did not help: the three rows publishing the Scala source count were stale seven sources later,
and the date beside them was the part of the row a reader trusted most.

So the right-hand column says what each command prints rather than what it once printed. Every one
of them ends by printing its own size — Mill prints `N/N` tasks and names each target's source count
as it goes, `checkArchitecture` prints its module and rule counts, the boundary and accessibility
sweeps print theirs, `run-tests.sh` and vitest and Playwright print their case tallies — so a reader
who wants a number is one command away from one nobody had to remember to re-take here.

| Gate | What it checks | What running it prints |
| --- | --- | --- |
| `./mill __.compile` | Scala compilation under `-Werror` | the build-task total, as `N/N`. It prints no source count; `git ls-files '*.scala' \| wc -l` is what counts those |
| `./scripts/run-tests.sh` | every Scala suite | the case count and the module count, as its last line |
| `./mill checkArchitecture` | the ADR-041 module dependency direction | the module count, the rule count, and either the violations or `no layering violations` |
| `./mill __.checkFormat` | Scalafmt | the task total, and `Checking format of N Scala sources` once per reporting target — summing those is how the source count in this repository's reports is taken |
| `./mill __.fix --check` | Scalafix, including the stricter no-`var` rule set for `libs` and every `domain`, over every tree including tests, with `.scalafix-tests.conf` relaxing four sub-rules for `test/src` only | the task total, and the sources and rule count per target |
| `./mill __.openApiCheck` | the committed OpenAPI documents against a fresh render | the task total over the committed documents and the `openApiCheck` targets §2 counts; the merged document's own size is in the checked block above |
| `pnpm test` | the component and unit suites | the case count and the file count, from vitest |
| `pnpm typecheck`, `pnpm lint:boundaries`, `pnpm a11y` | types, package boundaries, accessibility | typecheck prints nothing and exits 0; the boundary sweep prints the files and packages it read; the accessibility sweep prints the stories and themes it swept and every violation |
| `pnpm e2e` | Playwright against a quickstart built from the tree | passed, skipped and failed — and the skips are deployment-shaped rather than gaps |
| `./scripts/feature-matrix-check.sh` | every count and every service sentence this repository publishes about itself | its claim total and its per-section sizes. The block above is where this page publishes that total, because this is the one gate that can read its own size |

**The `__.fix --check` row carried a clause that was false when it was read again.** It said *5353
sources — and no test source anywhere*. Wave 9 widened both style gates to every test tree, and the
two figures that say so are ones a reader can re-take in a second: `./mill resolve '__.fix'` answers
163 targets and 81 of them end in `.test.fix`, where none did. The third — *380 of 499 test sources
had to be reformatted the first time the formatter reached them* — is wave 9's record of that one
pass and not a count of this tree; `git ls-files '*.scala' | grep -c /test/` is what counts the test
sources today, and it has moved since. `TECH_DEBT.md` TD-027 is closed on those figures.

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

**And it compares two things that are not figures.** One is a sentence and the other is a
quotation, and each of them was added after a false one had stood in this repository with every
gate green. Every gate in this list was once green over a
`README.md` whose *What is not built* section said *"No Kafka Connect, no ksqlDB"* — against a tree
that ships both services and routes both through the gateway — because the false sentence sat
outside every marker and nothing here read prose. The `capability-claims` section compares the
service roster that README publishes against `services/` on disk and against
`ServiceContracts.byService`, in both directions: a service named as not built that is built fails,
and so does a service on disk the page does not mention at all. Since 2026-09-12 it also reads the
**sentences** around those lists: inside a checked block a service's state is stated in the three
labelled lists, where it is compared, or it is not stated there at all, and a sentence that names
`connect` or `ksql` — or any service by its backticked id — beside a negation is refused with the
identity printed. That rule exists because the original sentence was appended, unchanged, inside
the very block built to refuse it on 2026-09-12 and the run printed `404 claims checked, all true`.
Since 2026-09-12 it reads the positive voice as well as the negative one: *"the Connect screen is a
placeholder"* and *"ksqlDB is a stub"* are refused for the same reason *"neither is built"* is,
because a service named beside a word that puts it in a state is making the same unheld claim about
the tree as a service named beside a negation.

**The rule refuses honest sentences too, and that is the decision rather than a defect.** *"The
Connect screen is the placeholder for a worker that is not configured"* is true of an unconfigured
deployment and this rule refuses it, because the refusal is not *that sentence is false* — it is
*nothing here can check that sentence, so do not make it where the markers promise everything is
checked*. So the rule for anybody editing a checked block is one line: **a service's state goes in
the three labelled lists, where it is compared against `services/` and `ServiceContracts.byService`,
and prose about a service goes below the `<!-- /checked -->`.** The escape is four lines long and
`README.md` already keeps every paragraph of that shape there. The alternative — qualifying the rule
so that `placeholder` is allowed near `configured` — was considered and rejected: it is a heuristic
about meaning, the next synonym defeats it, and it buys an author the right to make an unchecked
claim inside the markers. `README.md`'s *Editing the checked blocks above* carries this decision in
the file it constrains, which is where a document author meets it.

**And the rule reads services, not screens.** *"KUI has no topic detail page and no consumer lag
chart"* is refused by nothing, measured on 2026-09-12 inside `README.md`'s block: it names no
service by a backticked id and no product name the alias table carries, so there is nothing for the
comparison to hold it against. That is the declared scope and not a hole in it — widening the rule
to every noun this documentation uses for Kafka's own concepts would refuse honest prose far more
often than a false claim — but it means a green run is **not** a statement that every sentence
inside the markers is true. It is a statement that every sentence naming a service is.

**The second is a quotation, and it is newer than the sentence.** Wave 11 repaired
`docs/operations/masking.md` and this repository went on quoting, in the present tense, a sentence
that page no longer carried — in `README.md` and in `docs/FEATURE_MATRIX.md`, both of which the
definition of done names, both green under every gate here, because a figure is compared against
the thing that produces it and a sentence against the tree, and nothing compared a quotation of one
file against that file. Inside a checked block a backticked or italicised span of eight words or
more is now a quotation of the nearest backticked repository path before it, and it has to occur in
that file; a backticked token that looks like a path in this repository and is not one is refused
with the token printed, which is the same check applied to a citation whose file has been deleted.
The scope is the marked block and not the document, and that is a measurement — but not one this
page prints a figure for any more. Run the same reader over every markdown file here and it answers
orders of magnitude more attributions than the marked blocks do and finds almost every one of them
absent, because a fenced code block, an ASCII diagram and a table column all look like quotations.
How many is a number nobody has reproduced twice: four runs of those same two shipped functions over
four different file sets answered four different pairs, none of them the pair the gate's own comment
published. The conclusion survives every reading and the number survives none, so the reader is
named here and the figure is not. A
document that wants to say what another file says either puts the quotation inside a block, where
it is compared, or names the file without quoting it. Both are honest and only one is checked.

---

## 6. Where to look next

* `README.md` — how to run it, and the commands above with their flags.
* `ARCHITECTURE.md` — the module structure in detail.
* `DECISIONS.md` — one line per ADR, linked, and compared row by row against `docs/adr/` by
  `./scripts/feature-matrix-check.sh`, which prints how many it read.
* `docs/FEATURE_MATRIX.md` — every capability row with its state, and the rule that a row reaches
  `COMPLETE` only when a person has done the thing from a browser against a running KUI. The same
  checker recounts the rows and prints the total; this page does not restate it.
* `TECH_DEBT.md` — what is known to be wrong and what closing it would take.
* `docs/plan/ROADMAP.md` — how the work has actually gone, wave by wave, including the parts that
  did not work.
