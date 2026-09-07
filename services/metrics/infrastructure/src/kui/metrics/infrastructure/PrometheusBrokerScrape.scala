package kui.metrics.infrastructure

import java.time.Instant

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.client4.*
import sttp.model.{StatusCode, Uri}

import kui.config.SafeUrl
import kui.http.upstream.UpstreamFailure
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}
import kui.metrics.domain.BrokerSample

/** One reading of one source, taken now. The scrape half of the collector, separated from the retention half
  * so that neither has to be faked to test the other.
  *
  * It never throws: an exporter that is down, slow, answering HTML or answering a body with no Kafka families
  * in it all arrive as a `Left`, because the caller is a background loop whose one rule is that a failed pass
  * must not take anything down with it.
  */
trait BrokerScrape[F[_]] {

  /** @param at
    *   the instant KUI is taking the reading. Supplied rather than read here, so that one clock decides the
    *   axis and a test can put a scrape in a bucket without waiting for one.
    */
  def sample(at: Instant): F[Either[KuiError, BrokerSample]]
}

/** A Prometheus text exposition endpoint — a JMX exporter in httpserver mode, in every deployment KUI ships —
  * read over `libs/http`'s resilient backend.
  *
  * ==Why this protocol and not JMX==
  *
  * `MetricsSourceSettings.url` is a `SafeUrl`, which `ARCHITECTURE.md` §14 restricts to `http` and `https`
  * with no exception. `service:jmx:rmi:///jndi/rmi://kafka:9999/jmxrmi` cannot be written into KUI's
  * configuration at all, so the JMX case cannot be reached through the configuration that exists.
  * `MetricsSourceKind.Jmx` therefore stays declared and unimplemented, and a cluster configured with it is
  * refused by name rather than silently measured as nothing (ADR-050).
  *
  * ==Why the request is built against the root==
  *
  * `Failover.rebase` replaces a request's scheme and authority with the chosen address and **prefixes that
  * address's own path**, so a request built against the full configured URL has `/metrics` applied twice.
  * That defect shipped once already, in the schema service against an Apicurio registry mounted at a
  * sub-path, and the fix is the same one: build against the root and let `rebase` put the path back exactly
  * once.
  */
final class PrometheusBrokerScrape[F[_]: Async](backend: Backend[F], url: SafeUrl) extends BrokerScrape[F] {

  import PrometheusBrokerScrape.*

  private val root: Uri =
    Uri.parse(url.value).getOrElse(uri"http://metrics-exporter.invalid").withWholePath("")

  def sample(at: Instant): F[Either[KuiError, BrokerSample]] =
    basicRequest
      .get(root)
      .header("Accept", AcceptHeader)
      .response(asStringAlways)
      .send(backend)
      .map { response =>
        if !response.code.isSuccess then Left(statusFailure(response.code))
        else
          PrometheusExposition
            .brokerSampleAt(at, response.body)
            .left
            .map(why => malformed(why))
      }
      .recover {
        // The resilient backend carries its typed error — a timeout, an open circuit, a refused address —
        // inside this one exception rather than losing it in a message.
        case UpstreamFailure(error) => Left(error)
        case failure: Exception => Left(InfrastructureError.Unreachable(UpstreamName, describe(failure)))
      }
}

object PrometheusBrokerScrape {

  /** The label this source wears in errors and in upstream metrics. A name, never an address: a connection
    * failure's text routinely carries hosts and ports, and ADR-034 keeps them out of a user-visible message.
    */
  val UpstreamName: String = "metrics-exporter"

  /** Both spellings a Prometheus endpoint may answer. The version parameter is what the exposition format's
    * own specification asks a scraper to send; an exporter that ignores it answers the same text.
    */
  val AcceptHeader: String = "text/plain;version=0.0.4;q=0.9,text/plain;q=0.8,*/*;q=0.1"

  private def statusFailure(status: StatusCode): KuiError =
    if status == StatusCode.Unauthorized || status == StatusCode.Forbidden then
      InfrastructureError.AuthFailed(UpstreamName)
    else InfrastructureError.Upstream(UpstreamName, status.code)

  /** An address that answered and is not an exporter KUI can read. Separate from `Unreachable` because the
    * action is different: the host is up and the URL or the exporter's whitelist is what has to change.
    */
  private def malformed(why: String): KuiError =
    InfrastructureError.Remote(
      ErrorCode.UpstreamUnavailable,
      s"the metrics exporter's answer could not be understood: $why",
      Nil
    )

  private def describe(failure: Exception): String = {
    val message = Option(failure.getMessage).filter(_.nonEmpty).getOrElse("no further detail")
    s"${failure.getClass.getSimpleName}: $message"
  }
}
