package kui.http.upstream

import cats.effect.kernel.Concurrent
import cats.effect.std.Semaphore
import cats.syntax.all.*

import kui.kernel.PositiveInt

/** A hard cap on how many calls to one upstream are in flight at once.
  *
  * The name comes from ships: a hull divided into sealed compartments so that a breach floods one of them and
  * not the vessel. The failure it prevents is the one that makes a slow dependency into an outage — a schema
  * registry that takes thirty seconds per call, KUI's threads all waiting on it, and the topic list, which
  * needs nothing from the registry, timing out too.
  *
  * When the compartment is full the call **fails immediately** rather than queueing. That is the decision
  * worth understanding: an unbounded queue in front of a slow upstream does not reduce the load, it hides it,
  * and the requests that eventually get through are answering questions the user stopped caring about a
  * minute ago. Failing fast keeps the latency of the calls that do run honest, and hands the caller a typed
  * error it can turn into a degraded section of the UI.
  */
trait Bulkhead[F[_]] {

  /** Runs `call` if there is room, and fails with [[BulkheadFullException]] if there is not. */
  def protect[A](call: F[A]): F[A]

  /** How many calls could start right now. Used by the suites and by nothing else. */
  def available: F[Long]
}

/** Thrown when the bulkhead is full. */
final case class BulkheadFullException(upstream: String)
    extends Exception(s"$upstream already has as many calls in flight as it is allowed")

object Bulkhead {

  def make[F[_]: Concurrent](upstream: String, maxConcurrent: PositiveInt): F[Bulkhead[F]] =
    Semaphore[F](maxConcurrent.value.toLong).map { permits =>
      new Bulkhead[F] {
        def available: F[Long] = permits.available

        def protect[A](call: F[A]): F[A] =
          permits.tryPermit.use {
            case true => call
            case false => Concurrent[F].raiseError[A](BulkheadFullException(upstream))
          }
      }
    }
}
