# INFRA-002 — Docker Compose development environment

- **ID:** INFRA-002
- **Title:** Docker Compose development environment
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Infrastructure Lead
- **Size:** M
- **Dependencies / blocked by:** INFRA-001

## Goal (user value)

`docker compose up` gives the **distributed** shape — separate gateway and service containers
— which is what makes the fault-isolation demo real: `docker stop kui-cluster` genuinely kills
a process, and the UI must survive it.

## Scope

1. `deployment/compose/docker-compose.yml` with:
   - `kui-gateway` (port 8080 published) configured to reach `http://kui-cluster:8081`;
   - `kui-cluster` (no published port — it is an internal service, per
     `ARCHITECTURE.md` §14 "services must not be exposed outside the cluster network");
   - an OpenTelemetry Collector container with a console exporter, so a developer can see
     traces without external infrastructure (optional profile `observability`);
   - a shared network, `restart: unless-stopped` **disabled** for `kui-cluster` (the E2E test
     stops it deliberately and must not race a restart policy);
   - health checks and `depends_on: condition: service_healthy` for the gateway.
2. `deployment/compose/kui.yaml` (extended from CFG-001) with the distributed service URLs and
   a matching `principalKeys` entry shared by both containers.
3. A second file `docker-compose.allinone.yml` running only `kui-allinone`, for the fastest
   possible start.
4. `deployment/compose/README.md`: the four commands (up, down, stop one service, logs) and
   what each demonstrates.

## Non-goals

No Kafka container (M1 adds it). No Schema Registry, Connect, ksqlDB, Prometheus, LDAP (their
milestones). No production compose file — this is a development and E2E environment and says
so at the top of the file.

## Design references

`docs/ROADMAP.md` M0 exit criterion (the `docker stop kui-cluster` scenario), ADR-020 (both
containers share a signing key, which is what makes the signed-principal path real here and
not just in tests), `ARCHITECTURE.md` §1 (distributed shape), feature matrix KU-008.

## Files to create

```
deployment/compose/docker-compose.yml
deployment/compose/docker-compose.allinone.yml
deployment/compose/kui.yaml
deployment/compose/kui-cluster.yaml
deployment/compose/otel-collector.yaml
deployment/compose/README.md
```

## Acceptance criteria

```
$ docker compose -f deployment/compose/docker-compose.yml up -d
$ curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
available
$ curl -s 'localhost:8080/api/v1/ping?message=hi' | jq -r .message
hi
$ docker compose stop kui-cluster
$ sleep 12 && curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
unavailable
$ curl -s localhost:8080/api/v1/info | jq -r .authType        # the gateway still answers
disabled
$ docker compose start kui-cluster
$ sleep 12 && curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
available
$ docker compose down -v
```

This command sequence **is** the milestone's fault-isolation criterion at the API level;
E2E-002 adds the browser assertions on top of it.

## Tests required

No MUnit suite here; E2E-002 drives this stack. CI runs the command sequence above as a
smoke script (`deployment/compose/smoke.sh`) in the `docker build` stage so a broken compose
file is caught even if the E2E job is skipped.

## Observability

With the `observability` profile, traces from both containers appear in one collector log,
demonstrating that the correlation id and `traceparent` propagation of GW-002 actually work
across processes. Include one example trace in `deployment/compose/README.md`.

## Degraded behavior

The gateway must start and serve the UI even when `kui-cluster` never becomes healthy — assert
this by starting the stack with `kui-cluster` scaled to zero and checking that `/ui/` and
`/api/v1/capabilities` still answer (the second listing the service as `Unavailable`).

## Docs to update

`deployment/compose/README.md`; `README.md` (the compose quick start and the fault-isolation
demo, which is the most convincing thing a newcomer can run in two minutes).

## Deviations

### 1. Both containers listen on 8080

The spec has the gateway reaching `http://kui-cluster:8081`. They are separate containers on their
own network, so there is no port to collide over, and one port for every KUI image is one less thing
for an operator to remember. The published host port is still 8080 and is the only one.

### 2. Observability is an overlay file, not a Compose profile

The spec asks for `--profile observability`. Turning tracing on has two halves — start the
collector, *and* tell both KUI processes to export to it — and a Compose profile can add a container
but cannot change another container's environment. Doing it with a profile would have meant leaving
`kui.telemetry.otlpEndpoint` switched on in the configuration files permanently, so every ordinary
`docker compose up` would spend its first minute retrying connections to a collector that is not
running and filling the log with exporter stack traces (observed, before the change).

`docker-compose.observability.yml` is a standard Compose overlay that turns both halves on together
and leaves the default start silent. Same result, one extra `-f`.

### 3. `depends_on` is `service_healthy` with `required: false`

The spec asks for `depends_on: condition: service_healthy`, and separately requires that the gateway
start and serve `/ui/` and `/api/v1/capabilities` when `kui-cluster` is scaled to zero. A plain
health gate cannot satisfy both. `required: false` orders startup when the service is present and
does not gate the gateway when it is not, which is also the behaviour the gateway is designed for:
it has no mandatory upstream, on purpose (GW-001). The scale-to-zero case was verified and the
transcript is in the implementation report.

### 4. A fourth configuration file

The spec lists `kui.yaml` and `kui-cluster.yaml`. `kui-allinone.yaml` was added because the
all-in-one image follows the same "configuration is mounted, never baked in" convention as the other
two (INFRA-001), and pointing it at `kui.yaml` earns two warnings about the service addresses and
signing keys it is ignoring. Three shapes, three files, no warnings that mean nothing.

### 5. The collector receives on gRPC as well as HTTP

The spec's example uses port 4318. The KUI images export over gRPC on 4317: the OpenTelemetry Java
SDK's autoconfigure defaults `otel.exporter.otlp.protocol` to `grpc`, and pointing it at 4318
produces a stream of "endpoint port is likely incorrect" warnings and no data (observed). The
collector now listens on both, and the overlay points KUI at 4317.

## Defects found and fixed while building this

Two, both in other lanes' modules, both committed separately so they can be reviewed on their own.

### `kui.server.devInsecureCookies` could not be set in a configuration file

`KuiConfigSource` decodes the key but it was missing from the list of recognised keys, so a flag or
`KUI_SERVER_DEVINSECURECOOKIES` worked while the same setting written in YAML was rejected as
`is not a KUI configuration key`. The Compose environment needs it in the file — plain HTTP on
localhost means the session cookie cannot carry `Secure` — which is how it surfaced. One line, plus
a regression test in `ValidationSuite`.

### The gateway never chose its log format

`LogbackSelection` translates `kui.telemetry.logFormat` into the system property Logback reads, and
it has to run before the first logger is created. `kui-cluster`'s `Main` called it; the gateway's
never did, so `logFormat: text` did nothing in the one process an operator is most likely to be
reading the logs of. Observed directly: `KUI_TELEMETRY_LOGFORMAT=text` on the gateway container
produced JSON.

It also lived in `services/cluster/app`, which is why `apps/allinone` had to import it from another
service's composition root — an edge that rule A4 forbids for the gateway and that nothing forbade
here only because `apps` is not a service. It has been hoisted to `libs/observability`, beside the
two Logback configuration files it chooses between, and all three `Main`s now call it.
