# KUI

A Kafka management and observability interface, written entirely in Scala 3.

> **Status: design phase.** The architecture and the plan are complete and reviewable in this
> repository. There is no runnable code yet. The first milestone builds the foundation; see
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
```

### The quality gates

```
./mill __.compile        # compiles everything; a warning is an error (see below)
./mill __.reformat       # rewrites sources to the project's formatting
./mill __.checkFormat    # fails if anything is not formatted
./mill __.fix            # applies the lint rules that can be applied automatically
./mill __.fix --check    # fails if a lint rule is violated, changing nothing
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

`resolveAll` is worth knowing about: it exists purely to fail fast. It asks the build to download
every library version listed in [DEPENDENCY_MATRIX.md](DEPENDENCY_MATRIX.md), even ones no module
uses yet, so that a wrong version number is caught in seconds instead of surfacing weeks later when
somebody finally writes the code that needs it.

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
