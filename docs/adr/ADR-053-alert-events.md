# ADR-053 — The alerts service: which rules ship, what an event is, and who may clear a bell

- Status: Accepted
- Date: 2026-09-07

## Context

M8 adds `services/alerts`, the ninth service. Two pieces of it shipped in wave 1 and are unchanged
here: `Resource.Alerts` with a non-altering `AlertsView` and an altering `AlertsAcknowledge`
(`libs/security-core/src/kui/security/rbac/Vocabulary.scala`), and `kui.alerts` with five thresholds,
every one bounded by the loader (`libs/config/src/kui/config/AlertsConfig.scala`). That file's own
scaladoc states the boundary this milestone had to keep — *"The rule — which fact is read, what
severity is opened, what the event says — belongs to the alerts service and is deliberately not
modelled here: a rule expressed in YAML is a small programming language"* — and nothing below widens
`kui.alerts`.

What did not exist anywhere was an alert, a severity, an acknowledgement or an event record. This
document is the six decisions that had to be taken before any of them could be written, and it is
the document `frontend/packages/feature-alerts` is written against: the event shape is read here
rather than off the endpoint, so that the browser and the server are not two guesses at one wire.

## Decision

### 1. `isAlter` does not split, and an acknowledgement is refused on a read-only cluster

`Action.isAlter` answers two questions with one field: whether an operation belongs in an
`ALTER_ONLY` audit trail, and whether it is refused on a cluster the operator has declared read-only
(`Vocabulary.scala`, `Action.AlertsAcknowledge`). `AlertsAcknowledge` is marked altering. The
consequence is that an operator watching a read-only cluster **may not clear their own bell**, even
though the acknowledgement writes to KUI's own store and never to Kafka.

The milestone was told to take this decision rather than inherit it. It is kept, for three reasons:

- **The write is real, even though the cluster is not touched.** An acknowledgement is a claim that
  somebody has looked at an incident, and it is the claim an incident review reads. A silenced alert
  nobody is named for is exactly the record a review cannot reconstruct, which is why the action was
  made altering in the first place.
- **Splitting the field costs more than it buys, and it costs it everywhere.** `isAlter` is read by
  the RBAC closure, by `RbacGuard`'s read-only branch, by every service's endpoint classification
  suite and by `RbacLawsSuite`. A second field — say `touchesCluster` — would have to be given a
  value for all twenty-nine actions by somebody who is thinking about alerts, and twenty-eight of
  those values would be guesses nothing tests.
- **The stricter reading is the recoverable one.** An operator who cannot acknowledge on a read-only
  cluster can still see the feed, and the deployment can change one flag. An operator who *could*
  acknowledge on a cluster they had declared untouchable would have discovered a hole in a promise
  the flag exists to make.

It is not a comment. `RbacLawsSuite` asserts the vocabulary half; `AcknowledgementSuite` asserts that
the guard refuses before the store is written and records the refusal; `AlertsRoutesSuite` asserts
that the permission guard refuses the same call one layer out. **The revisit condition is stated
rather than left open**: if M9 or M10 finds an operator who has to acknowledge on a read-only
cluster, the change is to split `isAlter`, and the place to start is the three call sites above.

### 2. Four rules ship, and three candidates deliberately do not

A rules engine that half exists is indistinguishable from one that is broken, so the list is closed
and named in `kui.alerts.domain.AlertRule`:

| Rule | Fact | Severity | Category |
| --- | --- | --- | --- |
| `offline-partitions` | partitions with no leader, from one `describeTopics` sweep | critical | partition |
| `under-replicated-partitions` | partitions with fewer in-sync replicas than replicas, same sweep | warning | replication |
| `stuck-rebalance` | a consumer group in `PREPARING_REBALANCE` or `COMPLETING_REBALANCE` past `rebalanceDuration` | warning | rebalance |
| `disk-usage` | a log directory past `diskUsedWarningPercent` or `diskUsedCriticalPercent` | warning / critical | storage |

Every one reads a fact this product already measures through an `AdminClient` call it already makes
somewhere else. What does **not** ship, and why:

- **A failed connector task.** It needs `services/connect`, which is M9's. There is no Connect client
  anywhere in this repository.
- **A schema registration.** The Confluent registry publishes no event feed, so KUI would have to
  poll the subject list — and a poll cannot tell a registration from a restart of the poller. A rule
  that fired on KUI's own memory loss is worse than no rule.
- **A broker that left the cluster.** The Brokers screen already draws the topology, and an alert
  that repeats a tile is noise. It is a candidate for M10 if operators ask for it.

