# Port invariants Kafka forces on us, recorded before the ports exist

One behaviour is still recorded here. It is established by `research/kafka/admin-capabilities.md`, is
**required** of a port KUI did not build in M1, and would otherwise be rediscovered as a production bug by
whoever writes that port. It is stated with its evidence, its rule, and the consequence of getting it wrong.

The second invariant this file used to carry — *describing a consumer group that does not exist returns a
fabricated dead group rather than an error* — has moved, as the closing section instructs, into the scaladoc
of `GroupAdmin.describeGroups` in `libs/kafka/src/kui/kafka/admin/GroupAdmin.scala` (M4, task GRP-002). It is
no longer homeless, so it is no longer here.

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

**This rule now has an implementation.** M4's `OffsetLookup`
(`libs/kafka/src/kui/kafka/admin/OffsetLookup.scala`, task GRP-006) filters leaderless partitions before it
sends a `listOffsets`, returns them as `SkipReason.NoLeader`, and makes no Kafka call at all when every
partition is offline. The section stays here because `TopicAdmin.listOffsets` is its other owner and does not
exist yet: **that method must call `OffsetLookup` rather than write the filter a second time.** Two
implementations of a sixty-second-timeout guard is one more than can be kept correct.

---

## Why these are here and not in a trait

The obvious place for this rule is a doc comment on `TopicAdmin.listOffsets`. That trait did not exist when
this file was written, and could not be created yet.

DEVPLAN §3 forbids declaring empty `TopicAdmin`, `GroupAdmin`, `SecurityAdmin` or
`MessageBrowsePort` traits in M1, and risk R-11 says why: an empty trait is an invitation to fill
it, and a port designed before its first caller exists is designed wrong. M1 implements
`ClusterAdmin` and nothing else.

But the *knowledge* must not be lost with the trait. This one was expensive to learn — it is a sixty-second
timeout that looks like a network problem — and it would otherwise be rediscovered as a bug report. So it
lives in a file next to the module that will eventually implement it, and each milestone's grooming step
picks this file up through the DEVPLAN's reference to it.

**If you are the person creating `TopicAdmin`:** move the remaining section into a doc comment on
`listOffsets`, delete it from here, and delete this file — it will hold nothing else.
