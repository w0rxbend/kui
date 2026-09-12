# The quickstart

One command that leaves you with KUI running in a browser and a Kafka broker behind it with data in
it. The only thing you need installed is Docker.

```
deployment/quickstart/quickstart.sh
```

It prints two lines, and the first is the one you act on:

```
  KUI is running:  http://localhost:8090/ui/
  the API is at:   http://localhost:8080/api/v1
```

Two ports because there are two containers. The interface is its own image
(`deployment/frontend/`), built from the pnpm workspace under `frontend/` and served by an nginx
that proxies `/api/` through to the gateway, so the browser still sees one origin. The gateway's own
image contains no interface at all since ADR-048 — `http://localhost:8080/ui/` is not where KUI is.
`KUI_FRONTEND_PORT` and `KUI_PORT` move them.

To remove everything again:

```
deployment/quickstart/quickstart.sh down
```

## What you actually see today, honestly

This section said **"KUI does not connect to Kafka yet"** and described the Milestone 0 shell, which
stopped being true eight milestones ago and stayed written here. Re-measured against the stack this
command starts, on 2026-09-11, with `curl` beside the browser rather than from memory:

```
$ curl -s localhost:8080/api/v1/clusters | jq -r '.clusters.data[].cluster.id'
quickstart
staging-eu-01
```

- **Two registered clusters**, and they are two profiles over the **same** broker rather than two
  Kafkas — see the `kui.clusters` block in `kui-quickstart.yaml`, which says so at length. The
  second one exists so that the cluster selector in the drawer head has something to select and the
  *"Switched to staging-eu-01"* toast has something to name.
- **Real data behind both.** The topics screen lists **12** topics, and **16** with the *"show
  internal topics"* switch on. The message browser decodes the one Avro topic through the registry
  beside the broker; the Connect screen lists `quickstart-file-source` off a real worker; the ksqlDB
  screen lists `QUICKSTART_ORDERS` off a real server; the dashboard's traffic cards read a real JMX
  exporter. Every one of those is a container in the table below and can be asked the same question
  by hand on a published port.