### 3. Two severities, four categories, and the four dots in the design

`SCREENS-V4.md` §3.8 draws four severity dots — `primary`, `success`, `warning`, `danger`. Two of
them are not severities:

- the **success** dot is a **resolved** row (`inventory.stock.events p7 back in sync` in `M01`). A
  resolved event is drawn in the success tone whatever it opened at, so resolution chooses that tone
  and severity never does. `AlertEvent.tone` is where that is decided, once, so the bell, the card
  and the notifications panel cannot each decide it differently.
- the **primary** dot is an informational row, and **no rule above opens one**. A case nothing can
  open is a case a screen has to guess a meaning for. `AlertSeverity` therefore has exactly two
  cases, `warning` and `critical`, and M9's connector rule is the first candidate to add a third —
  with its first caller.

§3.9 is a correction and this service is built around it: the notifications panel draws two
**warning** items with different glyphs, a rebalance arrow and a disk, and the shipped component
could not, because it picked the glyph from the severity. **Severity chooses the tone; category
chooses the glyph; they are two fields.** Both the raw field and the derived one travel on the wire
(`severity`/`tone`, `category`/`glyph`) so that the browser derives neither.

### 4. What an event may be opened from, and what it may never be opened from

An event is opened from a number the product **measured**. Two consequences, and each has a case:

- **A fact that could not be read opens nothing, closes nothing, and says so.** A rule whose admin
  call was refused reports `NotEvaluated` with the failure attached; the feed renders that as an
  `unavailable` section carrying the reason. It is never `ok` with a zero. "No offline partitions"
  and "KUI could not count the partitions" are different sentences, and an alerts screen that
  confused them would report a cluster it cannot see as well. **Closing an event because KUI stopped
  being able to check it is the single most dangerous thing an alerting system can do**, and
  `AlertRulesSuite` is where that is held.
- **A log directory whose broker reported no capacity has no percentage.** Brokers before 3.3 publish
  neither `totalBytes` nor `usableBytes`, and the replica bytes alone cannot say whether they are
  most of a disk or a rounding error on one. Such a directory is skipped and **counted**, so the row
  can say "of the four directories whose capacity this cluster reports" instead of implying it looked
  at all of them. Filling that gap with anything is the fabrication house rule 7 forbids.

An **incomplete** topic sweep is treated the same way. `describeTopics` over ten thousand topics is
chunked and a chunk can fail on its own; a sweep that read four hundred of four hundred and eighteen
topics and found no offline partition has established nothing about the other eighteen. Both counting
rules therefore refuse to run on it.

One age on the screen is weaker than it looks, and the event says so in its own words. Kafka
publishes **no** rebalance start time — a group's state is `PREPARING_REBALANCE` and there is no
"since" — so the stuck-rebalance rule measures from KUI's own first sighting, and the detail line
reads *"at least 6 minutes since KUI first saw it rebalancing · threshold 5 minutes"*. A group KUI
has only just noticed has an elapsed of zero and cannot fire, which is the correct answer on the pass
after a restart.

### 5. Acknowledgement is audited, and it writes a second record type rather than a thirteenth `MutationKind`

ADR-047 §3 requires one `MutationRecord` per mutation, and the record's `kind` is a
`kui.security.audit.MutationKind` — a sealed enum in `libs/security-core` with twelve cases and none
for an acknowledgement. Wave 6's partition gives `libs/security-core` to **no packet**, so W6-01
could not add the thirteenth. ADR-051 §5 already settled what a service does in that position: it
does not invent a second vocabulary locally, and it does not reuse the nearest case, because either
produces an audit trail whose answer to "what changed today" depends on which service did it.

What ADR-051 then did was ship one **unaudited** mutation. That is not available here: this
milestone's brief is that an acknowledgement is a write and it is audited, and an unaudited
acknowledgement is precisely the record an incident review needs.

So this service takes `AuthenticationRecord`'s answer, which is the precedent in the same package for
the same shape — a second record *type* that shares the vocabulary rather than forking it:

- `kui.alerts.application.AcknowledgementRecord` carries the at, the principal, the cluster, the
  event id, the outcome and a detail map;
- its `outcome` is `MutationOutcome`, so "how it ended" is one enum across the whole trail;
- its principal is rendered through `AuditPrincipal`, so "who" has one spelling;
- `LoggingAcknowledgementSink` writes `LoggingAuditSink.Field`'s **own** constants, referred to
  rather than retyped, so a query over the trail reads one set of keys.

