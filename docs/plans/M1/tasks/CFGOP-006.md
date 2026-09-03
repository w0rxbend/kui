# CFGOP-006 — All-in-one and Compose: a broker, store settings, the encryption key

- **ID:** CFGOP-006
- **Title:** All-in-one and Compose: a broker, store settings, the encryption key
- **Milestone / Feature:** M1 / OT-004, OT-007, OT-008, KU-010
- **Owner role:** Infrastructure Lead
- **Context / service:** `apps/allinone`, `deployment/*`
- **Size:** M
- **Dependencies / blocked by:** CLAPI-005

## Goal (user value)

A developer who has never seen this repository runs one command, waits, opens a browser, and
sees a populated dashboard for a real Kafka cluster — including the metadata store creating its
own topics on that same broker. DEVPLAN §9.13 puts a fifteen-minute bound on that experience;
this task is what makes the bound achievable.

## Scope

1. A Kafka broker in both Compose topologies, as a first-class service with a health check.
2. `kui.clusters[]` filled in, in all three configuration files, pointing at that broker.
3. `kui.store.*` filled in, pointing at the same broker, with a development encryption key.
4. `apps/allinone`'s composition root wiring the cluster service's new modules —
   `services.cluster.infrastructure`, `libs.kafka`, `libs.cache` and the store — in the ADR-042
   bootstrap order that CLAPI-005 established for the standalone service.
5. The all-in-one integration suite from DEVPLAN §7: the whole graph boots against one broker
   that is both the store cluster and the managed cluster, readiness flips only after replay
   completes, and `GET /api/v1/clusters` reports the cluster.
6. `deployment/compose/smoke.sh` and `deployment/compose/README.md` updated to the new topology.

## Non-goals

- **No secured broker in Compose.** The development topology is PLAINTEXT. Secured connections
  are proven by CFGOP-004's containers and CFGOP-005's parity suite, where the assertion is
  mechanical; making a developer's first run depend on generated certificates would trade the
  fifteen-minute promise for a demonstration that is already covered.
- **No separate store cluster.** One broker is both the store cluster and the managed cluster,
  which is what `docs/operations/metadata-store.md` already says the development Compose file
  does. The two-cluster case is documented, not deployed.
- **No production values.** Every file this task touches is a development environment and says so
  at the top, in the shape the existing files already use. `replicationFactor: 1` and a literal
  encryption key are correct here and wrong everywhere else, and the comments must say which.
- **No new image.** The three images of INFRA-001 are unchanged; only what is mounted into them
  changes.
- **No `__kui_audit`.** DEVPLAN §10 D7: M1 creates two topics and documents three.
- **No E2E scenario.** CFGOP-007 adds the fault-isolation and dead-cluster scenarios on top of
  what this task deploys.

## Design references

