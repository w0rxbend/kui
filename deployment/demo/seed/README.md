# The demo's sample data

This directory fills the demo's **three** Kafka clusters with data, so that every screen in KUI has
something on it and switching between clusters shows you something different.

An empty Kafka makes every screen an empty state: no topics, no messages, no consumer groups, no
lag — nothing to click and nothing to understand. Somebody who has just started the demo cannot
tell an empty product from a broken one. A minute of seeding is the difference between "I see" and
"is it working?".

Three clusters rather than one is not decoration. Multi-cluster management is what this product is
for, and a single cluster cannot show the cluster switcher, the per-cluster capability states, or
the thing the architecture exists to prove — one cluster failing while the others carry on.

## Where this sits in the demo

Two kinds of thing, and the difference matters:

| | What it is | Lifetime |
| --- | --- | --- |
| `seed.sh` | creates topics and writes messages for **one** cluster, according to a **profile** | a job: it runs, it finishes, it exits |
| `consume.sh` | **one** consumer group that is genuinely alive | a service: it runs until the stack stops |

The Compose stack that starts the brokers and KUI lives one directory up. It runs `seed.sh` three
times — once per cluster, with a different `KUI_SEED_PROFILE` each time — and `consume.sh` once per
live consumer group. The full contract each script expects, including a Compose service definition
that satisfies it, is written at the top of the script itself. **Read those two headers before
wiring anything up**; this file is the tour, they are the specification.

The short version:

```yaml
seed-production:
  image: apache/kafka:4.3.1
  entrypoint: ["/bin/bash", "/seed/seed.sh"]
  environment:
    KAFKA_BOOTSTRAP_SERVERS: kafka-prod-1:9092
    KUI_SEED_PROFILE: production        # which profiles/<name>/ directory to apply
    KUI_SEED_EXPECT_BROKERS: "3"        # do not start until all three are up. See below.
  volumes:
    - ./seed:/seed:ro
  depends_on:
    kafka-prod-1: { condition: service_healthy }
    kafka-prod-2: { condition: service_healthy }
    kafka-prod-3: { condition: service_healthy }
  restart: "no"
```

There is nothing to build. Both scripts run inside the stock `apache/kafka` image using the shell
tools it already carries, as the non-root user it already runs as. No Dockerfile, no extra image in
the pull, no compile step between the reader and their first look.

**`KUI_SEED_EXPECT_BROKERS` is the one setting that is easy to leave out and expensive to get
wrong.** Without it the seed proceeds the moment *one* broker answers. On the three-broker cluster
that means every topic is created with a replication factor of 1 — because that is genuinely all
the cluster could offer at that instant — and it stays that way for the life of the stack. The
in-sync-replica column KUI exists to show would then be empty, with nothing obviously broken to
explain it.

## Where it came from

`deployment/quickstart/seed/`, which does the same job for one cluster. The **file formats are
unchanged**, deliberately, so a data file can be moved between the two without editing, and seven
of the message files here are byte-for-byte copies of the quickstart's with a provenance line
added. What is new is:

- **profiles** — `profiles/<name>/`, so one script can produce three different-looking clusters;
- **`groups.tsv`** — a declarative table of consumer groups, instead of two hard-coded ones;
- **`bulk.tsv`** — generated messages, for the volume the production-shaped cluster needs;
- **`KUI_SEED_COMMAND_CONFIG`** — a Kafka client properties file, so it can seed a secured broker.

## The three clusters

### `development` — somebody's laptop

Five topics, one to three partitions each, one broker, short retention, one leftover test topic
nobody cleaned up, about forty messages, and a single consumer group that is a little behind.

Its job is to be the cluster you switch *away* from. Put it beside the production cluster in the
switcher and the difference in scale is immediately legible, which is the argument for managing
several clusters in one place. Three of its topics share names with production topics on purpose:
it is the same application deployed twice, which is what a development cluster actually is.

### `production` — a system somebody is running

Sixteen topics, three brokers, replication factor 3, partition counts from 1 to 24, about
**20 175 messages**, and eight consumer groups.

| | |
| --- | --- |
| Topics | `orders.v1` (12 partitions), `payments.transactions` (6), `shipping.dispatches` (3), `billing.invoices` (4), `notifications.outbound` (6), `fraud.scores` (3), `analytics.pageviews` (24), `clickstream.raw` (12), `platform.metrics` (6), `customers.profiles` (6), `search.index-updates` (8), `inventory.stock-levels` (4), `audit.log.raw` (2), `orders.v1.DLQ` (3), `loyalty.points` (3, deliberately empty), `_schemas` (1) |
| Cleanup policies | `delete`, `compact`, and `compact,delete` — all three, on real topics |
| Not JSON | `audit.log.raw`: logfmt and plain application log lines |
| Tombstones | `customers.profiles`, `_schemas`, and about 240 of them in `search.index-updates` |
| Dead letters | `orders.v1.DLQ`, whose headers name real partitions and offsets in `orders.v1` |
| Volume | 20 000 generated records across four topics, so the virtualised table and offset seek do real work |

