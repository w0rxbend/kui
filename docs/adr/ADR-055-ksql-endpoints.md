# ADR-055 — The ksql service: which ksqlDB API is assumed, what a statement answers, what makes a push-query frame arrive, and what the result region shows

- Status: Accepted
- Date: 2026-09-11

## Context

M9's second half adds `services/ksql`, the eleventh and last new service in this plan, over the
ksqlDB REST API. Four pieces of it shipped in **wave 1** and are unchanged here:

* `Resource.Ksql` in `libs/security-core/src/kui/security/rbac/Vocabulary.scala`, with `KsqlView`
  and `KsqlExecute`, and the implication `KsqlExecute ⇒ KsqlView` that `RbacLawsSuite` already
  holds. `Resource.Ksql` is **unnamed**: a deployment has at most one ksqlDB per cluster;
* `ErrorCode.UpstreamKsql` — `KUI-UPSTREAM-KSQL`, 502, retryable — declared and unused for seven
  waves, and given its first caller here;
* `KsqlSettings` in `libs/config`, which gives a cluster **one** ksqlDB rather than a list, with two
  separate budgets: `callTimeout` for a request that finishes and `streamTimeout` for a push query
  that does not;
* `UpstreamAuthConfig`, the three authentication mechanisms every new HTTP dependency uses.

None of the four is widened by this service, and the shape of three of them decided three of the
decisions below.

What did not exist anywhere was a stream, a table, a query, a statement, a result or a push query.
This document is the decisions that had to be taken before any of them could be written, and it is
the document `frontend/packages/feature-ksql` is written against: the object shape and the result
shape are read here rather than off the endpoint, so that the browser and the server are not two
guesses at one wire. **ADR-056 settles the *rendering* of the result region; this document settles
its *wire*, and neither guesses at the other.**

The design is `research/design/SCREENS-V4.md` §3.16 (the workspace as drawn), §4.15 (the screen) and
§7.9 — the open finding this service and ADR-056 exist to close: *"The ksqlDB result region is still
never shown. Columns, streaming rows, the row cap and the error rendering are all unspecified while
`Run` is drawn as an enabled control."*

## Decision

### 1. The API assumed is ksqlDB 0.14's, over HTTP/1.1, and `/query-stream` is deliberately not used

Two endpoints are used and no others:

* `POST /ksql` — everything that finishes and is not a query: `SHOW`, `CREATE`, `DROP`, `TERMINATE`,
  `INSERT`. It answers a JSON array of entities, one per statement sent;
* `POST /query` — the queries. It answers a JSON array whose first element is a `header` carrying
  the result schema and whose remaining elements are `row` objects, **chunked as they are
  produced**. For a pull query the array completes; for a push query it does not.

`/query-stream` is the newer and better endpoint, and it is not used because it requires **HTTP/2**.
The transport this process shares with every other upstream is `HttpClientFs2Backend` over HTTP/1.1,
and a second transport for one service would be a second connection pool, a second set of timeouts
and a second thing to tune. The cost of not using it is one extra JSON array wrapper per response
and no per-query flow control; the day KUI adopts an HTTP/2 client, `KsqlHttp` changes and nothing
above it does, because the frames this service emits are `QueryFrame`s and not ksqlDB's JSON.

The media type sent and accepted is `application/vnd.ksql.v1+json` rather than `application/json`,
because it is what pins the *response* shape: a server that later changes its default representation
answers this one unchanged.

### 2. The object list is one request over four `SHOW` statements, and its order is a rule

`SHOW STREAMS; SHOW TABLES; SHOW QUERIES; SHOW TOPICS;` goes in one `POST /ksql`. One round trip
rather than four, for `ConnectEndpoints`' reason: §3.16's pane is one pane and §4.15's voice line
counts `4 objects` across the lot, and four requests for one screen is how four panels come to
disagree about how many objects there are.

Every row carries its **kind** — `stream`, `table`, `query`, `topic` — because §3.16 says out loud
that *"the glyph is the only thing that says which"*. A browser that had to infer stream-from-table
by which fields happened to be present would infer it differently from this service on the first row
that is neither.

The answer is ordered **kind first, then name, case-insensitively**. ksqlDB answers in its
metastore's iteration order, which is a hash order that changes when an object is created or
dropped; a pane whose rows move between polls is one an operator cannot click accurately. This is
the same defect `ConnectHttp`'s three ordering rules exist for, one service over, and it is gated by
a fixture whose server order is neither the sorted order nor its reverse.

