package kui.gateway.application.capability

import cats.effect.kernel.{Async, Clock, Ref}
import cats.syntax.all.*

import kui.contracts.capability.CapabilityKey
import kui.kernel.ServiceId

/** Where the four raw inputs of the fold are kept between observations.
  *
  * The poller learns about readiness and latency; the circuit feed learns about breakers; each arrives on its
  * own schedule and knows nothing about the other. The fold, though, is a function of *all four* at once
  * (ADR-039 §1) — that is what makes it decidable as a table rather than as a sequence of patches. This is
  * the small piece of state that reconciles the two: each source updates the field it knows about, and every
  * update refolds everything and reports the result.
  *
  * Keeping it here rather than inside the registry is deliberate. The registry's job is publishing
  * transitions and it should not care where a state came from; a future input — a maintenance flag, a health
  * signal a service pushes — is added to `CapabilityInputs` and to this class, and the registry does not
  * change.
  */
final class CapabilitySignals[F[_]: Async] private (
    config: RegistryConfig,
    registry: CapabilityRegistry[F],
    store: Ref[F, Map[CapabilityKey, CapabilityInputs]]
) {

  /** Applies one observation, refolds, and reports the result.
    *
    * Keyed by `(service, cluster)` rather than by service alone. The service-wide key is
    * `CapabilityKey(service, None)`, and it is the one a transport failure and a circuit breaker write to: a
    * connection that could not be made says something about the service, never about one of its clusters.
    * What a service reports *about* one cluster is content of a healthy answer, and belongs on that cluster's
    * key.
    *
    * The distinction is the whole reason this signature changed. Folding a cluster's state into the service's
    * meant one unreachable Kafka cluster dimmed the cluster feature for everybody, which is exactly the
    * failure DEVPLAN D4 and ADR-039 §6 exist to prevent.
    */
  def update(key: CapabilityKey)(observe: CapabilityInputs => CapabilityInputs): F[Unit] =
    for {
      inputs <- store.updateAndGet(current =>
        current.updated(key, observe(current.getOrElse(key, CapabilityInputs.unknown)))
      )
      now <- Clock[F].realTimeInstant
      previous <- registry.snapshot.map(_.get(key))
      folded = CapabilityFold.fold(
        previous,
        inputs.getOrElse(key, CapabilityInputs.unknown),
        now,
        config.degradedP95Threshold
      )
      // The name travels with the state because the registry is where the browser reads both. A cluster
      // key's name is the display name the owning service reported for it; a service-wide key has none.
      _ <- registry.report(
        key,
        folded,
        inputs.getOrElse(key, CapabilityInputs.unknown).serviceReport.flatMap(_.name)
      )
    } yield ()

  /** The service-wide key of one service, for the callers that only ever mean that one. */
  def updateService(service: ServiceId)(observe: CapabilityInputs => CapabilityInputs): F[Unit] =
    update(CapabilityKey(service, None))(observe)

  /** What is currently known about one key. Used by tests and by `probeNow`'s caller. */
  def inputs(key: CapabilityKey): F[CapabilityInputs] =
    store.get.map(_.getOrElse(key, CapabilityInputs.unknown))

  /** Every key currently known for one service: the service key, plus one per cluster it last reported.
    *
    * The poller reads it to retire a cluster that has disappeared from configuration. A cluster removed by an
    * operator must stop appearing in the switcher; leaving its last state behind is how a deleted cluster
    * stays on screen until someone restarts the gateway.
    */
  def keysOf(service: ServiceId): F[Set[CapabilityKey]] =
    store.get.map(_.keySet.filter(_.service == service))
}

object CapabilitySignals {

  def make[F[_]: Async](
      config: RegistryConfig,
      registry: CapabilityRegistry[F],
      services: List[ServiceId]
  ): F[CapabilitySignals[F]] =
    for {
      store <- Ref.of[F, Map[CapabilityKey, CapabilityInputs]](
        services.map(service => CapabilityKey(service, None) -> CapabilityInputs.unknown).toMap
      )
      signals = new CapabilitySignals[F](config, registry, store)
      // Every configured service is reported before the first poll runs, as degraded-and-starting. A
      // service that is missing from the snapshot and one that is present but not yet checked look
      // identical to a browser, and the second is the truth: the gateway knows about it and has not
      // asked it anything yet.
      _ <- services.traverse_(service => signals.updateService(service)(identity))
    } yield signals
}
