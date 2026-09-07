# Running KUI with Docker Compose

**These are development and test environments, not production deployments.** The signing key has a
default written into the compose file, there is no TLS, and the session cookie is served without
`Secure` so that plain HTTP on `localhost` works. Each of those is wrong for anything anyone else
can reach.

Build the backend images first, if you have a JDK. The interface's image is not one of them — Mill
never builds the browser bundle — but both compose files carry a `build:` stanza, so the first
`docker compose up` builds whatever is missing.

```
./mill deployment.docker.__.build
```

## Two shapes, and why both exist

| File                            | What runs                                       | What it demonstrates                     |
| ------------------------------- | ----------------------------------------------- | ---------------------------------------- |
| `docker-compose.allinone.yml`   | The backend in one container, and the interface  | The fastest possible start               |
| `docker-compose.yml`            | The gateway and all six services, apart, and the interface | Fault isolation between real processes |

They run the same code. That is ADR-005's whole argument, and it is why the distributed environment
is worth having even though the all-in-one one starts faster: the all-in-one process is a single
failure domain, so it can show you a *feature* degrading but it cannot show you a *process* dying.

## The fastest start

```
docker compose -f deployment/compose/docker-compose.allinone.yml up -d --wait
open http://localhost:8090/ui/
curl -s localhost:8080/api/v1/capabilities | jq
docker compose -f deployment/compose/docker-compose.allinone.yml down -v
```

**Both stacks here serve the interface, and it is a second container in each.** Since ADR-048 the
browser bundle is a separate image built from the pnpm workspace under `frontend/`, and the KUI jar
contains none of it — so `/ui/` is served by nginx on `8090`, which proxies `/api/` to KUI so that
the two share an origin (ADR-019). KUI's own port stays published on `8080` because every claim
below is made against the API, and a `curl` is a better witness than a screenshot.

Neither image is published to a registry, and the interface is not even a Mill target — Mill never
builds the browser bundle, which is what makes the two halves separable. Both compose files carry a
`build:` stanza, so the first `docker compose up` builds what it needs; `./mill deployment.docker.__.build`
beforehand makes the backend half instant.

There is no Kafka broker in either file. For a stack with a broker and data in it, use
`deployment/quickstart/quickstart.sh`.

## The distributed environment

All four commands assume you are in the repository root.

### 1. Bring it up

```
docker compose -f deployment/compose/docker-compose.yml up -d --wait
open http://localhost:8090/ui/
```

Eleven containers: `kui-frontend` and `kui-gateway`, which publish a port each, plus `kui-cluster`,
`kui-topic`, `kui-message`, `kui-consumer`, `kui-schema` and `kui-metrics`, which publish none, plus
the three things they now have something to say about — a single-node Kafka broker (`kafka`), a
Prometheus JMX exporter beside it (`kafka-metrics`) and a Schema Registry (`schema-registry`) —
which publish none either. The six services are reachable only from inside the compose network,
which is the same rule `ARCHITECTURE.md` §14 states for a real deployment — a service must not be
exposed outside the cluster network.

**The broker is new, and it is here for one reason.** This stack ran with `clusters: []` for three
milestones because its subject is process isolation rather than Kafka. M7 ended that: the throughput
endpoint had to be shown answering `ok` with real numbers, and no assertion about measuring
something can be made against nothing to measure. `kui-service.yaml` therefore declares two clusters
on that one broker — `measured`, which has the exporter named under `kui.metrics.sources`, and
`unmeasured`, which has no entry at all — and `smoke.sh` asserts both answers. The pair is the
point: whether KUI can measure a cluster is a fact about the deployment's configuration and not
about the broker, and until now only the refusal could be produced — which is why a metrics
service containing no adapter satisfied every clause of M7's old exit criterion.

Six is every contract the gateway holds. It used to be five: `services/schema` was in
`ServiceContracts.byService`, had a `deployment.docker.schema` image target nothing built, and
appeared in neither `kui.yaml` nor this compose file — so the gateway published none of its routes
and the smoke test could not notice, because a contract with no address is missing from both sides
of an addresses-against-containers comparison. `smoke.sh` now reads the contract set as well, and
`docker-compose.yml` has no room left for a service that is declared and unreachable.