**The lags are spread across four orders of magnitude on purpose**, because lag that is the same
everywhere is a colour rather than a signal. Measured on a seeded cluster:

| Group | State | Lag |
| --- | --- | --- |
| `analytics-rollup` | Empty | 8 000 — the whole topic |
| `metrics-exporter` | Empty | 1 500 — exactly half |
| `order-fulfilment` | Empty | 9, uneven across 12 partitions |
| `billing-reconciler` | Empty | 8 on one topic, 0 on another — it is subscribed to two |
| `dlq-triage` | Empty | 4, and the most important number on the screen |
| `payments-ledger-sync` | Empty | 0 |
| `search-indexer` | **Stable, 3 members** | 0 |
| `clickstream-archiver` | **Stable, 1 member** | 0 |
| `fraud-scoring` | **Stable ⇄ PreparingRebalance, 2 members, 2 topics** | 0 |

### `secured` — proof that the secured path carries real data

Four topics, one broker, about forty messages, two consumer groups — one behind, one caught up.

Deliberately thin. The other two clusters exist to be explored; this one exists to prove one
claim, and four topics test it as thoroughly as forty would. The four cover the four things that
can serialise differently: keyed JSON with headers, keyed JSON without them, a value that is not
JSON at all, and a compacted topic with a tombstone in it — a null being the one payload a
transport layer can quietly turn into an empty string.

Two groups rather than one because lag is computed from two separate calls to the broker, either
of which could be the one that fails over TLS. `0` and `0` would not tell you whether both numbers
were being read or both were coming back empty; `13` and `0` does.

## What is on each screen

The point of all of the above. Every row is on at least one cluster.

| Screen | What it has to show | Where |
| --- | --- | --- |
| Cluster list / switcher | three clusters, different sizes, one secured | all |
| Broker list | three brokers with in-sync replicas, and single-broker clusters | production |
| Topic list | 25 topics across the three, no two the same shape | all |
| Topic detail — partitions | 1 to 24 partitions; leaders spread over three brokers | production |
| Topic detail — configuration | three cleanup policies, retention from 1 hour to 180 days, four compression types, `max.message.bytes` | production |
| Empty state | `scratch.jm-test`, `loyalty.points` — created, holding nothing | dev, production |
| Internal topics | `_schemas`, behind "show internal topics" | dev, production |
| Message browser — JSON | payloads whose fields differ within one topic, nested three deep | all |
| Message browser — not JSON | `audit.log.raw` | all |
| Message browser — tombstones | a key with a null value, in three topics | all |
| Message browser — headers | trace ids, event types, and the DLQ's failure headers | all |
| Message browser — no headers | `payments.transactions`, `billing.invoices` | all |
| Message browser — paging | 8 000 records in one topic, 20 000 on the cluster | production |
| Consumer groups — states | Empty, Stable, and PreparingRebalance | production |
| Consumer groups — members | 1, 2 and 3 members, with readable client ids | production |
| Consumer groups — lag | 0, 4, 8, 9, 1 500, 8 000 | production |
| Offset-reset wizard | `analytics-rollup`, 8 000 behind and stopped, which is exactly its use case | production |
| Degrading honestly | stop one broker: see below | production |

### The failure the demo is for

`shipping.dispatches` is created with `min.insync.replicas` equal to its replication factor, so all
three copies must acknowledge every write. Stop any one broker of the production cluster and, on a
seeded cluster, this is what happens — all of it observed, not asserted:

- **97 partitions across all 16 topics go under-replicated**, so the in-sync-replica column has
  something real in it;
- **`shipping.dispatches` starts rejecting writes** with
  `org.apache.kafka.common.errors.NotEnoughReplicasException`;
- **every other topic keeps accepting them**, because they ask for 2 of 3;
- the development and secured clusters are untouched, which is the whole point.

An under-replicated partition cannot be manufactured by a seed script. Kafka's controller refuses a
replica assignment naming a broker that does not exist, so the only way to have one is to be
missing a broker. What this directory can do is arrange for *stopping a broker* to be interesting,
and that is what `shipping.dispatches` is for.

## How long it takes

Measured on a laptop with 16 cores, against `apache/kafka:4.3.1`, with all five brokers and all
three seeds running at the same time — which is what the demo actually does, and is slower than
timing one profile alone:

| | |
| --- | --- |
| **All three clusters, first run** | **48 seconds**, wall clock, in parallel |
| — of which `production` | 46s: 3s waiting for brokers, 15s creating 16 topics, 16s writing 20 175 messages, 12s positioning 7 consumer groups |
| — of which `development` | 44s |
| — of which `secured` | 43s |
| **Every run after that** | **6 to 15 seconds**, because there is nothing left to do |

**Nearly all of that is JVM startup, not Kafka.** Each of Kafka's shell tools costs a JVM start of
two to four seconds, and seeding three clusters needs about sixty of them. The script is built
around spending as few as it can — one command to list every topic, one to read every topic's end
offsets, one to list every group — and running the unavoidably-per-topic ones all at once.