`audit.before` and `audit.after` are absent, and that absence is a fact: those two fields hold a
scalar the operation moved, and an acknowledgement moves none — the event was open and now it is
closed, which `audit.operation` and `audit.outcome` already say between them.

**The one line that removes all of this** is

```scala
case AcknowledgeAlert extends MutationKind("alerts.event.acknowledge")
```

in `libs/security-core/src/kui/security/audit/AuditSink.scala`, after `SetSubjectCompatibility`. It
is asserted rather than described: `MutationKindGapSuite` fails the day `MutationKind` grows a case
whose operation starts with `alerts.`, which is the day `AcknowledgementRecord`,
`AcknowledgementSink` and `LoggingAcknowledgementSink` should be deleted and `MutationGuard` given an
`AuditSink`.

**A cancelled acknowledgement is `MutationOutcome.Unknown`**, following `services/consumer`'s
`MutationGuard` and not `services/topic`'s or `services/message`'s. `AuditSink.scala`'s own words for
why are about a Kafka write — *"Kafka gives no guarantee that it was not applied, so a record
claiming either would be a lie"* — and the argument survives the move to KUI's own store intact: a
cancellation lands between the store's compare and its swap, and a record saying `Failed` would tell
a review that the bell was still ringing when it may not have been.

### 6. One write, and the read marker is a parameter of the read

The service publishes exactly two JSON endpoints and one stream. The write is the acknowledgement.
There is **no** second write for the per-principal read marker, and that is a decision with a stated
cost.

`GET …/alerts/events?markRead=true` moves the caller's marker to now, **after** `unreadCount` has
been computed, so a caller that asks to be marked up to date still learns how many it had not seen.
The default is `false`, so the card polling the feed on a dashboard does not clear somebody's bell —
which would mean the bell never lit up on any screen that also draws the card, which is every screen.

The alternative was a second endpoint. It was rejected because it would need a permission, and the
vocabulary has none: `libs/security-core` declares `AlertsView` and `AlertsAcknowledge` and nothing
else, and reusing `AlertsAcknowledge` for "I have looked at the list" would mean a read-only cluster's
operator could not clear their own unread mark either — a much worse consequence of §1 than the one
§1 accepts. Widening the vocabulary is a change to a file this wave's partition gives to nobody.

Because this is a parameter rather than an endpoint, dropping it between the route and the store is
invisible in the response: what `markRead` changes is the *next* read, so every assertion about the
document that comes back still holds. The claim is therefore made against what the store was handed —
`AlertsRoutesSuite`'s *"a markRead=true query reaches the store rather than being decoded and thrown
away"*, which reads the argument off the fixture on the far side of the route.

The cost, stated plainly: **a GET with a side effect is unusual, and it carries no CSRF header.** The
side effect is on KUI's own per-principal bookkeeping and never on a cluster; a forged request
achieves nothing but clearing the caller's own unread dot; and no information is disclosed that the
same GET without the parameter would not have disclosed. It is recorded here so that the next reader
finds a decision rather than an oversight.

### 7. The feed is `Section`-wrapped per rule as well as per document

The response is `{"events": Section<AlertFeed>}` — one top-level section, like every other read in
KUI — and inside it, `rules` carries **one `Section` per rule**.

The reason is that the four rules read three different admin calls that fail independently. A cluster
whose `describeLogDirs` is refused by an ACL still has readable partition counts, so a document
wrapped only at the top would lose all four rules to one refusal — and the feed is the screen an
operator is looking at precisely when calls are being refused. Wrapping per rule makes **one dead
rule cost one row**. It is the same argument `MetricsEndpoints` makes for five endpoints instead of
one document, applied one level in.

It is *not* five endpoints, and that is the difference from the metrics service. A JMX exporter's
whitelist decides which families exist, so a deployment can genuinely have latency and not
throughput; the alerts rules all read facts every Kafka cluster publishes, so no configuration
produces three of the four. What varies is whether a call answered, which is a section inside the
document rather than four endpoints outside it — and the card, the bell and the notifications panel
then draw from one request, which is what stops them disagreeing.

Every rule has a row on every response, in `AlertRule.All`'s order, whether or not it has anything to
say. A rule simply absent from the list would be indistinguishable on the screen from a rule that
found nothing, and telling those apart is the whole reason the rows exist. A cluster the rules have
never run for carries `unavailable` with `STARTING` — *"KUI has not finished reading this cluster
yet"* — and not `ok` with zero.

### 8. The store keeps seven days, five hundred events, and loses everything on a restart

