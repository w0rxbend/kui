# Port invariants Kafka forces on us, recorded before the ports exist

Two behaviours are established by `research/kafka/admin-capabilities.md`, are **required** of ports
KUI does not build in M1, and would otherwise be rediscovered as production bugs by whoever writes
those ports in M2 and M3. Each is stated here with its evidence, its rule, and the consequence of
getting it wrong.

---

## 1. Leaderless partitions are filtered before `listOffsets`, at the port

**Owner: M2, `TopicAdmin.listOffsets`.**

**The behaviour.** If any partition named in a `listOffsets` request has no leader, the AdminClient
does not fail the call. It retries metadata, quietly, until `default.api.timeout.ms` expires — sixty
seconds with KUI's defaults — and only then reports a timeout.

**Why that is worse than an error.** A single offline partition turns a call that normally takes
milliseconds into a one-minute call that eventually fails with a message naming nothing useful. The
operator sees "the topic list timed out" on a cluster that is 99% healthy, with no indication that
one partition is the reason, and the timeout is charged to the whole request rather than to the
partition that caused it. Worse, the failure is intermittent in exactly the way that makes it hard
to reproduce: it appears only while a broker is down, which is also when the operator most needs the
screen.

**The evidence.** `research/kafka/admin-capabilities.md` §2, "Topic offsets / message counts": the
reference product filters no-leader partitions before it asks, and skips a whole topic when any of
its partitions is leaderless, because a per-topic message count computed from a partial set of
partitions would be wrong rather than merely incomplete.

**KUI's rule.** The **port** filters, not the caller. Every partition removed by the filter appears
in the returned `BatchResult` as `SkipReason.NoLeader`, so the caller can render "offline" for that
partition instead of a number, and so that a per-topic aggregate can refuse to compute rather than
report a wrong total.

That is why `SkipReason.NoLeader` exists in `SkipReason.scala` (KAFKA-005) before any code produces
it. It is not dead code; it is the vocabulary this invariant needs, put in place by the milestone
that could still afford to think about it.

---

## 2. Describing a consumer group that does not exist returns a fabricated dead group

**Owner: M3, `GroupAdmin.describeGroups`.**

**The behaviour.** Brokers disagree about what "that group does not exist" means. Older brokers
answer `describeConsumerGroups` for an unknown group id with a perfectly ordinary description whose
state is `DEAD` and whose member list is empty. Newer brokers throw `GroupIdNotFoundException`.

**Why that matters.** A port that passes the difference through makes every caller branch on broker
version — and ADR-030 is explicit that KUI gates on *capabilities*, never on version numbers. Two
callers written six months apart will branch differently, and the one that forgot will show a stack
trace on a cluster that is behaving correctly.

**The evidence.** `research/kafka/admin-capabilities.md` §3, "Describe groups".

**KUI's rule.** The port normalises to the *older* behaviour: an unknown group is a group
description in state `Dead`, with no members and no assignment. `GroupIdNotFoundException` is caught
inside the adapter and turned into that value.

Normalising to the older behaviour rather than the newer one is deliberate. "Dead with no members"
is a true statement about a group that does not exist, and it is what a screen wants to render — an
empty group page rather than a 404 — whereas an error forces every caller to decide what to do about
it. Where existence genuinely matters, the caller confirms it with a listing first, which is what
the reference product does before an offset reset. Note that `KafkaErrorMapper` maps
`GroupIdNotFoundException` to `ApplicationError.InvalidState` today (KAFKA-005's deviation, because
`ErrorCode` has no group-not-found code yet); once M2 adds the code, this invariant is the reason
the adapter must catch the exception *before* the mapper ever sees it.

---

## Why these are here and not in a trait

The obvious place for these two rules is a doc comment on `TopicAdmin.listOffsets` and
`GroupAdmin.describeGroups`. Neither trait exists, and neither may be created yet.

DEVPLAN §3 forbids declaring empty `TopicAdmin`, `GroupAdmin`, `SecurityAdmin` or
`MessageBrowsePort` traits in M1, and risk R-11 says why: an empty trait is an invitation to fill
it, and a port designed before its first caller exists is designed wrong. M1 implements
`ClusterAdmin` and nothing else.

But the *knowledge* must not be lost with the trait. Both of these were expensive to learn — one of
them is a sixty-second timeout that looks like a network problem — and both would otherwise be
rediscovered as bug reports. So they live in a file next to the module that will eventually
implement them, and the M2 and M3 grooming steps pick this file up through the DEVPLAN's reference
to it.

**If you are the person creating `TopicAdmin` or `GroupAdmin`:** move the relevant section into a
doc comment on the method, delete it from here, and leave this file with only what is still
homeless.
