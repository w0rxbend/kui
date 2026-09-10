# ADR-054 — The connect service: which Connect API is assumed, what a failure reason is, and what an operator sees while a restart is in flight

- Status: Accepted
- Date: 2026-09-10

## Context

M9 adds `services/connect`, the tenth service, over the Kafka Connect REST API. Four pieces of it
shipped in wave 1 and are unchanged here: `ConnectName`, `ConnectorName` and `TaskId` in
`libs/kernel/src/kui/kernel/ids.scala`; the whole Connect RBAC closure in
`libs/security-core/src/kui/security/rbac/Vocabulary.scala`, including the `RESTART` alias and
`ResourceAccess.connector`'s fallback to a parent Connect cluster; `ErrorCode.ConnectRebalancing`,
declared and unused for six waves; and `ConnectClusterSettings` in `libs/config`, which gives a
Kafka cluster a **list** of Connect clusters rather than one. None of the four is widened by this
service, and the shape of three of them decided three of the decisions below.

What did not exist anywhere was a connector, a task state, a failure reason or an operation. This
document is the decisions that had to be taken before any of them could be written, and it is the
document `frontend/packages/feature-connect` is written against: the connector shape is read here
rather than off the endpoint, so that the browser and the server are not two guesses at one wire.

The design is `research/design/SCREENS-V4.md` §3.14 (the cards), §4.14 (the screen) and §7.7 (the
open finding this service exists to close: *"A failed connector shows no reason"*).

## Decision

### 1. The API assumed is Kafka 2.3's, and an older worker is read one connector at a time

`GET /connectors?expand=status&expand=info` returns every connector, its type and every task's state
in one request. It arrived in Kafka **2.3** (KIP-465), and it is what `ConnectHttp` asks for first.

A worker that predates it answers the plain array of names it has answered since 0.10. That is not
an error and is not a configuration mistake: the shape of the answer is the negotiation, and the
client falls back to one `GET /connectors/{name}/status` per connector. The fallback costs a request
per connector, which is why it is the fallback and not the default; there is no version probe,
because a probe would be a round trip that can go stale between itself and the call it informs.

`ConnectHttpSuite` drives both shapes. Nothing else in the service knows which one answered.

### 2. A state is the worker's word, kept

`ConnectorState` wraps a string. It is deliberately not an enum, and the argument is the same one
§7.7 makes about reasons.

Connect has added states across releases — `STOPPED` arrived in Kafka 3.5 (KIP-875), `DESTROYED`
appears on a connector mid-deletion, and a Connect-compatible implementation may add more. A sealed
enum has to answer for a word it has never seen, and every available answer is wrong: an `Unknown`
case greys out a connector the worker described perfectly well, and mapping to the nearest known
state invents a fact about somebody's data pipeline. So an unrecognised state travels to the browser
unchanged, and the screen draws it in the neutral tone.

Three constants — `RUNNING`, `PAUSED`, `FAILED` — exist because three rules *are* about specific
states, and comparison is case-insensitive because at least one implementation answers `Running`.

The same reasoning applies to `type`: every Connect release before 2.0 sends none, so
`ConnectorKind.Other("")` is "the worker did not say" and the icon's direction is left undrawn. A
defaulted `source` would point half the arrows on the screen the wrong way.

### 3. `CONNECT:OPERATE` on the Connect cluster, and the connector-level grant is a stated gap

The three writes declare one requirement: `Action.ConnectOperate` on `Resource.Connect`, **named by
the `connectName` path parameter**. That is the action an operator's file spells `RESTART` —
`Action.ConnectRestartAlias`, shipped in wave 1 — and a grant written over the pattern `payments`
covers the Connect cluster called `payments` and no other.

It is deliberately not a `Resource.Connector` requirement, and the reason is a seam that does not
exist rather than a preference. `ResourceAccess.connector(connect, connector, actions)` builds
exactly the access the RBAC model was designed around: a connector named `payments/orders-sink` with
a fallback to `CONNECT:OPERATE` on `payments`, so that granting a Connect cluster does not have to
be repeated for each of its forty connectors. **`EndpointAuthorization.access` cannot build one.** It
turns a `NameSource.PathParam` into `ResourceAccess(resource, Some(value), actions, None)` — one
path parameter, no fallback — so a `Resource.Connector` requirement here would ask for a permission
on a name spelled `orders-sink` while every grant in the model is spelled `payments/orders-sink`,
and it would refuse the holders of a parent grant that the model says are covered.

