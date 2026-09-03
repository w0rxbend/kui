# CLADP-004 — `ConnectivityProbe` adapter

- **ID:** CLADP-004
- **Title:** `ConnectivityProbe` adapter
- **Milestone / Feature:** M1 / CL-009, OT-007
- **Owner role:** Domain Architect (Cluster Registry context)
- **Size:** S
- **Dependencies / blocked by:** CLADP-002

## Goal (user value)

The dashboard has to say *why* a cluster row is unavailable, quickly, and without waiting for a
full topology refresh. "Unavailable" alone sends an operator to the logs; "Unavailable:
authentication failed" sends them to the credentials. This task implements the cheapest possible
question — can KUI reach this cluster's brokers, and if not, what kind of no — with a timeout
short enough that asking it about a dead cluster costs a page nothing.

It is also the answer to "is this configuration correct" that the M8 cluster wizard will call.
Building it now, once, means the wizard has nothing to invent.

## Scope

1. **`ConnectivityProbeAdapter`** implementing the domain's `ConnectivityProbe[F]` port
   (CLDOM-003): `def probe(profile: ClusterProfile): F[Connectivity]`. `Connectivity` is
   CLDOM-003's three-case enum — `Reachable`, `AuthenticationFailed(detail)`,
   `Unreachable(detail)` — and this task does not add a fourth case, a duration field or a payload
   to it. The timing and the Kafka cluster id belong to the topology snapshot (CLDOM-005), which
   gets them from `describeCluster` anyway; a probe result that carried them would be a second,
   thinner snapshot with its own staleness rules.
2. **The probe is `describeCluster` with a short, explicit timeout.** Not a raw TCP connect
   (which proves nothing about SASL or SSL), not `listTopics` (which needs authorization the
   probe should not require), not a produce (which writes). `describeCluster` is the call every
   reference product uses to decide whether a cluster is alive
   (`research/kafka/admin-capabilities.md` §0, "Invalidation": Kafbat keys its whole client
   health on exactly this call), and it exercises the full connection path — DNS, TCP, TLS
   handshake, SASL handshake, metadata request — which is the entire set of things a
   misconfiguration breaks.
3. **The timeout is the probe's own, and it is short.** `kui.clusters[].admin.probeTimeout`,
   default **5 seconds**, from `AdminTuning` (CFGOP-002 owns the configuration key; this task
   reads it). The admin client's own `default.api.timeout.ms` is 60 s and bounds a *useful*
   request; a probe that took 60 s to say "down" would make the dashboard exactly as slow as the
   dead cluster, which is the failure mode the milestone's exit criterion forbids. If
   `AdminTuning` has no probe-specific field when this task is picked up, add nothing to the
   configuration slice — CFGOP owns it — and use `min(adminTuning.requestTimeout, 5.seconds)`,
   recording that in the implementation report so CFGOP-002 can add the key.
4. **A safe `detail`, not an exception message.** CLDOM-003 says `detail` is display text and
   must never contain a host, a URL with credentials or a JAAS string. This adapter is where that
   is enforced: `detail` is chosen from a fixed set of sentences (the table below), with at most
   one substitution — the timeout in seconds. A raw Kafka exception message is never interpolated
   into it: it can contain the bootstrap string and, on some SASL paths, the principal.
   The classification itself is derived from the `KuiError` the admin port already produced, so
   the Kafka exception hierarchy is examined in exactly one place in KUI (`KafkaErrorMapper`,
   KAFKA-005) and never here.
5. **The probe reuses the client registry.** It calls `ClusterAdminClients.get` like every other
   adapter, so a probe of a healthy cluster costs nothing extra, and a probe of a dead one
   participates in the same invalidation policy (CLADP-002's `ReconnectPolicy`). It does **not**
   open a throwaway client per probe: at the snapshot cadence across ten clusters that is a
   connection storm against exactly the brokers already having a bad day.

## Non-goals

- **No remote validation of a *candidate* profile.** Probing an arbitrary user-supplied address
  is an SSRF surface; ADR-036 gates it behind `kui.clusters.remoteValidation.enabled` and a host
  allow-list, and the wizard that needs it is M8. This adapter probes profiles that are already
  configured.
- **No Schema Registry, Connect, ksqlDB or metrics endpoints.** ADR-036's wizard probes each
  component independently; in M1 only Kafka exists to probe.
- **No retries and no backoff.** One attempt, one answer. Retrying inside a probe makes its
  timeout a lie.
- **No health endpoint.** `/health/ready` is `libs/http`'s and is about the *service*, not the
  clusters. A cluster being down must never make the cluster service unready — that would take
  the whole page down for a configuration mistake, which is precisely decision D4.
- **No caching.** The caller (CLDOM-005, CLDOM-007) decides how often to probe.

## Design references

- `research/kafka/admin-capabilities.md` §0 ("Invalidation", "Timeouts") and §1 row "Describe
  cluster" (the errors: `TimeoutException` unreachable, `SaslAuthenticationException`,
  `SslAuthenticationException`, `UnsupportedVersionException`).