Two measured optimisations are worth knowing about, because both looked like nothing and were not:

- **Kafka's launcher scripts set garbage-collection flags tuned for a broker**, a process that runs
  for months. Every process here lives for two seconds. Overriding them with `-XX:+UseSerialGC
  -XX:TieredStopAtLevel=1` took one `kafka-topics.sh` call from 4.6s to 3.3s and its CPU from 2.7s
  to 1.6s — and took the whole three-cluster seed **from 69 seconds to 48**.
- **The bulk generators originally shelled out to `date`** once per record for a readable
  timestamp. Twenty thousand records have twenty thousand distinct second values, so caching does
  not help: it was twenty thousand subprocesses, and about thirty seconds. `strftime()` inside awk
  does it with no process at all, and the generators now take 0.3 seconds.

## Repeatability

Running any of it twice is safe and does nothing. A topic is created only when absent, messages are
written only into a topic that holds none, and a consumer group is given offsets only when that
group does not already exist. That last rule matters more than it looks: if you have been clicking
around in KUI and a container restarts, having your consumer group silently rewound underneath you
would be a small betrayal.

Verified: a second run of the production profile reported `all 16 already present`, `produced
nothing`, `all already present, left untouched`, and the cluster still held exactly 20 175 records.

If the offset check cannot be run at all, the script stops rather than guessing. Guessing wrong in
that one place would mean appending a second copy of every message on every restart.

The generated messages are deterministic too: each topic's random seed is derived from its name, so
the same topic gets the same messages on every run and on every machine. A screenshot taken today
still matches the data next month.

## Two things it deliberately does not do

**It does not fake a live consumer group.** A group has members only while some process is holding
a session open with the broker, and `seed.sh` exits. Every group `seed.sh` makes is therefore
reported by Kafka as `Empty` — which is a real and common state, being what a stopped service looks
like. The live ones are containers running `consume.sh`.

**It cannot control record timestamps, and this is worth knowing before you reach for a
time-based feature.** Kafka stamps every record with the moment it was *produced*, and
`kafka-console-producer` has no option to override that. The timestamps inside the JSON say "six
hours ago" because a person wrote them that way, but as far as the broker is concerned the whole
cluster happened within a few seconds of the seed running. So a seek-by-timestamp in the message
browser works and returns valid offsets, but the entire topic falls inside a one-minute window;
and a consumer group reset with `--to-datetime` resolves to offset 0 on every partition. Use
offsets. Fixing this properly needs a producer that sets timestamps, which needs a compile step,
which is exactly what "no Dockerfile, nothing to build" rules out.

## The files

| File | What it is |
| --- | --- |
| `seed.sh` | the job: waits for the brokers, creates topics, writes messages, creates the stopped groups |
| `consume.sh` | the service: one live consumer group, optionally with several members and a rebalance on a timer |
| `profiles/<name>/topics.tsv` | the topic table — name, partitions, replication, configuration — with a comment per row |
| `profiles/<name>/groups.tsv` | the stopped consumer groups — group, topic, where to commit |
| `profiles/<name>/bulk.tsv` | the generated messages — topic, generator, count, layout (production only) |
| `profiles/<name>/data/<topic>` | the hand-written messages for that topic, one per line |

Each `.tsv` explains its own columns and every row in it. `seed.sh` explains the generators.

### The data file format

Unchanged from the quickstart's, so files are interchangeable. One file per topic, named exactly
after the topic. Lines beginning with `#` are comments, and one of them is load-bearing: a `#mode:`
line saying how the rest of the lines are laid out.

| `#mode:` | Line layout |
| --- | --- |
| `headers-key-value` | `h1:v1,h2:v2` `\t` `key` `\t` `value` |
| `key-value` | `key` `\t` `value` |
| `value-only` | `value` |

Three conventions inside the lines:

- **`\t` is two literal characters**, a backslash and a `t`, not a real tab. `seed.sh` converts them
  just before handing the line to Kafka. Real tabs in a data file are invisible, and the first
  editor that helpfully turns them into spaces would break the file silently.
- **`%TS-3600%` becomes an ISO-8601 UTC timestamp** 3 600 seconds before the seed runs, and
  `%TS+86400%` one 86 400 seconds after it. This is what stops the sample data reading as a museum
  piece: an order placed "an hour ago" stays an hour ago however long the file sits in git.
- **`<NULL>`** as a whole field means a genuine null rather than an empty string. That is how a
  tombstone is written.

To add a topic: add a row to that profile's `topics.tsv` and, if it should hold messages, a file
under `data/` named after it with a `#mode:` line at the top, or a row in `bulk.tsv`. Nothing else
needs changing. A topic with no data at all is created and left empty, which is itself a legitimate
thing to want on screen.

**Do not give a topic both a `data/` file and a `bulk.tsv` row.** The "has this topic got records
already?" check is one snapshot taken before anything is produced, so both would fire, and the
topic would end up with two sets of messages.
