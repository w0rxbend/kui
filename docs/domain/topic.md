# Topic Catalogue

The bounded context served by `kui-topic-service` (`docs/domain/context-map.md`). It owns what
topics exist on a cluster, what shape each one has — its partitions, their leaders, their replicas
and their offsets — and what each one is configured with. It is **read-only** in M2: nothing in
this context creates, changes or deletes anything on a Kafka cluster.

It is downstream of the Cluster Registry context. It holds no cluster list of its own and reads no
`kui.clusters[]` configuration: it learns which clusters exist, and how to connect to them, from
the cluster service's `/internal/v1` profile contract (ADR-036, ADR-046). There is exactly one
resolver of cluster configuration in the product, and it is not this one.

## Status in M2

**Modelled.** This document grows one section per task as the layers land.

- The model and its invariants — below. (TOP-011.)

## Why the model refuses to answer

If you read nothing else here, read this. Four of the fields on a topic row are `Option`, and every
one of them is a place where KUI would rather say nothing than say something plausible and wrong.

A Kafka topic's message count is not a number a broker reports. It is arithmetic: for each
partition, the latest offset minus the earliest, summed. That arithmetic needs every partition's
offsets, and there are ordinary, healthy-looking reasons a cluster will not give you all of them —
a partition with no leader, a topic KUI is not authorized to describe, a broker that has gone away
between two calls.

The tempting behaviour is to sum what you have. It produces a number for every topic and no error
anywhere. It is also the single worst thing this service can do, because a *wrong* count and a
*missing* count are read completely differently by the person looking at the screen: a missing
count starts an investigation, and a wrong one ends it. A topic holding four million records that
displays as forty thousand — because one of its hundred partitions was offline — tells an operator
their retention policy is working when in fact a third of their data is unreadable.

So: **an aggregate over a partial set refuses.** `TopicSummary.messageCount` is `None` if any one
partition's count is missing. `TopicSummary.sizeBytes` is `None` if any one partition's size is
missing. They refuse *independently*, because a cluster that answers `listOffsets` and refuses
`describeLogDirs` can still be given a count, and throwing away a number the operator can have has
no upside.

A refusal is never silent. `TopicSummary.offlinePartitions` is a count and not a flag precisely so
that the screen can say "the count is missing **because** two partitions are offline", and
`TopicSnapshot.incomplete` names the topics a scrape could not read, with a reason, so a list can
say "9 998 of 10 000 topics; 2 could not be read" instead of quietly showing fewer rows.

The underlying rule is recorded in `libs/kafka/PORT-INVARIANTS.md` §1 and is enforced twice: once
at the port, which does not ask a leaderless partition for its offsets at all (asking retries until
a sixty-second timeout and would stall the whole scrape), and once here, at the aggregate. Two
assertions at two levels is how a rule survives a refactor of either one.

## The values

| Type | What it is |
| --- | --- |
| `TopicSummary` | one row of the topic list: the name, whether it is internal, the partition count, the replication factor, the out-of-sync replica count, the offline partition count, the message count and the size |
| `PartitionView` | one partition on the detail page: its id, its leader, its replicas, its earliest and latest offsets and its size |
| `Replica` | one broker holding a copy, with the two flags the partition table renders as chips: `isLeader` and `isInSync` |
| `TopicDetail` | a `TopicSummary` plus the `PartitionView`s it was derived from, the cleanup policy and the segment count |
| `TopicConfigEntry` / `TopicConfig` | one configuration key, and the whole set sorted by name |
| `TopicSnapshot` | every row of one cluster at one instant, plus the search index over their names and the `scrapedAt` every screen shows |
| `TopicSortField` | the six fields a list may be sorted by |

### `PartitionView` — the states that are refused

`PartitionView.from` is a smart constructor returning `Either[ValidationError, PartitionView]`, and
each rule it enforces is a state that renders as a plausible-looking lie rather than as an obvious
failure. Every one of them has been shipped by at least one reference product.

| Refused | Why it matters |
| --- | --- |
| an in-sync replica that is not a replica | renders as "3 of 2 in sync". Kafka reports the replica set and the in-sync set as two separate lists and nothing stops them disagreeing; KUI merges them into one list of `Replica` at the boundary, after which the disagreement cannot be expressed |
| a leader that is not one of the replicas | the detail page would show a leader chip on no row |
| a duplicated replica | inflates the replication factor |
| offsets on a leaderless partition | KUI never asks a leaderless partition for its offsets, so a value here means something invented one |
| an earliest offset after the latest | a negative message count |
| a negative offset or size | Kafka's sentinel `-1` leaking through as data. This is also why `leader` is `Option[BrokerId]` and never `-1`: `-1` sorts before every real broker id and sums into every total as a number |

An **empty** partition is not a missing one. When both offsets are present and equal the count is
`Some(0)`, and confusing that with `None` is how a screen tells an operator a topic is empty when
KUI could not read it.

### `TopicConfigEntry` — two rules about not inventing information

`defaultValue` is **derived**, from the synonym whose source is `Default`, and not stored. Kafka
does not report a default beside a value; it reports the whole chain of values the key would have
taken, and the default is a link in that chain. KUI carrying its own table of Kafka defaults would
be a table that is wrong on the next broker release, and the broker already knows the answer. No
synonyms means no default — `None`, never the value itself, which would make every key look
un-overridden.

A **sensitive** entry has no value, ever: Kafka returns `null` for one and KUI does not invent a
replacement. Its `defaultValue` is `None` too. The default of a sensitive key is not itself a
secret, but showing a default beside a masked value invites the reader to conclude the value equals
it. For the same reason `isOverridden` is always false for a sensitive entry: "overridden" is not
knowable without the value, and a bolded row would be a guess presented as a fact.

### `TopicSnapshot` — why the search index is part of the value

A search index built per request over ten thousand names would be rebuilt for every keystroke of
every user. Built inside the snapshot, it is built once per scrape and thrown away with it, which
is ADR-038's "in memory first" position.

Making the index a field rather than something the list pipeline constructs also closes the way
that design goes wrong. An index built from a different list from the rows makes search silently
return topics the list cannot show, or hide topics it can — a defect with no error anywhere, which
is exactly the shape of failure the M1 review named as this project's most expensive. The
constructor is private and `TopicSnapshot.of` builds the rows and the index from one input, so they
cannot drift apart. `TopicSnapshotSuite.theIndexContainsExactlyTheTopicNames` asserts it anyway.

`topics` is a `Vector` and not a `List` because the list pipeline slices it by index for every page
of every request, and a `List` makes that a walk from the head each time.

### What the domain deliberately does *not* decide

**Whether a topic is internal.** `TopicSummary.isInternal` is whatever the value was constructed
with. The rule that decides it — the union of Kafka's own `isInternal` flag and a configured name
prefix (DEVPLAN §10 D3) — belongs to the application layer and lives in exactly one place, so that
there is exactly one place it can be got wrong. `TopicSummarySuite.internalIsWhateverItWasConstructedWith`
asserts that the domain applies no prefix rule of its own.

**Where a value came from.** `ConfigSource` is declared in this context and not shared with the
cluster service's identically-named enum. Rule A11 forbids one service from seeing another's domain
at all, and a shared copy in `libs/kernel` would be a wire vocabulary the two services could not
evolve apart. The duplication is deliberate, and the mitigation — an exhaustive match in the
adapter, checked by the compiler — is the same one `KafkaToDomain` records one service over.
