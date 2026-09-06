# ADR-050 — The metrics adapter reads a Prometheus exposition; `Jmx` stays unimplemented

- Status: Accepted
- Date: 2026-09-06

## Context

`services/metrics` shipped in wave 1 as a walking skeleton: six ADR-041 layers, a Mill module, a
container image, a `ServiceContracts` entry, an `AllInOneWiring` entry, and one `Section`-wrapped
endpoint — `GET …/clusters/{id}/metrics/throughput?range=24h|7d|30d` — that answered `not_configured`
for every cluster in every deployment. `ConfiguredClusterSources.source` returned `None` and its own
scaladoc said what was missing: *"adding the adapter later is one class and one line in
`MetricsWiring`."*

Nothing else in the repository read a broker metric. There was no JMX client and no Prometheus
parser anywhere in it, which is why M7's exit criterion — "the throughput status prints one of
`ok|stale|unavailable|not_configured`, and with no exporter configured it prints `not_configured`" —
was satisfied in full by a service that measured nothing and was never meant to yet. A criterion
whose only positive assertion is a refusal is one the absence of the code will always be able to
pass. That is the trap this decision exists to get out of, and it is why the work here is judged
against a deployment where something *can* be measured rather than against one where nothing is.

`MetricsSourceKind` has had two cases since wave 1 — `Prometheus` and `Jmx` — and
`MetricsSourceSettings` has carried the address, the kind and the call timeout for both. So the
question was never which protocols to model. It was which one to implement first, and whether the
other could be implemented at all through the configuration that exists.

## Decision

### 1. Implement `MetricsSourceKind.Prometheus`, and only that

`PrometheusThroughputScrape` reads the Prometheus text exposition format from
`kui.metrics.sources.<cluster>.url` over `libs/http`'s resilient backend, and
`PrometheusExposition` folds the body into a `ThroughputSample`. In every deployment KUI ships,
the thing at that address is a `jmx_exporter` in httpserver mode attached to a broker's JVM.

Three reasons, and they are structural rather than preference.

**`MetricsSourceSettings.url` is a `SafeUrl`, which `ARCHITECTURE.md` §14 restricts to `http` and
`https` with no exception, not even for development.** A JMX service URL —
`service:jmx:rmi:///jndi/rmi://kafka:9999/jmxrmi` — therefore **cannot be written into KUI's
configuration at all**: `SafeUrl.from` rejects the scheme before the value reaches the metrics
section. Implementing the JMX case would have meant either a second address field with a weaker
rule than the one guarding every other upstream, or relaxing the SSRF policy for one key. Both are
worse than not having the case.

**`Prometheus` is already the default.** `MetricsSourceSettings(url = …)` with no `kind` is a
Prometheus source, so every deployment that names an address and says nothing else already asked
for the protocol this build reads.

**A text parser is a pure function a test can feed a captured body; an RMI client is not.** The
exposition fixtures in `services/metrics/infrastructure/test/resources/exposition/` carry the two
naming schemes the exporter serves, the per-topic slices that must not be double-counted, a family
whose name contains one of the names being looked for, a `NaN`, and several hundred families' worth
of things the parser must ignore rather than fail on. None of that is reachable through a mock of an
RMI connection.

### 2. `Jmx` stays declared, and a cluster configured with it gets a stated refusal

The case is **not** deleted, and the next wave must not delete it. It is the record of a protocol a
deployment may genuinely have and this build cannot read, and removing it would turn `kind: jmx` from
a refusal into a configuration error with a different message.

What a cluster configured with `kind: jmx` gets is a sentence naming the build:

> cluster prod configures kui.metrics.sources.prod.kind: jmx, and this build reads the Prometheus
> text exposition only; point the address at a JMX exporter in httpserver mode and set
> kind: prometheus (ADR-050)

It travels three ways, because three people read it in three places: `SourceProfile.unreadableReason`
carries it out of the adapter, `ThroughputUseCase` puts it in the `NotMeasured` reading, and
`MetricsCapabilities.stateOf` puts it in the capability row's `reason`. The endpoint answers **200
with `not_configured`** and the card keeps its written sentence, exactly as a cluster with no source
at all does — the difference between the two is the reason, where a person reads it, and the WARN
line the process writes at start-up naming the cluster.

`ConfiguredClusterSources.unreadable` is a `match` over the enum rather than an `if`, so a third
`MetricsSourceKind` is a compile error at the one point where a new protocol must not be able to
arrive silently and be measured as nothing.

### 3. The port a use case holds is the buffer, not the exporter

`MetricsSourcePort.throughput` asks for a whole range — twenty-four hours at a five-minute step — and
an exporter can only answer for *now*. So `ThroughputBuffer` is what implements the port:
`SeriesWindowCell` from `libs/cache`, one per cluster, stepped at `kui.metrics.scrapeInterval`,
retained for `kui.metrics.retention`, bounded by `kui.metrics.maxSamplesPerSeries`. The scrape loop
fills it in the background and a request is answered from memory, so a repaint of the dashboard is
never a network call to a component KUI does not control.

`ThroughputUseCase` did not change. `ConfiguredClusterSources` now returns a port for a configured
cluster and `None` for the rest, which is the one line the skeleton's scaladoc promised.

