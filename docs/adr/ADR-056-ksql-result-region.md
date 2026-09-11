# ADR-056 — The ksqlDB result region: what a screen draws while a query is arriving, when it stops, and when it stops because the server went away

- Status: Accepted
- Date: 2026-09-11

## Context

`research/design/SCREENS-V4.md` §3.16 draws the ksqlDB workspace as two panes: `STREAMS & TABLES` on
the left, a SQL editor and a footer with `auto.offset.reset` and `Clear` / `▶ Run query` on the
right. Its own **Absent** paragraph then says what is missing:

> The result region. Every ksqlDB capture is of an unrun query; `SCREENS.md` open finding 4 stands
> unresolved and is restated as §7.9.

§7.9 states it in full:

> **The ksqlDB result region is still never shown** (`SCREENS.md` finding 4, unresolved). Columns,
> streaming rows, the row cap and the error rendering are all unspecified while `Run` is drawn as an
> enabled control.

So there are three captures of this screen (`M20`, `M21`, `M22`), a kernel component that has existed
since wave 3 with a `result` slot nothing ever filled, and no drawing anywhere of what goes in it.
This is the one decision in wave 8 that no capture can settle, and it has to be taken before the
screen is drawn rather than discovered while drawing it — which is why it is a document rather than a
commit message.

ADR-055 settles the **wire**: what a statement answers with, what a push query's frames look like,
what happens when a client disconnects. This document settles the **rendering**, and the two were
written against each other rather than each guessing at the other. Everything here is implemented in
`frontend/packages/feature-ksql`, and every numbered decision below has a case in `model.test.ts`,
`ksql.test.tsx` or both, plus a story in `ksql.stories.tsx` — because whether a live query *reads* as
alive is a thing only a person can say.

## Decision

### 1. There are four ways to have no rows, and the screen says which one it is

This is the decision the rest of the document hangs off. A push query that has not produced a row
yet, a pull query that ran and matched nothing, a query that died before producing anything, and a
statement that returns no rows at all all put an empty region on the screen. They are four different
facts and the operator's next action differs for each: **wait**, **your predicate is wrong**, **go
and look at the server**, and **nothing was ever going to appear here**.

A region that drew `0 rows` for all four would be the product refusing to say which — the exact
defect this project's central promise is about, in the one place a reader is most likely to read an
empty table as an answer. So each has its own sentence and there is no row count anywhere that could
be zero:

| State | What the region says |
| --- | --- |
| push query open, nothing yet | *"No row has arrived yet. A push query shows rows as they are produced, so nothing appears here until something is written to the stream it reads."* |
| pull query, no match | *"This query ran and matched no rows."* |
| push query that died first | *"The query ended before it produced a row."* |
| statement (DDL/DML) | the server's own sentence, verbatim — *"Stream created and running"* |

The fourth is not a row count at all. `StatementResultDto`'s `outcome` discriminates `rows` from
`status`, and a `status` answer has no `rows` field to misread: the words **are** the answer, and the
screen draws no table under one.

### 2. Columns come from the `phase` frame, once, and the first list wins

A push query's columns arrive on ADR-035's shared `phase` event as soon as ksqlDB accepts the query —
before any record — which is what makes an idle push query *a stream that has visibly started*
rather than a socket that has said nothing. `QueryRowDto` does not repeat them; a stream that sent
the schema on every row would send it a thousand times to describe a thousand rows.

The **first** column list wins. A server that re-sent a different one mid-query would otherwise
silently re-align every row already on screen against headings they were never produced under, and
nothing about the screen would look different while it happened.

Where a row is **wider** than the column list — which a dropped `phase` frame produces, and which a
service and a build that disagree certainly produce — the extra columns are drawn under positional
headings (`Column 4`) rather than cut off. Narrowing the table to the heading list would hide a real
disagreement by throwing data away, and showing what came back is this screen's whole job.

### 3. A cell is text, `null` is a value, and a missing cell is not

ADR-055 §4 renders every cell as text: a ksqlDB row's schema is whatever the statement selected, and
a browser drawing a table draws strings. That leaves two more things a cell can be, and they are not
the same as each other:

