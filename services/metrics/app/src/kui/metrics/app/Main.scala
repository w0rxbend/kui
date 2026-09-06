package kui.metrics.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.http.ServiceMain
import kui.metrics.api.MetricsApi

/** The metrics service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about metrics.
  *
  * ==It starts even when nothing is configured, and today that is every deployment==
  *
  * No cluster can be measured by this build, because no collector exists in it yet. The process still starts,
  * still serves its routes, and reports every cluster as `not_configured` — which is the fact the dashboard
  * needs in order to keep its written "not measured" sentences rather than draw six empty axes. A process
  * that exited because it had nothing to measure would restart-loop in every deployment there is.
  *
  * ==Why there is no URL policy parameter here==
  *
  * Unlike the schema service's `Main`, this one dials nothing, so there is no upstream address to check. The
  * `kui.metrics.sources` URLs are already `SafeUrl`s — the loader applied the same SSRF rule to them that it
  * applies to a registry address — and the collector that will use them arrives with the milestone that needs
  * the policy passed down.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      MetricsApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        MetricsWiring
          .make[IO](config.clusters, config.metrics, telemetry, principals, logger)
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