`kui-metrics` answers both ways here, which it could not do before: `ok` with a series for the
`measured` cluster, and a 200 saying `not_configured` for `unmeasured`, whose dashboard cards then
keep their written "not measured" sentence (ADR-032). It is here whichever way it answers, because
the gateway derives a service's public routes from the contract it holds *and* the address it was
given — so a metrics container that is absent is not a quiet feature, it is a feature the browser
cannot tell apart from an outage.

This is the stack that grows. M8 and M9 each add a service beside those six, and
`docker-compose.yml` carries a commented slot naming the container and the address each of
`kui-alerts`, `kui-connect` and `kui-ksql` will take, so that adding one is a copy of `kui-consumer`
and two more edits rather than a reshaping of the file. `kui-metrics` is that recipe already
applied, and is the worked example to read beside it.

Check that the gateway can reach every service, and that a request really does travel through one:

```
$ curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[] | "\(.key.service) \(.state.status)"'
cluster available
consumer available
message available
metrics available
schema available
topic available
$ curl -s localhost:8080/api/v1/clusters | jq -r .clusters.status
ok
```

The second one is the interesting one. The gateway serves `/api/v1/clusters` itself, but it cannot
answer it alone: the rows come from the cluster service's own list endpoint, derived from the
contract that service published, called over HTTP with a signed principal header that the service
verified before answering. `ok` means that call happened and came back fresh. If the cluster service
were unreachable the status would read `stale` or `unavailable` instead, and the gateway would still
answer — which is the whole point of the next section.

### 2. Stop one service — the fault-isolation demo

```
docker compose -f deployment/compose/docker-compose.yml stop kui-cluster
```

Wait about ten seconds, which is one readiness poll interval, and ask again:

```
$ curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[] | "\(.key.service) \(.state.status)"'
cluster unavailable
consumer available
message available
metrics available
schema available
topic available
$ curl -s localhost:8080/api/v1/info | jq -r .authType
disabled
```

Note what did *not* change: the other five services are untouched, because they are five other
processes. The topics screen, the message browser and the consumer-group screens all keep working
while the cluster list degrades. That is the statement the all-in-one shape cannot make at all, and
it is why these services have `main`s and images of their own rather than only running inside the
assembly.

That is the demo, and it is the most convincing thing in this repository. A process died. The
gateway noticed by itself, without anybody reporting it, and it is still serving: the UI still
loads, `/api/v1/info` still answers, and the capability document now says which part of the product
is unavailable and why. The browser dims the affected navigation entry and leaves it clickable with
an explanation, rather than removing it or showing an error page.

`kui-cluster` deliberately has **no restart policy**. Docker will not quietly bring it back while
you are looking at it, and the E2E test that automates this sequence cannot race one.

### 3. Start it again

```
docker compose -f deployment/compose/docker-compose.yml start kui-cluster
```

Ten seconds later it is `available` again. Nobody pressed anything: the gateway polls, and recovery
is just a poll that succeeded.

### 4. Logs, and tearing down

```
docker compose -f deployment/compose/docker-compose.yml logs -f kui-gateway
docker compose -f deployment/compose/docker-compose.yml down -v
```

### All of it as a script

```
./deployment/compose/smoke.sh
```

Runs the whole sequence and exits non-zero if any step does not produce what it should. Its
capability check is derived from `/api/v1/capabilities` rather than from a list written into the
script, so a service added to `kui.yaml` and to the compose file is checked without anybody
remembering this line — which is how `kui-metrics` stayed unreachable through a whole milestone
while the script passed. It also reads the gateway's *contract* set out of
`ServiceContracts.byService` and refuses to start a stack that declares a service it does not run,
which is the half that check was missing: `kui-metrics`'s defect survived one service over in
`services/schema` precisely because a contract with no address is absent from both sides of the
addresses-against-containers equality. And it checks that every image the stack names is already on
the machine, so a build step somebody skipped is reported by name instead of as `pull access denied`
against a registry KUI publishes nothing to — a check that now refuses to pass on an empty list,
which it used to do, because a `for` over nothing runs no iterations and reports no failure.

And it asserts the measurement. Five hundred records are produced to `smoke-traffic` and read back,
so that there is something to measure; the exporter is then asked for **every line shape the
Prometheus reader reads** — thirteen of them, name and labels together — `measured`'s throughput is
awaited until it answers `ok` with at least one bucket carrying a rate that is not null, and
`unmeasured`'s is asserted to be `not_configured`. The last of those is the one M7's criterion used
to consist of on its own, and on its own it is satisfied by a service that was never built.

