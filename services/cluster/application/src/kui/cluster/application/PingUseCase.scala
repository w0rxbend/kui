package kui.cluster.application

import cats.Monad
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.cluster.domain.{ClockPort, Ping}
import kui.kernel.error.KuiError

/** Echoing a message back, with the time the service saw it.
  *
  * This is the sample use case the rest of the project copies: it takes untrusted input, asks the domain to
  * make a value out of it, and returns `F[Either[KuiError, A]]` — never an exception, never a wire type. What
  * it deliberately does *not* do is decide anything about HTTP. It does not know that the message arrived as
  * a query parameter or that the refusal will become a 400; `services/cluster/api` knows both, and that is
  * the whole point of the split (ADR-041 A3).
  */
trait PingUseCase[F[_]] {

  /** Echoes `message`, or explains why it will not. */
  def ping(message: String): F[Either[KuiError, Ping]]
}

object PingUseCase {

  /** The operation name. It is the span name OBS-002 records and the `operation` log field, so the two cannot
    * drift apart: they are the same constant.
    */
  val Operation: String = "kui.cluster.ping"

  /** Wires the use case to a clock and a logger.
    *
    * `Monad` is the weakest bound that works (ADR-002): the body reads the clock and then logs, which is a
    * `flatMap`, and nothing here needs to spawn, time out or allocate a `Ref`. A use case that asks for `IO`
    * can only be run one way; one that asks for `Monad` can be run under a test's fake effect as well.
    */
  def make[F[_]: Monad](clock: ClockPort[F], logger: StructuredLogger[F]): PingUseCase[F] =
    new PingUseCase[F] {

      /** The fields every line from this use case carries. `correlation.id` is not among them on purpose: the
        * use case has no request context, and the correlation id is put on the line by the MDC bridge in
        * `libs/observability` (OBS-001), which sees the request the use case does not.
        */
      private val context: Map[String, String] =
        Map("service.name" -> ClusterService.Id.value, "operation" -> Operation)

      def ping(message: String): F[Either[KuiError, Ping]] =
        clock.now.flatMap { at =>
          Ping.from(message, at) match {
            case Right(ping) =>
              logger.info(context)(s"echoed a message of ${message.length} characters").as(Right(ping))

            // WARN, not ERROR. A message the caller got wrong is a client mistake, and logging it at ERROR
            // means the alert that fires on ERROR fires whenever someone types badly — which is how a team
            // learns to ignore the alert.
            case Left(error) =>
              logger.warn(context)(s"refused a ping: ${error.message}").as(Left(error))
          }
        }
    }
}
