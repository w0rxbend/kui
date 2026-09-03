package kui.cluster.app

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.kernel.{Resource, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

import kui.config.PrincipalKeyConfig
import kui.security.{JwsPrincipalCodec, PrincipalCodec, SigningKey}

/** Deciding what this process will believe about who is calling it.
  *
  * There are exactly two acceptable answers and one refusal, and the refusal is the important part.
  *
  *   - **Keys are configured.** The service verifies a real signature. This is the only shape a deployment
  *     that runs the gateway and the services as separate processes may use.
  *   - **No keys, and `KUI_ALLOW_UNSIGNED=true`.** The service accepts unsigned claims. This is a development
  *     escape hatch for running one service on a laptop without minting keys first, and it announces itself
  *     in the log every minute for as long as it is in effect.
  *   - **No keys, and no escape hatch: the process does not start.** A service that started anyway would
  *     accept an `X-Kui-Principal` header from anyone who could reach its port — which is every pod in the
  *     cluster — and would do it silently. KERN-006's degraded-behaviour rule is that a missing security
  *     configuration is a startup failure and never a default.
  */
object PrincipalCodecs {

  /** The variable that turns the unsigned codec on. Spelled as an environment variable and not as a
    * configuration key on purpose: it is not a setting a deployment should be able to inherit from a file
    * someone copied.
    */
  val AllowUnsignedVariable: String = "KUI_ALLOW_UNSIGNED"

  /** How often the unsigned codec says so. Once a minute is often enough that nobody can claim they did not
    * see it and rare enough that it does not drown the log.
    */
  val WarningInterval: FiniteDuration = 60.seconds

  val UnsignedWarning: String =
    s"$AllowUnsignedVariable=true: this service accepts UNSIGNED principal headers. Anyone who can " +
      "reach this port can claim to be anyone. This is a development-only setting; configure " +
      "kui.gateway.principalKeys before running the gateway and the services as separate processes."

  val MissingKeys: String =
    "no principal signing keys are configured. A service that starts without them would trust an " +
      "X-Kui-Principal header from anyone who can reach its port. Configure kui.gateway.principalKeys, " +
      s"or set $AllowUnsignedVariable=true for local development only."

  /** The issuer stamped into, and checked on, every token of this deployment. */
  val Issuer: String = "kui-gateway"

  /** Builds the codec, or says in one sentence why the process must not start.
    *
    * The failure is a `Left` and not an exception because `Main` has to print it and exit non-zero without a
    * stack trace, exactly as it does for a configuration problem: an operator reading `docker logs` needs a
    * sentence, not a trace.
    *
    * The result is a `Resource` because the unsigned codec owns a background fiber — the repeating warning —
    * that has to stop when the process does.
    */
  def make[F[_]: Temporal](
      keys: List[PrincipalKeyConfig],
      env: Map[String, String],
      logger: StructuredLogger[F]
  ): Either[String, Resource[F, PrincipalCodec[F]]] =
    NonEmptyList.fromList(keys) match {
      case Some(configured) =>
        JwsPrincipalCodec
          .make[F](configured.map(signingKey), Issuer)
          .bimap(weak => weak.message, codec => Resource.pure[F, PrincipalCodec[F]](codec))

      case None if allowsUnsigned(env) =>
        Right(unsigned[F](logger))

      case None => Left(MissingKeys)
    }

  def allowsUnsigned(env: Map[String, String]): Boolean =
    env.get(AllowUnsignedVariable).exists(_.equalsIgnoreCase("true"))

  /** The unsigned codec, with the warning running beside it for as long as it is in use.
    *
    * The first warning is written before the codec is handed over, so it appears in the startup log rather
    * than a minute into it — the log line has to be there when someone reads the first screen of output, not
    * only when they leave the process running.
    */
  def unsigned[F[_]: Temporal](logger: StructuredLogger[F]): Resource[F, PrincipalCodec[F]] =
    Resource
      .eval(logger.warn(UnsignedWarning))
      .flatMap(_ => (Temporal[F].sleep(WarningInterval) *> logger.warn(UnsignedWarning)).foreverM.background)
      .as(PrincipalCodec.inProcess[F])

  /** A configured key, in the form the JWS codec wants.
    *
    * The secret is configured as text — a literal, `env:NAME` or `file:/path` — and HMAC signs bytes, so this
    * is where the one becomes the other. UTF-8 explicitly and never the platform default: the gateway and the
    * service must derive the same bytes from the same configured string whatever locale their containers were
    * started with.
    */
  private def signingKey(key: PrincipalKeyConfig): SigningKey =
    SigningKey(key.kid, key.key.map(_.getBytes(StandardCharsets.UTF_8)), key.notBefore)
}
