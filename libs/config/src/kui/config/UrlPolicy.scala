package kui.config

import java.net.URI

import scala.util.Try

import kui.kernel.ValidationError

/** What a URL is allowed to look like before KUI will call it.
  *
  * KUI calls addresses that an operator typed into a configuration file: a schema registry, a Kafka Connect
  * cluster, an OTLP collector. That makes every one of them a server-side request forgery risk — the classic
  * attack is to point KUI at `http://169.254.169.254/`, the address a cloud instance uses to hand out its own
  * credentials, and read the answer back through KUI. `ARCHITECTURE.md` §14 therefore restricts outbound
  * URLs, and this type is that restriction as data so the same rule can be applied strictly in production and
  * loosely in development.
  *
  * @param allowLoopback
  *   whether `localhost`, `127.0.0.1` and `[::1]` are acceptable hosts
  * @param allowPrivate
  *   whether addresses that are not routable on the public internet — the private ranges (`10.0.0.0/8`,
  *   `172.16.0.0/12`, `192.168.0.0/16`), link-local addresses (`169.254.0.0/16`, `fe80::/10`) and the cloud
  *   metadata addresses that live inside them — are acceptable
  * @param allowedSchemes
  *   the URL schemes that may be used, lowercase
  */
final case class UrlPolicy(allowLoopback: Boolean, allowPrivate: Boolean, allowedSchemes: Set[String])

object UrlPolicy {

  /** The scheme allow-list, which is the same under every policy.
    *
    * `ARCHITECTURE.md` §14 says `http` and `https` only, with no exception for development: a `file://` or
    * `ftp://` upstream is never a legitimate KUI configuration, and relaxing it for developers would mean the
    * strict path is the one nobody exercises.
    */
  val HttpSchemes: Set[String] = Set("http", "https")

  /** The production default: public HTTP(S) addresses only. */
  val Strict: UrlPolicy = UrlPolicy(allowLoopback = false, allowPrivate = false, HttpSchemes)

  /** Development and tests, where every upstream is a container on `localhost` or on a private Docker
    * network.
    */
  val Dev: UrlPolicy = UrlPolicy(allowLoopback = true, allowPrivate = true, HttpSchemes)

  given CanEqual[UrlPolicy, UrlPolicy] = CanEqual.derived
}

/** A URL string that has been checked against a [[UrlPolicy]].
  *
  * It is an opaque type over `String` so that the check cannot be skipped: the only way to obtain one is
  * [[SafeUrl.from]], and every function that takes a `SafeUrl` therefore knows the policy was applied.
  * Nothing is normalised or rewritten — the value is the operator's own text, so that what appears in a log
  * or an error message is what they wrote.
  */
opaque type SafeUrl = String

object SafeUrl {

  private val Field: String = "url"

  /** Hosts that always mean "this machine", whatever the DNS says. */
  private val LoopbackNames: Set[String] = Set("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")

  /** Checks `raw` against `policy`.
    *
    * The failure is a kernel `ValidationError` rather than a bespoke type because the caller is always either
    * the configuration loader (which turns it into one line of a startup error) or the upstream client
    * factory (which turns it into a `KUI-VALIDATION` response). Both already know how to render one.
    */
  def from(raw: String, policy: UrlPolicy): Either[ValidationError, SafeUrl] =
    for {
      uri <- parse(raw)
      _ <- schemeIsAllowed(uri, raw, policy)
      _ <- noUserInfo(uri)
      host <- hostOf(uri, raw)
      _ <- hostIsAllowed(host, raw, policy)
    } yield raw

  /** Builds a `SafeUrl` without checking. Reserved for tests and for literals in this repository that are
    * known-good; production code paths go through [[from]].
    */
  def unsafe(raw: String): SafeUrl = raw

  extension (u: SafeUrl) def value: String = u

  given Ordering[SafeUrl] = Ordering.String
  given CanEqual[SafeUrl, SafeUrl] = CanEqual.derived

  private def parse(raw: String): Either[ValidationError, URI] =
    Try(new URI(raw)).toEither.left.map(_ =>
      ValidationError.Format(Field, "an absolute http or https URL", raw)
    )

  private def schemeIsAllowed(
      uri: URI,
      raw: String,
      policy: UrlPolicy
  ): Either[ValidationError, Unit] =
    Option(uri.getScheme).map(_.toLowerCase) match {
      case Some(scheme) if policy.allowedSchemes.contains(scheme) => Right(())
      case _ =>
        val expected = policy.allowedSchemes.toList.sorted.mkString(" or ")
        Left(ValidationError.Format(Field, expected, raw))
    }

  private def noUserInfo(uri: URI): Either[ValidationError, Unit] =
    if Option(uri.getUserInfo).isEmpty && Option(uri.getRawUserInfo).isEmpty then Right(())
    else
      Left(
        ValidationError.Invariant(
          Field,
          "a URL must not carry a username or password; put credentials in their own configuration " +
            "keys so they can be redacted"
        )
      )

  private def hostOf(uri: URI, raw: String): Either[ValidationError, String] =
    Option(uri.getHost).filter(_.nonEmpty) match {
      case Some(host) => Right(host.toLowerCase)
      case None => Left(ValidationError.Format(Field, "a URL with a host name", raw))
    }

  private def hostIsAllowed(
      host: String,
      raw: String,
      policy: UrlPolicy
  ): Either[ValidationError, Unit] =
    if isLoopback(host) && !policy.allowLoopback then
      Left(
        ValidationError.Invariant(
          Field,
          s"'$raw' points at this machine; a loopback address is only allowed in development"
        )
      )
    else if !isLoopback(host) && isNotPubliclyRoutable(host) && !policy.allowPrivate then
      Left(
        ValidationError.Invariant(
          Field,
          s"'$raw' is a private, link-local or cloud-metadata address; those are refused so that " +
            "a mistyped or hostile URL cannot make KUI read a network's internal endpoints"
        )
      )
    else Right(())

  private def isLoopback(host: String): Boolean =
    LoopbackNames.contains(host) || host.startsWith("127.")

  /** Private ranges, link-local ranges (which contain the cloud metadata addresses) and IPv6 unique-local
    * addresses. Matched textually on purpose: resolving the name would make the answer depend on DNS at load
    * time, and a configuration file must validate the same way everywhere.
    */
  private def isNotPubliclyRoutable(host: String): Boolean =
    isLoopback(host) ||
      host.startsWith("10.") ||
      host.startsWith("192.168.") ||
      host.startsWith("169.254.") ||
      isPrivateIpv6(host) ||
      isCarrierGradeOrPrivate172(host)

  /** IPv6 link-local (`fe80::/10`) and unique-local (`fc00::/7`) prefixes.
    *
    * The `contains(':')` guard matters: without it a perfectly ordinary host name such as `fdn.example.com`
    * would be refused for starting with the letters of a private prefix.
    */
  private def isPrivateIpv6(host: String): Boolean =
    host.contains(':') &&
      (host.startsWith("fe80:") || host.startsWith("fd") || host.startsWith("fc"))

  private def isCarrierGradeOrPrivate172(host: String): Boolean =
    host.split('.').toList match {
      case first :: second :: _ :: _ :: Nil =>
        (first, second.toIntOption) match {
          case ("172", Some(octet)) => octet >= 16 && octet <= 31
          case ("100", Some(octet)) => octet >= 64 && octet <= 127
          case _ => false
        }
      case _ => false
    }
}
