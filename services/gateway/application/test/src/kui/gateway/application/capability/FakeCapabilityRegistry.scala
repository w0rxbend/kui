package kui.gateway.application.capability

import java.time.Instant

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import fs2.Stream

import kui.contracts.capability.{CapabilityChange, CapabilityEntry, CapabilityKey, CapabilityState}
import kui.kernel.ServiceId

/** A `CapabilityRegistry` that records instead of deciding.
  *
  * Every report is stored and published verbatim: no debounce, no fold, no clock. Suites about *other*
  * components — the readiness poller, the proxy routes, the capability endpoints — need to assert what
  * was reported to the registry, and would otherwise have to reason about a ten-second debounce window
  * that has nothing to do with what they are testing.
  *
  * ==Why this is not in `libs/testkit`==
  *
  * The task asked for it there so that other lanes could reuse it. It cannot go there: `CapabilityRegistry`
  * is a gateway type, ADR-041 rule A5 forbids a library module from depending on a service module,
  * and `./mill checkArchitecture` enforces it. Putting the fake in `libs/testkit` would mean either moving
  * the registry out of the gateway — which ADR-041 §1a explicitly decided against — or breaking the check.
  * It lives in the gateway's own test module instead, and `services.gateway.api.test` depends on that
  * module so the two suites that need it can share one fake.
  */
final class FakeCapabilityRegistry[F[_]: Async] private (
    states: Ref[F, Map[CapabilityKey, CapabilityState]],
    published: Ref[F, Vector[CapabilityChange]],
    probes: Ref[F, Vector[ServiceId]],
    probe: Ref[F, ServiceId => F[Unit]]
) extends CapabilityRegistry[F] {

  def snapshot: F[Map[CapabilityKey, CapabilityState]] = states.get

  def state(key: CapabilityKey): F[CapabilityState] =
    states.get.map(_.getOrElse(key, CapabilityState.NotConfigured))

  def changes: Stream[F, CapabilityChange] = Stream.evalSeq(published.get.map(_.toList))

  def report(key: CapabilityKey, next: CapabilityState): F[Unit] =
    states.modify(current => (current.updated(key, next), current.get(key))).flatMap { previous =>
      published
        .update(_ :+ CapabilityChange(CapabilityEntry(key, next, Instant.EPOCH), previous))
        .void
    }

  def probeNow(service: ServiceId): F[Unit] =
    probes.update(_ :+ service) *> probe.get.flatMap(_.apply(service))

  def attachProbe(next: ServiceId => F[Unit]): F[Unit] = probe.set(next)

  /** Everything reported, oldest first. */
  def reported: F[List[CapabilityChange]] = published.get.map(_.toList)

  /** Every service `probeNow` was called for. */
  def probedServices: F[List[ServiceId]] = probes.get.map(_.toList)

  def reset: F[Unit] = states.set(Map.empty) *> published.set(Vector.empty) *> probes.set(Vector.empty)
}

object FakeCapabilityRegistry {
  def apply[F[_]: Async]: F[FakeCapabilityRegistry[F]] =
    for {
      states <- Ref.of[F, Map[CapabilityKey, CapabilityState]](Map.empty)
      published <- Ref.of[F, Vector[CapabilityChange]](Vector.empty)
      probes <- Ref.of[F, Vector[ServiceId]](Vector.empty)
      probe <- Ref.of[F, ServiceId => F[Unit]](_ => Async[F].unit)
    } yield new FakeCapabilityRegistry[F](states, published, probes, probe)
}
