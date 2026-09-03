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
