package kui.ksql.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.config.UrlPolicy
import kui.http.ServiceMain
import kui.ksql.api.KsqlApi

/** The ksql service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about ksqlDB.
  *
  * ==It starts even when nothing is configured==
  *
  * A deployment where no cluster has a `ksql` block starts this process, serves its routes, and reports every
  * cluster as `not_configured`. It does **not** refuse to start, and that is deliberate: this service is
  * optional, and a process that exited because an optional feature was unused would restart-loop in every
  * deployment that simply does not run ksqlDB — which is most of them — turning "you have no ksqlDB" into a
  * crashing container.
  *
  * ==The two configuration keys it reads that are not `kui.clusters[]`==
  *
  *   - `kui.streaming.cursorKey`, which signs the plan tokens that confirm a `DROP … DELETE TOPIC` (ADR-045).
  *     It is the same secret ADR-026 already makes an operator configure for streaming cursors: one secret
  *     and one rotation procedure, with this service's use kept apart from the topic and consumer services'
  *     by the operation name inside the payload. Running more than one replica without configuring it means a
  *     confirmation minted by one replica is refused by the other, and `KsqlWiring` says so in the log rather
  *     than failing quietly.
  *   - `KUI_ALLOW_PRIVATE_UPSTREAMS`, through `UrlPolicy.fromEnv`, exactly as `ServiceMain` reads it for the
  *     configuration loader. A ksqlDB is among the upstreams most likely to live at
  *     `http://ksqldb-server:8088` inside a Compose network, and a policy that refused that address here —
  *     after the loader had accepted it — would be a service that cannot reach the ksqlDB it just logged at
  *     startup.
  *
  * The per-query budget is **not** here: `kui.clusters.<n>.ksql.streamTimeout` is per cluster, because one
  * deployment's ksqlDB may be the one somebody watches for an hour and another's the one behind a proxy that
  * closes a connection at sixty seconds.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      KsqlApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        IO.delay(sys.env)
          .toResource
          .flatMap(environment =>
            KsqlWiring.make[IO](
              config.clusters,
              UrlPolicy.fromEnv(environment),
              config.rbac,
              config.streaming.cursorKey,
              telemetry,
              principals,
              logger
            )
          )
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
