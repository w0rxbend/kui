# ADR-052 — Four more metrics endpoints, and the three figures a Kafka broker will not give us

- Status: Accepted
- Date: 2026-09-07

## Context

ADR-050 built the adapter: a Prometheus text-exposition reader, a retention window over
`libs/cache`'s `SeriesWindow`, a scrape loop and one endpoint — `…/metrics/throughput` — answering
real numbers from a real broker. What it did not build is the other four cards
[`SCREENS-V4.md`](../../research/design/SCREENS-V4.md) draws on the Dashboard's Traffic tab: the p99
latency line (§4.2), the request-handler ring gauges (§3.4), Top producers (§4) and the message-size
histogram (§3.5).

Three of those four ask for a figure this product's only metrics source may not have. The house rule
this wave added says so plainly: **a card may not be drawn from a figure the design did not name**,
and deriving something close and labelling it with the design's word is definition-of-done rule 1
broken in the one service that exists to keep it. So the measuring came before the building, and this
document is the measurement.

## What was measured, and how

A `bitnamilegacy/jmx-exporter:1.4.0` container was started on the quickstart network with the
**stock** ruleset — `hostPort`, `lowercaseOutputName`, `lowercaseOutputLabelNames`, and no `rules:`
block at all, so that nothing was filtered — pointed at the quickstart broker
(`apache/kafka:4.3.1`), and its `/metrics` was captured whole on 2026-09-07.

```
12,993 lines     1,285,357 bytes     902 distinct metric families, 670 of them kafka_
10,845 kafka_ sample lines     0 histogram buckets     0 client-quota metric families
```