- **`null`** — which `statement-rows.json` and `ksql-stream-frame.json` both carry — is a SQL null
  the query produced. The column was selected and its value is nothing, so the word `null` is drawn.
- **missing** — a row shorter than the column list the *same answer* declared — is a disagreement
  inside one document rather than a value, and it says so in words: *"no value sent"*.

Neither is drawn as a blank cell, because a blank cell reads as an empty string, which is a third
thing again. One of the two is the shape a decoding fault takes, and padding it into a blank is how a
fault comes to read as data.

### 4. The rows outlive the failure that ended them

A push query whose stream dies keeps every row it had already delivered, with a danger banner **over**
them carrying the reason. It does not clear them and it does not replace them with an error panel.

They arrived, they were true, and they are the only record the operator has of what their query was
producing at the moment the connection went. An error panel that replaced them would delete evidence
in order to show an error message. This is the state `interrupted`, and it is deliberately distinct
from both of its neighbours:

- `failed` — the statement never ran. There is nothing to keep, so the region is an error panel.
- `ended` — the query finished, or the reader stopped it. Info tone, because it is not a fault, and
  the reason is the stream's own words for why it stopped.

A row that arrives *after* the region has ended is dropped rather than appended. The region has
already said how many rows the query delivered; a late row makes that sentence false, and a reader
who watched the count move after *"no longer running"* has been lied to.

### 5. The row cap is 500, it is a window on the most recent rows, and it says what it dropped

A push query is unbounded and a browser tab is not. `MAX_RESULT_ROWS = 500` is a chosen number, not a
measurement: it is roughly a screenful of scrolling, and what would change it is a measurement of the
row height at each of the three density settings against the region's own `max-height: 46vh`, which
nobody has taken.

It is a window on the **most recent** rows rather than the first ones. Keeping the first 500 is
cheaper and is worse in the way that matters: the rows on screen stop moving and the region then
looks exactly like a query that has finished, which is the most expensive wrong impression this
screen can give. What was dropped is counted and said out loud — *"12 rows arrived earlier and are no
longer held: this screen keeps the most recent 500"* — and the sentence is absent when nothing has
been dropped, because a sentence about a cap that has not been reached is noise, and noise is what
stops the sentence being read on the run where it matters.

There is no figure anywhere claiming how many rows the *query* produced. Nobody measured that:
ksqlDB reports a push query's production rate in its metrics endpoints, which `services/ksql` does
not read. Every count on this screen is a count of what this browser received and every sentence says
so.

### 6. Running is a two-phase act, and the plan is what routes it

`services/ksql` implements ADR-045 over free-form text, which no other service in this product does.
So `Run query` does not post the statement: it **plans** it, and then acts on what came back.

```
Run ─→ POST …/ksql/statements/plan  { statement, token: null }
          ├─ shape = push_query   → GET …/ksql/stream?statement=…     (the stream, §2)
          ├─ destructive = true   → confirm (§7), then apply with the plan's token
          └─ otherwise            → POST …/ksql/statements { statement, token }
```

Every statement is planned, including the harmless ones. A browser that only planned the statements
it suspected would have to decide which those are, and that is the decision `services/ksql` exists to
make; a second classifier disagrees with the first eventually, and the statement it disagrees about
is the one that deletes a topic.

The same argument routes the push query. `KsqlEndpoints.execute` **refuses** a
`SELECT … EMIT CHANGES` and names the stream address, because a push query does not finish. The
browser does not read the SQL to find that out — two definitions of "push query" is two chances to
disagree, and the browser's copy would be a SQL parser living in a screen.

### 7. A destructive statement is confirmed against the plan's text, not the editor's

The dialogue shows the **service's canonicalised statement** and the **service's own warnings**, in
the service's order. Nothing in the browser composes a warning.

The statement is shown because it is the thing being confirmed: a dialogue saying *"this is
destructive, continue?"* over a statement the reader can no longer see is a dialogue whose answer is
always yes. And the apply sends the plan's text rather than the editor's, because
`KsqlPlanToken.verify` refuses a token whose statement is not character-for-character the one being
applied — sending whatever is in the editor when the button is pressed would break exactly the
guarantee the two-phase flow exists for, the moment somebody typed while the dialogue was open.