The traffic step is not decoration. Kafka creates most of its MBeans on the first event they count,
so an idle broker publishes only the three broker-wide `BrokerTopicMetrics` meters, which exist
from boot and read `0.0`. Checked on this stack before the step was added: no `RequestMetrics` bean
for either request kind, and no per-topic byte rate at all, because the broker had no topics. Every
card beyond throughput would have been asserted against a broker that had nothing to say.

CI runs it in the end-to-end job, right after the seven backend images are built, so that a broken
compose file is caught by the same run that builds the artefacts it describes. That list is derived
from this compose file rather than written into the workflow, because it was written into the
workflow, said five, and was wrong for a milestone. The interface's image is not one of the seven
and Compose builds it here, which adds a few minutes to a cold run and nothing to a warm one.

The interface is unaffected throughout. It is a static file server that proxies `/api/`, so it has
nothing to lose when a KUI service dies: the page still loads, and what an operator sees is the
capability document's verdict rendered as a dimmed navigation entry with an explanation, rather than
an error page. `smoke.sh` asserts that while `kui-cluster` is stopped.

## When the first measured bucket arrives, and what was and was not reproduced

`smoke.sh` waits ninety seconds for `measured`'s throughput to carry a bucket with a rate in it.
Wave 4 reported that wait failing deterministically — twice, from a torn-down stack — and attributed
it to a scrape loop that does not survive its first failed pass. **Neither half of that stands up.**

The failure did not reproduce. Three consecutive `smoke.sh` runs from a torn-down stack, on the tree
that was said to fail, on images built from it: `buckets carrying a measured rate: yes` every time.

And the loop plainly survives a failed pass. `kui-metrics` starts before the exporter does — the
exporter waits on the broker's health and this process waits for nothing — so its first scrape
always fails on a cold stack. Timed here: the process starts, logs one WARN three seconds later, and
the next pass about thirty-four seconds after that succeeds; the first bucket is filled roughly
forty seconds after start, with sixty seconds of the budget unspent.

Forced to the worst case the ordering can produce — `kui-metrics` alone for three minutes with no
broker and no exporter at all — the log reads five WARNs, `circuit … is now open`, then `halfopen`
and `open` again on each failed probe. When the exporter finally appears it goes `halfopen`,
`closed`, and files a bucket **two seconds** after Compose called the exporter healthy. The whole
recovery is bounded by one circuit `resetTimeout` (30s) plus one scrape interval, against a 90s
budget.

So `kui-metrics` now carries `depends_on: kafka-metrics`, and that is **headroom rather than a
repair**: it removes the failed scrapes and the circuit from a cold start, so the WARNs an operator
sees on a first `docker compose up` are about something real. It is not the fix for the failure
above, because nothing here reproduced that failure, and a patch justified by a story is how
`degraded` came to be asserted where `available` was meant.

What remains unexplained is the original report, and it is left unexplained rather than closed. Two
differences between that machine and this one are worth trying before anyone reaches for the
timeout: `smoke.sh` now produces traffic before it measures, which adds ten to twenty seconds of
wall clock before the bucket wait starts, and the broker's own health can take up to 135s
(`retries: 24` at `interval: 5s`) on a cold or loaded host, every second of which is a failed
scrape.

## The gateway starts even when nothing else does

The gateway has no mandatory upstream, deliberately. It is the only KUI process a browser's requests
reach, so a gateway that refused to start until every service was healthy would turn one service's
outage into a blank page — at exactly the moment an operator needs a working UI to find out what is
wrong.

You can check that claim directly by starting the stack with no service at all:

```
$ docker compose -f deployment/compose/docker-compose.yml up -d --scale kui-cluster=0
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/capabilities
200
$ curl -s localhost:8080/api/v1/capabilities | jq -c '.entries[] | select(.key.service == "cluster") | {service:.key.service, status:.state.status, reason:.state.reason}'
{"service":"cluster","status":"unavailable","reason":"UPSTREAM_UNAVAILABLE"}
```

The gateway answers and the capability document says the truth. This is why `depends_on` in
`docker-compose.yml` uses `required: false`: it orders startup when the service is there and does
not gate the gateway when it is not.

## Seeing traces cross a process boundary

```
docker compose \
  -f deployment/compose/docker-compose.yml \
  -f deployment/compose/docker-compose.observability.yml \
  up -d

curl -s localhost:8080/api/v1/clusters > /dev/null
docker compose -f deployment/compose/docker-compose.yml -f deployment/compose/docker-compose.observability.yml \
  logs otel-collector
```