The answer is **bounded at 500 objects**, and what is dropped is reported rather than absent:
`truncated` is a count, so a screen can say *"showing 500 of 2,314"*. `SHOW TOPICS` on a shared
cluster routinely names thousands, and a list that quietly stopped is how an operator concludes a
stream does not exist.

A row the server returned and KUI could not describe — a stream with no `topic`, a query with no
`queryString` — is named in `unreadable` rather than dropped, for `ConnectorFacts.unreadable`'s
reason. An **entity kind this build has never seen** is ignored entirely: a future ksqlDB adding a
listing to the answer must not put a permanent false row on the screen of every deployment that
upgraded.

### 3. `KSQL:VIEW` reads, `KSQL:EXECUTE` runs, and a read-only cluster cannot open a push query

The object list declares `Action.KsqlView`. The plan, the apply and the push query declare
`Action.KsqlExecute`. Both requirements are **unnamed**, because `Resource.Ksql.isNamed` is false; a
named requirement over an unnamed resource is a permission no role file could ever satisfy.

`Action.closure` expands a grant of `KSQL:EXECUTE` to include `KSQL:VIEW`, so an operator granted
only EXECUTE reaches the read as well. That implication is the reason `KsqlView` was declared in
wave 1 — the read-only gate refuses an altering request *before any resource is considered*, so a
resource whose only action altered could not be looked at on a read-only cluster — and it is
asserted at the endpoint, through a real `RbacPolicy`, in `KsqlRoutesSuite`, rather than only in the
vocabulary's own laws.

**A read-only cluster therefore lists objects and refuses statements, and it also refuses a push
query.** That last clause is a cost and is stated rather than discovered: a push query changes no
data, so a reader may reasonably expect to watch one on a read-only cluster and cannot. It follows
from `Action.KsqlExecute.isAlter`, which is what asks ksqlDB to *start and hold* a query, and a
read-only cluster whose ksqlDB anybody can set persistent queries running on is not read-only in any
sense an operator means. The seam that would change it is a third action — `KsqlQuery`, non-altering
— in `libs/security-core`, which this packet does not own; until it exists the refusal is the
conservative answer and `KsqlUseCasesSuite` holds it.

The plan asks for the same permission as the apply, deliberately: a preview shown to somebody who
cannot make the change is a dialogue whose only button leads to a refusal, which is §3.7's rule that
a control changing position between users is worse than a disabled one.

### 4. A statement that returns rows and one that returns a status are one document with a discriminator

`StatementResultDto` carries `outcome`, which is `"rows"` or `"status"`.

* `"rows"` — a **pull query**. `columns` names the columns and `rows` carries them. **An empty
  `rows` list is a measured emptiness**: the query ran and matched nothing, and a screen must say
  so. That is only true because the other case exists — a statement that returns no rows *at all*
  lands on `"status"` and has no `rows` field to misread.
* `"status"` — a DDL or DML statement. `message` is ksqlDB's own sentence, verbatim (*"Stream created
  and running"*), and `entity` is its `commandId`. The sentence is never one KUI composed, for
  §7.7's reason applied one service over: the words on the screen after a statement ran are the
  server's report of what happened.

A cell is **`null` when the value is a SQL NULL**, and that is the one shape decision in this
document that is not about ksqlDB at all. A cell with no value and a cell whose value is the four
characters `null` are different facts; a wire that rendered both as the string `"null"` would be
inventing a measurement, which is the failure this whole product is built against. Everything else
is rendered as text — a number as its compact JSON, a struct as its compact JSON — because a ksqlDB
row's schema is whatever the statement selected and a table draws strings. Sending the types as well
would need a second schema document per result and a browser that could render each; §3.16 draws no
types, so the wire carries none.

`shape` says what the statement was: `pull_query` or `statement`. Never `push_query` — see §5.

### 5. A push query is a stream, its first frame is the header, and a disconnected client ends the query

`SELECT … EMIT CHANGES` never finishes. It is therefore answered **only** by
`GET /internal/v1/clusters/{clusterId}/ksql/stream?statement=…` and is **refused** by
`POST …/ksql/statements`, with a refusal that names the stream address. Answering it from the JSON
endpoint would mean buffering an unbounded result into one document: a request that never returns
and a heap that never stops growing. The reverse refusal exists too — a statement that finishes is
refused by the stream and named at the statements endpoint — because a dead end with no alternative
is useless to somebody holding a `curl`.

**The frames**, in ADR-035's grammar:

| Event | When | Payload |
| --- | --- | --- |
| `phase` | as soon as ksqlDB accepts the query | `{"columns": [...]}` |
| `row` | when a record is produced to the topic behind the query | `{"values": [...]}` |
| `heartbeat` | after 15 idle seconds | `{}` |
| `error` | a failure after the headers have gone | the ADR-034 envelope |
| `done` | exactly once, at the end | `{"reason": …, "cursor": null}` |