The store exists because a percentage of "1h ago" cannot come from a `SnapshotCell`: every other read
in KUI is a snapshot of a cluster as it is now, and an alert has a beginning.

- **Retention** is the operator's: `kui.alerts.retention`, seven days by default, refused below an
  hour and above ninety days by the loader. An event is kept for that long **after it opened**,
  resolved or not — not after it resolved, because an incident review reads a week of events and a
  store that kept a resolved one for a further seven days would answer a different question from the
  one the operator configured.
- **A ceiling of five hundred events per cluster** sits under it, because retention is a promise
  about *time* and a cluster that opened and cleared a thousand events an hour would hold a million
  inside a week. It is not configurable: an operator raising it would be choosing how much of this
  process's heap the feed may take, with no screen telling them what they had chosen.
- **The read markers** are a `libs/cache` `BoundedCache` of two thousand entries with **no** TTL. A
  marker that expired would relight a bell somebody cleared last week.

**It is not durable.** A restart loses the open events and the read markers, and the next pass
re-opens whatever is still true with a fresh `openedAt` — so an age is, at worst, an age since KUI
last started. That is stated here rather than left to be discovered. Making it durable means a Kafka
topic and a replay, which is `libs/config`'s metadata store and a milestone of its own.

## Consequences

<!-- checked: openapi-totals -- verified by ./scripts/feature-matrix-check.sh -- claims: openapi-document, openapi-totals, residue -->
- `services/alerts/api/openapi.json` is **6 paths, 6 operations and 12 component schemas** — three
  alerts paths carrying three operations, plus the three health probes every KUI service serves,
  one operation per path — generated from the endpoint values and committed. The committed merged
  service and browser documents contain all three public alerts operations, and
  `docs/api/openapi.json` is **65 paths, 76 operations and 160 component schemas**. That figure
  moves every time a service is added, so it is quoted with the command that recounts it:
  `jq '[(.paths|length), ([.paths[]|keys[]]|length), (.components.schemas|length)]'
  docs/api/openapi.json`, and the same command over the service's own document answers the first
  three. `./mill services.gateway.api.openApiCheck` keeps both generated views aligned with the
  endpoint values. **Both sentences sit inside a marker because the second one was wrong for a
  whole wave** — it published the totals the tenth service left behind, and nothing read it.
<!-- /checked -->
- `ServiceContracts.byService` gains a ninth service, `AllInOneWiring` gains an entry and the compose
  stack gains a container — and there is a **fourth** edge, which belonged to none of the three and is
  the reason the service was unroutable for a wave: `alerts.contract.jvm` in `services.gateway.api`'s
  `moduleDeps` in `build.mill`. A service is reachable only when all four exist, and the first three
  are each visible in a suite while the fourth is visible only in a compile that nobody was running.
- **The change stream needs a hand-written gateway relay, and the endpoint is placed so that one can
  be written.** `ContractRouting.derive` decodes and re-encodes an upstream's JSON, which is the
  wrong thing to do to a stream, so every stream a browser reads has a relay of its own in
  `services/gateway` (`MessageStreamRoutes` is the worked example). Rules A4 and A11 let the gateway
  see a service only through its `contract` module, so `AlertsStreamEndpoint` lives in
  `services/alerts/contract/src-jvm/` — the JVM half, because an event-stream body needs `fs2` —
  exactly where `services/message` puts its browse stream and for exactly that reason. An endpoint
  declared in `api` would have been invisible to the gateway by construction. `AlertsStreamRoutes`
  now rewrites that endpoint to `GET …/alerts/stream`, performs the edge authorization check, and
  relays the service's bytes to the browser without decoding the stream. The card and the bell use
  that change signal and fall back to polling `…/alerts/events` if the connection drops.
- **The routing status of the stream, because it was 404 for the length of a wave and a green suite
  said nothing.** The service shipped complete, imaged and contracted with no relay in front of it:
  `AlertsStreamEndpoint` was in `ServiceContracts` reach and `ContractRouting.derive` cannot carry an
  event stream, so every unit case passed and `GET …/alerts/stream` answered 404 through the gateway.
  What closes that gap is `services/gateway/api/src/kui/gateway/api/AlertsStreamRoutes.scala`, added
  after the fact, asserted by `AlertsStreamRoutesSuite`, and mounted by `AllInOneWiring` as well as by
  the compose stack. Two things follow for anyone reading this document to build against: a contract
  entry is **not** a route for a stream, and the only evidence that this one is reachable is a
  `curl -N` through the gateway that prints a frame — no Scala suite in this repository can produce
  it, because none of them binds the gateway and the service to one another.
