# Running KUI with Docker Compose

**These are development and test environments, not production deployments.** The signing key has a
default written into the compose file, there is no TLS, and the session cookie is served without
`Secure` so that plain HTTP on `localhost` works. Each of those is wrong for anything anyone else
can reach.

Build the images first:

```
./mill deployment.docker.__.build
```

## Two shapes, and why both exist

| File                            | What runs                                | What it demonstrates                            |
| ------------------------------- | ---------------------------------------- | ----------------------------------------------- |
| `docker-compose.allinone.yml`   | One container, everything inside it       | The fastest possible start                      |
| `docker-compose.yml`            | The gateway and the cluster service, apart | Fault isolation between real processes          |

They run the same code. That is ADR-005's whole argument, and it is why the distributed environment
is worth having even though the all-in-one one starts faster: the all-in-one process is a single
failure domain, so it can show you a *feature* degrading but it cannot show you a *process* dying.

## The fastest start

```
docker compose -f deployment/compose/docker-compose.allinone.yml up -d
open http://localhost:8080/ui/
docker compose -f deployment/compose/docker-compose.allinone.yml down -v
```

## The distributed environment

All four commands assume you are in the repository root.

### 1. Bring it up

```
docker compose -f deployment/compose/docker-compose.yml up -d
```

Two containers. Only `kui-gateway` publishes a port; `kui-cluster` is reachable only from inside the
compose network, which is the same rule `ARCHITECTURE.md` §14 states for a real deployment — a
service must not be exposed outside the cluster network.

Check that the gateway can reach the service, and that a request really does travel through it:

```
$ curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
available
$ curl -s 'localhost:8080/api/v1/ping?message=hi' | jq -r .message
hi
```

The second one is the interesting one. `/api/v1/ping` is not a gateway endpoint: it is the cluster
service's `/internal/v1/ping`, derived from the contract that service published, called over HTTP
with a signed principal header that the service verified before answering.

### 2. Stop one service — the fault-isolation demo

```
docker compose -f deployment/compose/docker-compose.yml stop kui-cluster
```

Wait about ten seconds, which is one readiness poll interval, and ask again:

```
$ curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
unavailable
$ curl -s localhost:8080/api/v1/info | jq -r .authType
disabled
```

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

Runs the whole sequence and exits non-zero if any step does not produce what it should. CI runs it
so that a broken compose file is caught even when the browser-level E2E job is skipped.

## The gateway starts even when nothing else does

The gateway has no mandatory upstream, deliberately. It is the only thing a browser can reach, so a
gateway that refused to start until every service was healthy would turn one service's outage into a
blank page — at exactly the moment an operator needs a working UI to find out what is wrong.

You can check that claim directly by starting the stack with no service at all:

```
$ docker compose -f deployment/compose/docker-compose.yml up -d --scale kui-cluster=0
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/ui/
200
$ curl -s localhost:8080/api/v1/capabilities | jq -c '.entries[0] | {service:.key.service, status:.state.status, reason:.state.reason}'
{"service":"cluster","status":"unavailable","reason":"UPSTREAM_UNAVAILABLE"}
```

The UI is served and the capability document says the truth. This is why `depends_on` in
`docker-compose.yml` uses `required: false`: it orders startup when the service is there and does
not gate the gateway when it is not.

## Seeing traces cross a process boundary

```
docker compose \
  -f deployment/compose/docker-compose.yml \
  -f deployment/compose/docker-compose.observability.yml \
  up -d

curl -s 'localhost:8080/api/v1/ping?message=trace-me' > /dev/null
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
| `kui-allinone.yaml`  | `kui-allinone`  | Where to listen and telemetry. No addresses, no keys — see below   |
| `otel-collector.yaml`| the collector   | Receive on 4317 and 4318, print everything                         |

**The one thing the first two must agree about is `kui.gateway.principalKeys`.** It looks like a
gateway setting and it is not: it is the shared key set of one deployment. The gateway signs the
`X-Kui-Principal` header with the newest key whose `notBefore` has passed, and every service accepts
any key in the set (ADR-020). Both files name the same key id and read the same secret from
`KUI_PRINCIPAL_KEY`, which Compose passes to both containers. Get this wrong and every call the
gateway makes comes back `401`.

The all-in-one file has no keys at all and that is correct: nothing is signed when nothing leaves
the process. Pointing the all-in-one image at `kui.yaml` works too, and earns two warnings about the
half of the file it is ignoring.

Every key can also be set as an environment variable — `kui.server.port` becomes `KUI_SERVER_PORT` —
or as a flag. Flags beat the environment, which beats the file, which beats the defaults.

## Useful variables

| Variable             | Default              | What it does                                   |
| -------------------- | -------------------- | ---------------------------------------------- |
| `KUI_PORT`           | `8080`               | The host port the gateway is published on      |
| `KUI_VERSION`        | `0.1.0-SNAPSHOT`     | The image tag to run                           |
| `KUI_PRINCIPAL_KEY`  | a development string | The shared signing secret                      |

```
KUI_PORT=9090 docker compose -f deployment/compose/docker-compose.yml up -d
```

## Not here

No Kafka broker: M1 adds it, and until then the cluster service has no cluster to talk to. No schema
registry, Kafka Connect, ksqlDB, Prometheus or LDAP — each arrives with the milestone that needs it.