**House rule 16 asks what makes a frame arrive, and this stream has two answers.** The first is the
`phase` frame: it arrives the moment the server accepts the query, so a push query over a completely
idle topic is a stream that has *visibly started* rather than a socket that has said nothing. That
is what makes this stream demonstrable with one command and no seeding. The second is a `row` frame,
which arrives when something is produced to the underlying topic — so the way to make one arrive is
to produce a record, exactly as the alerts stream's frame is made to arrive by writing an
acknowledgement three seconds in.

`done`'s reason distinguishes two endings that look identical on a socket: `exhausted` when ksqlDB
itself finished the query (a `LIMIT` was reached, or the server is shutting down, which arrives as
its `finalMessage`), and `budget` when `kui.clusters.<n>.ksql.streamTimeout` expired. A client told
"finished" for both would report a topic as having stopped producing when in fact the tab had simply
been open for five minutes.

**A client that disconnects ends the query.** The chain is browser abort → gateway relay cancelled →
this service's fiber cancelled → the HTTP response body to ksqlDB closed → ksqlDB terminates the
transient query. There is no explicit `TERMINATE` statement and there does not need to be: a
transient push query is bound to its HTTP response, and ksqlDB's own query cleanup is what ends it.
A **persistent** query — one created by `CREATE STREAM … AS SELECT` — is not affected by any of
this, which is the point: it belongs to the cluster and outlives every browser.

**The push query does not go through the resilient backend, and that is a trade with a cost.** A
`StreamRequest` needs a `StreamBackend`, which `UpstreamClient` is not; and a bulkhead permit held
for the length of a push query is a permit held for minutes, which would let three open queries
starve every other call to the same server. So a push query has the timeout and the cancellation
chain, and it does **not** have the circuit breaker or the failover list. Failing a stream over
mid-query would restart it on another server and replay rows the client had already drawn, so the
absence of failover here is correct rather than merely accepted.

`auto.offset.reset` is **not sent**: no `streamsProperties` are set, and ksqlDB's own default
applies, which for a push query is `latest`. §3.16's footer draws `auto.offset.reset = earliest` as
a caption; **that caption is not what KUI sends**, and until a control exists it must read `latest`
or not be drawn at all. The exact edit that would change it is one optional query parameter on
`KsqlStreamEndpoint` and one entry in the `streamsProperties` object in `KsqlHttp.payloadFor`;
ADR-056 decides whether the control is worth drawing.

### 6. The statement classifier is a classifier, and its limits are stated

`KsqlStatement.parse` is **not a SQL parser**, and the line matters because its verdict gates a
destructive operation. It strips `--` line comments and slash-star block comments, it knows that a
`;` inside a single-quoted literal does not end a statement, and it then asks three questions of the
comment-stripped, literal-blanked text: does it start with `SELECT`, does it contain `EMIT CHANGES`,
does it start with `DROP` and contain `DELETE TOPIC`.

Three consequences follow and each is gated in both directions in `StatementsSuite`:

* a comment can neither hide a `DELETE TOPIC` nor invent one;
* a string literal can neither hide one nor invent one;
* **more than one statement per request is refused.** A request carrying
  `CREATE STREAM a AS SELECT * FROM b; DROP STREAM c DELETE TOPIC;` has two statements and no single
  classification, and any rule that classified "the request" would either refuse harmless batches or
  wave a topic deletion through behind a create. One statement per request is the only shape in
  which the classification is sound, and it is the reason the refusal exists at all.

A `DROP` the classifier cannot pick a target name out of is **still destructive**. The conservative
direction is the only safe one: the harmless branch is the one with no confirmation.

A statement is bounded at 16 KB, which is far past any hand-written ksqlDB statement and is what
stops a pasted log file becoming a request this service forwards to a server that then parses it.

### 7. `DROP … DELETE TOPIC` is plan → token → confirm, and the token signs the statement

