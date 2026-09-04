# Observing KUI

Every KUI process emits three things that are meant to be read together: **traces** (what a
request did, and where the time went), **metrics** (how often, how long, how many) and
**logs** (what happened, in words). This document is how an operator uses them.

## The one field that ties everything together

`correlation.id` is a 16-character value that appears in three places for the same request:
in the error body the user is looking at, on every log line the request produced, and as the
span id in the trace. So all three are one search away from each other.

Inside a request it *is* the span id, which means it can be pasted straight into a trace
backend. Outside a request — a background scheduler, a startup path — there is no span to
derive it from, so a random one is generated; it is still unique, it just does not point at
a trace.

A caller may supply their own in the `X-Kui-Correlation-Id` header and KUI echoes it, so a
caller who logged "I sent request X" can find X on this side too. A supplied value that is
not 1 to 64 letters, digits and dashes is ignored and a fresh one is generated, because the
value ends up in a response header and in log output, and an unchecked header is how a
newline gets into a log file.

## The context keys on every log line

These five names are fixed (`ARCHITECTURE.md` §13). They are the same in every service, so
one search works across all of them. They come from `ContextKeys` in `libs/observability`
and never from a string typed at a call site.

| Key | On which entries | What it is |
| --- | --- | --- |
| `service.name` | every entry | which process wrote it — `kui-gateway`, `kui-cluster`, … |
| `correlation.id` | every entry produced while serving a request or a stream | the id above |
| `user.id` | entries from an authenticated request | the signed-in principal, hashed when `kui.telemetry.hashUserIds` is on (it is, by default) |
| `cluster.id` | entries about one Kafka cluster | the configured cluster slug |
| `operation` | entries from an endpoint | the endpoint's operation id, e.g. `listTopics` |

A field that does not apply produces **no key at all**, rather than a key with a null value.
`"user.id": null` on every line from every scheduler is noise that makes the real entries
harder to find.

Inside a span, two more appear:

| Key | What it is |
| --- | --- |
| `trace_id` | the trace this entry belongs to, 32 hex characters |
| `span_id` | the span, 16 hex characters |

Underscores rather than dots, because those are the names the OpenTelemetry logging
conventions use and the names a trace-search backend already indexes. They are absent
outside a span, so a startup line never carries a link to a trace that does not exist.

## Log format

`kui.telemetry.logFormat` is `json` (the default) or `text`.

`json` writes one JSON object per line, which is what a log system parses:

```json
{"timestamp":"2026-09-03T11:46:04.450Z","message":"listing topics","logger":"kui.topic.Api",
 "thread":"io-compute-4","level":"INFO","service.name":"kui-topic","correlation.id":"9f2c…",
 "cluster.id":"prod-eu","operation":"listTopics","trace_id":"4bf9…","span_id":"9f2c…"}
```

`text` is for a developer reading a terminal, where a line of JSON is unreadable. It keeps
the message and the correlation id and drops the rest, because a terminal line has a width.

The configurations are `logback.xml` and `logback-text.xml` in `libs/observability`'s
resources. They include **every** MDC key rather than listing the ones they know about: a
list here would mean adding a key in Scala and forgetting it in the XML, and the field would
then be silently missing in production.

## The metrics

Every metric KUI emits is in this table. The names are constants in
`libs/observability`'s `MetricNames`, and `MetricNamesSuite` asserts the list against this
document and `ARCHITECTURE.md` §13, so the code and the docs cannot drift apart.

"Live from" is when the metric starts being *emitted*. Every name is declared from M0, so a
later milestone cannot accidentally reuse one for something else.

| Metric | Labels | Live from | What it tells you |
| --- | --- | --- | --- |
| `kui.http.server.duration` | `service`, `route`, `status` | M0 | How long KUI took to answer, in seconds. The one metric that answers "is KUI slow". |
| `kui.upstream.duration` | `service`, `upstream`, `outcome` | M0 | How long a call to another system took, and how it ended. |
| `kui.upstream.circuit.state` | `upstream` | M0 | Whether an upstream's circuit breaker is closed, open or half-open. |
| `kui.kafka.admin.duration` | `cluster`, `operation`, `outcome` | M1 | How long an admin call to a broker took. |
| `kui.kafka.consume.records` | `cluster`, `topic` | M3 | Records read while browsing messages. |
| `kui.kafka.consume.bytes` | `cluster`, `topic` | M3 | Bytes read while browsing messages. |
| `kui.cache.hits` | `cache` | M1 | Cache hits, by cache. |
| `kui.cache.misses` | `cache` | M1 | Cache misses, by cache. |
| `kui.capability.state` | `service`, `cluster`, `state` | M0 | What the UI is allowed to show, per service and cluster. |
| `kui.stream.events` | `service`, `stream`, `event` | M0 | Events pushed down an open stream, by event name. |
| `kui.stream.active` | `service`, `stream` | M0 | Open streams. A gauge that never returns to zero is a leak. |
| `kui.cursor.rejected` | `reason` | M3 | Paging cursors refused, by why. |
| `kui.principal.rejected` | `reason` | M0 | Signed principal headers refused, by why. |
| `kui.config.version` | `section` | M1 | The version of each configuration section in use. |

### Reading `outcome`

`kui.upstream.duration` groups by outcome rather than by HTTP status, because these six lead
to six different actions:

| `outcome` | What happened | Where to look |
| --- | --- | --- |
| `success` | 2xx or 3xx | — |
| `client_error` | 4xx | KUI sent something the upstream did not accept — usually configuration |
| `server_error` | 5xx | the upstream is unwell |
| `timeout` | KUI gave up waiting | the upstream is slow, or the configured timeout is too tight |
| `circuit_open` | KUI did not even try | the breaker is open; see below |
| `unreachable` | no connection | DNS, network policy, or the upstream is down |

