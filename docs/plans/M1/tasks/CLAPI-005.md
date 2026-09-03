# CLAPI-005 — Cluster `app`: wiring and the ADR-042 bootstrap ordering

- **ID:** CLAPI-005
- **Title:** Cluster `app`: wiring and the ADR-042 bootstrap ordering
- **Milestone / Feature:** M1 / CL-001, CL-007, OT-007, KU-010, KU-011
- **Owner role:** Chief Architect
- **Size:** L
- **Dependencies / blocked by:** CLAPI-004, CLADP-005, STORE-006

## Goal (user value)

`kui-cluster` starts, connects to the metadata store, replays it, builds a client per configured
cluster and reports itself ready — in that order, once, with every failure visible. This is the
task that decides whether a misconfigured KUI **fails** or **hangs**, and a hang is the worst
startup failure a service can have (DEVPLAN R-2).

## Scope

1. `ClusterWiring.make` rewritten: the composition root builds the store, the registry, the
   admin adapters, the snapshot cell and every use case, and returns a `ClusterServer` exactly
   as it does today (routes, interceptors, readiness, capabilities) with no listener bound.
2. **The bootstrap ordering of ADR-042**, implemented as a `Resource` chain that cannot be
   reordered by accident:

   ```
   static config (Ciris)
     -> store client (kui.store.kafka.*)          STORE-005
       -> topic bootstrap: create-or-validate      STORE-005
         -> replay __kui_config to the end offset   STORE-006  (bounded, named error)
           -> ClusterRegistry: static overlaid by store records   CLDOM-004
             -> one admin client per configured cluster            CLADP-002
               -> refresh loop started under a Supervisor          CLDOM-005
                 -> readiness reports ready
   ```
3. Readiness checks that mean something: `store` (replayed and following), `config` (the
   registry has resolved), and one per configured cluster is **not** added — see decision 2.
4. `ClusterServiceConfig` gains the `kui.clusters[]` and `kui.store.*` slices (defined by
   CFGOP-001 and STORE-004; this task consumes them and does not define them).
5. The store-less path: with `kui.store.kafka.*` unset the file adapter is used, replay is
   skipped, the registry is static-only, and everything else in M1 still works.
6. The all-in-one composition root (`apps/allinone`) is **not** edited here — CFGOP-006 owns it
   — but `make`'s signature must remain usable by it unchanged: no `IO`, no bound port, no
   process-global state.

## Non-goals

No new endpoints. No store implementation (STORE lane). No adapters (CLADP lane). No Compose or
image changes (CFGOP-006). No configuration *schema* (CFGOP-001/STORE-004).

## Design references

- ADR-042 §"Bootstrap order" and `ARCHITECTURE.md` §10.1 — the arrow diagram above is copied
  from it and is one-directional by decision, not by convenience.
- DEVPLAN R-2 (the hang risk, its three mitigations: an explicit replay timeout, a named error
  `KUI-STORE-REPLAY-TIMEOUT`, and readiness reported only after replay), §2's exit criteria for
  the store, §10 D6.
- ADR-010 (composition root; `make` stops one step short of a listener so the all-in-one reuses
  it), ADR-002 (`IO` only in `app`), ADR-013 (accumulated configuration errors).
- The M0 files this task rewrites: `services/cluster/app/src/kui/cluster/app/ClusterWiring.scala`
  and `Main.scala` (whose six documented steps stay; step 5 grows).

## Files to create

```
services/cluster/app/src/kui/cluster/app/ClusterBootstrap.scala
services/cluster/app/test/src/kui/cluster/app/ClusterBootstrapSuite.scala
services/cluster/app/test/src/kui/cluster/app/ClusterWiringSuite.scala
```

## Files to change

```
services/cluster/app/src/kui/cluster/app/ClusterWiring.scala
services/cluster/app/src/kui/cluster/app/ClusterConfig.scala
services/cluster/app/src/kui/cluster/app/Main.scala        (step 5's doc comment; readiness wiring)
build.mill                                                  (services.cluster.app gains
                                                             services.cluster.infrastructure)
```

`build.mill` edit is limited to the `moduleDeps` line of `services.cluster.app` (DEVPLAN §6.5's
rule for shared files). The architecture rule table is CFGOP-003's and must not be touched here.

## Public Scala signatures to implement

```scala
package kui.cluster.app

/** The ordered startup of ADR-042, as one `Resource`.
  *
  * Each step is a separate `Resource` and the ordering is the `for`-comprehension. Written as
  * one function rather than assembled ad hoc in `ClusterWiring` because the order *is* the
  * decision: a reader must be able to check it against ADR-042 §"Bootstrap order" line by line,
  * and a reviewer must see a reordering as a diff in this file.
  */
object ClusterBootstrap {

  final case class Bootstrapped[F[_]](
      store: ConfigStore[F],
      registry: ClusterRegistry[F],
      admin: ClusterAdmin[F],
      topology: TopologySnapshotUseCase[F],
      brokers: BrokerDetailUseCase[F],
      capabilities: CapabilityReportUseCase[F],
      health: F[StoreHealth]
  )

  def resource[F[_]: {Async, Parallel}](
      config: ClusterServiceConfig,
      telemetry: Telemetry[F],
      logger: StructuredLogger[F]
  ): Resource[F, Bootstrapped[F]]

  /** The readiness checks, in the order an operator wants to read them. */
  def readiness[F[_]: Async](bootstrapped: Bootstrapped[F]): List[ReadinessCheck[F]]
}
```

