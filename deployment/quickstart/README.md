# The quickstart

One command that leaves you with KUI running in a browser and a Kafka broker behind it with data in
it. The only thing you need installed is Docker.

```
deployment/quickstart/quickstart.sh
```

It prints one line you have to act on:

```
  KUI is running:  http://localhost:8080/ui/
```

To remove everything again:

```
deployment/quickstart/quickstart.sh down
```

## What you actually see today, honestly

**KUI does not connect to Kafka yet.** Milestone 0 — the build, the gateway, the service topology,
the images, the user-interface shell — is finished, and Kafka connectivity is Milestone 1, which is
being planned as this is written. There is no Kafka client in the product yet at all.

So what you get when you open the URL is:

- the KUI shell: the navigation drawer, the header, the settings and gallery pages, all working;
- a home page that says *"Nothing to show yet — this is the KUI shell. Cluster overviews appear
  here once the clusters feature is installed."*;
- a broker that is genuinely running, seeded with topics, JSON messages and a consumer group that is
  behind, that you can point your own tools at on `localhost:9092`.

That is the truth about the release this file was written against and not a bug in this quickstart.
The reason the whole thing is built now anyway is a standing requirement: as soon as cluster
connectivity lands, `quickstart.sh` must show real clusters, topics and lag with nothing new for
anybody to type. The broker, the seed data and the cluster entry in the configuration are already
here waiting for it.

## What it starts

| Container | Image | What it is |
| --- | --- | --- |
| `kui-quickstart-kafka` | `apache/kafka:4.3.1` | one Kafka node in KRaft mode: it is its own controller and there is no ZooKeeper |
| `kui-quickstart-seed`  | `apache/kafka:4.3.1` | a one-shot container that creates topics, publishes messages and sets consumer-group offsets, then exits |
| `kui-quickstart-consumer` | `apache/kafka:4.3.1` | a long-lived consumer, so one consumer group is genuinely live rather than merely a set of committed offsets |
| `kui-quickstart-schema-registry` | `apicurio/apicurio-registry:3.0.6` | a Schema Registry, so one topic holds Avro that KUI has to decode rather than read. Apicurio speaks the same REST API Confluent's registry does, at `/apis/ccompat/v7`, and is Apache-2.0 throughout |
| `kui-quickstart-avro-seed` | `apache/kafka:4.3.1` | a one-shot container that registers the Avro schema and writes the Avro records **through KUI's own produce API**, because a console producer cannot write a record that begins with a zero byte |
| `kui-quickstart-kui`   | `kui-allinone:0.1.0-SNAPSHOT` | KUI, gateway and every service in one process (ADR-005) |

### Why Kafka 4.3.1 and not `apache/kafka:latest`

Two reasons, and the second is the one that matters.

The 4.x series is the first Kafka that removed ZooKeeper outright — KRaft, Kafka's own built-in
consensus, is the only mode there is — so this is a modern broker rather than an old one with KRaft
switched on. `4.3.1` is a patch release of that line.

And the tag is pinned rather than floating because a quickstart that pulls whatever was published
this morning can break without anybody touching KUI, and the breakage lands on the one person least
able to diagnose it: somebody running the project for the first time, who has no way to tell whether
they did something wrong. A pinned tag means the first run works the same way in a year as it does
today. Upgrading it is then a deliberate commit somebody tested, which is exactly what it should be.

### What is in the broker

Created by [`seed/seed.sh`](seed/seed.sh), which runs inside the Kafka image and uses Kafka's own
command-line tools, so nothing extra is installed and no extra image is pulled. The topics it makes
are listed in [`seed/topics.tsv`](seed/topics.tsv) and the messages in `seed/data/`;
[`seed/README.md`](seed/README.md) explains what each one is for.

| Topic | Partitions | What is in it |
| --- | --- | --- |
| `orders.v1` | 6 | keyed JSON orders with headers — the shape most people's busiest topic has |
| `payments.transactions` | 3 | keyed JSON payments, no headers |
| `analytics.pageviews` | 12 | unkeyed JSON events: the high-volume firehose |
| `customers.profiles` | 3 | compacted, so it reads as a table: repeated keys and a tombstone |
| `inventory.stock-levels` | 4 | `cleanup.policy=compact,delete` — compaction and a retention window together |
| `audit.log.raw` | 1 | deliberately **not** JSON: logfmt and plain log lines, so the message viewer has something it cannot parse |
| `orders.v1.DLQ` | 3 | dead letters whose headers point at real offsets in `orders.v1` |
| `orders.avro` | 3 | **Avro**, written in the Schema Registry wire format: a magic byte, a schema id, then the encoded body. There is no way to read it without the registry, which is the point — KUI fetches the schema by the id inside each record and shows the decoded JSON, with the schema's type, id and subject beside it |
| `_schemas` | 1 | an internal topic, the kind a UI hides behind "show internal topics" |

Three consumer groups, in the three states an operator actually has to tell apart:

- `order-fulfilment` is **stopped and behind** on `orders.v1`, with uneven lag across partitions;
- `payments-ledger-sync` is **stopped and caught up**, so zero lag is not the same as no group;
- `analytics-indexer` is **live**: a real consumer process in the `kui-quickstart-consumer`
  container, holding the group open with one member and no lag.

