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