`DROP STREAM orders;` removes ksqlDB's view of a topic and leaves every record in it. `DROP STREAM
orders DELETE TOPIC;` deletes the Kafka topic. It is the only statement in ksqlDB's language that
destroys records, so it is the only one ADR-045 applies to.

`POST …/ksql/statements/plan` classifies the statement and answers what it would do. **Every**
statement can be planned, not only the destructive ones: a screen that only knew how to plan the
dangerous ones would have to decide which those are, which is the decision this service exists to
make. A harmless statement plans to `destructive: false`, no warnings and **no token**, and the
editor goes straight on to running it — a confirmation on every statement teaches an operator to
click past them.

A destructive plan carries a token valid for five minutes and a warning that **names the Kafka
topic**: the plan looks the dropped object up in the cluster's own listing and quotes the topic
behind it. *"This deletes something"* is not a thing anybody can weigh and `orders` is. When the
lookup fails — the server did not answer, or does not list an object by that name — the warning says
exactly that instead of naming a topic nobody measured. That is this product's central promise
applied to a confirmation dialogue.

**The deviation from ADR-045, and why it is safe.** ADR-045's apply endpoints take a token and
nothing else, so the change applied cannot differ from the change that was shown. A statement editor
cannot work that way: the statement *is* the request, and there is no second phase for the ordinary
`CREATE STREAM`. So `POST …/ksql/statements` carries both a statement and an optional token, and the
protection is moved rather than dropped — the token is minted over the **canonical statement text**,
`KsqlPlanToken.verify` is given the text being applied, and a token minted for
`DROP STREAM ORDERS DELETE TOPIC;` cannot be spent on `DROP TABLE USERS DELETE TOPIC;`. That is the
substitution the two-phase flow exists to make impossible, and `KsqlPlanTokenSuite` and
`KsqlUseCasesSuite` hold it from both ends.

The statement is **hashed** into the token's payload rather than written into it: a 16 KB statement
would otherwise make a 16 KB token, and a token carrying the text would put somebody's SQL into
every log that records a request body. The key is ADR-026's streaming cursor key, as
`services/topic`'s is — one secret to configure and one to rotate — and the two uses are kept apart
by the operation name `ksql.statement` inside the signed payload.

A token sent with a **harmless** statement is ignored rather than refused: a client that always
sends back the token it last received is doing nothing wrong, and refusing it would make the editor
fail on the request after a confirmed one.

`destructive` and `deletesTopic` are two fields on the plan and are the same flag today. They
coincide because `DROP … DELETE TOPIC` is currently the only statement that destroys records; a
browser that drew one warning from one boolean would draw the wrong warning the day a second
destructive statement arrives that deletes nothing.

### 8. The SSE event name is declared once, in `contracts-core`, and compared to a golden

`SseEventName.Row` — the literal `"row"` — is added to
`libs/contracts-core/src/kui/contracts/sse/SseEvents.scala` and to nothing else.

It is there rather than as a literal in this service because of what the names above it cost.
`services/message` spells its own two — `message`, `consumed` — in `BrowseAddress.Events`;
`services/alerts` spells `alerts` in `AlertChangeDto` *and* again in `AlertsStreamEndpoint`, where
nothing in the tree compares the two to each other, and the browser's third copy is compared to a
golden file by eye. That is five copies of a name over two streams, and a sixth would have been
worse than the first five.

**How the browser's constant is compared to it.** `services/ksql/contract/test/resources/golden/`
carries `ksql-stream-frame.json` and `ksql-stream-header.json`, each `{"event": …, "data": …}`,
rendered from this service's own encoder. `GoldenFilesSuite` asserts the `event` field against a
**literal** — `"row"`, not `SseEventName.Row`, because comparing the constant with itself is the
assertion that proves nothing — and `frontend/packages/feature-ksql`'s wire suite reads the same two
files **by repository path** and asserts its own constant against them. The path is the contract,
which is why it is written out as a string on both sides and why a red says so when they differ.

`Row` is deliberately **not** in `SseEventName.shared`: a client that registered a `row` listener on
the capability stream would wait for ever, and `shared` is the set a suite asserts a stream emits
nothing else from.

### 9. What this service keeps, and the debt it names

**It keeps nothing.** There is no store, no buffer, no cache and no evaluation loop. Every fact it
reports is read from a ksqlDB server at the moment it is asked, so there is no retention policy,
nothing for a configuration change to invalidate, and nothing to reconcile after a restart. The only
state with a lifetime anywhere near this service is the **plan token**, which lives for five minutes
in the operator's browser and is verified by signature rather than by lookup — which is precisely
why it is signed rather than stored, and why a second replica can confirm a plan the first one
minted.

Two debts are named rather than left to be discovered:

1. **`KsqlCredentials` is the third copy** of the same three authentication mechanisms, after
   `RegistryCredentials` in `services/schema` and `ConnectCredentials` in `services/connect` — whose
   own header says the count "stops at two". It could not be avoided here: ADR-041 rule A11 forbids
   one service reaching into another's private layers, and `libs/http` belongs to a different packet
   this wave. **The exact edit that makes them one** is to move `ConnectCredentials`' interface into
   `libs/http` as `kui.http.upstream.UpstreamCredentials`, parameterised over the request type the
   way `KsqlCredentials` already is, and to delete all three. This file's interface is deliberately
   identical to the other two so that the move is a rename rather than a rewrite.
2. **A push query outlives its OAuth token.** The `Authorization` header is applied when the query
   is opened and ksqlDB does not re-check it, so a stream held open for an hour is held open on a
   credential that expired forty minutes in. That is ksqlDB's behaviour rather than KUI's choice;
   the alternative is to close and reopen the query at each refresh, which would replay rows the
   client had already drawn.

### 10. The result region, decided before it is drawn — §7.9's *wire* half

`SCREENS-V4.md` §7.9 is unresolved because every ksqlDB capture is of an *unrun* query. This section
decides what the wire gives the region; **ADR-056 decides what the region does with it**, and the
two were written against each other rather than one guessing at the other.

What the wire provides:

* **Columns arrive before rows, always, and exactly once.** A pull query's `columns` is a field of
  the result document; a push query's arrives as the `phase` frame. So the region can draw its
  headings before it has a single row, which is what makes an idle push query look like a query that
  is running rather than one that is broken.
* **A row is a list of cells in the header's order**, each a string or `null`. There is no per-row
  schema and no per-cell type.
* **"No rows" and "this statement returns no rows" are different documents** (§4), so the region can
  say *"the query matched nothing"* for the first and draw the server's status sentence for the
  second.
* **Every ending says why** (§5): `exhausted`, `budget`, `cancelled`, or an `error` frame carrying
  the ordinary envelope. The region never has to infer that a stream ended from the fact that it
  stopped.
* **There is no row cap on the wire.** A push query's rows are unbounded by definition and the wire
  does not bound them; the bound belongs in the region, which is the only place that knows how many
  rows a person can look at. ADR-056 sets it and says what the screen does when it is reached. This
  is deliberate: a cap applied here would silently drop rows a screen might have wanted to count,
  and `KsqlObjects.MaxObjects` exists precisely because the *object list* is the one place where the
  wire can honestly bound something and say how much it cut.

## Consequences

* The eleventh service is routable, has an OpenAPI document of its own, and answers `not_configured`
  with a 200 for the deployments — most of them — that run no ksqlDB.
* `ErrorCode.UpstreamKsql`, `Resource.Ksql`, `Action.KsqlView`, `Action.KsqlExecute` and
  `KsqlSettings` all have their first callers, seven waves after they were declared.
* `SseEventName` gains its sixth name and its first since wave 1.
* The gateway gains its third hand-written relay (`KsqlStreamRoutes`, W8-03's), because
  `ContractRouting.derive` cannot carry a stream.
* `ServiceContracts`, `smoke.sh`'s scraped contract set, `docs/api/openapi.json` and
  `scripts/feature-matrix-check.sh` all move for this service's endpoints. That is the designed
  intermediate state of this wave and it is W8-03's and W8-09's to close.

## Alternatives rejected

* **`/query-stream` over HTTP/2.** Better in every way except that it needs a second transport in
  every process that speaks to ksqlDB. Revisit when KUI's shared client is HTTP/2.
* **A push query answered from the JSON endpoint, buffered to a limit.** It would make `Run` work
  for every statement with no second address, and it would also make a `SELECT … EMIT CHANGES` over
  a busy topic a request that returns whenever a limit happened to be hit — a result set whose
  contents depend on how fast the browser asked. §7.9 asks for streaming rows; this is the endpoint
  that gives them.
* **Four listing endpoints instead of one.** Rejected for `ConnectEndpoints`' reason, and the count
  on the screen is the argument: §4.15's voice line is one number over all four kinds.
* **`TERMINATE` on client disconnect.** Unnecessary for a transient query, and actively wrong for a
  persistent one — a browser closing a tab must not stop a query the cluster owns.
* **A `MutationKind` case for `ksql.statement`.** `libs/security-core` belongs to another packet
  this wave. `MutationKindGapSuite` goes red the day somebody adds it, which is the day
  `KsqlStatementRecord`, `KsqlStatementSink` and `LoggingKsqlStatementSink` should be deleted.

## Reversibility

Every decision here is a wire decision and reversing one costs a version of the document plus a
browser release. The two that are hard to reverse are §7's plan-token flow — a client that learned
to send a statement with no token would start failing the day the flow were removed — and §8's event
name, which is an `addEventListener` argument on both sides. The one that is cheap to reverse is §1:
`KsqlHttp` is the only file that knows which ksqlDB endpoints exist, and the frames it produces are
this service's own types.