ADR-042 (the store's topics, its bootstrap ordering and its failure behaviour), ADR-005
(all-in-one: every service in one process, called in memory), ADR-010 (macwire composition
roots), M1 DEVPLAN §7 row "All-in-one integration" and §9.13, `docs/operations/metadata-store.md`
§1 and §2 (the settings and topic table this task must configure consistently with),
CFGOP-001 and CFGOP-002 (the cluster keys), STORE-004 (the `kui.store.*` keys — read it before
writing YAML, because it owns the spellings), INFRA-002 and the header comments in
`deployment/compose/*.yml`, which are the tone and the level of explanation to match.

## Files to create or change

```
deployment/compose/docker-compose.yml                (a kafka service; kui-cluster depends on it)
deployment/compose/docker-compose.allinone.yml       (the same broker)
deployment/compose/kui.yaml                          (clusters[]; the gateway needs no store)
deployment/compose/kui-cluster.yaml                  (clusters[] and store.*)
deployment/compose/kui-allinone.yaml                 (clusters[] and store.*)
deployment/compose/smoke.sh                          (assert the cluster and the store)
deployment/compose/README.md                         (what the broker is for; how to reset it)
apps/allinone/src/kui/allinone/AllInOneWiring.scala  (the cluster service's new modules)
apps/allinone/src/kui/allinone/AllInOneConfig.scala  (clusters and store reach the wiring)
apps/allinone/test/src/kui/allinone/StoreBootstrapSuite.scala          (new)
apps/allinone/test/src/kui/allinone/AllInOneWiringSuite.scala          (change)
build.mill                                           (apps.allinone.test gains libs.testkit.jvm
                                                      if it does not have it)
docs/operations/metadata-store.md                    (the "development Compose" paragraph)
README.md                                            (the quick start, now with a broker)
```

## The broker service

Added to both Compose files, identically, so a reader who has seen one recognises the other:

```yaml
  # The Kafka cluster this development stack manages, and the cluster KUI keeps its own metadata
  # in. One broker is both, which is the simplest thing that demonstrates the whole product and is
  # exactly what docs/operations/metadata-store.md calls the development arrangement. A real
  # deployment usually separates them, so that KUI's own state survives the cluster people are
  # looking at going down.
  kafka:
    image: apache/kafka:4.1.0            # pinned; never `latest` (see build.mill Versions)
    container_name: kui-kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"
      # One broker, so every internal topic has to be replicated once and no more. These are the
      # settings a single-node broker cannot start without, not tuning.
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      CLUSTER_ID: "kui-development-cluster"
    networks: [kui]
    healthcheck:
      # The broker answers its own admin protocol. `--bootstrap-server` against itself is the
      # cheapest question that only a serving broker can answer.
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 20s
    restart: unless-stopped
```

No published port by default, for the same reason the cluster service has none: nothing outside
the network needs to reach it, and a published 9092 collides with the broker a developer is
already running. A commented `ports:` block with `29092:9092` and the one extra
`KAFKA_ADVERTISED_LISTENERS` entry it needs is included, with a sentence saying what to
uncomment it for.

`kui-cluster` (and `kui` in the all-in-one file) gains
`depends_on: { kafka: { condition: service_healthy } }` — **gated, not merely ordered**, unlike
its own `depends_on` on the gateway. The asymmetry is deliberate and the comment must say why:
the gateway has no mandatory upstream on purpose, but the cluster service's own store *is*
mandatory at start-up (ADR-042: replay before ready), so starting it before its broker exists
would only produce a slower version of the same failure.

## The configuration files

`kui-cluster.yaml` and `kui-allinone.yaml` gain, with the same comments in both:

```yaml
  clusters:
    - name: "Local"
      bootstrapServers: ["kafka:9092"]
      # No `security` key at all means PLAINTEXT, which is what this development broker speaks.
      # docs/operations/configuration.md has a worked example for each of the secured modes.

  store:
    # KUI keeps its own metadata in Kafka (ADR-042). Pointing it at the same broker it manages is
    # the simplest arrangement and the one this development stack uses; production usually points
    # it somewhere else so KUI's state outlives the cluster being looked at.
    topicPrefix: "__kui_"
    replicationFactor: 1        # ONE BROKER. Use 3 anywhere that matters.
    minInSyncReplicas: 1        # must be < replicationFactor in a real deployment
    encryptionKey: "env:KUI_STORE_ENCRYPTION_KEY"
    kafka:
      bootstrapServers: ["kafka:9092"]
```

`kui.yaml` (the gateway's) gains **only** the `clusters:` list, and not `store:`. The gateway has
no store client and must never acquire one — rule A8 forbids it a Kafka client at all. It needs
the cluster list for nothing in M1 either; it is included so that the reference file shows the
whole shape an operator writes, and a comment says the gateway ignores it. *If* CFGOP-001's
unknown-key handling would make an unread section an error for the gateway process, the section
is omitted from `kui.yaml` instead and the comment says that; the loader is shared, so it will
not, but check rather than assume.

The encryption key is passed to the container from the Compose file:

```yaml
      # A development key, in the file, in version control. That is wrong everywhere except here.
      # Anything that reaches a network gets this from a secret store, and
      # docs/operations/metadata-store.md's key section says what happens if you lose it: every
      # stored secret becomes permanently unreadable. There is no recovery path and there cannot be.
      KUI_STORE_ENCRYPTION_KEY: ${KUI_STORE_ENCRYPTION_KEY:-a2V5LWZvci1kZXZlbG9wbWVudC1vbmx5LW5vdC1yZWFsIQ==}
```

The literal is 32 bytes base64-encoded. STORE-002 owns the key's expected encoding; if it expects
something else, follow STORE-002 and keep the comment.

## The all-in-one wiring

`AllInOneWiring.services` gains the cluster service's real dependency graph, in the order ADR-042
fixes and CLAPI-005 implements for the standalone process. **Do not reimplement that order
here.** `ClusterWiring` is the composition root the cluster service already exposes and that
CLAPI-005 extends; the all-in-one calls it. If CLAPI-005's entry point takes the store and the
cluster list as arguments, the all-in-one supplies them from its own loaded configuration; if it
constructs them itself from a `KuiConfig`, the all-in-one passes the `KuiConfig`. Either way
there is exactly one implementation of the bootstrap order in the codebase, and this task does
not become the second.

The one thing that is genuinely this task's: **readiness**. `AllInOne`'s `/health/ready` must
report not-ready until the store replay has completed, and its liveness must report alive
throughout. A process that is live but not ready during a 20-second replay is correct; one that
is ready before the replay finishes would serve an empty cluster list as though it were the
truth, and one that is not *live* would be restarted by the orchestrator half-way through
(R-2 in the DEVPLAN's risk register is precisely this failure mode).

## Decisions taken here

**D-1 — one broker, both roles.** Stated above. It also gives CFGOP-007's dead-cluster scenario
something honest to contrast with: a second, deliberately unreachable cluster entry against a
host name that does not resolve.

**D-2 — the broker's data is not persisted to a named volume.** `docker compose down` loses it,
including the `__kui_*` topics. That is right for a development stack: "how do I get back to a
clean state" has to have a one-command answer, and an operator who wants persistence in a real
deployment is not using this file. `README.md` says so in one sentence, next to the `down -v`
command it already documents.

**D-3 — the all-in-one gets the same broker rather than an embedded one.** There is no embedded
Kafka in the dependency matrix and adding one would be a second broker implementation to keep
current. One more container in the all-in-one Compose file is the cheaper answer, and it keeps
the all-in-one shape honest: it is one KUI process, not one process for everything.

**D-4 — `KUI_STORE_ENCRYPTION_KEY` has a default in the Compose file.** Requiring a developer to
generate a key before their first run costs a step and a failure mode ("KUI won't start and I
don't know why"), and the key protects nothing in a stack whose broker is on the same bridge
network. The comment above it is what makes this safe: it is three lines and it says exactly
where the rule changes.

**D-5 — the all-in-one integration suite starts its broker with CFGOP-004's fixture.** Not with a
Compose file. `apps.allinone.test` already runs the process in-JVM; adding
`KafkaFixture(KafkaTopology.Plaintext)` gives it a broker in the same way every other integration
suite gets one, and keeps `docker compose` in the E2E module where it belongs.

**D-6 — `smoke.sh` gains two assertions and no more:** that `GET /api/v1/clusters` reports the
`local` cluster with `status: "online"`, and that the broker's topic list contains `__kui_config`
and `__kui_files`. The script is the thing a human runs to check a stack by hand; it stays short
enough to read.

## Library coordinates

None new in production code. `apps.allinone.test` gains `libs.testkit.jvm` on its `moduleDeps` if
it does not already have it, for `KafkaFixture`.

## Acceptance criteria

```
$ ./mill apps.allinone.test
kui.allinone.StoreBootstrapSuite:
  + the process is live but not ready while the store is replaying
  + readiness flips only after replay completes
  + __kui_config and __kui_files are created with the documented settings
  + GET /api/v1/clusters reports the configured cluster as online
  + a pre-existing __kui_config with cleanup.policy=delete fails startup naming topic, setting, expected and found
```

```
$ docker compose -f deployment/compose/docker-compose.allinone.yml up -d --wait
$ open http://localhost:8080/ui/
# a populated dashboard: one cluster, one broker, a scrapedAt timestamp

$ ./deployment/compose/smoke.sh
...
clusters: local online (1 broker)
store: __kui_config, __kui_files present
OK
```

```
$ docker compose -f deployment/compose/docker-compose.yml up -d --wait
$ docker compose -f deployment/compose/docker-compose.yml exec kafka \
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic __kui_config --from-beginning --max-messages 10 | grep -i password
# no output: exit criterion "a console-consumer dump contains no plaintext password"
```

The last command is the operator-facing form of the secrets-at-rest exit criterion. Its
mechanical form is STORE-009's; both are required, because the one an operator can run by hand is
the one they will believe.

## Tests required

- `StoreBootstrapSuite` (new, MUnit + `munit-cats-effect` + `KafkaFixture`), the five cases in the
  acceptance output. The incompatible-topic case creates `__kui_config` with
  `cleanup.policy=delete` *before* starting the process, and asserts the message names all four
  facts — that exact assertion is an M1 exit criterion, so assert the four substrings, not the
  whole sentence, so a wording change does not fail it.
- `AllInOneWiringSuite` (change): the graph builds with clusters and a store configured, and
  **also** builds with `kui.store` absent (the file-adapter path), because "with
  `kui.store.kafka.*` unset … everything else in M1 still passes" is its own exit criterion.
- `ComposeConfigSuite` (new, in `apps.allinone.test`): parses the three YAML files in
  `deployment/compose/` and asserts each loads through `KuiConfigSource` with no problems. It is
  ten lines and it is the only thing standing between a typo in a comment-heavy YAML file and a
  developer's first run failing. (The M0 files were not covered; add all three.)

## Observability

Startup logs one line per phase, at INFO, so a fifteen-minute first run has a readable narrative:
configuration loaded (with the cluster count), store client opened (with the bootstrap servers,
never the credentials), topics validated or created (naming each), replay started, replay
completed (with the record count and the elapsed time), ready. The replay's elapsed time is also
recorded as a metric — ADR-042's consequences section wants a real number from the
implementation, and CFGOP-008 copies it there.

## Degraded behavior

- **Broker not up when KUI starts:** Compose's health gate normally prevents it. If it happens
  anyway, the process fails at start-up with the store's named error and the bootstrap servers in
  the message (ADR-042; R-2's mitigation), never a hang and never an empty registry.
- **Broker stops while KUI runs:** clusters keep resolving from the last replayed state, the
  store's capability reports `Degraded` with a reason, and writes are rejected rather than lost
  (STORE-008). This is fault-injection scenario 3 in DEVPLAN §7; CFGOP-007 exercises it in a
  browser, and `README.md` documents `docker compose stop kafka` as the way to see it by hand.
- **No store configured at all:** the file adapter is used, the write endpoint reports
  `NotConfigured`, and everything else works. `AllInOneWiringSuite` covers it.

## Docs to update

`deployment/compose/README.md`: what the broker is, that its data is not persisted, how to reset
it, and how to watch the store degrade. `docs/operations/metadata-store.md`: the development
arrangement paragraph now describes something that exists. `README.md`: the quick start, with the
one command and what the developer should see, inside the fifteen minutes DEVPLAN §9.13 promises
— and time it, on a cold Docker cache, and record the number.

## Deviations

Recorded during implementation.