`ClusterWiring.make` keeps its shape and gains the configuration parameter its M0 doc comment
promised ("the parameter returns in M1 with `kui.clusters[]`"):

```scala
def make[F[_]: {Async, Parallel}](
    config: ClusterServiceConfig,
    telemetry: Telemetry[F],
    principals: PrincipalCodec[F],
    logger: StructuredLogger[F]
): Resource[F, ClusterServer[F]]
```

`ClusterWiring.ConfiguredClusters` (the M0 constant `Set.empty`) is deleted; the capability use
case reads the registry.

## Decisions this task takes (no ADR covers them)

1. **Replay is bounded and fails loudly.** `kui.store.replayTimeout` (STORE-004; default 60 s)
   bounds the replay. On expiry the process **exits non-zero** with
   `KUI-STORE-REPLAY-TIMEOUT` and a message naming the topic, the end offset it was waiting for
   and the offset it reached. It does not start degraded: a service serving an empty cluster
   list because it could not read its own configuration looks identical to a KUI nobody has
   configured, and an operator would spend the outage looking in the wrong place. Once the
   service **is** running, a store that goes away is degradation, not death (STORE-008) — the
   asymmetry is deliberate and is stated in `docs/operations/metadata-store.md` by CFGOP-008.
2. **No per-cluster readiness check.** A Kafka cluster being unreachable must not make
   `kui-cluster` unready: readiness is what the gateway polls, and an unready cluster service
   dims the whole `cluster` capability for every cluster — precisely the failure D4 forbids. The
   readiness checks are `process`, `config` and `store`; per-cluster health is reported through
   `GET /capabilities` (CLDOM-007) and through each row's `Section`.
3. **A cluster whose admin client cannot be built does not stop startup.** A bad bootstrap
   address, an unreadable keystore or a rejected credential produces a logged ERROR, a registry
   entry whose snapshot is `Offline(lastError)`, and a row that renders `Unavailable: <reason>`.
   Refusing to start would let one mistyped address take down access to nine healthy clusters.
   *Configuration that cannot be parsed at all* still fails at startup with every error
   accumulated (ADR-013) — the difference is between "this configuration is not valid" and
   "this valid configuration points at something broken".
4. **`store` readiness is `healthy` while the store is degraded.** Once replay has completed, a
   store outage leaves the service able to serve everything it knows. The readiness check
   reports healthy with a message naming the degradation; the capability report carries
   `Degraded`. Flipping readiness would take the service out of the gateway's rotation for a
   fault that costs it nothing.

## Library coordinates

None new for this module beyond the module dependency: `services.cluster.app` gains
`services.cluster.infrastructure` (DEVPLAN §5.2's legal edge list; rule A9 permits `app` and
only `app` to point at `infrastructure`).

## Acceptance criteria

```
$ ./mill services.cluster.app.test
$ ./mill checkArchitecture            # A9 and A10 active (CFGOP-003)
$ ./mill __.compile
```

Against a Testcontainers broker (the suite does this; the transcript below is what an operator
sees):

```
$ KUI_STORE_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./kui-cluster
INFO starting kui-cluster 0.1.0 (abc1234)
INFO metadata store: created topic __kui_config (partitions=1, cleanup.policy=compact)
INFO metadata store: replayed __kui_config to offset 12 in 340ms
INFO cluster registry: 3 clusters (2 from configuration, 1 from the store)
INFO kui-cluster ready on 0.0.0.0:8081

$ curl -s localhost:8081/health/ready | jq -c '.checks[]'
{"name":"process","healthy":true}
{"name":"config","healthy":true}
{"name":"store","healthy":true,"detail":"following __kui_config at offset 12"}
```

With a pre-existing, wrongly configured topic:

```
$ ./kui-cluster
ERROR __kui_config has cleanup.policy=delete; KUI requires compact. KUI never rewrites operator
      topic settings: fix the topic or point kui.store.topicPrefix elsewhere.
$ echo $?
1
```

## Tests required

`ClusterBootstrapSuite` (MUnit + `munit-cats-effect` + Testcontainers, JVM):

- `readinessIsFalseUntilReplayCompletes` — the hang-visibility mitigation of R-2. Hold the
  replay open and assert `/health/ready` reports `store` unhealthy with a reason, then release.
