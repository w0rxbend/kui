# The quickstart's sample data

This directory fills a throwaway Kafka cluster with data that looks like it came from a running
business, so that the first screen a newcomer sees in KUI has something on it.

That is the whole point of it. An empty Kafka makes every screen in the product an empty state:
no topics, no messages, no consumer groups, no lag — nothing to click and nothing to understand.
Somebody who has just run the quickstart cannot tell an empty product from a broken one. Ten
seconds of seeding is the difference between "I see" and "is it working?".

## Where this sits in the quickstart

Two pieces, and they are different kinds of thing:

| | What it is | Lifetime |
| --- | --- | --- |
| `seed.sh` | creates the topics and writes the messages | a job: it runs, it finishes, it exits |
| `consume.sh` | the one consumer group that is alive | a service: it runs until the stack stops |

The Compose stack that starts Kafka and KUI lives one directory up, in
`deployment/quickstart/`, and runs both of these. The full contract each one expects — image,
entrypoint, environment, ordering, exit code — is written at the top of the script itself,
including a Compose service definition that satisfies it. Read those two headers before wiring
anything up; this file is the tour, they are the specification.

The short version:

```yaml
kui-seed:
  image: apache/kafka:4.3.1
  entrypoint: ["/bin/bash", "/seed/seed.sh"]
  environment:
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092     # the only thing it needs
  volumes:
    - ./seed:/seed:ro
  depends_on:
    kafka: { condition: service_healthy }
  restart: "no"
```

There is nothing to build. Both scripts run inside the stock `apache/kafka` image, using the
shell tools that image already carries, as the non-root user it already runs as. No Dockerfile,
no extra image in the pull, no compile step between the newcomer and their first look.

## What it creates

Eight topics. They are chosen so that no two of them look the same in a list, because a topic
list where every row is identical teaches nothing:

| Topic | Partitions | Replication | Shape | Why it is here |
| --- | --- | --- | --- | --- |
| `orders.v1` | 6 | 3 | JSON, keyed, headers | the busy, important stream most systems have |
| `payments.transactions` | 3 | 3 | JSON, keyed, no headers | plenty of producers set no headers; the UI must not look broken |
| `analytics.pageviews` | 12 | 1 | JSON, no key, no headers | the firehose: many partitions, short retention |
| `customers.profiles` | 3 | 3 | JSON, keyed, compacted | a compacted topic is a table, not a log, and renders differently |
| `inventory.stock-levels` | 4 | 2 | JSON, keyed, headers, `compact,delete` | both cleanup policies at once |
| `audit.log.raw` | 1 | 1 | **not JSON** | one topic the JSON viewer must decline to parse |
| `orders.v1.DLQ` | 3 | 3 | JSON, keyed, failure headers | a dead-letter queue, interesting because it is not empty |
| `_schemas` | 1 | 3 | JSON, keyed | an internal topic, hidden behind "show internal topics" |

The replication column is what these topics would have on a real cluster. The quickstart runs one
broker, and Kafka refuses to create a topic with more replicas than it has brokers, so `seed.sh`
counts the brokers first and lowers every number above that count — and lowers
`min.insync.replicas` with it, which is the setting that would otherwise let the topic be created
and then reject every write to it. Point the same directory at a three-broker cluster and the
numbers above are used as written.

Retention, compaction, compression and message-size limits are set per topic and are all real
Kafka settings. `topics.tsv` holds the table and explains each one.

Then about 110 messages across those topics:

- **JSON of varying shape.** Messages in the same topic do not all have the same fields — orders
  carry a `schemaVersion` that changes what is in them, pageviews carry an experiment block about
  one time in five, some fields are explicitly `null`. That is what a topic looks like after a
  year of shipping, and it is the case a JSON viewer has to survive.
- **Keys and headers on some, neither on others.** `orders.v1` and `inventory.stock-levels` carry
  headers (trace ids, event types, warehouse names); `orders.v1.DLQ` carries the headers that say
  why a message failed, referencing real partitions and offsets in `orders.v1`, so the two topics
  tell one story. `analytics.pageviews` and `audit.log.raw` have no key and no headers at all.
- **Two tombstones.** In `customers.profiles` and `_schemas` there is a record with a key and no
  value, which in a compacted topic is how a delete is expressed. A message browser has to draw
  that as absence, not as an empty string.
