package kui.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import cats.effect.kernel.Sync
import cats.syntax.all.*

import kui.kernel.Secret

/** Where a secret's value actually lives.
  *
  * An operator has three reasonable ways to supply a signing key, and all three appear in the same YAML
  * field:
  *
  * {{{
  * key: "s3cret"                 # literal, fine on a laptop, bad in a repository
  * key: "env:KUI_SIGNING_KEY"    # read from an environment variable at startup
  * key: "file:/run/secrets/kui"  # read from a mounted file, which is how Kubernetes and Docker
  *                               # Compose deliver secrets
  * }}}
  *
  * Resolving the reference is deliberately separate from parsing it, so that the parse stays pure and
  * testable and only the resolution needs an effect.
  */
enum SecretRef {
  case Literal(value: String)
  case FromEnv(name: String)
  case FromFile(path: String)

  /** How the reference is described in an error message.
    *
    * A literal describes itself as `a literal value` and never as its contents: this method is what a failing
    * load prints, and it must be impossible for it to leak the secret.
    */
  def describe: String = this match {
    case Literal(_) => "a literal value"
    case FromEnv(name) => s"environment variable $name"
    case FromFile(path) => s"file $path"
  }
}

object SecretRef {

  private val EnvPrefix: String = "env:"
  private val FilePrefix: String = "file:"

  /** Reads the `env:` / `file:` prefixes; anything else is the secret itself. */
  def parse(raw: String): SecretRef =
    if raw.startsWith(EnvPrefix) then FromEnv(raw.drop(EnvPrefix.length).trim)
    else if raw.startsWith(FilePrefix) then FromFile(raw.drop(FilePrefix.length).trim)
    else Literal(raw)

  /** Produces the secret, or a message explaining why it could not be produced.
    *
    * The failure message names the reference, never its value: a `file:` reference that points at the wrong
    * file must not print that file's contents into a startup error, which is exactly the accident this method
    * is shaped to prevent.
    *
    * @param env
    *   the process environment, passed in rather than read here so the caller reads it once and so tests can
    *   supply their own
    */
  def resolve[F[_]: Sync](ref: SecretRef, env: Map[String, String]): F[Either[String, Secret[String]]] =
    ref match {
      case Literal(value) =>
        Sync[F].pure(nonEmpty(value, "a literal value"))

      case FromEnv(name) =>
        Sync[F].pure(
          env.get(name) match {
            case Some(value) => nonEmpty(value, s"environment variable $name")
            case None => Left(s"references environment variable $name, which is not set")
          }
        )

      case FromFile(path) =>
        Sync[F]
          .blocking(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
          .attempt
          .map {
            case Right(contents) => nonEmpty(contents.trim, s"file $path")
            case Left(_) => Left(s"references file $path, which could not be read")
          }
    }

  private def nonEmpty(value: String, what: String): Either[String, Secret[String]] =
    if value.nonEmpty then Right(Secret(value)) else Left(s"resolves to an empty value from $what")

  given CanEqual[SecretRef, SecretRef] = CanEqual.derived
}
