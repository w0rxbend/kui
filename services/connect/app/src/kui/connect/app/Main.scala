package kui.connect.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.config.UrlPolicy
import kui.connect.api.ConnectApi
import kui.http.ServiceMain

/** The connect service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about Kafka Connect.
  *
  * ==It starts even when nothing is configured==
  *
  * A deployment where no cluster has a `connect` block starts this process, serves its routes, and reports
  * every cluster as `not_configured`. It does **not** refuse to start, and that is deliberate: this service
  * is optional, and a process that exited because an optional feature was unused would restart-loop in every
  * deployment that simply does not run Kafka Connect — which is most of them — turning "you have no Connect
  * cluster" into a crashing container.
  *
  * ==The address policy==
  *
  * Worker URLs are checked against the same `UrlPolicy` every other upstream address is, read from
  * `KUI_ALLOW_PRIVATE_UPSTREAMS` exactly as `ServiceMain` reads it for the configuration loader. A Connect
  * worker is the upstream most likely to live at `http://kafka-connect:8083` inside a Compose network, and a
  * policy that refused that address here — after the loader had accepted it — would be a service that cannot
  * reach the Connect cluster it just logged at startup.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      ConnectApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        IO.delay(sys.env)
          .toResource
          .flatMap(environment =>
            ConnectWiring.make[IO](
              config.clusters,
              UrlPolicy.fromEnv(environment),
              config.rbac,
              telemetry,
              principals,
              logger
            )
          )
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