- **Four consumer groups, and none of them is both live and behind.** The sentence this replaces
  said *"4 consumer groups, one of them genuinely live and behind"*, and no such group has ever
  existed on this stack: a group is behind because nothing is reading it, so *live* and *behind* are
  the two ends of the same axis and the seed deliberately puts one group at each. What is actually
  there is below, under [What is in the broker](#what-is-in-the-broker).
- **`staging-eu-01` is deliberately thinner**, and that is the other thing the quickstart now
  demonstrates: it has no metrics source, no Schema Registry, no Connect worker and no ksqlDB
  server, so its dashboard says *"not configured"* where the first cluster draws a figure and its
  drawer hides the three feature rows entirely (ADR-032). A deployment where every cluster has
  everything cannot show that half of the product.

**Both figures in the second bullet were wrong the day they were written, and this is what replaced
them.** The topic count said *"12 topics (18 counting the internal ones the switch hides)"*. The
twelve is right; the eighteen never was. Measured on this stack on 2026-09-11:

```
$ docker exec kui-quickstart-kafka \
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | wc -l
16

$ curl -s 'localhost:8080/api/v1/clusters/quickstart/topics?pageSize=100' |
    jq -r '.topics.data.page.totalItems'
12

$ curl -s 'localhost:8080/api/v1/clusters/quickstart/topics?pageSize=100&showInternal=true' |
    jq -r '.topics.data.page.totalItems'
16
```

The four the switch hides are `__consumer_offsets` and `__transaction_state`, which Kafka keeps for
itself; `_schemas`, which the seed creates so that this quickstart has one; and
`_confluent-ksql-kui-quickstart-_command_topic`, which the ksqlDB server creates on its first
statement. Of the twelve that are shown, eight come from [`seed/topics.tsv`](seed/topics.tsv) and
four do not: `connect-configs`, `connect-offsets` and `connect-status` are the Connect worker's own
bookkeeping, and `connect.file.lines` is what the one registered connector writes into. So the
broker holds more topics than the seed file lists, and that is the worker and the ksqlDB server
being real rather than a drift in the seed.

**Nothing in this repository can fail when these numbers drift again.**
`./scripts/feature-matrix-check.sh` guards every figure this repository publishes about itself, and
every one of its claim kinds compares a document against the *repository* — a file roster, a table's
own total, a generated OpenAPI document. The figures above are answers from a *running deployment*,
and no claim kind can ask a container a question. That is why they survived two waves: each was
published in a file with no marked region, in a section whose own heading is *"honestly"*. A
`deployment-claims` kind that runs the three commands above against the stack `quickstart.sh` has
just started is filed for whoever owns that script next; until it exists, those commands are the
gate and a reader is the one running it.

The counts here are this stack's, not promises: re-run them and count for yourself. The broker is
still yours to point your own tools at on `localhost:9092`.

## What it starts

| Container | Image | What it is |
| --- | --- | --- |
| `kui-quickstart-kafka` | `apache/kafka:4.3.1` | one Kafka node in KRaft mode: it is its own controller and there is no ZooKeeper |
| `kui-quickstart-kafka-metrics` | `bitnamilegacy/jmx-exporter:1.4.0` | a Prometheus JMX exporter beside the broker, so the cluster's throughput is a number KUI can read. KUI reads a Prometheus endpoint and cannot read JMX: `MetricsSourceSettings.url` is a `SafeUrl`, restricted to `http` and `https` by `ARCHITECTURE.md` §14, so a `service:jmx:` address cannot be configured at all (ADR-050). Without this container every metrics card correctly says it cannot measure the figure — and that sentence is indistinguishable from KUI being unable to measure anything |
| `kui-quickstart-seed`  | `apache/kafka:4.3.1` | a one-shot container that creates topics, publishes messages and sets consumer-group offsets, then exits |
| `kui-quickstart-consumer` | `apache/kafka:4.3.1` | a long-lived consumer, so one consumer group is genuinely live rather than merely a set of committed offsets |
| `kui-quickstart-schema-registry` | `apicurio/apicurio-registry:3.0.6` | a Schema Registry, so one topic holds Avro that KUI has to decode rather than read. Apicurio speaks the same REST API Confluent's registry does, at `/apis/ccompat/v7`, and is Apache-2.0 throughout |
| `kui-quickstart-kafka-connect` | `apache/kafka:4.3.1` | a Kafka Connect worker in distributed mode, so the Connect screens have a real worker to be honest about. **One connector runs on it**, registered by `kui-quickstart-connect-seed` below. Its REST port is published on `8083` (`KUI_QUICKSTART_CONNECT_PORT`), so the worker's own answer can be read beside KUI's screen |
| `kui-quickstart-connect-seed` | `apicurio/apicurio-registry:3.0.6` | a one-shot container that registers one `FileStreamSource` connector on the worker and waits until it and its task both report `RUNNING`. This file used to say no connector should ever be registered here, on the argument that an empty list from a worker that answered is a *measured* empty list and is a different fact from `not_configured` and from a worker that will not answer. That distinction is real and the conclusion was wrong: `frontend/e2e/connect.spec.ts`'s two positive cases had nothing to list and skipped. The empty-worker state is covered by `deployment/compose`, whose worker runs nothing; this stack covers the other one |
| `kui-quickstart-ksqldb` | `confluentinc/ksqldb-server:0.29.0` | a ksqlDB server, so the ksqlDB screens have a real server to be honest about. **It is the one image here that is not Apache-2.0** — ksqlDB has no second implementation to choose, unlike the Schema Registry (ADR-014 Amendment 1) — and the one that is a genuine extra download rather than an image this file already pulls |
| `kui-quickstart-ksql-seed` | `apicurio/apicurio-registry:3.0.6` | a one-shot container that creates one ksqlDB stream over `orders.v1` and reads it back with `SHOW STREAMS`, so the object listing has a row and a push query has something to select from |
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

Nine rows, and the broker ends up with **16** topics. The other seven belong to the containers
beside it and not to the seed: `connect-configs`, `connect-offsets`, `connect-status` and
`connect.file.lines` from the Connect worker and its one connector,
`_confluent-ksql-kui-quickstart-_command_topic` from the ksqlDB server, and `__consumer_offsets` and
`__transaction_state` from Kafka itself.

The seed creates three consumer groups, in the three states an operator actually has to tell apart.
Measured on this stack on 2026-09-11 with
`curl -s localhost:8080/api/v1/clusters/quickstart/consumer-groups`, which is where each figure
below comes from:

- `order-fulfilment` is **stopped and behind** on `orders.v1`: `EMPTY`, no members, and **behind by
  single digits with uneven lag across its six partitions** — see the note below before quoting a
  number;
- `payments-ledger-sync` is **stopped and caught up**: `EMPTY`, no members, `totalLag` 0 — so zero
  lag is not the same as no group;
- `analytics-indexer` is **live**: a real consumer process in the `kui-quickstart-consumer`
  container, holding the group open — `STABLE`, one member, `totalLag` 0.

**Why `order-fulfilment`'s lag is described rather than published, and what it depends on.** This
line has carried a number three times and been wrong three times: 8, then 10, and 10 again after
wave 10 replaced it. Measured by W10-05's verifier on 2026-09-12, on a stack `quickstart.sh` had
just built and that nothing but read-only cases had touched: `totalLag` **9**, per-partition
`0,0,0,1,2,6`. On an older stack in the same pass, **11**. Both are correct reports about the stack
they were taken on, and that is the problem with writing either one down.

The figure is a subtraction and the subtrahend is fixed while the minuend is not:

- `seed.sh` commits `order-fulfilment` at **offset 2 in every one of `orders.v1`'s six partitions**
  (`--to-offset 2`), and clamps to the log end on a partition holding fewer than two records, so the
  committed side is at most 12 and in practice 7;
- `seed/data/orders.v1` holds **16** records, which is the whole of the other side **on a stack
  nobody has produced to**;
- and every record produced into `orders.v1` afterwards adds exactly one to the lag: the message
  browser's produce form, a browser case that produces, a second seed run against a broker whose
  volume survived.

So: **the lag is `orders.v1`'s length minus seven**, it is 9 on a cold stack, and it only ever goes
up. A reader comparing the screen against a number here would be reading a fact about how much the
stack has been used, which is not what this paragraph is about. What is stable, and is the whole
point of the row, is `EMPTY` with no members and a non-zero lag spread unevenly across six
partitions. Nothing in this repository compares this file to a running stack; that is why it drifted
three times, and it is filed for the `deployment-claims` claim kind W10-05 asked for.

**A fourth group is on the broker and the seed does not make it.** `kui-quickstart-connect` is the
Kafka Connect worker's own group, and KUI reports it as `STABLE` with no members and an
`incomplete` note saying it could read neither its members nor its offsets — which is a fourth state
and an honest one. The screen therefore shows **four** groups, not three.

**And the thing the lead section used to claim is not one of them.** No group here is *live and
behind* at the same time, because a group falls behind precisely when nothing is reading it: the
one with lag (`order-fulfilment`) has no members, and the one with members (`analytics-indexer`)
keeps up. Producing faster than the live consumer can read would be the only way to build that
state, and a quickstart whose numbers depend on the speed of the machine it runs on is one nobody
trusts — the same argument the paragraph below makes about resetting offsets with a timer.

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
  all-in-one example with **two** entries under `kui.clusters`: the broker this stack starts, and a
  second registered profile (`staging-eu-01`) over that same broker. The sentence that stood here
  said *"nothing reads that section yet"*, which was true of the release this file was written
  against and has not been true since M1 — the section is read at start-up, refused if it is
  malformed, and is where every cluster id in every URL comes from.
