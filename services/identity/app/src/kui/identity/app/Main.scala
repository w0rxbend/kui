package kui.identity.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.http.ServiceMain
import kui.identity.api.IdentityApi

/** The identity service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about who may sign in.
  *
  * It reads three sections of the configuration: `kui.auth` for how people authenticate, `kui.rbac` for what
  * they may then do, and `kui.store` for where a changed password is kept. It reads no `kui.clusters[]` at
  * all, which is the honest shape of a service that has nothing to do with Kafka — and the reason its
  * capability document reports no clusters.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      IdentityApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        IdentityWiring
          .make[IO](config.auth, config.rbac, config.store, telemetry, principals, logger)
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
