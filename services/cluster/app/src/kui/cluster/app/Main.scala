package kui.cluster.app

import cats.effect.kernel.{Clock, Resource}
import cats.effect.{ExitCode, IO, IOApp}

import kui.cluster.api.ClusterApi
import kui.http.ServiceMain

/** The cluster service process.
  *
  * `IO` appears here and nowhere else in the service (ADR-010). Every layer beneath is written against an
  * abstract `F[_]`, which is not an aesthetic preference: it is what lets a suite run the same code on a
  * deterministic clock, and what lets the all-in-one deployment run this same wiring inside a different
  * process.
  *
  * The startup sequence — configuration, log format, principal codec, telemetry, wiring, listener, drain — is
  * [[ServiceMain]]'s and is shared with the topic, message and consumer processes. It used to live here, and
  * moved when those three grew `main`s of their own: four copies of a sequence whose fourth step is "refuse
  * to start when the signing keys are missing" is four chances for one of them to start anyway.
  *
  * What is left here is the part that is only true of this service. Its wiring step is the largest of the
  * four, because ADR-042 fixes an order: the metadata store's clients are opened, its topics are created or
  * validated, and `__kui_config` is replayed to its end offset before anything else is built
  * (`ClusterBootstrap`). A store that cannot be replayed stops the process there, with a named error —
  * deliberately, because a service that started anyway would serve an empty cluster list, indistinguishable
  * from a KUI nobody has configured.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServiceMain.run(
      ClusterApi.ServiceName,
      args,
      (loaded, telemetry, principals, logger) => {
        val config = ClusterServiceConfig.from(loaded)

        for {
          startedAt <- Resource.eval(Clock[IO].realTimeInstant)
          _ <- Resource.eval(ClusterWiring.startupLog[IO](logger, config, startedAt))
          cluster <- ClusterWiring.make[IO](config, telemetry, principals, logger)
        } yield ServiceMain.Serving(cluster.routes, cluster.interceptors)
      }
    )
}
