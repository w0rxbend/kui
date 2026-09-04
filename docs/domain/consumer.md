# Consumer Groups

The bounded context served by `kui-consumer-service` (`docs/domain/context-map.md`). It owns who is
consuming what, how far behind they are, and the operations that change where a group will read
next: reset offsets, delete offsets, delete a group. It reads cluster connection details from the
cluster context and nothing else from anyone: its end offsets are its own.

## Status in M4

**Modelled.** `services/consumer/domain` holds the aggregate, the lag arithmetic and the reset
planner. Everything in this document that describes behaviour is executable — the section headings
below name the type or the object the rule lives on, and each rule has a test beside it.

## The vocabulary lives in `libs/kernel`

`GroupState`, `GroupProtocol`, `ResetTarget` and `LagAnomaly` are declared once, in
`kui.kernel.group`, cross-compiled to the JVM and the browser. Six state names cross the process
boundary and have a natural home in four modules — the Kafka adapter, this domain, the HTTP
contract, the browser — and whichever module spells `STABLE` second is the one that drifts. The
domain imports them; it does not restate them.

## The aggregate — one group on one cluster

`ConsumerGroup` is the aggregate root, and its boundary is deliberately narrow: it does not hold the
cluster, the topics' configuration or the broker set. A group that held those could not be refreshed
without refreshing them too, which is how a thirty-second group refresh becomes a cluster-wide
scrape.

| Field | Meaning |
| --- | --- |
| `groupId`, `state`, `protocol`, `isSimple` | Identity and lifecycle. A "simple" group was made with the low-level `assign` API: it has no protocol, still holds committed offsets, and can still be reset. |
| `partitionAssignor` | The assignor the group negotiated (`range`, `cooperative-sticky`, …); empty for a simple group. |
| `members` | `GroupMember` — the member id, the static `group.instance.id` when there is one, the client id, the host, what it holds and where it is being moved to. |
| `coordinator` | `None` is a real state during a coordinator move, not an error. |
| `subscriptions` | `TopicSubscription` per topic, each holding its `PartitionState`s. The shape the screen renders, so nothing reshapes it later. |
| `completeness` | Which parts of the picture were actually obtained. |
| `observedAt` | When this was true. Every screen that renders it also renders this. |

### Completeness — "no members" and "we were not allowed to ask" are different screens

`GroupCompleteness` carries a flag per part of the picture (`membersKnown`,
`committedOffsetsKnown`, `endOffsetsKnown`) plus `excludedPartitions`, a map from partition to the
reason it contributes to no figure. Without this type, a group whose member list KUI may not read
renders exactly like a group nobody is running. One of those is a group to leave alone and the other
is a permission to go and grant.

## Lag — `LagMath`, and the zero that is never substituted

Lag is `Option[Long]` plus a set of `LagAnomaly`, end to end. The four rules:

| Situation | Lag | Anomaly |
| --- | --- | --- |
| The group has never committed on this partition | `None` | `NoCommit` |
| The end offset could not be read (no leader) | `None` | `NoLeader` |
| The committed offset is past the end of the log | `None` | `CommittedBeyondEnd` |
| The committed offset is older than the log start | `end - committed` | `CommittedBeforeStart` |

The third row is a real condition — it happens after a topic is recreated or records are deleted —
and the honest answer is "these numbers do not make sense", not a negative number. The fourth is the
one that looks like an error and is not: the consumer really is that far behind, and it will resume
from the earliest retained record rather than from where it committed, so the lag is computed *and*
flagged.

`LagMath.total` sums the defined lags and nothing else, and reports how many partitions it left out.
A total of nothing is `None`, not zero. The reference product sums with `orElse(0)`
(`ConsumerGroupUtil.java:28-34`), which turns "this consumer has never run" into "this consumer is
perfectly caught up" on the screen an operator sizes a cluster from. That substitution is the single
defect this whole section exists to prevent, and a property test asserts it cannot happen.

## Pace — a rate, not a lag

`LagMath.pace` is the change in a group's total committed offset per second between two consecutive
snapshot passes. It is `None` until two passes exist, `None` when either total is unknown, and
`None` when the partition set changed between them — arithmetic across a changed partition set
subtracts two different quantities and renders as a spike exactly when an operator is watching a
rebalance. A dash for one refresh interval is honest; a spike is not. Commits moving *backwards* —
which is what a reset does — are reported as a negative rate rather than clamped, because that is
the event this number is most useful for noticing.

## When may a group's offsets be changed?

`ConsumerGroup.permitsOffsetChange` is the union of both halves: the state is `EMPTY` or `DEAD`
**and** the member list is empty. Kafbat checks the state; Kouncil checks the member list; they
disagree about a group that reports `EMPTY` while a member is joining, and only the union is safe.
`offsetChangeRefusal` gives the sentence an operator can act on — "stop its consumers" — rather than
a state name alone.

The rule is checked twice: when a reset is planned, and again immediately before it is written. The
broker's own rejection is mapped as a third line of defence, never relied on as the first.

## Existence

Describing a group that does not exist does not fail. `GroupAdmin.describeGroups` normalises the
newer brokers' `GroupIdNotFoundException` into the older brokers' answer — a `DEAD` group with no
members — so that no caller branches on a broker version, and so that an operator following a stale
link gets an empty group page rather than a 404.

The consequence is that a describe cannot answer "does this group exist". Where existence matters —
before any offset operation — the caller confirms it with a listing first, and
`ErrorCode.GroupNotFound` (`KUI-GROUP-NOT-FOUND`) is what it raises when the listing does not
contain it.
