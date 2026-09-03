package kui.http.health

import cats.Parallel
import cats.effect.kernel.{Clock, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given
import kui.contracts.capability.ServiceCapabilities
import kui.contracts.health.{CheckResult, LivenessReport, ReadinessReport}

/** The three endpoints every KUI service exposes, implemented once.
  *
  * Kubernetes, Docker Compose and the gateway's capability registry all learn about a service through these,
  * so getting them subtly wrong in one service is a class of bug that surfaces as "why does that one pod keep
  * restarting". They live in `libs/http` rather than in each service's contract because they are
  * infrastructure: nothing about them is specific to topics or clusters.
  *
  * ==Liveness and readiness are not the same question==
  *
  * This distinction is the whole point of having two endpoints, and it is the one most often got wrong:
  *
  *   - **`/health/live` asks "should this process be restarted".** It never depends on an upstream. A service
  *     whose schema registry is down is not broken — it is a service with a broken dependency, and restarting
  *     it will not fix the registry. Making liveness depend on an upstream turns one outage into two, because
  *     every replica restart-loops while the upstream is down.
  *   - **`/health/ready` asks "can this serve requests now".** It does depend on upstreams, and a `false`
  *     answer means "take me out of rotation", not "restart me".
  *
  * ==They are unauthenticated==
  *
  * A probe has no credentials and cannot be given any (`ARCHITECTURE.md` §13). These three endpoints are
  * allow-listed and carry no principal requirement, even in a service that is otherwise configured with a
  * principal codec.
  */
object HealthEndpoints {

  val LivePath: String = "/health/live"
  val ReadyPath: String = "/health/ready"
  val CapabilitiesPath: String = "/capabilities"

  /** The three endpoints, ready to be served.
    *
    * @param checks
    *   what this service considers a prerequisite for serving. They run in parallel, each inside its own
    *   timeout, inside a total budget.
    * @param capabilities
    *   what this service can currently do, per cluster. Recomputed per request rather than cached, because
    *   the gateway polls it precisely to learn when the answer changes.
    */
  def make[F[_]: {Temporal, Parallel}](
      checks: List[ReadinessCheck[F]],
      capabilities: F[ServiceCapabilities]
  ): List[ServerEndpoint[Any, F]] =
    List(live[F], ready[F](checks), capabilitiesEndpoint[F](capabilities))

  // ---------------------------------------------------------------------------------------------

  private def live[F[_]: Temporal]: ServerEndpoint[Any, F] =
    endpoint.get
      .in("health" / "live")
      .out(jsonBody[LivenessReport])
      .errorOut(jsonBody[ErrorEnvelope])
      .name("health.live")
      .description("Whether the process should be restarted. Never depends on an upstream.")
      .serverLogicSuccess[F](_ => Clock[F].realTimeInstant.map(LivenessReport.at))

  private def ready[F[_]: {Temporal, Parallel}](checks: List[ReadinessCheck[F]]): ServerEndpoint[Any, F] =
    endpoint.get
      .in("health" / "ready")
      .out(statusCode.and(jsonBody[ReadinessReport]))
      .errorOut(jsonBody[ErrorEnvelope])
      .name("health.ready")
      .description("Whether the service can serve requests now, and which checks say otherwise.")
      .serverLogicSuccess[F](_ => report(checks).map(r => (statusOf(r), r)))

  private def capabilitiesEndpoint[F[_]](
      capabilities: F[ServiceCapabilities]
  ): ServerEndpoint[Any, F] =
    endpoint.get
      .in("capabilities")
      .out(jsonBody[ServiceCapabilities])
      .errorOut(jsonBody[ErrorEnvelope])
      .name("capabilities")
      .description("What this service can currently do, per cluster.")
      .serverLogicSuccess[F](_ => capabilities)

  // ---------------------------------------------------------------------------------------------

  /** Runs every check at once, inside the total budget, and reports what came back.
    *
    * Parallel and not sequential, because three checks of a second each would otherwise take three seconds
    * and a probe with a two-second deadline would call the service dead. The total budget exists on top of
    * the per-check timeouts as a backstop: a check whose timeout was configured to something absurd still
    * cannot hold the endpoint open indefinitely, and the checks that did answer are reported rather than
    * lost.
    */
  def report[F[_]: {Temporal, Parallel}](checks: List[ReadinessCheck[F]]): F[ReadinessReport] =
    for {
      results <- checks
        .parTraverse(_.bounded)
        .timeoutTo(ReadinessCheck.TotalBudget, checks.map(check => CheckResult.timedOut(check.name)).pure[F])
      now <- Clock[F].realTimeInstant
    } yield ReadinessReport.of(results, now)

  /** 200 when every check passed, 503 otherwise.
    *
    * 503 and not 500: "I am working but cannot serve you right now" is what a load balancer and an
    * orchestrator both know how to act on, and 500 would suggest a fault in the request.
    */
  def statusOf(report: ReadinessReport): StatusCode =
    if report.ready then StatusCode.Ok else StatusCode.ServiceUnavailable

  /** The paths a server should exclude from request metrics and request logging.
    *
    * A liveness probe every second would dominate the duration histogram and drown the log, and neither would
    * have told anyone anything.
    */
  val paths: Set[String] = Set(LivePath, ReadyPath, CapabilitiesPath)
}
