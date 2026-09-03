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
    store: Ref[F, Map[ServiceId, CapabilityInputs]]
) {

  /** Applies one observation, refolds, and reports the result. */
  def update(service: ServiceId)(observe: CapabilityInputs => CapabilityInputs): F[Unit] =
    for {
      inputs <- store.updateAndGet(current =>
        current.updated(service, observe(current.getOrElse(service, CapabilityInputs.unknown)))
      )
      now <- Clock[F].realTimeInstant
      key = CapabilityKey(service, None)
      previous <- registry.snapshot.map(_.get(key))
      folded = CapabilityFold.fold(
        previous,
        inputs.getOrElse(service, CapabilityInputs.unknown),
        now,
        config.degradedP95Threshold
      )
      _ <- registry.report(key, folded)
    } yield ()

  /** What is currently known about one service. Used by tests and by `probeNow`'s caller. */
  def inputs(service: ServiceId): F[CapabilityInputs] =
    store.get.map(_.getOrElse(service, CapabilityInputs.unknown))
}

object CapabilitySignals {

  def make[F[_]: Async](
      config: RegistryConfig,
      registry: CapabilityRegistry[F],
      services: List[ServiceId]
  ): F[CapabilitySignals[F]] =
    for {
      store <- Ref.of[F, Map[ServiceId, CapabilityInputs]](
        services.map(_ -> CapabilityInputs.unknown).toMap
      )
      signals = new CapabilitySignals[F](config, registry, store)
      // Every configured service is reported before the first poll runs, as degraded-and-starting. A
      // service that is missing from the snapshot and one that is present but not yet checked look
      // identical to a browser, and the second is the truth: the gateway knows about it and has not
      // asked it anything yet.
      _ <- services.traverse_(service => signals.update(service)(identity))
    } yield signals
}