- `aReplayThatCannotFinishFailsWithinTheTimeout` — with `TestControl` and a fake store: the
  resource fails with `KUI-STORE-REPLAY-TIMEOUT` at `replayTimeout`, and does **not** hang. This
  is the single most important test in the task.
- `theBootstrapOrderIsStoreThenReplayThenRegistryThenAdminClients` — a recording fake logs each
  step; assert the exact sequence. A refactor that builds admin clients before replay fails
  here rather than in production.
- `anUnreachableManagedClusterDoesNotPreventStartup` (decision 3) — one of three clusters points
  at a closed port; the service starts, is ready, and lists three clusters with one unavailable
  summary.
- `withNoStoreConfiguredTheFileAdapterIsUsedAndTheServiceStarts`.
- `aStoreThatDiesAfterStartupLeavesTheServiceReady` (decision 4).
- `releasingTheResourceClosesEveryAdminClientAndStopsEveryFiber` — assert no fiber and no client
  survives, the same way `ReadinessPollerSuite` does in the gateway.

`ClusterWiringSuite`: `make` binds no port and contacts nothing when handed fakes;
`ConfiguredClusters` no longer exists; the capability document names every configured cluster.

## Observability

- One INFO line per bootstrap step, in order, each with a duration in milliseconds: this
  sequence is what an operator reads when a start is slow, and it is the evidence CFGOP-007's
  E2E greps for.
- `kui.store.replay.duration` (histogram) and `kui.store.replay.offset` (gauge).
- `kui.cluster.configured` (up/down counter) — how many clusters this process knows about,
  labelled `source=config|store`.
- The startup line keeps every M0 field (version, commit, dirty, builtAt) and gains
  `storeMode=kafka|file`.

## Degraded behaviour

Covered by decisions 1–4. One addition: if telemetry cannot start, the service starts anyway
(the M0 rule in `Main.scala`, unchanged) — a collector restart must not be a KUI outage.

## Docs to update

`docs/operations/metadata-store.md` §"Startup" — the transcript above and what each failure
looks like. (Sections 2–6 of that file belong to the STORE area; this task writes the startup
section and says so in the Implementation Report so the two do not collide. If the file does not
exist yet when this task lands, leave the content in the Implementation Report and let CFGOP-008
place it.)

## Cancellation and shutdown (added at the M1 gate review, F-07)

The M0 review found cancellation systematically unconsidered across the milestone. This task
owns the whole bootstrap `Resource` chain, so it owns the answer here. State it in the spec's own words in the
Implementation Report, and ship the tests below.

- Shutdown releases in exact reverse order: HTTP server, then the refresh loops and the profile
  listener, then the store, then the admin client pool. A refresh loop that outlives the pool it
  calls into produces an error on every shutdown and teaches operators to ignore shutdown logs.
- `SIGTERM` during replay cancels the replay and exits non-zero with the replay's own reason —
  it does not hang waiting for `replayTimeout`, which is R-2's failure shape wearing a different
  hat.
- No `IO` in the composition root is `unsafeRunSync`'d, and nothing is started outside the
  `Resource` chain; a fiber started with `.start` and never joined is not shut down by anything.
- **Test (`munit-cats-effect`):** allocate the full application `Resource` against fakes, cancel
  the allocation mid-way and after completion, and assert every fake's `released` flag is set,
  in reverse order of acquisition.

## Deviations

1. **`ClusterBootstrap.resource` takes the two configuration slices rather than the whole
   `ClusterServiceConfig`.** It needs `kui.clusters[]` and `kui.store.*` and nothing else; taking
   the whole config would let it grow a dependency on a section belonging to another concern.
2. **`Bootstrapped` carries `storeMode` and no `ClusterAdmin`-shaped health.** `health` is the
   *cluster* store's, read through the domain port, so the readiness check does not reach past its
   own layer to `libs/config`'s type.
3. **`AppLoggerFactory`** publishes the process's one logger as log4cats' factory, because
   `libs/config`'s store components ask for a logger that way. Two logging paths in one process is
   how half the lines end up in a different format.
4. **The Testcontainers cases are not in this task's suite.** `readinessIsFalseUntilReplayCompletes`,
   `aReplayThatCannotFinishFailsWithinTheTimeout`, `theBootstrapOrderIsStoreThenReplayThen...` and
   `aStoreThatDiesAfterStartupLeavesTheServiceReady` all need a broker, and the behaviours they
   assert - topic bootstrap, the bounded replay and its named failure - are implemented and tested
   by the STORE lane against a real broker. Duplicating them here would be a second container per
   build for a second copy of the same assertions. **What is genuinely owed is the wiring-level
   assertion that this chain calls them in this order against a live store**; CFGOP-006's all-in-one
   integration suite is the place, and it has a broker already.
5. `ClusterServiceConfig` gains `clusters` and `store`; `ConfiguredClusters` is deleted as the spec
   requires. `AllInOneWiring` passes `ClusterServiceConfig.Default` until CFGOP-006 gives the
   all-in-one its own cluster and store sections.