**A range always answers `bucketCount` buckets.** A window holding three samples answers 288 of them
with three carrying values, never three — because the axis is a property of the range and not of what
was sampled, and a quiet hour drawn on a narrower axis than a busy one is two clusters rendered as
two different pictures. The fold that guarantees it is the domain's `ThroughputSeries.over`, called
by the buffer rather than reimplemented in it, so a second source added later cannot disagree with
this one about what a gap is.

`SeriesWindowCell.bucketsOver` is deliberately **not** used, and the difference is worth stating: it
answers `None` until the window has been collecting for the whole period, which is the right refusal
for a percentage folded over a day and the wrong one for an axis. A process four minutes old has a
day-shaped chart with four minutes of it filled in; refusing would leave the screen with nothing to
draw and nothing to say.

### 4. Each rate is separately optional, and `null` always means *not measured*

`ThroughputSample`'s three rates are each an `Option`, folded independently into the bucket. A JMX
exporter is configured with a whitelist, and a deployment publishing `BytesInPerSec` and
`BytesOutPerSec` without `MessagesInPerSec` is an ordinary configuration; refusing the whole card —
including the two rates the Traffic screen actually draws — because a third one was absent would lose
the screen this milestone is for. A body carrying **none** of the three is still a `Left`: a source
publishing no throughput at all is a misconfiguration somebody has to be told about, and a sample of
three `None`s would file a measured gap instead of raising it.

The broker-wide aggregate is the only line read. `BrokerTopicMetrics` is published once for the
broker and once per topic, and a parser that added both would count every byte twice. The rule is
"a sample carrying any label other than the MBean attribute's `name` is a slice", stated that way
rather than as a list of known slice labels, because a list has to be complete to be safe: the day a
release dimensions these families by anything else, a parser holding a list would start adding the
slices to the aggregate and the chart would read high with nothing failing.

The one-minute rate is read rather than the `Count` or the `MeanRate`. A count is a monotonic total
whose chart only ever goes up and resets when a broker restarts; a mean rate taken since boot stops
moving after a week of uptime, which is the one thing a live traffic chart must not do.

### 5. A failed scrape is logged and dropped, and never clears what is held

One fibre per configured cluster, at `scrapeInterval`, each call bounded by
`MetricsSourceSettings.callTimeout` inside the upstream client. A failed pass writes nothing and logs
one WARN naming the cluster and the error code. It does not retry inside the pass — ADR-037's retry,
breaker and bulkhead are already in front of the address, and a second loop would turn one slow
exporter into a pile of overlapping scrapes. It does not clear the buffer: the honest answer to "the
exporter died ten minutes ago" is the last ten minutes of data with a gap on the end, not an empty
chart, which reads as a cluster that stopped rather than as a collector that did. And it does not
stop: a fibre that exited on a failure would leave a cluster unmeasured for as long as the process
ran with nothing on any screen saying so.

Only age removes a sample, and the age is measured against an `Instant` the caller supplies.

### 6. A cluster with a readable source is `available` in the capability report

`MetricsCapabilities.stateOf` now answers `configured = true`, `status = available` and
`features = ["metrics.throughput"]` for a cluster this process is scraping. That row could not exist
before the collector did, and it is the assertion M7's old criterion could not make — a service with
no adapter can produce every refusal in the vocabulary and none of this.

The row is **not** probed first, unlike the schema service's. A metrics source is already scraped
every `scrapeInterval` by a loop that is running, so a `degraded` row would be a second and slower
opinion about a fact the throughput endpoint's own `Section` already carries per request — which is
what "one dead exporter costs one card" means.

## What a deployment has to run to be measurable

Three things, and none of them is KUI:

1. **Remote JMX enabled on the broker.** `apache/kafka:4.3.1` does not enable it by default; it is
   `KAFKA_OPTS` on the broker container.
2. **A JMX exporter beside it, in httpserver mode**, whose whitelist includes at least
   `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` and `BytesOutPerSec`.
   `MessagesInPerSec` is read too when it is published and its absence is a gap in one rate rather
   than a refusal of the card.
3. **`kui.metrics.sources.<cluster>.url` naming the exporter's address** — `http://…`, because
   `SafeUrl` admits nothing else. Inside a Compose network that address is private, so the process
   needs `KUI_ALLOW_PRIVATE_UPSTREAMS=true`; the policy is read from the environment inside
   `MetricsWiring` rather than passed in, so the all-in-one and the stand-alone process cannot
   apply two different ones.

A deployment missing any of the three answers `not_configured` and every metrics card keeps its
written sentence. That is the correct rendering (ADR-032) and it is not a failure — but from this
decision onward it is also *provably* different from a build with no adapter in it, which is the
whole point.

## Consequences

- `services/metrics/infrastructure` gained `libs/cache`, `libs/http`, `libs/observability` and an
  sttp client. It holds no Kafka client and needs none: a JMX exporter is an HTTP endpoint.
- The published wire did not move. No endpoint was added, no DTO changed, and
  `services/metrics/api/openapi.json` regenerates byte-identical — so nothing in `docs/api/**` moves
  on account of this decision.
- `ThroughputSample`'s rates became `Option[Double]`. The type is in the service's `domain` and
  nothing outside `services/metrics` can name it (ADR-041 A11), so the change is local.
- The four other measurements the dashboard wants — latency, request handlers, top producers and the
  record-size histogram — are not built here. They are another entry in `MetricsEndpoints.all` and
  another series in the same buffer, against an adapter that has now been run.