- **This wire has committed documents, and the SSE event name travels in one of them.**
  `services/alerts/contract/test/resources/golden/*.json` holds six documents rendered by this
  service's own encoders — the feed in three shapes, an acknowledgement, a change frame, and the SSE
  frame with its event name beside its payload. `AlertResponsesSuite` asserts each against the encoder,
  and `GoldenFilesSuite` reconciles the roster against the directory itself, by repository path rather
  than off the classpath — because the path is what the browser reads and a classpath copy would let it
  move. The event name is in the sixth document because it is the one field of this wire that is not a
  DTO: the server writes frames under `AlertChangeDto.EventName`, the browser listens under a constant
  of its own, and `tools/error-codes` writes the five SSE names by hand with no `SseEventName.Alerts`
  for either side to read — so the two spellings are a hand-copied pair that nothing compares. The
  server half of the comparison is landed here, and so is the browser half:
  `frontend/packages/kernel/src/data/alerts/wire.golden.test.ts` reads `alerts-stream-frame.json`
  off disk and asserts its `event` field against `ALERTS_EVENT_NAME`, the way
  `overview/wire.golden.test.ts` reads the metrics documents. The two spellings are compared rather
  than copied.
- **`acknowledge` publishes on the change topic, and that is what the relay carries.** The store's
  one write wakes every subscriber for that cluster; without it the stream would deliver only rule
  passes, so a bell cleared on one tab would stay lit on every other until its next poll.
  `InMemoryAlertStoreSuite` asserts the publication for **both writers, and for both halves of the
  condition `record` publishes under**: a pass that opens an event, a pass that only *resolves* one,
  and an acknowledgement — three cases, because the resolution half was covered by nothing and a
  condition clearing itself is the change an operator never sees without a reload. A fourth case
  holds the other direction, that a pass which changed nothing wakes no bell.
- **The stream is not in the capability document's `features` list**, because that list is derived
  from `AlertsEndpoints.all` and the stream cannot be in it. A browser therefore learns the feed and
  the acknowledgement from the capability row and the stream's address from this document. It is a
  small asymmetry and it is written down rather than left to be noticed.
- The five metrics `data` payloads have no sub-schema in the published document because Tapir cannot
  see inside a `Section`, and **the alerts feed's `data` is opaque for the same reason**. The browser
  reads the shape from this document rather than from `schema.d.ts`.
- No new `ErrorCode`. An acknowledgement of an event that is already closed, and an id that names no
  event, both answer `409 KUI-INVALID-STATE` through `ApplicationError.Conflict`. The roster has no
  404 for this resource and house rule 3 forbids adding one; from the caller's side the two are one
  fact — *the event you are looking at is not open* — and neither message says whether the id ever
  existed. (Wave 6's brief named `KUI-CONFLICT` for this; there is no such code in the
  thirty-one — see `frontend/packages/api/src/constants.generated.ts`.)

## Alternatives rejected

- **A rules engine in YAML.** `AlertsConfig`'s own scaladoc rejects it: `libs/config` sits below
  every service and must not grow a small programming language.
- **Reading the facts through the cluster and consumer services over HTTP.** It would make one
  service's rules a property of another service's paging, which is `KafkaPartitionSweeper`'s argument
  one service over, and it would put two more services in the critical path of the screen an operator
  reads when things are wrong.
- **Evaluating on the request path.** "Opened four hours ago" would mean "opened when you last opened
  this tab", every repaint of the dashboard would be four admin calls, and a cluster nobody is
  looking at would be a cluster with no alerts — which is precisely the cluster the bell exists for.
- **Seeding an event so the screen looks alive.** A feed with no events answers `ok` with an empty
  list and the screen says so.
- **A UUID for an event id.** The id is derived from the key and the opening instant, which keeps
  `AlertRules.evaluate` a pure function a suite can call twice and compare, and makes a condition that
  clears and returns a second event with its own age rather than the first one silently reopening.
- **Deriving severity from a rate of change, or from how many rules are firing at once.** Both are
  numbers KUI did not measure dressed as numbers it did. A severity KUI infers from a threshold it
  chose is an alert; a severity KUI infers from a number it did not measure is a fabrication.

## Reversibility

High for the rules, low for the store. Each rule is one case of `AlertRule` and one branch of
`AlertRules.evaluate`, and removing one removes its row from every response with no wire change. The
store's shape is what the feed's counts and ages are, so replacing it is the milestone §8 describes
rather than an edit.
