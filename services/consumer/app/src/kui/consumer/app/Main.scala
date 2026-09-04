package kui.consumer.app

import cats.effect.{ExitCode, IO, IOApp}

import kui.consumer.api.ConsumerApi
import kui.http.ServiceMain

/** The consumer service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). The startup sequence is [[ServiceMain]]'s,
  * shared with every other service process; what is left here is the part that is about consumer groups.
  *
  * Its refresh interval comes from `kui.consumers.refreshInterval` and deliberately not from
  * `kui.topics.refreshInterval`: describing every consumer group on a cluster and sweeping its topics are
  * different costs against different broker paths, and one knob for both would mean tuning the cheaper one by
  * the expensive one.
  *
  * `kui.streaming.cursorKey` signs the plan tokens that confirm an offset reset or a group deletion
  * (ADR-045). Configure it before running a second replica: a plan minted by one process is refused by the
  * other, and what the operator sees is a confirmation dialog that will not confirm.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      ConsumerApi.ServiceName,
      args,
      (config, telemetry, principals, logger) =>
        ConsumerWiring
          .make[IO](
            config.clusters,
            config.consumers.refreshInterval,
            config.streaming.cursorKey,
            telemetry,
            principals,
            logger
          )
          .map(service => ServiceMain.Serving(service.routes, service.interceptors))
    )
}