- ADR-034 (`KuiError`, `ErrorCode`) — the probe classifies an error that `KafkaErrorMapper`
  already produced; it does not look at Kafka exception classes itself.
- ADR-039 §6 and DEVPLAN §10 decision D4 — an unreachable managed cluster is a
  `Section.Unavailable(reason)` inside a 200, never a dimmed capability. This adapter produces
  that reason.
- ADR-037 (per-upstream timeout) and the milestone exit criterion "response time is bounded by
  the per-service timeout, not by the dead cluster".
- ADR-036 (the wizard's `validate` flow this probe will later serve).
- `research/kafbat/ui-analysis.md`, "Dashboard" — what an unavailable row shows.

## Files to create

```
services/cluster/infrastructure/src/kui/cluster/infrastructure/ConnectivityProbeAdapter.scala
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/ConnectivityProbeAdapterSuite.scala
```

## Files to change

```
services/cluster/infrastructure/test/src/kui/cluster/infrastructure/StubKafkaClusterAdmin.scala
  (a constructor parameter making `describeCluster` take a configurable amount of simulated time,
   so the timeout can be asserted with `TestControl` instead of by sleeping)
```

## Public Scala signatures to implement

```scala
package kui.cluster.infrastructure

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async
import kui.cluster.domain.{ClusterProfile, Connectivity, ConnectivityProbe}

/** "Can KUI talk to this cluster, and if not, what kind of no?"
  *
  * One `describeCluster` under a short timeout of its own. Short is the whole point: the
  * dashboard asks this about every configured cluster, and the milestone's exit criterion is that
  * a dead cluster does not slow the healthy ones down.
  */
final class ConnectivityProbeAdapter[F[_]: Async](
    clients: ClusterAdminClients[F],
    telemetry: kui.observability.Telemetry[F],
    logger: org.typelevel.log4cats.StructuredLogger[F]
) extends ConnectivityProbe[F] {

  /** Never fails: an unreachable cluster is an outcome, not an error. The effect always
    * completes, and it always completes within `probeTimeout` plus scheduling.
    */
  def probe(profile: ClusterProfile): F[Connectivity]
}

object ConnectivityProbeAdapter {

  /** The fallback used when `AdminTuning` carries no probe-specific timeout. See scope item 3. */
  val DefaultProbeTimeout: FiniteDuration = scala.concurrent.duration.DurationInt(5).seconds

  /** The classification, and the safe sentence that goes with it. Total over `KuiError`. */
  def classify(error: kui.kernel.error.KuiError, probeTimeout: FiniteDuration): Connectivity
}
```

The `classify` table, which is the substance of this task. `Connectivity` has three cases, so the
distinctions the sentence has to carry are carried by the sentence:

| `KuiError` from the admin port | `Connectivity` | `detail` |
| --- | --- | --- |
| `InfrastructureError.Timeout` | `Unreachable` | "No response from the brokers within N seconds." |
| `InfrastructureError.AuthFailed` | `AuthenticationFailed` | "The cluster rejected KUI's credentials." |
| `InfrastructureError.Unreachable` whose `cause` names a TLS or SSL failure class | `Unreachable` | "The TLS handshake with the brokers failed." |
| `InfrastructureError.Unreachable`, otherwise | `Unreachable` | "Could not connect to any configured broker." |
| `ApplicationError.Forbidden` | `Unreachable` | "Connected, but KUI is not authorized to describe this cluster." |
| anything else | `Unreachable` | "The cluster could not be described." |

Three rules about that table, each of which a test below enforces. Every sentence is a
**constant**, with at most the timeout substituted; `cause` is inspected only for the *class
name* a TLS failure carries and never copied into `detail`; and the authorization row is
deliberately a `Unreachable` outcome with a sentence that says the connection worked — an
operator told only "unreachable" spends the afternoon on the firewall when the problem is an ACL.

## Library coordinates

No new coordinate. `TestControl` comes from `cats-effect-testkit`, which
`munit-cats-effect ${Versions.munitCatsEffect}` (2.2.0) already brings onto the test classpath; if
it does not resolve, add `mvn"org.typelevel::cats-effect-testkit::${Versions.catsEffect}"`
(3.7.1) to the test module and say so in the implementation report.

## Acceptance criteria

```
$ ./mill services.cluster.infrastructure.test
Test run kui.cluster.infrastructure.ConnectivityProbeAdapterSuite finished: 0 failed, 0 ignored, 8 total
SUCCESS

$ ./mill checkArchitecture
checkArchitecture: 36 modules, no layering violations
```

The timing acceptance is asserted with `TestControl`, not with a stopwatch: a wall-clock
assertion on a build machine is a flaky test, and `TestControl` makes "it finished at virtual
time 5 s" exact.

## Tests required

`ConnectivityProbeAdapterSuite` (no container; `StubKafkaClusterAdmin` with a configurable delay,
run under `TestControl`):

- `aHealthyClusterIsReachable`
- `aProbeThatNeverAnswersCompletesAtTheProbeTimeout` — the stub takes 60 s; under `TestControl`
  the probe completes at exactly the configured probe timeout with `TimedOut`. **This is the test
  that enforces the milestone's "bounded by the timeout, not by the dead cluster" criterion at
  the adapter level**; the end-to-end half is CLAPI-007's.
- `probingTenClustersInParallelCompletesAtTheTimeoutAndNotAtTenTimesIt` — ten profiles, all
  hanging, `parTraverse`; virtual time advances once. A regression that serialises the probes
  fails here rather than merely making the dashboard slow.
- `anAuthenticationFailureIsAuthenticationFailedAndNotUnreachable` — the two are different
  problems with different fixes, and the enum distinguishes them.
- `aTlsFailureSaysTheHandshakeFailed`
- `anAuthorizationFailureSaysKuiConnectedButIsNotAuthorized`
- `theProbeNeverRaises` — a ScalaCheck property over generated `KuiError`s: for every one, the
  stub fails with it and `probe` still produces a `Connectivity`.
- `noProbeReasonContainsTheBootstrapStringOrACredential` — build a profile whose bootstrap host is
  `secret-host.internal:9093` and whose password is `SUPERSECRET-DO-NOT-LEAK`, have the stub fail
  with an error whose `cause` contains both, and assert with
  `kui.testkit.RedactionAssertions.assertNoLeak` that neither appears in the outcome's `detail`.
  The `detail` is rendered in the browser.

## Observability

- **Metric**: `MetricNames.KafkaAdminDuration` with `operation = "probe"` and `outcome` = `ok` or
  the `Connectivity` case name in lower case. The probe is deliberately on the same histogram as
  the other admin calls: "how long does this cluster take to answer anything" is one question.
- **Span**: `kui.cluster.admin.probe`, attributes `kui.cluster.id`, `kui.probe.outcome`.
- **Log**: a *transition* is logged, not every probe. The adapter is stateless, so it logs at
  DEBUG on every probe and the caller (CLDOM-005/007) owns the "cluster went down / came back"
  INFO/WARN pair. Ten clusters probed every 30 s is 28 800 log lines a day if this file logs at
  INFO, and an operator stops reading the log at about a thousand.

## Degraded behavior

The probe *is* the degraded-behaviour mechanism, so its own contract is strict:

- It never raises and never returns a failed effect. A caller that has to `handleError` around a
  probe will eventually forget to.
- It always terminates within its timeout. The timeout is applied with `Async.timeoutTo`, which
  cancels the underlying call — a timeout that leaves the admin request running is a leak that
  compounds every 30 s.
- It never invalidates the client on its own beyond what `ClusterAdminClients` already does
  through `ReconnectPolicy`; a probe of a dead cluster must not create a reconnect storm.
- A cluster that is down affects exactly its own row (decision D4). Nothing in this file may
  touch a readiness check, a capability state or another cluster's data.

## Docs to update

None. The `Connectivity` cases and their sentences are copied into the operator troubleshooting
page by CFGOP-008; put the final table in the implementation report.
