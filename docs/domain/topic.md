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
- The ports the use cases are stated in terms of — below. (TOP-012.)
- The list pipeline and its ordering rule — below. (TOP-014.)
- The detail page's live-versus-snapshot rule — below. (TOP-015.)
- The configuration view and its two empty states — below. (TOP-016.)
- The internal-topic rule and the per-cluster capability report — below. (TOP-013, partly: the
  snapshot registry itself is blocked on `libs/cache`'s `SnapshotRegistry`.)

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

## The ports

The topic service's use cases are written against three narrow interfaces, so that they can be
tested without a broker and without an HTTP client, and so that an adapter can be replaced without
touching a rule. All three live in the domain and none of them names a runtime type: no `IO`, no
`fs2.Stream`, no Kafka class.

| Port | What it provides |
| --- | --- |
| `TopicAdmin[F]` | `scrape`, `detail` and `config` against one cluster, in the domain's own vocabulary |
| `ClusterProfiles[F]` | which clusters exist, and notification when that set changes |
| `ClockPort[F]` | `now` |

### `TopicAdmin` — two traits with one name, on purpose

`libs/kafka` has a `TopicAdmin` too, and they are deliberately different traits. That one speaks
Kafka's vocabulary — `TopicListing`, `TopicPartitionInfo`, `BatchResult`, `SkipReason`. This one
speaks the topic domain's — `TopicSummary`, `TopicDetail`, `TopicError`.

One trait would be simpler and is not available: rule A5 forbids `libs/kafka` from depending on a
service, and rule A1 forbids this module from depending on `libs/kafka`, so neither of them can
name the other's types. The bridge is one file in `infrastructure`, `KafkaToTopicDomain`, and every
pair it bridges is joined by an exhaustive match that the compiler checks. The duplication is the
cost; a compiler-checked translation of every shape a real cluster can produce is what it buys.

`scrape` is one call and not "list the names, then describe each one" because the chunking, the
parallelism, the order the admin calls must run in and the offset arithmetic are all the adapter's
business (`research/kafka/admin-capabilities.md` DC-D4). A port that specified them would have
specified a Kafka client.

`scrape` always includes internal topics — see "what the domain deliberately does not decide".

`config` answers `TopicConfigView`, which is `Entries` or `NotPermitted`, and never
`TopicError.Forbidden`. A 403 there would take the whole topic page down, and the partitions the
user is entitled to see would vanish along with the tab they are not.

### `ClusterProfiles` — what it deliberately cannot tell you

It carries no connection material: no bootstrap addresses, no security mechanism, no credentials.
The adapter behind it holds all of that, because it is what builds the Kafka clients. A use case
that could see a password is a use case that could log one, and the cheapest way to guarantee it
never does is for the type it is written against not to have the field.

`onChange` takes a callback returning its own deregistration, rather than answering with an
`fs2.Stream`, per ADR-041 Amendment 3: a domain that imports a concrete runtime type can no longer
be read, tested or moved without that runtime. The handler is given the whole new set rather than a
delta, because its consumer's job — retaining exactly the snapshots whose clusters still exist — is
stated over a set, and reconstructing a set from deltas is how a removal gets missed.

### Every failure is a value

`TopicError` has four cases because the caller renders four different things: "no such topic", "you
may not see this", a greyed stale screen with a retry, and a red one. `Forbidden` is never
`Unreachable`: per ADR-039 §6 an authorization failure is an `ApplicationError`, it is not a sign
that anything is broken, and it must not dim a capability.

The ports are **total**. There is no throwing path, and `PortContractSuite` asserts it: an adapter
that let a `TimeoutException` escape would make every signature in the application layer a lie.

### `PortContractSuite`

The behaviours every `TopicAdmin` implementation must have, written in the module that declares the
port and *before* either implementation, because a contract written after the second implementation
is a description of the first one. Two things run it: the fake the application layer's suites are
built on, and the live adapter in `services/topic/infrastructure` against a real broker in a
container. A fake that drifts from the adapter fails there, instead of quietly making every
use-case test agree with a bug.

A subclass supplies the implementation and says what its cluster contains. Optional hooks — an
unreadable-configuration topic, a leaderless partition, a partly readable cluster — return `None`
for a fixture that cannot produce that state, and the case is skipped rather than passing
vacuously.

## The list pipeline

`ListTopics` is one pure function from a snapshot plus a query to one page of rows. It performs no
I/O. The order it runs in is the contract:

1. `visible` — RBAC (M6). A user must not be able to learn that a topic exists from a page count.
2. internal — the `showInternal` toggle.
3. `q` / `mode` — the name search.
4. `sort`.
5. `Page.of` — which counts exactly what it is handed.

Every filter runs before the total is taken, and that is the whole difference from the
implementation this product is modelled on. Its page count is computed from the list *before* the
internal-topic filter (`research/kafbat/api-analysis.md` §3.3, citing `TopicsController.java:213-220`),
so it overstates the number of pages whenever internal topics are hidden — which is the default.
The screen says "page 3 of 40", the user clicks through to page 34, and there is nothing there and
no error anywhere to explain it.

Getting the order right once is not the hard part. Keeping it right is, because any later edit that
moves a filter below the count reintroduces the defect invisibly. `ListTopicsSuite` states the
relationship as a property over generated snapshots and generated queries, computing the expected
total through a separate code path, and `hidingInternalTopicsChangesTheTotal` pins the exact
reference defect as a named regression test.

Two consequences of putting the search *after* the filters rather than before. Both orders return
the same rows; only this one returns a count that matches them, because the count is taken once, at
the end. And the index — which belongs to the snapshot, not to the request — proposes names while
the filtered set disposes of them, so a topic the caller may not see cannot come back through the
search box.

`TopicListQuery.sort` is an `Option`, because "no sort given" is not the same request as "sorted by
name ascending": in `fts` mode the first comes back in relevance order and the second
alphabetically. Only the edge can see whether the parameter was in the query string, so the
distinction has to survive as far as the pipeline. An explicit sort is never silently ignored.

Sorting has two rules that apply to all six fields, which is why `TopicOrdering` is a named object
rather than six inline lambdas. **Missing values sort last in both directions** — a topic whose
count could not be computed must not float to the top of a descending sort, which is exactly what a
naive reverse of the ascending order does. **The tiebreak is always the name, ascending** — without
it two equal rows swap places between two identical requests, and the same topic appears on two
pages of a "stable" list while another appears on none.

An out-of-range page is an empty page with the correct total, not a 404: a bookmark to page 9 of a
list that has since shrunk should say "nothing here, 3 pages".

## The detail page: live, with the snapshot as the fallback

The list is served from a background snapshot. The detail page is not: it is the one place in M2
where a request costs an admin call. A list is a thousand rows a user scans; a detail page is one
topic a user is looking at *because something is wrong with it*, and showing them a minute-old
partition assignment during an incident is the wrong trade. The cost is bounded by the number of
humans looking at topic pages, not by the number of topics.

When the live read fails, the page is served from the last snapshot rather than turning red
(ADR-032), and it says which of the two happened. That is what `Fresh` is: `Live`, or
`FromSnapshot` with the instant the data was seen and the reason the live read failed. Returning a
bare value and letting the HTTP layer infer freshness from a timestamp would make that layer guess,
and a guess there ends with a page either claiming stale data is current or refusing to show data it
has.

Two failures deliberately do **not** fall back, because they are answers rather than failures: a
topic that does not exist, and a cluster that does not exist. A topic deleted since the last scrape
must not be resurrected by its own fallback — a page showing partitions for something that is gone
is worse than a 404. The same applies when the live read fails and the snapshot is real but does not
contain the topic: KUI has evidence the topic is absent, so it says so.

The fallback page carries the summary and an empty partition table. The list snapshot holds counts,
not partition assignments — ten thousand topics of fifty partitions each is half a million objects
held in memory to render six columns — so the screen puts its stale badge over exactly what KUI
knows rather than inventing rows.

## The configuration view

`TopicConfigView` is `Entries` or `NotPermitted`, and never a bare list, because an empty list is a
valid answer that means one of two completely different things: the broker reports no configuration
for this topic, or the caller may see the topic but not its configuration. An empty table reads as
the first when it means the second.

"Not permitted" is a case of the result and not a `TopicError.Forbidden`. An error would give the
whole topic page a 403, and the partitions the user is perfectly entitled to see would disappear
along with the tab they are not.

Entries are sorted by name, always, in the use case rather than on the screen. The tab is a
reference list somebody scans alphabetically, and a broker-dependent order would make two clusters
look different for no reason at all.

## Internal topics

Two definitions disagree and KUI takes the **union**: Kafka's own `isInternal` flag, **or** a name
starting with the configured prefix (`kui.topics.internalPrefix`, default `__`).

KUI's own metadata topics settle it. `__kui_config` and `__kui_files` are ordinary topics as far as
Kafka is concerned — its flag is false for both — and noise as far as an operator browsing their own
topics is concerned. The flag alone would list them; the prefix alone would miss whatever a future
Kafka marks internal without using the prefix. Either one on its own is a decision to be wrong in
one of those two ways.

An empty prefix disables the name half rather than matching everything. Every string starts with the
empty string, so a naive `startsWith` would make every topic internal the moment an operator blanked
the setting — an empty topic list, with no error anywhere.

The rule lives in `InternalTopics.isInternal` and nowhere else, and the port does not apply it: a
scrape always lists internal topics, because filtering them there would leave `showInternal=true`
with nothing to show.

## Capability, per cluster

`TopicCapabilityUseCase` reports one entry per configured cluster: `Available`, `Degraded` or
`Unavailable`. Per cluster, and not per service — a Kafka cluster the topic service cannot reach is
a section of a screen that cannot render, not a feature that should vanish from the sidebar. Dimming
the whole Topics entry because one of four clusters is down would hide three working screens
(DEVPLAN §10 D11). Whether `kui-topic-service` itself is answering is the gateway's readiness
poller's business, not this use case's.

`Degraded.since` is passed through from `SnapshotStatus.Offline` rather than stamped when the report
is taken, because it is sticky. An operator asks "how long has this been broken", and a field that
moved with every retry would answer "a second ago" during an outage that started at breakfast.