`deletesTopic` gets a banner of its own, separate from `destructive`. Every `DROP` is destructive;
only `DROP … DELETE TOPIC` destroys records, and a dropped stream can be recreated from its topic
while a deleted topic cannot be recreated from anything.

### 8. Cancel closes the stream, and so does leaving the page

`Run` becomes `Cancel query` while a push query is open, and both `Cancel` and unmounting call the
kernel's `SseHandle.close()`. That aborts the `fetch`, which cancels the gateway's relay, which
cancels the service's fiber, which stops the query on the ksqlDB server.

This is why the browser uses `openFetchStream` and not the native `EventSource`: `EventSource.close()`
stops the *browser* listening and does not cancel the request. A push query never ends by itself, so
a screen that only hid its rows would leave one running on somebody's cluster until
`kui.clusters.<n>.ksql.streamTimeout` expired — and navigating away is one click.

### 9. `auto.offset.reset` is drawn disabled, with the reason beside it

§3.16 puts `auto.offset.reset` on the footer and the kernel's `KsqlWorkspace` draws it, for a good
reason: it decides whether `SELECT * FROM orders EMIT CHANGES` shows the operator the last hour of a
topic or only what arrives from now on — the difference between a query that answers and a query that
appears to hang.

**The wire has nowhere to carry it.** `StatementRequestDto` is `{statement, token}` and
`KsqlStreamEndpoint` takes one `statement` query parameter. So KUI cannot set it, and the control is
rendered **disabled** with a sentence under the workspace saying so:

> KUI cannot set auto.offset.reset: the ksqlDB statement wire carries no query properties, so a push
> query starts wherever this ksqlDB server's own configuration puts it.

An enabled control that silently changes nothing is the `MESSAGES_PURGE` defect again — a control
that looks live and has never worked — and it is worse here than a disabled one, because the operator
who sets it to `earliest` and sees no history concludes their topic is empty.

Making it live is a wire change and it is stated here so that whoever takes it knows what to add: a
`properties: Map[String, String]` on `StatementRequestDto` and a matching query parameter on
`KsqlStreamEndpoint`, both validated against a small allow-list on the service side — a free-form
property map reaching ksqlDB from a browser is a configuration channel nobody designed.

### 10. What this screen does not draw

- **Topics.** ksqlDB names them and the object listing carries them; KUI has a Topics screen, and two
  places listing one thing under two headings is how a reader comes to believe there are two. The
  count is stated in the voice line and the row is not drawn in the pane.
- **A kind this build has never seen.** `KsqlObjectDto.kind` is a string and ksqlDB's vocabulary has
  grown across releases. An unrecognised kind is `other`: it is counted and named in words rather
  than drawn as a stream, because guessing `stream` would be KUI making a claim about somebody's
  ksqlDB on no evidence.
- **Throughput or lag, anywhere.** See §5.
- **Syntax colouring.** `KsqlWorkspace`'s own header records the decision and it stands: a real code
  editor is a dependency, a bundle and an accessibility surface, and a `<textarea>` in the monospace
  face is a control every assistive technology already understands.

## Consequences

The result region exists, so `SCREENS-V4.md` §7.9 — `SCREENS.md` finding 4, open since the first
design pass — is answered. The answer is a document and an implementation rather than a capture, and
the next design pass should hold the stories in `Screens/Ksql` beside `M20` and say whether the
region belongs where this puts it.

`KsqlWorkspace` gains a real caller, five waves after it was written. It is unchanged: every decision
above fits its existing `result` slot, its `running`/`onCancel` pair and its `runDisabledReason`,
which is some evidence that designing a component against fixtures before its service exists works.

**Three things are now stated and not yet true**, and each is named where somebody can act on it:

1. `auto.offset.reset` is inert (§9). The wire change is specified above.
2. The push query's positive half is proved only in unit cases and stories. `frontend/e2e/ksql.spec.ts`
   drives the real address, and the quickstart ships no ksqlDB server, so on that stack the spec
   asserts the `not_configured` rendering and says so rather than skipping quietly.
3. The row cap's 500 is a chosen number. §5 says what would turn it into a measured one.
