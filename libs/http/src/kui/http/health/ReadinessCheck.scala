package kui.http.health

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.kernel.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*

import kui.contracts.health.CheckResult

/** One thing a service checks before it says it is ready.
  *
  * @param name
  *   what is being checked, in words an operator can act on. It ends up in a 503 body.
  * @param run
  *   the check itself. It answers with a `CheckResult` rather than failing, because a check that throws would
  *   fail the whole readiness endpoint and turn "one upstream is down" into "the probe is broken".
  * @param timeout
  *   the check's own budget. Mandatory — see [[ReadinessCheck.DefaultTimeout]].
  */
final case class ReadinessCheck[F[_]](name: String, run: F[CheckResult], timeout: FiniteDuration) {

  def withTimeout(d: FiniteDuration): ReadinessCheck[F] = copy(timeout = d)

  /** The check, guaranteed to answer inside its budget.
    *
    * A check that exceeds it is reported `healthy = false` with `detail = "timeout"` rather than failing the
    * endpoint, and a check that raises is reported as failed with its message. Both are information an
    * operator wants; neither is a reason for the probe itself to break.
    */
  def bounded(using Temporal[F]): F[CheckResult] =
    run
      .timeoutTo(timeout, CheckResult.timedOut(name).pure[F])
      .handleError(error =>
        CheckResult.failed(name, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
      )
}

object ReadinessCheck {

  /** Every check gets one, and two seconds is it unless a service says otherwise.
    *
    * The timeout is not optional because the failure it prevents is the worst kind: a readiness probe that
    * hangs looks, to Kubernetes and to the gateway, exactly like a service that is thinking about it — so
    * nothing is restarted, nothing is taken out of rotation, and the outage is invisible. A check that
    * answers "I do not know" in two seconds is strictly better than one that never answers.
    */
  val DefaultTimeout: FiniteDuration = 2.seconds

  /** The total budget for all checks together. They run in parallel, so this is a little more than the
    * slowest single check rather than the sum of them.
    */
  val TotalBudget: FiniteDuration = 3.seconds

  def apply[F[_]](name: String, run: F[CheckResult]): ReadinessCheck[F] =
    ReadinessCheck(name, run, DefaultTimeout)

  /** A check built from a plain yes-or-no answer. */
  def boolean[F[_]: cats.Functor](name: String, check: F[Boolean], whenFailing: String): ReadinessCheck[F] =
    apply(
      name,
      check.map(ok => if ok then CheckResult.healthy(name) else CheckResult.failed(name, whenFailing))
    )

  /** A check that always passes. The M0 sample service has one of these, and so does any service whose
    * readiness is simply "the process started".
    */
  def always[F[_]: cats.Applicative](name: String): ReadinessCheck[F] =
    apply(name, CheckResult.healthy(name).pure[F])
}
