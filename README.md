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
