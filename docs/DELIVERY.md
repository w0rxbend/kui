# Delivery: what "a finished application" means

The roadmap has ten milestones and reaches feature parity with the reference products at the eighth.
That is the long road. This document defines the shorter one: the smallest set of work after which
somebody can run KUI against their own Kafka and use it for real work, and how far along that road
the project currently is.

It exists because "build the application" is not a task anyone can pick up. This turns it into a
sequence with an end.

## The bar

KUI is finished, for this purpose, when a person who has never seen the project can:

1. Clone it, run one command, and have KUI and a Kafka broker both running.
2. See their clusters, brokers and topics without configuring anything beyond a bootstrap address.
3. Read messages from a topic, including the JSON inside them, and publish one.
4. See consumer groups and how far behind they are.
5. Point it at their own cluster with a documented example configuration, including a secured one.
6. Have any part of it fail without the rest becoming unusable.

Point six is not a feature. It is the reason the architecture is shaped the way it is, and it is
tested rather than asserted.

## The sequence

| Stage | Delivers | State |
| --- | --- | --- |
| M0 Foundation | build, libraries, gateway, sample service, shell, single-process assembly, images, Compose | done |
| M1 Cluster connectivity | real Kafka connections with production security, clusters and brokers, the metadata store | planned |
| M2 Topic explorer | topic list, search, detail, partitions, configuration | roadmapped |
| M3 Message explorer | browsing with every seek mode, streaming, serialization formats, publishing, filters | roadmapped |
| M4 Consumer groups | groups, members, assignments, lag, offset reset | roadmapped |
| Quickstart | one command that starts Kafka, seeds it with data, and opens KUI on it | not started |
| Configuration examples | a plain example, a secured example, and the reference for every key | partial |

Stages M5 to M9 — cluster administration, authentication, the ecosystem plugins, metrics — are
beyond this bar. They are real work and they are planned, but nobody needs them to use the product.

## The quickstart, specifically

The thing a newcomer runs must not require them to understand the architecture:

- One command. It starts a Kafka broker, waits for it, creates a handful of topics with realistic
  names and shapes, publishes messages into them including JSON payloads, starts a consumer group so
  there is lag to look at, and starts KUI pointed at all of it.
- It must work on a machine with nothing installed but Docker.
- It must print the URL to open, and nothing else the reader has to act on.
- Tearing it down must leave nothing behind.

Anything that makes a newcomer read a second document before they see the product working is a
defect in the quickstart, not a gap in their understanding.

## Configuration examples

Three, each one a file a person can copy:

- The simplest thing that works: one cluster, no security.
- A production shape: several clusters, one with SASL and TLS, secrets read from the environment
  rather than written in the file.
- The full reference: every key, its default, and what happens when it is wrong.

## How progress is tracked

`STATUS.md` holds the current position and `docs/FEATURE_MATRIX.md` the per-capability state. This
document holds only the delivery bar and the sequence above. When a stage completes, its row changes
here and the detail lives in the milestone's own plan.