That last one has to be a separate long-lived container rather than a line in the seed script,
because a group has members only while some process is holding a session open with the broker. A
seed job exits, and the group it created goes empty the moment it does.

The stopped groups' offsets are written directly with `kafka-consumer-groups.sh --reset-offsets`
rather than by running a consumer for a few seconds and stopping it. A consumer racing a timer
produces different lag on a fast laptop than on a loaded machine, and a demonstration whose numbers
change every run is one nobody trusts.

Running the seed again changes nothing: it creates only missing topics, writes messages only into a
topic that has none, and never resets the offsets of a group that already exists.

## Waiting for Kafka properly

This is the part that looks like a detail and is not.

A Kafka container reports itself started well before it can serve a client. The process is up and
the port accepts connections while the controller is still electing itself and publishing the
cluster's metadata. Anything that connects in that window has its request refused or times out — and
the symptom is a KUI, or a seed step, that fails once at start-up and then looks fine, which is the
hardest kind of failure to reproduce.

So `depends_on` alone is not enough, and neither is a TCP port check. The broker's health check runs

```
kafka-topics.sh --bootstrap-server localhost:9092 --list
```

which is a real metadata request over the real client protocol. It cannot pass until the broker can
genuinely serve a client. The seed step waits on `condition: service_healthy`, and KUI waits on that
plus `service_completed_successfully` for the seed, so the first screen is never a half-seeded
cluster.

KUI's wait on the seed carries `required: false`, which means *wait for it, but start anyway if it
failed*. That is deliberate: KUI's central design position is that it stays up and reports what is
broken rather than vanishing along with it, and a quickstart that showed a blank page because one
topic could not be created would be contradicting the product it is demonstrating.

## Building without a JDK

KUI's images are not published to a registry yet, so there is nothing to pull, and the repository's
normal way of building them (`./mill deployment.docker.__.build`) needs a Java Development Kit
installed. The quickstart's promise is that Docker alone is enough, so the toolchain goes in a
container instead: [`Dockerfile`](Dockerfile) compiles KUI in a throwaway JDK stage and copies one
jar into a runtime image that has no compiler in it.

The script builds that image **only if it is not already on the machine**, and says so before it
starts:

```
The KUI image kui-allinone:0.1.0-SNAPSHOT is not on this machine, so it has to be built.

  This compiles KUI from source inside a container, so that a machine with only Docker
  installed is enough. It downloads a JDK image, the Mill build tool and every Scala
  dependency, then compiles the project.

  EXPECT SEVERAL MINUTES the first time — around two on a fast connection, longer on a
  slow one. It happens once: the image is kept, and later runs start immediately.
```

The build's own output is printed as it goes, rather than a spinner, so it is visible that nothing
is stuck. Every later run skips all of it and is up in about half a minute.

If you do have a JDK, run `./mill deployment.docker.allinone.docker.build` first. The script finds
that image and skips its own build, and you get the byte-reproducible image `build.mill` goes to
some length to guarantee.

## When the default ports are taken

`8080` and `9092` are popular. Pass different ones:

```
KUI_PORT=18080 KUI_QUICKSTART_KAFKA_PORT=19092 deployment/quickstart/quickstart.sh
```

The script checks both ports before it starts anything and, if one is busy, says which and suggests
free numbers, instead of letting Compose fail halfway through with a message about a container.

The Kafka port is only for *your* tools — `kcat`, an IDE, a console consumer on your machine. KUI
reaches the broker over the private Compose network by container name, so it is unaffected by which
host port you choose. That is also why the broker advertises two addresses: `kafka:9092` for
containers and `localhost:<your port>` for you. A Kafka client always reconnects to the address the
broker advertises rather than the one it was given, which is why getting this wrong produces the
classic failure where the first connection works and every later one hangs.

## The other commands

```
deployment/quickstart/quickstart.sh          start it and print the URL
deployment/quickstart/quickstart.sh logs     follow every container's log
deployment/quickstart/quickstart.sh status   what is running
deployment/quickstart/quickstart.sh down     remove containers, network and volumes
```

`down` passes `-v` and `--remove-orphans`, so no volume and no stray container survives. The
broker's data is not on a volume in the first place — it lives in the container's own writable
layer, so removing the container removes the data, and there is nothing left for anybody to find
months later. What remains on the machine after `down` is the two images, and only because
re-downloading them on the next run would be a waste of your time:

```
docker image rm kui-allinone:0.1.0-SNAPSHOT apache/kafka:4.3.1
```

## How this relates to the other deployment directories

- [`../compose/`](../compose/README.md) is the development and test topology, including the
  distributed shape (gateway and cluster service as separate containers) and the fault-isolation
  demonstration. Use it when you want to see KUI survive a service being killed. This directory does
  not touch it.
- [`../docker/`](../docker/README.md) documents the three published images and the conventions every
  one of them follows.
- [`kui-quickstart.yaml`](kui-quickstart.yaml) is this quickstart's own configuration, a copy of the
  all-in-one example with the quickstart's broker added under `kui.clusters`. Nothing reads that
  section yet — the configuration loader accepts everything under `kui.clusters` and ignores it, on
  purpose, so that a file written for the version you are about to upgrade to still loads on the one
  you are running.