`deployment/metrics/kafka-jmx-exporter.yml`'s own comment says an unfiltered exporter serves "about a
megabyte, per scrape", and that is the first time anybody has measured it: 1.29 MB, so the comment is
right. (Its other figure, "roughly four thousand MBean attributes", is about attributes rather than
lines and this capture neither confirms nor contradicts it — one dimensioned attribute produces many
lines. The file is W5-02's and nothing here asks them to change it.)

A trimmed copy of that capture is committed at
`services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt`. It is
trimmed and not edited: every line in it, and every digit of every value, is what the exporter
served. It exists so that the three refusals below are **evidence** rather than omissions — which is
the trap M7's criterion sat in for two waves, because a `NotMeasured` sentence and an endpoint nobody
wrote are indistinguishable from the outside.

## Decision

### 1. p99 request latency — the design's figure exists, so it is drawn

`kafka.network:type=RequestMetrics,name=TotalTimeMs,request=…` is a Yammer histogram and the broker
publishes its own percentiles. From the capture:

```
kafka_network_requestmetrics_99thpercentile{name="TotalTimeMs",request="Produce"}        9.0
kafka_network_requestmetrics_99thpercentile{name="TotalTimeMs",request="FetchConsumer"}  502.0
```

`GET …/metrics/latency?window=` answers a `Section`-wrapped series carrying `produceP99Millis` and
`fetchP99Millis` per bucket. **KUI never computes a percentile.** The `mean` and `max` attributes are
published beside the p99 on the same MBean and would both parse; a p99 assembled from either is a
number with no relationship to any request.

**Where several scrapes land in one bucket the value drawn is the worst of their percentiles, not
their mean.** The mean of four p99s is a p99 of nothing — percentiles do not average — while the
maximum stays true of the data it came from: somewhere in this step, one produce request in a hundred
took at least this long. Averaging would hide a spike inside a quiet five minutes, which is the exact
reading the card is drawn for. `LatencySeries.over` states it and `LatencySeriesSuite` holds it.

### 2. Request handlers — two of the three rings are real; the third is a length, so it is drawn as one

Two of §3.4's three sub-tiles are ordinary work:

```
kafka_server_kafkarequesthandlerpool_oneminuterate{name="RequestHandlerAvgIdlePercent"}  0.9993945459789384
kafka_network_socketserver_value{name="NetworkProcessorAvgIdlePercent"}                  1.0
```

The third is not. §3.4 draws **"38% PURGATORY"**, and what the broker publishes is:

```
kafka_server_delayedoperationpurgatory_value{delayedoperation="Fetch",name="PurgatorySize"}    481.0
kafka_server_delayedoperationpurgatory_value{delayedoperation="Produce",name="PurgatorySize"}  0.0
```

**`PurgatorySize` is a queue length**, and there is no ceiling anywhere in the other 12,968 lines to
divide it by. A percentage would need a denominator KUI invented, and the ring would then be a
picture of that invention.

**The decision is to redraw the sub-tile as the count it is.** `GET …/metrics/request-handlers`
answers `purgatory` as a list of `{operation, delayedRequests}` — a count of parked requests, per
delayed-operation type, carrying the broker's own name for the operation. The DTO has no field a
caller could read as a fraction, so the card cannot be drawn as a ring by accident.

Per operation and not summed, because the sum is not a thing an operator acts on: `Fetch` is deep by
design on any cluster with consumers (481 of the 481 above are long-polling fetches), and `Produce`
being deep at all means acknowledgements are waiting on replicas. Adding them makes the ordinary
number hide the interesting one.

**Both ratios cross the wire as ratios in `0..1`, never as pre-formatted percentages.** A service
that shipped `"64%"` would be choosing a rounding and a locale for every client that will ever read
it, and a ring gauge needs the fraction to draw an arc at all.

**One thing the capture settled that a hand-written fixture would have got wrong:** a Yammer meter's
one-minute rate for an idle ratio can read slightly **above 1.0** — `1.0006422923680975` on the
broker handler pool in this very capture. It is a moving-average artifact. KUI reports what the
broker published and does not clamp it; a gauge clamps its arc, and the number stays true. The
capture also shows why the attribute has to be matched whole: `RequestHandlerAvgIdlePercent` is
published beside `BrokerRequestHandlerAvgIdlePercent` and `ControllerRequestHandlerAvgIdlePercent`,
which are different pools with different numbers, and a substring match reads whichever comes first.

### 3. Top producers — a broker publishes topics, not clients, so the field is called `topic`

§4 draws **Top producers by `client.id`**. In the whole capture, **nine lines** carry a client label
and they carry three values between them — `client_id="1"` on the broker's own `app_info` bean, and
`clientid="Replica"` and `clientid="ReplicaAlterLogDirs"` on its replica-fetcher managers. None of
them is a byte rate and none of them is a producer.

There is **no client-quota metric family**: the 176 lines matching "quota" are all
`kafka.network:type=RequestMetrics` timings for the `AlterClientQuotas` and `DescribeClientQuotas`
request kinds, which are the APIs for configuring quotas rather than measurements of one. A Kafka
broker publishes no per-`client.id` byte rate unless client quotas are configured, and the quickstart
configures none.

What it does publish is `BytesInPerSec` dimensioned by topic:

```
kafka_server_brokertopicmetrics_oneminuterate{name="BytesInPerSec",topic="__consumer_offsets"}   156.97…
kafka_server_brokertopicmetrics_oneminuterate{name="BytesInPerSec",topic="orders.v1"}              5.95e-20
```

**The decision is to answer the real number under the name of what it holds.** `GET
…/metrics/producers?top=` returns `{measuredBy: "topic", topics: [{topic, bytesInPerSecond}]}`. There
is no `clientId` field on the response, and `measuredBy` is a field rather than a comment because the
browser reads it to choose the card's title: **W5-04 draws "Top topics by traffic", not "Top
producers by client id"**. A field called `clientId` carrying a topic name is precisely the defect
house rule 7 exists to stop.

`measuredBy` is a field and not a constant because there *is* a second answer available to a
deployment that configures client quotas. Reaching it is a milestone decision about the broker's
configuration rather than a card, and wave 6 inherits it.

**An empty list has two causes and the exposition does not say which**, which was found by running
this against three exporters on one broker rather than by reading the parser. A ruleset with a
broker-wide `BytesInPerSec` rule and no per-topic one serves exactly what a broker with no producers
serves. So the endpoint answers `ok` with an empty list — the family *was* served — and the card's
sentence names both causes rather than picking one. The refusal is reserved for the family being
absent altogether, which is a fact the body does carry.

### 4. Record size — Kafka publishes no distribution, so the histogram is not drawn

§3.5 draws a twelve-bucket histogram, 256 B → 64 KB+, with `p50 · 1.1 KB`, `p99 · 18 KB` and
`max · 0.9 MB` chips beneath it.

**Kafka publishes no record-size distribution of any kind.** The capture contains **zero** `_bucket`
lines and no histogram of message size under any name, in 902 families. The only thing that can be
computed is a mean, as bytes-in over records-in.

**The decision is to answer the mean and to refuse the distribution.** `GET …/metrics/record-size`
returns `{meanBytes, bytesInPerSecond, recordsPerSecond}` and nothing else. There is no `p50`, no
`p99`, no `max` and no bucket array on the document, so a twelve-bucket histogram cannot be drawn
from it at all — which is the point of leaving them off rather than sending nulls. The two rates
travel beside the mean so that a card can say *what the figure is* instead of leaving a reader to
assume it is a median. **W5-04 draws a single readout with the sentence that the distribution cannot
be measured, not an empty twelve-bucket axis.**

`meanBytes` is `null` when either rate is absent **and** when the record rate is zero: a broker
receiving no records has no mean record size, and `x / 0` is `Infinity`, which serialises to `null`
by a route nobody chose and then reads as a gap KUI never measured.

### 5. One range vocabulary, two parameter names

The latency series uses `ThroughputRange` — the same `24h | 7d | 30d`, each with its own step —
under the query parameter `?window=`, because that is what the design's control on that card is
called. Two spellings of one vocabulary is a wire decision rather than an oversight: the two charts
are stacked on one screen, and a `24h` that meant a different window in each would be two axes a
reader compares without being able to see that they differ.

The domain type keeps the name `ThroughputRange`. Renaming it would move a wire spelling a browser
has already shipped against, which is the one thing its own scaladoc forbids.

The refusal is built per parameter name, so `?window=90d` answers `details[0].field: "window"`. A
shared codec answered `"range"`, which sends whoever reads it to look at a parameter that is not in
their URL.

### 6. Five endpoints, not one document

Each of the five is its own path and its own `Section`. A JMX exporter is configured with a whitelist
and every deployment's is different, so a body carrying the byte rates and not the request
percentiles is an ordinary configuration rather than a broken one — and it has to cost one card, not
a page. It is also what lets the browser ask for the two cards a tab is showing instead of all five.

### 7. Two refusals, because they need different actions

- **Nothing scraped yet.** The window holds no reading at all: no scrape has succeeded since the
  process started, or everything it read is older than `kui.metrics.retention`. It clears itself when
  the exporter answers, and the reader's move is to wait.
- **The family is not served.** The exporter answered, repeatedly, and no reading carried this
  family. It never clears itself, and the reader's move is to widen the exporter's whitelist.

Both are `unavailable` with a sentence naming which. Collapsing them would tell an operator to wait
for a card that was never going to draw.

**Throughput is deliberately asymmetric and keeps answering `ok` with a full axis of gaps.** A series
has an axis to show and a day of gaps says "KUI has nothing for this window" all by itself; a gauge
has nothing at all to draw, so it needs the sentence. `deployment/compose/smoke.sh` asserts the
throughput section is `ok`, and this decision is why that stays true on a stack whose exporter has
only just come up.

### 8. What the exporter must publish — the contract with `deployment/metrics/kafka-jmx-exporter.yml`

The ruleset file is W5-02's and its own comment already calls its metric-name list a contract with
this parser. This build reads **eight** attributes, and accepts both the whitelisted spelling
(attribute in the metric name) and the stock spelling (attribute in a `name` label, with label keys
in either case):

| MBean | Attribute | Dimensions read | Feeds |
| --- | --- | --- | --- |
| `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` | `OneMinuteRate` | none, **and** `topic` | throughput, top topics, record size |
| `kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec` | `OneMinuteRate` | none | throughput |
| `kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec` | `OneMinuteRate` | none | throughput, record size |
| `kafka.network:type=RequestMetrics,name=TotalTimeMs` | `99thPercentile` | `request=Produce`, `request=FetchConsumer` | latency |
| `kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent` | `OneMinuteRate` | none | request handlers |
| `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent` | *(gauge)* | none | request handlers |
| `kafka.server:type=DelayedOperationPurgatory,name=PurgatorySize` | *(gauge)* | `delayedOperation` | request handlers |

Two rules the whitelist must not break. **The broker-wide line must carry no dimension**: the
throughput reader takes only the `BytesInPerSec` line with no `topic` on it, because adding the
aggregate to its own slices counts every byte twice. And **the per-topic lines must be published**,
or the top-topics card has nothing — that is one exporter rule and it is a different rule from the
aggregate's.

Until the whitelist is widened, the four new endpoints answer `unavailable` with the
family-not-served sentence on a deployment whose throughput card is drawing. That is the correct
rendering and it is the reason the two refusals in §7 are spelled differently.

## Consequences

- `MetricsEndpoints.all` grows from one to five, so `services/metrics/api/openapi.json` gains four
  paths and nine schemas. `docs/api/openapi.json`, `docs/api/openapi.browser.json`,
  `frontend/packages/api/src/schema.d.ts` and `scripts/feature-matrix-check.sh`'s counts all move
  with it; regenerating them is W5-09's, and the gateway module's `openApiCheck` is red until it
  happens. Designed intermediate state, not a breakage.
- `MetricsCapabilities` reports all five feature names, derived from `MetricsEndpoints.all` rather
  than written out, so a sixth endpoint cannot be a card the browser never asks for.
- One scrape now produces one `BrokerSample` holding everything, kept in one `SeriesWindow` per
  cluster. `ThroughputSample` and `LatencySample` are views of it, so two cards on one screen cannot
  disagree about when "now" was.
- `MetricsSourceSettings` did not widen and `kui.metrics` gained no key. A metric name and a JMX
  object name are the adapter's business, which is what `MetricsConfig`'s own scaladoc says.
- Three cards are now drawn differently from `SCREENS-V4.md`. The design document is a record of the
  screenshots and is not edited; the divergence lives here, and W5-04 codes the cards against this
  decision rather than against the drawing.
- **Wave 6 inherits one open question and it is a broker question, not a card question.** Top
  producers by `client.id` needs client quotas configured on the broker, or a different source
  entirely. Nothing here stubs it: `measuredBy` says `topic`, and the day a deployment has quotas
  there is a second value for that field and a second title for the card.