- **Repeated keys in the compacted topics.** `CUST-8812` and `CUST-5501` each appear twice with
  different values, so the log cleaner has something real to do and browsing the topic may show
  you one version or both, depending on whether it has run.
- **Text that is not JSON**, in `audit.log.raw`: logfmt lines and plain application log lines. If
  KUI ever renders one of those as an error, this topic is how you find out in ten seconds.

And three consumer groups, in three different states, because a groups screen where everything is
identical says nothing:

| Group | State | Lag | Made by |
| --- | --- | --- | --- |
| `order-fulfilment` | stopped part-way through `orders.v1` | real, uneven across partitions (9 of 16 messages when last measured) | `seed.sh` |
| `payments-ledger-sync` | stopped, but caught up | zero | `seed.sh` |
| `analytics-indexer` | live, one member, `Stable` | zero, and staying there | `consume.sh` |

`seed.sh` writes the two stopped groups' offsets directly rather than running a consumer to
produce them: a stopped group is nothing but a set of committed offsets, so this is one command
instead of a consumer session per partition, and the result is exact rather than approximate. It
cannot do the same for the live group — a group is active only while a process is holding a
session open, and `seed.sh` exits — which is why `consume.sh` exists as a separate, long-lived
service.

## What you will actually see today

Milestone 0 of KUI does not connect to Kafka yet. Running the quickstart right now starts the
broker, seeds it with everything above, and opens KUI on its shell, where the cluster capability
reports itself as not yet available. All of this data is real and is sitting in Kafka; nothing in
the product reads it so far.

That is temporary and expected, and it is deliberately built anyway: when Milestone 1 lands, the
same command shows real clusters, topics and lag with nothing here changed.

## Speed and repeatability

Measured against a single-broker `apache/kafka:4.3.1` on a laptop:

- **first run, empty cluster: about 16 seconds**, of which roughly 4 are waiting for the broker
  to accept connections at all;
- **every run after that: about 4 seconds**, because there is nothing left to do.

Each of Kafka's shell tools costs a JVM start of a second or two, and that is nearly all of the
time above. The script is built around spending as few of them as possible: it reads the whole
topic list in one command instead of asking about each topic, reads every topic's end offset in
one command instead of one per topic, and runs the eight unavoidable per-topic commands
concurrently, so eight JVMs start together rather than in a queue.

Running it twice is safe and does nothing. It creates a topic only when the topic is absent,
writes messages only into a topic that holds none, and gives a consumer group offsets only when
that group does not exist. The last of those matters more than it looks: if you have been
clicking around in KUI and a container restarts, having your consumer group silently rewound
underneath you would be a small betrayal.

If the offset check cannot be run at all, the script stops rather than guessing. Guessing wrong
in that one place would mean appending a second copy of every message on every restart.

## The files

| File | What it is |
| --- | --- |
| `seed.sh` | the job: waits for the broker, creates topics, writes messages, creates the stopped groups |
| `consume.sh` | the service: the one live consumer group |
| `topics.tsv` | the topic table — name, partitions, replication, configuration — with a comment per row |
| `data/<topic>` | the messages for that topic, one per line |

### The data file format

One file per topic, named exactly after the topic. Lines beginning with `#` are comments, and one
of those comments is load-bearing: a `#mode:` line saying how the rest of the lines are laid out.

| `#mode:` | Line layout |
| --- | --- |
| `headers-key-value` | `h1:v1,h2:v2` `\t` `key` `\t` `value` |
| `key-value` | `key` `\t` `value` |
| `value-only` | `value` |

Two conventions inside the lines:

- **`\t` is two literal characters**, a backslash and a `t`, not a real tab. `seed.sh` converts
  them just before handing the line to Kafka. Real tabs in a data file are invisible, and the
  first editor that helpfully converts them to spaces would break the file silently.
- **`%TS-3600%` becomes an ISO-8601 UTC timestamp** 3 600 seconds before the seed runs, and
  `%TS+86400%` one 86 400 seconds after it. This is what stops the sample data reading as a
  museum piece: an order placed "an hour ago" stays an hour ago however long the file sits in the
  repository.
- **`<NULL>`** as a whole field means a genuine null rather than an empty string. Used for the
  tombstones.

To add a topic: add a row to `topics.tsv` and, if it should hold messages, a file under `data/`
named after it with a `#mode:` line at the top. Nothing else needs changing. A topic with no data
file is created and left empty, which is itself a legitimate thing to want on screen.
