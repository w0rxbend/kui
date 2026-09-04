package kui.message.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.http.ServiceMain
import kui.message.api.MessageApi

/** The message service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about messages.
  *
  * This service reads the least of the four. It holds no snapshot and runs no background scrape: it opens a
  * Kafka consumer when somebody browses, streams what was asked for, and closes it again. So there is no
  * refresh interval to configure, and a broker outage has nothing here to make stale — it simply fails the
  * browse that asked for it, with a reason.
  *
  * `kui.streaming.cursorKey` is the one secret it needs. It signs the streaming cursor a browse resumes from
  * (ADR-026) and the plan tokens that confirm a purge (ADR-045). A cursor signed by one replica must verify
  * on another, or a user paging through a topic behind a load balancer gets a refusal halfway down.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      MessageApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        MessageWiring
          .make[IO](config.clusters, config.streaming.cursorKey, telemetry, principals, logger)
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
