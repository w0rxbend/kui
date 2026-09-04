# The message domain

The Message Explorer context: reading the records in a topic, publishing one, copying a range of
them into another topic, emptying a topic, and finding one business event across several topics.

This document describes `services/message/domain` — the vocabulary the rest of M3 is written in.
It is the module rule A1 confines to `libs/kernel` and cats-core, so nothing here knows about
Kafka, HTTP, JSON or a serde implementation. Every one of those arrives through a port.

## The aggregate

There is no long-lived aggregate here, and that is worth stating because it is unusual. A topic's
records are not KUI's to own: they live in Kafka, they change while you are looking at them, and
nothing this service does makes them more or less true. So the domain models **requests** and
**one record's decoded form**, and holds no state between them.

### Requests

| Type | What it is | The invariants its smart constructor enforces |
| --- | --- | --- |
| `BrowseRequest` | one read of a topic, streamed | `limit` is clamped into `[1, maxLimit]`; a partition subset, if present, is non-empty; a live browse names no start position; an empty string filter is no filter |
| `PageRequest` | one page of the table view, newest first, per partition | pages are numbered from one and page 0 is refused, not clamped; the same partition-subset rule |
| `TrackQuery` | a bounded scan across several topics | the window is mandatory, ordered and no wider than `maxWindow`; at least one topic; the match source is explicit; a regular expression is compiled once, here |
| `ProduceRequest` | publish a record, `count` times | `count` is refused outside `[1, maxCount]`; every header has a name; an absent key or value is a null, not an empty string |
| `ResendRequest` | copy a half-open offset window into another topic | the window is non-empty; the destination is not the source partition |
| `PurgeRequest` | empty a topic's partitions | an absent partition set means all of them |

The rule that distinguishes the reads from the writes is deliberate and is asserted in
`MutationsSuite`: **a read clamps a quantity that is out of range, a write refuses it.** A user
who asked for a million records and got a hundred has seen a short page. A user who asked for a
million records to be *published* and silently got a thousand has written a thousand records they
did not mean to write, and there is no undo for that.

### `DecodedRecord`

The only record shape above the adapters, and what every response is mapped from. Its key and
value are `Decoded` — text, plus what kind of text it is, plus which serde produced it — rather
than bare strings, because the browser needs the kind to know whether it can flatten a payload
into a table, and needs the serde name to draw the marker that says "this decode fell back".

Sizes are of the **serialised** bytes, not of the decoded text: they are what an operator uses to
find the record that is filling a disk, and a 40-byte Avro record renders as 400 characters of
JSON.

`decodeErrors` is a list on the record, not an error of the request. That is the milestone's
central decoding rule: **a decode failure is a record annotation, never a stream failure.** A
record KUI cannot decode is still the record the screen was opened to find, so it is shown through
the fallback with the failure attached, and the stream carries on (ADR-035).

The ordering given for `DecodedRecord` is timestamp, then partition, then offset. The second and
third parts are not decoration: records sharing a timestamp are ordinary — a batch written inside
one millisecond — and sorting on the timestamp alone would reorder a partition, which shows a user
a reply above the request that caused it. Offset order within a partition is the only order Kafka
itself guarantees, so it is the one that has to survive a merge across partitions.

## Mutations, and their safety net

Produce, resend and purge are the first things KUI ever changes about a cluster, so ADR-047's
three parts start here rather than in M5:

1. **A marker.** Each of the three requests carries a `MutationKind`. M5's read-only policy and
   M6's permission model both key on that classification, so they arrive as a policy over an
   existing marker rather than as a hunt through an endpoint list.
2. **A per-cluster refusal.** `ClusterProfileSource` answers with a `BrowseCluster` carrying
   `readOnly`. Every mutation checks it **before any Kafka client is touched** — before
   serialisation, before a producer is created, before an offset is read.
3. **An audit record.** Every attempt, successful or failed, writes exactly one record carrying no
   credential. M5 adds the Kafka sink behind the same port; M3 ships the port and a log sink.

Purge is irreversible: `deleteRecords` moves a log's low watermark and the records below it are
gone. A resend is **not atomic**: it is a read and a series of produces, and cancelled or failed
halfway it leaves what it already wrote and reports how far it got. Both sentences are in the
types' own documentation, because a caller who assumes otherwise writes a retry that duplicates.

## The ports

| Port | What it hides | Why the domain states it |
| --- | --- | --- |
| `ClusterProfileSource` | that clusters arrive over HTTP from another process, with a cache in front | the domain has to be able to say "a mutation on a read-only cluster is refused" without knowing where clusters come from |
| `SerdeSource` | `libs/serde`, the Schema Registry, and the resolution order | decoding never fails a browse, which is a domain rule and not a library detail — so `decode` returns a payload and a reason, never an error |
| `FilterSource` | CEL, its compiler and its cache | a compile failure is a different error, with a different code, from an evaluation failure; and an evaluation failure is counted, not fatal |

`BrowseCluster` deliberately carries no connection: no bootstrap servers, no security mechanism,
no credentials. The domain's questions are "does this cluster exist", "what is it called" and "may
I change it". A type that answered those while also carrying a password would put the password in
every log line that ever printed a request.

`FilterVerdict` has three cases and not two. An expression that throws — an absent field, a
division by zero — is neither a match nor a non-match, and counting it as "did not match" turns a
broken filter into a screen that says "no records found", so the user concludes their data is
missing rather than their expression is wrong.

## The invariants inherited from the port contract

`libs/kafka/PORT-INVARIANTS.md` states two rules that survive above the adapter.

**§1 — a partition with no leader is excluded from a browse, not waited for.** A `listOffsets`
call against a leaderless partition blocks for the full request timeout, and one offline broker
would otherwise make every browse of every topic on that cluster take sixty seconds. The excluded
partitions are *named* in the stream's first `phase` event, so a user learns why a partition is
missing instead of wondering. M3 owns this one.

**§2 — the fabricated-dead-group rule.** M3 does not own it and does not implement it: the browse
consumer uses manual assignment with no `group.id` at all
(`research/kafka/admin-capabilities.md` §4), so nothing here describes a consumer group.
`GroupAdmin` is M4's, and the invariants file is amended to say so.

## What is deliberately not here

- **No streaming port.** `MessageBrowsePort` returns a stream of events, and a stream is `fs2`,
  which rule A1 does not allow this module. The port is therefore declared in the application
  layer, stated in the types declared here. This is a real constraint of the layering rather than
  a preference: the alternative is a domain module with a runtime dependency, which is what A1
  exists to prevent.
- **No `FilterId`.** `libs/filter` owns it, and it cannot move to `libs/kernel` with the rest of
  the shared vocabulary because it is computed with `java.security.MessageDigest` and
  `libs/kernel` is cross-compiled to the browser. `FilterRef` therefore carries the id as a
  validated string and the application layer, which may see `libs/filter`, converts it once.
- **No use cases, no DTOs, no adapters.** Those are the layers above and below.
- **No empty ports "for later".** Every trait declared here has a caller inside M3.
