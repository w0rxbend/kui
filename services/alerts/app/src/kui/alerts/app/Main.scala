package kui.alerts.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.alerts.api.AlertsApi
import kui.http.ServiceMain

/** The alerts service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about alerts.
  *
  * ==It starts even when nothing is configured==
  *
  * A deployment with no `kui.clusters[]` still starts this process and still serves its routes. There is
  * nothing to evaluate and the feeds are empty, which is a true statement about a deployment with no
  * clusters; a process that exited because it had nothing to watch would restart-loop in exactly the
  * deployment an operator is halfway through configuring.
  *
  * ==Why `kui.alerts` has no on-switch to read here==
  *
  * Every rule reads a fact this product already measures, so alerting is on for every deployment and the only
  * question is where the lines are drawn — `AlertsConfig`'s own scaladoc, and the reason there is no
  * `enabled` key for this file to consult.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      AlertsApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        AlertsWiring
          .make[IO](config.clusters, config.alerts, config.rbac, telemetry, principals, logger)
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
