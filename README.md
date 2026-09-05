# KUI

A Kafka management and observability interface. Scala 3 on the server, TypeScript and SolidJS in
the browser, built and shipped as two independent halves that talk over HTTP.

> **Status: milestones 0 to 4, plus topic administration from milestone 5.** KUI connects to real
> Kafka clusters and is usable from a browser: a dashboard, clusters and brokers, topics and their
> configuration, browsing and publishing records, consumer groups with their lag, and an offset-reset
> wizard. It can also create a topic, change a setting, add partitions, empty a topic and delete one
> — the three that cannot be undone are confirmed against a plan the server computed and applied
> against a token naming exactly what you were shown. What is *not* built is named plainly under
> [What is built, and what is not](#what-is-built-and-what-is-not) below — schema registry,
> connectors, ksqlDB, access control, authentication and metrics among them, and there is no
> authentication of any kind, so do not put this on a network you do not control. See
> [ROADMAP.md](docs/ROADMAP.md) for what lands when.

## What it is

KUI lets you operate Apache Kafka clusters from a browser: inspect brokers and topics, browse and
publish records, follow consumer groups and their lag, manage schemas, connectors and access
control lists, and watch it all through metrics and traces.

Three existing tools were studied in depth so that KUI starts from their combined capability
rather than a blank page: [Kafbat Kafka UI](https://github.com/kafbat/kafka-ui), its predecessor
[Provectus Kafka UI](https://github.com/provectus/kafka-ui), and
[Consdata Kouncil](https://github.com/Consdata/kouncil). KUI reimplements their functionality; it
copies none of their code. The analysis is in [`research/`](research/), with every claim cited
back to the source it came from.

## What makes it different

**It keeps working when parts of it break.** Each area of the domain runs as its own service. If
schema registry access fails, or consumer group inspection becomes unreachable, the rest of the
interface carries on. The navigation entry for the affected area stays clickable and tells you
what is wrong instead of disappearing or greying out.

**The two halves are one contract.** The backend is Scala 3 built with Mill; the user interface is
TypeScript and SolidJS, built with Vite in its own pnpm workspace under `frontend/`, and shipped as
its own container image. They talk over HTTP. The same endpoint definitions generate the server, its
documentation, and — through the committed OpenAPI documents the browser's types are generated from
— the client the browser uses, so a change to a contract cannot silently break one side (ADR-048).

**It streams instead of accumulating.** Browsing records, following a query, watching metrics: all
of it flows from Kafka to the browser without buffering whole topics in memory.

**It can be one process or five.** The same modules compose into a single JVM for local use, or
into a gateway and four services in separate containers for production. No code changes between the
two: `deployment/compose/docker-compose.yml` runs the second shape, and
`deployment/compose/smoke.sh` stops one of its containers and shows the other four carrying on.

## What is built, and what is not

The honest version, because a tool you are going to point at a production cluster should not make
you find its limits by hitting them.

**The bar this project set itself** is that somebody who has never seen KUI can clone it, run one
command and have KUI and a Kafka broker both running; see their clusters, brokers and topics
without configuring anything beyond a bootstrap address; read messages from a topic, including the
JSON inside them, and publish one; see consumer groups and how far behind they are; point it at
their own cluster with a documented example configuration, including a secured one; and have any
part of it fail without the rest becoming unusable. That last point is not a feature — it is the
reason the architecture is shaped the way it is, and it is tested rather than asserted.

**What works today:**

| Area | State |
| --- | --- |
| Build, gateway, single-process and multi-container assembly, container images | done |
| Cluster connectivity: real Kafka connections with SASL/TLS, clusters, brokers, metadata store | done |
| Topics: list, search, detail, partitions, configuration | done |
| Topic administration: create, reconfigure, add partitions, empty, delete | done, with read-only mode, plan-token confirmation and an audit trail |
| Messages: browsing with every seek mode, streaming, serialization formats, publishing, filters | done except purge from the message screen |
| Consumer groups: groups, members, assignments, lag, offset reset | done, wizard included |
| Quickstart, configuration examples, demonstration environment | done |

**What is not built:**

- **No authentication and no authorization.** Anyone who can reach the port can do anything KUI
  can do, including deleting topics. Run it on a network you control.
- **No schema registry integration**, so Avro and Protobuf payloads are not decoded against a
  registry.
- **No Kafka Connect, no ksqlDB, no ACL or quota management.**
- **No metrics collection**: throughput columns render as `—` rather than as numbers.
- Some components exist and are tested but are not yet reachable from a screen — live tailing, the
  CEL filter engine, the masking engine, event tracking and CSV export among them. These are
  marked `IMPLEMENTING` rather than `COMPLETE` in the feature matrix, and the matrix says exactly
  which.

Of 177 in-scope capabilities tracked in [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md), 53 are
delivered end to end — about 30%. Every row there was set by reading the code and driving the
running application, not by asking whether the work had been scheduled.
[docs/ROADMAP.md](docs/ROADMAP.md) says what lands when.

## Quick start

Four ways in. The first two need only Docker; the other two need a JDK 21 and this repository, and
the last also needs Docker.

### Just show me it running, with a Kafka behind it

```
deployment/quickstart/quickstart.sh
```

One command, and Docker is the only thing that has to be installed — no JDK, no Mill, no Scala. It
starts a single-node Kafka 4.3.1 in KRaft mode, waits until the broker can genuinely serve metadata
rather than merely until it has started, seeds it with topics, JSON messages and a consumer group
that is behind, starts KUI pointed at it, and prints the URL. `quickstart.sh down` removes all of it,
volumes included.

What you get is the product against that broker: the cluster and its one node, seven topics with
their partitions and configuration, the JSON records inside them, a form to publish more, three
consumer groups with their lag, and a wizard that resets a group's offsets and shows you what it
would write before it writes it. [`deployment/quickstart/README.md`](deployment/quickstart/README.md)
explains what runs, why the broker's readiness check is what it is, and how to run it when 8080 or
9092 are already taken.

One caveat if you have run KUI before: the quickstart reuses whatever `kui-allinone` image is
already on the machine and only builds one when none is there, so after changing code run
`./mill deployment.docker.allinone.docker.build` first or you will start the previous build.

### Show me three clusters at once, and one of them failing

```
deployment/demo/demo.sh
```

Also Docker-only, and the demonstration to run if you only run one. It starts **three genuinely
different Kafka clusters** and one KUI that already knows all three, so the cluster switcher has
something real in it:

| Cluster | Shape | Seeded with |
| --- | --- | --- |
| Development | one broker, `PLAINTEXT` | 4 topics, replication factor 1, a leftover `scratch.jm-test` nobody cleaned up |
| Production | three brokers, `PLAINTEXT` | 15 topics at replication factor 3 with `min.insync.replicas=2`, 20 000+ messages, 7 consumer groups |
| Secured | one broker, `SASL_SSL` + `SCRAM-SHA-512` | 4 topics behind a password and a private certificate authority the script generates for you |

Nothing has to be installed but Docker, and nothing has to be configured — if the KUI image is
missing the script builds it in a container, and if the demonstration certificate authority is
missing it generates that in a container too. Measured on a 16-core laptop from a machine with no
KUI image, no certificates and no containers, `demo.sh` printed its URL after **4 min 41 s**, of
which 3 min 50 s was the one-time compile. Every run after that is **about 45 seconds**.

Then switch one cluster off and watch the other two carry on:

```
deployment/demo/demo.sh stop prod          # or: dev, secured, prod-broker
deployment/demo/demo.sh start prod
deployment/demo/demo.sh down
```

With Production stopped, Development and Secured kept answering in **10–11 ms**, and Production
itself did not disappear: it went `stale` with the reason `UPSTREAM_UNAVAILABLE`, kept serving the
15 topics it had last read, and said when it read them. `start prod` brought it back with nothing
pressed. `stop prod-broker` is the subtler one — it stops a single broker of three, and the cluster
keeps serving while KUI's topic table shows 96 replicas fallen out of sync, which is exactly what
Kafka itself reports for the same moment.

[`deployment/demo/README.md`](deployment/demo/README.md) has the seven-step walkthrough.

### See the interface, and change it

The two halves build separately, because they are two builds. The backend is Mill and needs only a
JDK; the interface is a pnpm workspace and needs only Node.

```
git clone <this repository> && cd kui
(cd frontend && pnpm install && pnpm build)   # writes frontend/dist
./mill dev
```

`./mill dev` builds the backend and starts the whole product — the gateway and every service — as one
process on one port, with `frontend/dist` on its classpath so the interface is served from the same
origin as the API. Open <http://localhost:8080/ui/>. Stop it with Ctrl-C. Skipping the first line is
not an error: the gateway then answers `/ui/` with a 503 and everything else works.

To change something and see it:

```
./mill devStart                # the same server, in the background
(cd frontend && pnpm watch)    # `vite build --watch`: rebuilds every time you save
```

Now edit, say, `frontend/packages/shell/src/chrome/AppFrame.tsx`, save it, and refresh the browser. A
rebuild takes about a second and nothing restarts: the server reads `frontend/dist` through a symlink,
so there is no copy step and no proxy in the way. `./mill devStop` when you are done.

The port is 8080 unless you set `KUI_PORT`.

### See it survive a service dying

This is the demonstration worth two minutes of anyone's time, and it needs Docker.

```
./mill deployment.docker.__.build
docker compose -f deployment/compose/docker-compose.yml up -d

curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
# available

docker compose -f deployment/compose/docker-compose.yml stop kui-cluster
sleep 12
curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
# unavailable
curl -s localhost:8080/api/v1/info | jq -r .authType
# disabled   <- the gateway is still answering

docker compose -f deployment/compose/docker-compose.yml start kui-cluster
sleep 12
curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
# available   <- it recovered by itself; nobody pressed anything

docker compose -f deployment/compose/docker-compose.yml down -v
```

A process died and the interface stayed up, told you which part of the product was affected, and
came back on its own. That is the promise the whole architecture exists to keep.
`./deployment/compose/smoke.sh` runs that sequence and fails loudly if any step gives the wrong
answer. [`deployment/compose/README.md`](deployment/compose/README.md) explains each command.

### Where things are

| You want to | Look in |
| --- | --- |
| Change the interface | `frontend/packages/shell` (pages, layout) and `frontend/packages/kernel` (the design system) |
| Change what the gateway serves | `services/gateway` |
| Add a service endpoint | `services/cluster/contract`, then `services/cluster/api` |
| Run one test | `./mill libs.kernel.jvm.test`, or `./mill <module>.test.testOnly <SuiteName>` |
| Understand a decision | [docs/adr/](docs/adr/) — one file per decision, with the alternatives that were rejected |
| Understand the layout | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Contribute | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Building it

### Prerequisites

- **JDK 21.** Any distribution works; Temurin 21 is what the project is developed against. Check
  yours with `java -version` — the first number must be 21.
- **Docker.** Not needed to compile, but the integration tests start real Kafka brokers in
  containers, so they need a working Docker daemon.

You do **not** need to install Mill (the build tool). The `./mill` script in the repository root
downloads the exact version pinned in `.mill-version` the first time you run it, so everyone —
including the CI machine — builds with the same tool.

### The commands

```
./mill --version                  # prints the pinned Mill version; also does the one-time download
./mill resolveAll                 # downloads every third-party library the project pins
./mill libs.kernel.jvm.compile    # compiles one module

./mill dev                        # the whole product on one port, in the foreground
./mill devStart                   # the same, in the background, so you can re-link while it runs
./mill devStop                    # stop the background one

./mill deployment.docker.__.build # the three container images
```

### The quality gates

```
./mill __.compile        # compiles everything; a warning is an error (see below)
./mill __.reformat       # rewrites sources to the project's formatting
./mill __.checkFormat    # fails if anything is not formatted
./mill __.fix            # applies the lint rules that can be applied automatically
./mill __.fix --check    # fails if a lint rule is violated, changing nothing
./mill __.test           # every suite
./mill checkArchitecture # fails if a module dependency breaks the layering rules of ADR-041
```

`__` is Mill's wildcard: it means "every module".

**`-Werror` is not negotiable, per module or otherwise.** Every module compiles with
`-Werror`, which promotes every compiler warning — an unused import, a discarded result, a
deprecated call — into a build failure. No module may switch it off. The reason is not
perfectionism: a warning that can be ignored *is* ignored, and a project that tolerates a
hundred of them can no longer see the one that matters. Fixing each warning as it appears costs
seconds; clearing a backlog of them costs weeks and nobody ever does it.

Formatting is [scalafmt](https://scalameta.org/scalafmt/), configured in `.scalafmt.conf`.
Linting is [scalafix](https://scalacenter.github.io/scalafix/), configured in `.scalafix.conf`,
which forbids `null`, `throw`, `return` and `asInstanceOf` everywhere. `libs/` and every service's
`domain` module get a stricter set from `.scalafix-pure.conf`, which additionally forbids `var`.

### Adding a module

The build file decides once how modules are compiled and tested, so declaring a new one is short.
Every module in this build is a JVM module — the interface is not built here at all (see below):

```scala
object config extends KuiJvmModule {
  object test extends ScalaTests with KuiTests
}
```

Modules that used to be shared with the browser keep a `Shared` trait and a nested `jvm` object:

```scala
object contractsCore extends Module {
  trait Shared extends KuiModule with PlatformScalaModule
  object jvm extends Shared with KuiJvmModule {
    object test extends ScalaTests with KuiTests with KuiCrossTests
  }
}
```

That shape is left over from when the same sources were also compiled to JavaScript, and the `.jvm`
suffix in a target name (`./mill libs.kernel.jvm.test`) is where you meet it. Shared code lives in
`libs/<name>/src` and anything platform-specific in `src-jvm`. Swap `KuiModule` for `KuiPureModule`
when the module must also be free of `var`.

### A note on the frontend

The interface is **not built by Mill**. It is a pnpm workspace under `frontend/` — TypeScript,
SolidJS and Vite (ADR-048) — with its own `package.json`, its own tests and its own container image,
and it reaches the backend over HTTP like any other client. That is deliberate: this build needs
**nothing but a JDK**, so somebody working on a Kafka adapter never installs Node to run a suite,
and the interface can be rebuilt or rolled back without reassembling a jar.

Its commands are pnpm commands, run from `frontend/`:

```
pnpm install          # once, and after a dependency change
pnpm build            # writes frontend/dist
pnpm watch            # the same build, rebuilding on save
pnpm test             # Vitest
pnpm typecheck        # tsc --build
```

The one direction that still runs backend-to-frontend is `./mill frontend.apiConstants`, which
writes the error codes, the RBAC vocabulary and the CSRF header name into
`frontend/packages/api/src/constants.generated.ts` so a rename on the Scala side becomes a compile
error on the TypeScript side. `--check` fails when the committed file has drifted. The browser's
*types* are generated on the frontend's own side, from the committed `docs/api/openapi.browser.json`,
with `pnpm --filter @kui/api run generate`.

See [docs/frontend/README.md](docs/frontend/README.md) for the whole workspace.

`resolveAll` is worth knowing about: it exists purely to fail fast. It asks the build to download
every library version listed in [DEPENDENCY_MATRIX.md](DEPENDENCY_MATRIX.md), even ones no module
uses yet, so that a wrong version number is caught in seconds instead of surfacing weeks later when
somebody finally writes the code that needs it.

## What CI runs

Every push to `main` and every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
Each stage of the CI pipeline is a separate GitHub Actions job, so a red check names the thing that broke
rather than saying "build failed". Every one of them is a command you can run yourself:

| Job | What it proves | Run it locally |
| --- | --- | --- |
| `compile` | Every module compiles, with warnings treated as errors | `./mill __.compile` |
| `style` | Formatting and lint rules are clean | `./mill __.checkFormat` then `./mill __.fix --check` |
| `architecture` | No module dependency breaks the layering rules of ADR-041 | `./mill checkArchitecture` |
| `generated` | The committed OpenAPI documents and error-code table still match the code they were generated from | `./mill __.openApiCheck` then `./mill docs.errorCodes --check` |
| `test` | Every unit, property and contract suite passes on the JVM | `./scripts/run-tests.sh` |
| `frontend` | The interface's own build, with no JDK and no Mill: `pnpm install`, `pnpm typecheck`, `pnpm test`, `pnpm build`, and a check that the committed browser types have not drifted from the OpenAPI document they are generated from | — |
| `compose` | The five container images build and the Compose stack survives one service dying | `./deployment/compose/smoke.sh` |
| — | ⚠️ **There is no browser end-to-end gate.** The Scala Playwright suite in `e2e/` selects on `data-testid` attributes the deleted Laminar components carried, and points the browser at the gateway, which no longer serves an interface. It fails structurally on every commit, so it is not run rather than left red — a red build that is always red means nothing, and `continue-on-error` would be worse. `docs/ROADMAP-SOLID.md` M2 replaces it with a TypeScript Playwright suite beside the frontend | — |

One thing about the `test` stage is worth knowing before you are surprised by it.

It runs through `./scripts/run-tests.sh` rather than a list of Mill commands, and the reason is worth
one paragraph. Mill's `.test` is a *command*, so `./mill a.test b.test` does not run two modules: it
runs the first and hands `b.test` to the test framework as a name filter. The workflow used exactly
that form, MUnit matched nothing, every suite was reported "ignored", and the step passed green
having executed no tests at all. The script asks Mill which test modules exist, names them with
selector syntax — `./mill '{a.test,b.test}'`, which really does run them all — and then counts
`<testcase>` elements in the JUnit reports rather than trusting the number Mill prints at the end,
which is a count of *build tasks* and roughly twice the number of tests. Today that is **4140 test
cases across 57 modules**.

Planned stages that have no build task yet are deliberately not in the workflow. The task that
creates each one adds its own job. A job that cannot fail is not a check.

Caching: `~/.cache/coursier`, `~/.cache/mill` and `out/` are cached, keyed on `build.mill`,
`.mill-version` and `mill-build/build.mill`, so a dependency change invalidates the cache instead of
reusing a stale classpath.

## Checking which version is running

A support conversation starts with "which version are you running?", and a container tag is not a
reliable answer — a tag can be moved, rebuilt, or typed by hand. The gateway reports what it was
actually compiled from:

```
$ curl -s localhost:8080/api/v1/info | jq .build
{
  "version": "0.1.0-SNAPSHOT",
  "gitCommit": "44a33c1cd587efe016f95f554f36b57dfcd9f742",
  "gitCommitShort": "44a33c1",
  "gitDirty": false,
  "builtAt": "2026-09-03T13:00:34.000Z",
  "scalaVersion": "3.9.0",
  "jdkVersion": "21.0.10"
}
```

`gitDirty` is the field worth knowing about: it is `true` when the build was made from a working
tree with uncommitted changes, which is what explains the otherwise impossible situation where "it
works on the commit you gave me" and "it does not work for you" are both true.

The endpoint needs no authentication, so a health dashboard can read it. For that reason it contains
no URL, no hostname and no key id — `services` lists configured service *ids* only. The same values
appear in the one INFO line the process logs at startup, so a log file and a live endpoint can be
cross-checked rather than compared and hoped about.

A build made outside a git checkout — a release tarball, say — reports `"unknown"` for the git
fields rather than failing or leaving them blank.

## Running one service on its own

Every service is an ordinary process with its own `main`. There is no orchestration to set up and
nothing to install: point it at a configuration file and it starts.

```
$ export KUI_PRINCIPAL_KEY=0123456789abcdef0123456789abcdef
$ ./mill services.cluster.app.run -- --config my-cluster.yaml
{"timestamp":"...","message":"starting kui-cluster 0.1.0-SNAPSHOT (f62de3f)","level":"INFO",...}
{"timestamp":"...","message":"listening on http://localhost:8081","level":"INFO",...}
```

with `my-cluster.yaml`:

```yaml
kui:
  server:
    host: "127.0.0.1"
    port: 8081
  gateway:
    principalKeys:
      - kid: "local-1"
        key: "env:KUI_PRINCIPAL_KEY"
        notBefore: "2026-01-01T00:00:00Z"
```

The three endpoints every KUI service serves need no credentials, because a probe has none to give:

```
$ curl -s localhost:8081/health/live
{"alive":true,"at":"2026-09-03T16:48:38.581Z"}

$ curl -s localhost:8081/capabilities
{"service":"cluster","clusters":{}}
```

Everything under `/internal/v1` does. A service is only ever called by the gateway, which signs a
short-lived principal for each call, so a request without one is refused — and refused identically
however it is wrong, so that nobody can use the response to work out what to forge next:

```
$ curl -s localhost:8081/internal/v1/clusters
{"code":"KUI-UNAUTHENTICATED","message":"Unauthenticated","details":[],"correlationId":"cde7...","timestamp":"...","retryable":false}
```

**A service started with no signing keys refuses to start.** For local development, and only for
that, `KUI_ALLOW_UNSIGNED=true` accepts unsigned headers and says so in the log every minute.
`docs/operations/configuration.md` has the whole configuration surface;
`services/cluster/app/resources/reference.yaml` is a commented file to copy from.

## Running the gateway with a locally built frontend

In a **release** the two halves are two images. The interface is built by Vite and served by the
nginx in `deployment/frontend/`, which proxies `/api/…` through to the gateway so that the browser
still sees one origin — no CORS, no `SameSite` decisions, no preflight on every mutation (ADR-019,
ADR-048). The gateway's jar contains no interface at all.

In **development** one process is more convenient than two, so the gateway can still serve the
build. `StaticRoutes` serves `GET /ui/**` from the classpath under `/web`, and `./mill dev` and
`./mill devStart` put one extra entry at the front of that classpath: a directory whose `web` is a
symbolic link to `frontend/dist`, the Vite build's output. Nothing is copied, which is what makes
"save, rebuild, refresh" the whole loop — see the comment on `dev` in `build.mill`.

Running the gateway without having built the interface is not an error:

```
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/ui/
503
```

is what a developer running the API alone should see, with a plain HTML page explaining that the UI
was not bundled into this build — never a stack trace, and never an empty `200`.

`server.basePath` moves every served URL, the injected `<base href>`, and the API prefix the shell
reads together: set `KUI_SERVER_BASEPATH=/kui` and the same build answers under `/kui/ui/`, while
the bare paths 404 — see `docs/operations/configuration.md`.

## Reading the design

| Document | What it covers |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The system: services, their boundaries, contracts, and how failure is contained |
| [docs/adr/](docs/adr/) | Every significant decision, why it was made, and what was rejected |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Milestones, in order, with what each delivers |
| [docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md) | Every capability and where it stands |
| [docs/domain/kafka-glossary.md](docs/domain/kafka-glossary.md) | The vocabulary the code uses |
| [DEPENDENCY_MATRIX.md](DEPENDENCY_MATRIX.md) | Every library, its version, and why it is here |
| [research/](research/) | What the reference projects do, in detail |

## Licence

Apache 2.0.