An OpenTelemetry collector that prints everything it receives and forwards it nowhere, so no Jaeger
and no vendor account is needed. What to look for is one trace id under two different service names:

```
-> service.name: Str(kui-gateway)
   Trace ID       : 75d4f1cff05dc6ab625c8fd190cd08f0
   Name           : GET cluster
-> service.name: Str(kui-cluster)
   Trace ID       : 75d4f1cff05dc6ab625c8fd190cd08f0
   Name           : kui.cluster.cluster.ping
```

The gateway's span and the service's span, one trace, two processes. That is the evidence that the
`traceparent` propagation of GW-002 works across a network hop — something the all-in-one shape
cannot demonstrate at all, because it has no hop.

It is an overlay file rather than a Compose profile because turning tracing on has two halves: start
the collector, *and* tell both KUI processes to export to it. A profile can add a container but
cannot change another container's environment, so doing it that way would have meant leaving the
exporter switched on all the time — and every ordinary `docker compose up` would then spend its
first minute retrying connections to a collector that is not running.

## The configuration files

| File                 | Read by         | What is in it                                                     |
| -------------------- | --------------- | ----------------------------------------------------------------- |
| `kui.yaml`           | `kui-gateway`   | The service addresses, the poll interval, CORS, the shared keys    |
| `kui-cluster.yaml`   | `kui-cluster`   | Where to listen, telemetry, and the same shared keys               |
| `kui-service.yaml`   | the other five  | `kui-topic`, `kui-message`, `kui-consumer`, `kui-schema` and `kui-metrics` all mount it: shared keys, cursor key, the two clusters, and `kui.metrics.sources` for one of them |
| `kui-allinone.yaml`  | `kui-allinone`  | Where to listen and telemetry. No addresses, no keys — see below   |
| *(none)*             | `kui-frontend`  | Nothing on disk: the nginx block is written at start from `KUI_GATEWAY_URL`, `KUI_BASE_PATH` and `KUI_BUILD_VERSION` (`../frontend/`) |
| `otel-collector.yaml`| the collector   | Receive on 4317 and 4318, print everything                         |

**The one thing the first three must agree about is `kui.gateway.principalKeys`.** It looks like a
gateway setting and it is not: it is the shared key set of one deployment. The gateway signs the
`X-Kui-Principal` header with the newest key whose `notBefore` has passed, and every service accepts
any key in the set (ADR-020). All three name the same key id and read the same secret from
`KUI_PRINCIPAL_KEY`, which Compose passes to every container. Get this wrong and every call the
gateway makes comes back `401`.

Every one of them is loaded through the shipped loader by `ShippedConfigurationSuite` in
`libs/config`, so a file this stack mounts and no suite reads is a state this repository was in for
three milestones and is not in now. `kui-service.yaml` was the one that was missing, and it is the
one five containers read.

The all-in-one file has no keys at all and that is correct: nothing is signed when nothing leaves
the process. Pointing the all-in-one image at `kui.yaml` works too, and earns two warnings about the
half of the file it is ignoring.

Every key can also be set as an environment variable — `kui.server.port` becomes `KUI_SERVER_PORT` —
or as a flag. Flags beat the environment, which beats the file, which beats the defaults.

## Useful variables

| Variable             | Default              | What it does                                   |
| -------------------- | -------------------- | ---------------------------------------------- |
| `KUI_PORT`           | `8080`               | The host port the gateway is published on      |
| `KUI_FRONTEND_PORT`  | `8090`               | The host port the interface is published on    |
| `KUI_VERSION`        | `0.1.0-SNAPSHOT`     | The image tag to run                           |
| `KUI_PRINCIPAL_KEY`  | a development string | The shared signing secret                      |
| `KUI_BASE_PATH`      | *(empty)*            | Mount point when an outer proxy serves KUI under a sub-path |

```
KUI_PORT=9090 docker compose -f deployment/compose/docker-compose.yml up -d
```

## Not here

No Kafka broker: neither file here starts one, so the cluster service has no cluster to talk to and
every screen reports that rather than failing. Use `deployment/quickstart/quickstart.sh` for a stack
with a broker and data in it. No schema registry, Kafka Connect, ksqlDB, Prometheus or LDAP — each
arrives with the milestone that needs it, into the slot `docker-compose.yml` already names for it.