A dashboard grouped by status cannot tell `timeout` from `unreachable`, and those have
different causes and different fixes.

### What an open circuit looks like

When an upstream fails `failureThreshold` times in a row, KUI stops calling it for
`resetTimeout` and then lets one probe through. While that is happening:

- `kui.upstream.circuit.state{upstream}` is 1 (open) or 2 (half-open) rather than 0;
- `kui.upstream.duration{outcome="circuit_open"}` keeps counting, so the calls that were
  refused are visible as data rather than as an absence of data;
- exactly **one** INFO log line is written per transition, naming the upstream and the last
  error. There is deliberately no log line per failed call: a dead upstream that logs on
  every attempt floods the log exactly when you most need to read it;
- the capability that depends on that upstream is reported as degraded, which is what greys
  the corresponding part of the UI out instead of letting a user click into an error.

### Labels never carry user data

A route label is the path *template* — `/clusters/{clusterId}/topics` — and never the actual
path. A label whose values multiply (a cluster id, a topic name, a URL) turns one metric into
thousands of time series, which is a well-known way to take a monitoring system down. A topic
name is acceptable where it is deliberately chosen and bounded, such as
`kui.kafka.consume.*`; a message payload never is.

## Every endpoint is traced, and nobody instruments one

Spans are named `kui.<context>.<operation>` — `kui.topic.list` — derived from the endpoint's
own operation id, the same name the generated OpenAPI document uses. An endpoint that
declares no operation id falls back to `GET /clusters/{clusterId}/topics`, which works but
reads like a URL rather than like an operation. The fallback is a safety net, not a supported
state: `KuiInterceptors.missingOperationIds` is the check each service runs over its own
endpoints so the fallback stays unused.

Outbound calls get a client span of their own inside the request's span, with `traceparent`
injected, so when a page is slow the trace shows whether the time went in KUI or in the
system it called.

## Health endpoints: what to probe, and what a 503 means

Every KUI service serves three endpoints, and the first thing to get right is that the first
two answer **different questions**:

| Endpoint | The question | Configure it as |
| --- | --- | --- |
| `GET /health/live` | should this process be **restarted**? | the liveness probe |
| `GET /health/ready` | can this serve requests **now**? | the readiness probe |
| `GET /capabilities` | what can it currently do, per cluster? | not a probe; the gateway polls it |

**`/health/live` never depends on an upstream.** A service whose schema registry is down is
not broken — it has a broken dependency, and restarting it will not fix the registry.
Wiring liveness to an upstream turns one outage into two, because every replica
restart-loops for as long as the upstream is down. It returns 200 unless the process should
be restarted, and its body carries a flag and a timestamp and nothing else, deliberately, so
that nobody can make a restart decision depend on anything more.

**`/health/ready` returning 503 means "take me out of rotation", not "restart me".** The
body is a `ReadinessReport` listing **every** check, not only the failing ones: knowing that
one upstream out of four is down is a different situation from knowing the only check there
is has failed.

```json
{"ready":false,
 "checks":[{"name":"config","healthy":true,"detail":null},
           {"name":"schema-registry","healthy":false,"detail":"connection refused"},
           {"name":"connect","healthy":false,"detail":"timeout"}],
 "at":"2026-09-03T10:11:12.000Z"}
```

`"detail":"timeout"` is reserved and means the check did not answer inside its own budget,
which is a different cause from a check that answered no. Every check has a timeout — two
seconds unless the service says otherwise — and the endpoint has a total budget of three
seconds on top. Checks run in parallel, so three one-second checks answer in about one
second rather than three. A check that hangs, or one that throws, is reported as a failed
check; it never makes the endpoint itself fail, because a broken probe is indistinguishable
from a broken service.

All three endpoints are unauthenticated and allow-listed (`ARCHITECTURE.md` §13): a probe
has no credentials and cannot be given any. They are also excluded from
`kui.http.server.duration` and from request logging — a probe every second would dominate
the histogram and drown the log, and neither would have told anyone anything.

A service whose readiness flips reports it immediately. It is the gateway's registry that
applies a debounce, not the service, so that "is it up" and "should the UI grey this out"
stay two separate decisions.

## Two Prometheus endpoints, and why they are different

This confuses people, so it is worth being explicit (ADR-009):

| | `kui.telemetry.prometheusPort` | the metrics service's `/metrics` |
| --- | --- | --- |
| What it exposes | **KUI's own** telemetry: request durations, upstream latency, circuit state | **your Kafka clusters'** metrics, which KUI collects on your behalf |
| Who reads it | your monitoring system, watching KUI's health | your monitoring system, or KUI's own dashboards |
| When it exists | M0 | M8 (`kui-metrics-service`) |

They are never the same endpoint and one is never a substitute for the other. If KUI is
slow, the first one tells you; if a broker is slow, the second one does.

## What happens when telemetry itself fails

Nothing that affects a request.

- If the OTLP collector is unreachable at startup, the process **starts anyway** with an
  exporter that drops spans. Telemetry is never a startup dependency: a monitoring outage
  must not take KUI down with it.
- If the SDK cannot be configured at all, KUI falls back to recording nothing rather than
  failing to boot.
- Exporter errors are logged at WARN and swallowed. They never fail a request.

This is a deliberate asymmetry against the configuration loader, which *does* fail the
start (see `configuration.md`). A wrong configuration means KUI would behave wrongly; a
missing collector only means you cannot watch it.
