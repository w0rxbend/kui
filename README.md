# KUI

A Kafka management and observability interface, written entirely in Scala 3.

> **Status: milestone 0.** There is running code: a gateway, one service, a browser shell, and both
> deployment shapes. There are no Kafka features yet — the first of those arrive in M1. What M0
> proves is the foundation: that the pieces compose, that a service can fail without taking the
> interface down, and that the same code runs as one process or as several. See
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

**It is Scala from the browser down.** The user interface is Scala.js and Laminar, not JavaScript.
The same endpoint definitions generate the server, its documentation, and the client the browser
uses, so a change to a contract cannot silently break one side.

**It streams instead of accumulating.** Browsing records, following a query, watching metrics: all
of it flows from Kafka to the browser without buffering whole topics in memory.

**It can be one process or eleven.** The same modules compose into a single JVM for local use, or
into separate containers for production. No code changes between the two.

## Quick start

Two ways in. Both need only a JDK 21 and this repository; the second also needs Docker.

### See the interface, and change it

```
git clone <this repository> && cd kui
./mill dev
```

That builds everything, links the browser code, and starts the whole product — the gateway and every
service — as one process on one port. Open <http://localhost:8080/ui/>. Stop it with Ctrl-C.

To change something and see it:

```
./mill devStart                            # the same server, in the background
./mill -w frontend.uiShell.fastLinkJS      # re-links every time you save
```

Now edit, say, `frontend/ui-shell/src/kui/ui/shell/layout/Header.scala`, save it, and refresh the
browser. A re-link takes a few seconds and nothing restarts: the server reads the linker's output
directory directly, so there is no copy step and no proxy in the way. `./mill devStop` when you are
done.

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
| Change the interface | `frontend/ui-shell` (pages, layout) and `frontend/ui-kernel` (the design system) |
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

The build file decides once how modules are compiled, cross-compiled and tested, so declaring a new
one is short. A JVM-only module:

```scala
object config extends KuiJvmModule {
  object test extends ScalaTests with KuiTests
}
```

A browser-only module (compiled to JavaScript by Scala.js):

```scala
object uiShell extends KuiFrontendModule
```

A module compiled for *both*, sharing one set of sources:

```scala
object contractsCore extends Module {
  trait Shared extends KuiModule with PlatformScalaModule
  object jvm extends Shared with KuiJvmModule {
    object test extends ScalaTests with KuiTests with KuiCrossTests
  }
  object js extends Shared with KuiJsModule {
    object test extends ScalaJSTests with KuiJsTests with KuiCrossTests
  }
}
```

The cross layout puts shared code in `libs/<name>/src` and anything platform-specific in
`src-jvm` or `src-js`; tests are written once in `test/src`, with `test/src-jvm` and `test/src-js`
for the rare assertion that only holds on one platform. Swap `KuiModule` for `KuiPureModule` when
the module must also be free of `var`.

### A note on frontend tests

Running Scala.js tests needs a JavaScript engine. Install **Node.js** (20 or newer) for the plain
suites, and additionally `npm install --no-save jsdom` **in the repository root** for the suites
that need a `document` — a global install is not enough, because the generated test script resolves
`jsdom` by walking up from its own directory. Without Node
the frontend still compiles and links — only `./mill <module>.js.test` fails, with
`failed to start command List(node)`. See
[docs/development/toolchain.md](docs/development/toolchain.md) for the full setup, including the
version-manager trap that makes Node invisible to the build.

`resolveAll` is worth knowing about: it exists purely to fail fast. It asks the build to download
every library version listed in [DEPENDENCY_MATRIX.md](DEPENDENCY_MATRIX.md), even ones no module
uses yet, so that a wrong version number is caught in seconds instead of surfacing weeks later when
somebody finally writes the code that needs it.

## What CI runs

Every push to `main` and every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
Each stage of PLAN §49 is a separate GitHub Actions job, so a red check names the thing that broke
rather than saying "build failed". Every one of them is a command you can run yourself:

| Job | What it proves | Run it locally |
| --- | --- | --- |
| `compile` | Every module compiles, with warnings treated as errors | `./mill __.compile` |
| `style` | Formatting and lint rules are clean | `./mill __.checkFormat` then `./mill __.fix --check` |
| `architecture` | No module dependency breaks the layering rules of ADR-041 | `./mill checkArchitecture` |
| `test` | Every unit, property and contract suite passes, on the JVM and in JavaScript | `./mill libs.kernel.jvm.test build-tests.test`, then `./mill libs.kernel.js.test`, then `./mill frontend.uiKernel.test` |
| `frontend` | The frontend links with the optimising linker and has the bundle shape ADR-012 needs | `./mill frontend.__.fullLinkJS` then `./mill frontend.uiKernel.checkBundleShape` |

Two things about the `test` stage are worth knowing before you are surprised by them.

It needs **Node** on your `PATH` (see the note on frontend tests above) and `jsdom` installed into a
`node_modules` directory at the repository root. CI does both for you; a laptop does not.

It is also three commands rather than the one `./mill __.test` PLAN §49 asks for. Running a Scala.js
test module in the same Mill invocation as any other test module currently fails inside Mill's own
test runner, for reasons that have nothing to do with this code — see B-003 in
[BLOCKERS.md](BLOCKERS.md). Each module is green on its own, so the coverage is the same; the
command list is just longer until that is fixed.

Stages PLAN §49 lists that have no build task yet — integration tests, the OpenAPI diff, the Docker
build, end-to-end — are deliberately not in the workflow. The task that creates each one adds its
own job. A job that cannot fail is not a check.

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
$ curl -s localhost:8081/internal/v1/ping?message=hi
{"code":"KUI-UNAUTHENTICATED","message":"Unauthenticated","details":[],"correlationId":"cde7...","timestamp":"...","retryable":false}
```

**A service started with no signing keys refuses to start.** For local development, and only for
that, `KUI_ALLOW_UNSIGNED=true` accepts unsigned headers and says so in the log every minute.
`docs/operations/configuration.md` has the whole configuration surface;
`services/cluster/app/resources/reference.yaml` is a commented file to copy from.

## Running the gateway with a locally linked frontend

The gateway serves the shell from its own classpath at `GET /ui/**`, and the browser and the API
share one origin — no CORS, no second server, no separate deployment step (ADR-011, ADR-012).
`services/gateway/api/resources/web/index.html` is the committed template, and it references
`main.js` and `kui.css`. Where those two come from depends on how you started the process:

- **`./mill dev` and `./mill devStart`** put the Scala.js linker's output directory and the CSS
  pipeline's output directory on the classpath in front of everything else, so the server reads
  whatever the linker most recently wrote. Nothing is copied, which is what makes "save, re-link,
  refresh" the whole loop.
- **A release build** has the assets bundled into the gateway's own resources beside `index.html`.

Running the gateway with neither is not an error: it serves the template, and any file the template
asks for that is not there falls through to `index.html`, so the page loads unstyled and without a
shell rather than failing.

Running the gateway without a linked frontend at all is not an error:

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
