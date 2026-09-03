package kui.gateway.application.cluster

import java.time.Instant

import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all.*

/** The last value that worked, and when it did.
  *
  * ADR-043 §2 requires a direct service-to-service call to have a cached last-known fallback, and to never be
  * the reason its caller becomes unavailable. This is that cache: one value, replaced whole on every success,
  * read on every failure.
  *
  * A `Ref` rather than one of `libs/cache`'s primitives, deliberately. There is no refresh loop here, no time
  * to live, no supervisor and no per-key eviction - the value is refreshed by ordinary traffic and is only
  * ever read when the upstream has already failed. The gateway also has no `libs.cache` dependency, and
  * acquiring one for a single `Ref` would put a cache library on the classpath of the process that is
  * supposed to hold no state. If a second consumer appears, promote it then.
  */
final class LastKnown[F[_], A] private (ref: Ref[F, Option[(A, Instant)]]) {

  /** The last successful value and the instant it was fetched, or nothing if none ever was. */
  def get: F[Option[(A, Instant)]] = ref.get

  /** Replaces whatever was there. A partial merge would produce a row set that never existed. */
  def put(value: A, at: Instant): F[Unit] = ref.set(Some((value, at)))
}

object LastKnown {

  def of[F[_]: Concurrent, A]: F[LastKnown[F, A]] =
    Ref.of[F, Option[(A, Instant)]](None).map(new LastKnown[F, A](_))
}