**The gap, stated so that it is not rediscovered.** Per-connector permissions are not enforced by
this service; per-Connect-cluster ones are. Closing it needs a `NameSource` that can compose two path
parameters into one name and attach the parent fallback —
`libs/contracts-core/src/kui/contracts/rbac/EndpointAuthorization.scala`, which no packet owns this
wave — plus `Rbac.decide` already handling the fallback, which it does
(`Rbac.scala`'s `resourceGate` recurses into `access.fallback`). Until then, an operator who needs
connector-level control gives the two groups of connectors two Connect clusters, which is the
arrangement `ConnectClusterSettings`' list already exists for.

**And all three are refused on a read-only cluster.** `Action.ConnectOperate.isAlter` is `true`, and
that one field decides both the audit question and the read-only question (`Vocabulary.scala`). It is
stricter than the operation strictly needs — pausing a connector writes nothing to Kafka — and it is
kept for ADR-053 §1's reason, which was argued for the alerts service's acknowledgement and holds
here more plainly: a read-only cluster whose sinks can still be stopped by anyone with a browser is
not read-only in any sense an operator means by the flag. Two layers enforce it and neither reaches
the worker: `RbacGuard.fromPolicy`, given this process's own `kui.clusters[]`, refuses an altering
action at the route; `MutationGuard` refuses it again with `KUI-READ-ONLY` and records the attempt,
because an attempt to change a read-only cluster is exactly what an audit trail exists to notice.

The read declares `clusterScoped`, which is what `topic.list`, `schema.subjects` and `consumer.list`
declare, for ADR-021's reason: a list filters rather than refuses. `Resource.Connect.isNamed` is
`true`, so an unnamed requirement could never be covered by a pattern grant and would refuse every
reader in a deployment with RBAC on. Row filtering is not implemented — no service in KUI calls
`Rbac.visible` — and that is a standing gap of the product rather than of this service.

### 4. A rebalancing Connect cluster is not a broken one, and this is the rule this service is gated on

Connect answers `409` while its workers are agreeing on an assignment: the documented *"Cannot
complete request momentarily due to stale configuration (typically caused by a concurrent config
change)"*. It does so on reads as well as on writes, and it lasts seconds.

That is `ErrorCode.ConnectRebalancing`, and it is handled the same way in three places, which have
to agree or a browser reading one of them draws a screen the other two contradict:

1. `ConnectHttp.errorFrom` classifies it as an **`ApplicationError`**, not an
   `InfrastructureError`. ADR-039 §6 reports only infrastructure failures to the capability registry,
   so this classification is what keeps a rebalance from dimming anything for anybody.
2. `ConnectCapabilities.probe` leaves the capability **`available`** for this error and only this
   error. A capability dimmed by a rebalance is a red badge an operator cannot clear and learns to
   ignore, which is precisely what ADR-039 §6 warns about — and the next poll would clear it anyway.
3. `ConnectMapping.section` reports it as `ReasonCode.Starting`, whose own sentence is *"KUI has not
   finished reading this cluster yet"*, and **not** as `UPSTREAM_UNAVAILABLE`, whose sentence is
   *"the cluster is not answering"* and which sends an operator to look at a Connect cluster that is
   working correctly. The section keeps the worker's own rebalance message, so the screen says which
   Connect cluster is settling.

`ReasonCode` has no `REBALANCING` case and this wave does not add one: it is a shared contract type
in `libs/contracts-core` that the browser, the gateway's capability registry and every service's
sections all decode, and no packet owns it. `STARTING` is the one code in the vocabulary that means
transient-and-not-broken. If a later wave adds the case, the change is these three call sites and
the golden document beside them.

**It is not retried inside the call.** `RetryPolicy`'s own scaladoc names this status as the one KUI
knows about, and `UpstreamConfig.retryableStatuses` is left empty here deliberately: retrying would
hide, for as long as the retries last, the one state this service reports specially, and a rebalance
routinely outlives two backoffs. `ErrorCode.ConnectRebalancing.retryable` is `true`, which tells the
*caller* to ask again, and the screen polls (§5).

### 5. There is no stream, and the screen polls

House rule 16 says a stream ships its relay. The honest answer here is that there is no stream to
relay: **the Connect REST API publishes no change feed**. A worker holds its state and answers when
asked; there is no `/connectors/events`, no long poll and nothing to subscribe to.

An SSE endpoint would therefore have to be KUI polling on the browser's behalf and calling the
result a stream, which buys a second implementation of a poll, a gateway relay to write, and a
connection per open tab. The service publishes four endpoints and none of them streams;
`ConnectContractSuite` asserts that, so a fifth cannot arrive without the relay it would then need.

**What an operator sees while a restart is in flight**, stated because item 5 of the packet asked
for it and because a button with no visible effect is the failure this answers:

- The three writes answer `202 Accepted` from the worker and this service answers
  `ConnectorOperationDto` — what was accepted, and when. It carries **no connector state**, because
  the only state available at that moment is the state *before* the call.
- Connect applies the operation when its workers have agreed, which normally takes a second or two
  and can trigger a rebalance of its own.
- The next read of the connector list is where the change appears, and `runningTasks` is what moves:
  `RESTARTING` is not `RUNNING`, so a connector mid-restart reads `0/3 tasks` and then `3/3`. That is
  the whole reason `Connector.runningTasks` counts only `RUNNING`.
- `acceptedAt` is KUI's own instant, so a screen can say *"asked 3 seconds ago"* rather than leaving
  a button that appears to have done nothing.

### 6. A failure reason is a slice of the worker's trace, and a task with no trace is not a task with no problem

This is §7.7, which is the open finding this service exists to close. `M19`'s failed card carries a
state and a task count and no reason, while the notification for the same event carries `Task 0:
connection refused to es-01:9200`. The design offered two ways out — a connector detail page that
exists in no capture, or the first line of the trace on the card — and this service ships the second.

- Every task carries `trace`, the worker's text **verbatim and whole**, and `reason`, which is the
  first non-blank line of it. A Java stack trace's first line is the exception's class and message,
  which is exactly the sentence the design quotes.
- The reason is computed once, server-side, so that the card, the drawer row and any future
  notification cannot each take a different line of one trace.
- A connector's own `reason` is its own trace's first line if it has one — a connector that failed to
  start has no working task whose trace would be more specific — and otherwise the first failed
  task's, in task-id order, so that two screens reading one document cannot pick different tasks.
- **`reason` is `null` when the worker recorded no trace, and that is a real answer.** A worker that
  lost a task to a rebalance mid-failure reports `FAILED` with no trace at all. The row must still be
  failed and the reason must still be absent: the screen says the worker gave no reason, and KUI
  never composes a sentence from the state word. That is the same rule as §2, pointed at prose.
- `failed` is true when the connector **or any of its tasks** failed. A worker reporting the connector
  as `RUNNING` over a dead sink task is the live case: an operator shown only the connector's own
  state is told everything is fine.

### 7. What this service does not do, and what it keeps

**No throughput.** §3.14 draws `3/3 tasks · 1,204 msg/s · orders.*`. The Connect REST API publishes
no rate: a status document carries states, worker ids and traces, and per-connector record rates live
in the workers' JMX beans, which this service does not read. So **no rate is on this wire**, and the
card must draw a dash rather than a zero — §3.14's own *Absent* paragraph, which points out that a
literal `0 msg/s` on a paused connector is a *measured* zero and an unmeasured one must stay a dash.
Changing that means either a JMX exporter in front of every Connect worker, read the way
`services/metrics` reads the brokers', or the `/connectors/{name}/topics` endpoint, which answers
topic names and still no rate.

**No connector class and no topic list, and both are the design's own caption.** §3.14 draws a
`source · Debezium Postgres` sub-line and an `orders.*` in the caption. The class is available — it is
`info.config["connector.class"]` on the expanded document this client already fetches — and it is not
shipped, because `frontend/packages/feature-connect` was written against this wire without it and a
field with no reader is the orphan this project has shipped once per wave for three waves. The topic
list is a *second* request per connector (`GET /connectors/{name}/topics`, Kafka 2.5+, and only where
`topic.tracking.enable` is on), which is an N+1 on the one screen that draws every connector. Both are
one field and one mapping line away when a screen asks for them; neither is a measurement, so neither
can be faked in the meantime.

**No deploy, no delete, no configuration edit.** M9's list names deploy; wave 7 builds the read and
the three operations, which is what §3.14's cards draw. A deploy is a JSON body, a connector-class
catalogue and a validation round trip (`PUT /connector-plugins/{class}/config/validate`), and it is a
screen this design has never drawn. `Action.ConnectorCreate` and `Action.ConnectCreate` stay unused,
which is the state they have been in since wave 1.

**No store and no retention.** Everything this service reports is read from a worker at the moment it
is asked. Nothing is remembered between requests: there is no buffer, no evaluation loop and no
retention to configure — and therefore no answer to "how long does KUI keep this" other than "it does
not". A connector's history is the worker's own, and KUI does not copy it.

**No `kui.clusters.*.connect` key is added.** `ConnectClusterSettings` shipped in wave 1 with a name,
a URL list, an auth block and a call timeout, and this service reads exactly those.

### 8. A connector the worker does not have is a 409, not a 404

There is no `KUI-CONNECTOR-NOT-FOUND` in `ErrorCode` and this wave does not add one (house rule 3
freezes the thirty-one). The two shapes available are a `409 KUI-INVALID-STATE` naming the connector
and a `501 KUI-UNSUPPORTED`, which would say this deployment cannot restart connectors at all — false
about a deployment whose Connect cluster is answering. So it is the 409, with the connector and the
Connect cluster named in the message, which is the answer `services/alerts` already gives for an
event id that names nothing.

A **Connect cluster** the deployment did not configure is different, and is `501 KUI-UNSUPPORTED` —
the same classification the read gives a cluster with no Kafka Connect at all, so one fact has one
code whether it arrives as a section or as a status.

### 9. `not_configured` is a 200, and the row disappears

A cluster with no `connect` block answers **200** with `connectors.status = "not_configured"`. Not a
404, not an empty list, and not a degraded capability: ADR-032's rule is that a feature this
deployment does not have is hidden rather than drawn broken, and most Kafka deployments run no Kafka
Connect. The capability report says `configured = false` for that cluster, which is what removes the
Kafka Connect row from the drawer — and it is M9's own exit criterion.

A Connect cluster that *is* configured and whose client could not be built is `degraded` and says so.
Reporting it as not configured would hide a KUI defect behind a screen that looks deliberately
switched off.

### 10. The audit record is a second type, and the line that deletes it is asserted

`MutationKind` has twelve cases and none for a connector operation. `libs/security-core` is owned by
no packet this wave, so this service writes `ConnectorOperationRecord`: a second record type that
**shares** the vocabulary — `MutationOutcome`, `AuditPrincipal`, and `LoggingAuditSink.Field`'s own
key names, imported rather than retyped — rather than forking it. That is `AuthenticationRecord`'s
answer to the same shape and `services/alerts`' precedent, and ADR-051 §5 rejected the alternatives.

`MutationKindGapSuite` fails the day `MutationKind` grows a case whose operation starts with
`connect.`, which is the day this record collapses into `MutationRecord` and three files are deleted.
The resource is spelled `<connect>/<connector>` — the name `ResourceAccess.connector` builds and the
name an RBAC pattern is written against.

A **cancelled** operation is audited as `MutationOutcome.Unknown`, never `Failed`. `AuditSink`'s own
words — *"Kafka gives no guarantee that it was not applied, so a record claiming either would be a
lie"* — survive the move to an HTTP call intact and in fact more strongly: a cancellation lands
between the request going out and the `202` coming back, and the worker has very often already
accepted it. The topic and message services wrote `Failed` here and W6-A1 repaired both.

## Consequences

- The ECOSYSTEM drawer's Kafka Connect row can be drawn for the first time, with a count and a failed
  count, on a cluster that configures a Connect cluster — and is correctly absent on one that does
  not.
- §7.7 closes: a failed card carries the worker's own first line, and a failed task carries the whole
  trace behind it.
- A Kafka cluster with two Connect clusters draws both, and one of them being down or rebalancing
  costs one row rather than the screen.
- Per-connector RBAC is **not** enforced (§3). A deployment that needs it splits its connectors
  across two Connect clusters.
- No connector throughput is available anywhere in the product (§7), so the card's caption is a task
  count and a state, and never a rate.
- Every operation is audited under a record type that is scheduled for deletion by a failing test
  rather than by a comment (§10).

## Alternatives rejected

**An enum of connector states.** Rejected in §2: it has to answer for a word it has never seen, and
every answer is a claim KUI cannot support.

**Streaming task state over ADR-035.** Rejected in §5: Connect publishes nothing to stream, so the
endpoint would be KUI's own poll wearing a stream's clothes, plus a gateway relay to maintain.

**Retrying the rebalance 409 inside the call.** Rejected in §4: it hides the state the screen is
built to show, for a delay that usually does not outlast the rebalance anyway.

**One endpoint per Connect cluster for the read.** Rejected: §4.14 draws one screen over every
configured Connect cluster, and three requests for one screen is how three panels come to disagree
about how many connectors there are. The per-worker `Section` inside one document is `services/alerts`'
shape and its argument (ADR-053 §7).

**Reusing `RegistryCredentials` from `services/schema`.** Rejected: ADR-041 rule A11 forbids one
service reaching into another's private layers. `ConnectCredentials` is its own copy over
`UpstreamAuthConfig`, whose own scaladoc says the two auth enums *"should become one type the next
time the schema service is opened"* — at which point both credential classes become one in
`libs/http`.

## Reversibility

Every decision above is reversible inside this service except two.

The **wire shape** is not: `frontend/packages/feature-connect` decodes it and
`services/connect/contract/test/resources/golden/*.json` pins it, so a field rename is a change to a
golden document, a Scala suite and a browser suite together. That is house rule 12 and it is
deliberate.

The **`CONNECT:OPERATE` requirement** is not, quietly: an operator's role file names it, so narrowing
it later to a connector-level action would silently stop working for grants that already exist. The
widening direction — adding the connector-level requirement beside it once
`EndpointAuthorization.access` can express the fallback — is safe, because a caller who holds the
parent grant is covered by `ResourceAccess.connector`'s own fallback.
