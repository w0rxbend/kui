package kui.gateway.api

import java.util.Locale

import cats.effect.kernel.Sync
import cats.syntax.all.*
import sttp.model.Header
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.{Interceptor, RequestInterceptor, RequestResult}

import kui.kernel.CorrelationId
import kui.observability.Correlation

/** The security boundary between the public internet and everything KUI believes (ADR-040).
  *
  * KUI's services trust a family of headers named `X-Kui-*`: `X-Kui-Principal` says who the user is,
  * `X-Kui-Correlation-Id` says which request this is, `X-Kui-Cluster-Id` says which cluster it concerns.
  * Every one of them is set by the gateway. None of them is ever legitimately set by a browser — but a
  * browser can send any header it likes, and the gateway sits on the public network. So unless something
  * removes them, a user who types `X-Kui-Principal: admin` into a fetch call sends that header straight
  * through to a service.
  *
  * This object is that something, and it runs before routing, before authentication and before logging.
  *
  * ==Two rules, and why each is written the way it is==
  *
  *   1. **Deny by prefix, not by list.** Every header whose lowercased name begins `x-kui-` is removed. An
  *      enumeration of the three headers that exist today would be correct today and wrong on the day a
  *      fourth is added — and wrong silently, in a security-relevant way. [[Forbidden]] exists only so an
  *      error message can name the headers a person is likely to have sent on purpose.
  *   1. **The correlation id is generated here and never accepted.** Letting a caller supply one looks
  *      helpful — they could match their logs to ours — but a correlation id is only useful if it is unique
  *      and means what it appears to mean. A supplied id can be a duplicate, by accident or on purpose, and
  *      it is written into log lines and span attributes across every service, which makes accepting one the
  *      same thing as accepting attacker-controlled text into the observability pipeline. The caller loses
  *      nothing: the id the gateway generated comes back in the response header, so a client that wants to
  *      correlate reads it back.
  *
  * `traceparent` and `tracestate` are deliberately untouched. They are not `X-Kui-*`, distributed tracing has
  * its own specification with its own validation and sampling rules, and otel4s implements it (ADR-009).
  * Re-implementing that here would mean re-implementing it badly.
  */
object EdgeHeaders {

  /** The prefix that defines the family. Lowercase, because HTTP header names are case-insensitive and the
    * comparison has to be too — `X-Kui-Principal`, `x-kui-principal` and `X-KUI-PRINCIPAL` are one header.
    */
  val Prefix: String = "x-kui-"

  /** The `X-Kui-*` headers that exist today, lowercased.
    *
    * This set is **not** what [[isForbidden]] tests against, and that is the point: it is documentation and
    * error-message material, and a header missing from it is still stripped. It is kept beside the rule so
    * that a reader can see what the rule is protecting without going hunting through eleven services.
    */
  val Forbidden: Set[String] =
    Set("x-kui-principal", "x-kui-correlation-id", "x-kui-cluster-id", "x-kui-csrf")

  /** Whether one header name belongs to the family KUI reserves for itself.
    *
    * `Locale.ROOT` rather than the default locale is not pedantry: in a Turkish locale
    * `"X-KUI-ID".toLowerCase` produces a dotless ı, the prefix test fails, and a forged header sails through
    * on a machine whose `LANG` happens to be `tr_TR`. That is a real class of bug and it only appears in
    * production.
    */
  def isForbidden(name: String): Boolean =
    name.toLowerCase(Locale.ROOT).startsWith(Prefix)

  /** The headers a handler is allowed to see: everything that is not ours.
    *
    * Pure and total, so the rule can be tested as a property over generated header names rather than by
    * starting a server and hoping the interesting case was covered.
    */
  def remove(headers: Seq[Header]): Seq[Header] =
    headers.filterNot(header => isForbidden(header.name))

  /** The request as the rest of the gateway will see it: no inbound `X-Kui-*` header, and the gateway's own
    * correlation id in their place.
    *
    * Re-adding the id under the same reserved name is what lets everything downstream stay ignorant of this
    * policy. `kui.observability.KuiInterceptors` reads the id from that header to put it on a span,
    * `kui.http.ErrorInterceptor` reads it from there to put it in an error envelope, and neither has to know
    * that the value is trustworthy because it was minted three lines above rather than sent by a browser.
    *
    * Pure: the id is a parameter rather than something this function generates, because generating one needs
    * a random source and a function that reaches for entropy cannot be tested by writing down what it should
    * return.
    */
  def strip(request: ServerRequest, correlationId: CorrelationId): ServerRequest =
    request.withOverride(
      methodOverride = None,
      uriOverride = None,
      protocolOverride = None,
      connectionInfoOverride = None,
      pathSegmentsOverride = None,
      queryParametersOverride = None,
      headersOverride = Some(remove(request.headers) :+ Header(Correlation.HeaderName, correlationId.value))
    )

  /** The two interceptors that apply the policy, outermost first.
    *
    * They are a pair rather than one because Tapir exposes the request and the response at different
    * extension points, and both ends need attention: the first mints an id and rewrites the request, the
    * second copies that id onto whatever response came back. Splitting them is not a compromise — it is what
    * makes each one a single, obvious transformation.
    *
    * This list goes **before** `KuiInterceptors.serverInterceptors` in a gateway's interceptor chain. Order
    * is the whole mechanism: if tracing ran first, the span would already carry the browser's forged id.
    */
  def interceptors[F[_]: Sync]: List[Interceptor[F]] =
    List(stripInterceptor[F], echoInterceptor[F])

  /** Mints a fresh correlation id for the request and removes every header the browser had no business
    * sending.
    */
  def stripInterceptor[F[_]: Sync]: RequestInterceptor[F] =
    RequestInterceptor.transformServerRequest[F] { request =>
      Correlation.newRandom[F].map(strip(request, _))
    }

  /** Puts the correlation id on every response, including the ones no endpoint produced.
    *
    * "Including" is the reason this exists rather than an output on each endpoint. A 404 from the router, a
    * 400 from a decoder and a 500 from an exception are all responses a user might be looking at when they
    * ring up, and all three are produced before or instead of any endpoint's own outputs. Reading the id back
    * off the request — where [[strip]] put it — is what makes the header on the response and the
    * `correlationId` in the error body the same string by construction.
    */
  def echoInterceptor[F[_]: Sync]: RequestInterceptor[F] =
    RequestInterceptor.transformResultEffect[F](
      new RequestInterceptor.RequestResultEffectTransform[F] {
        def apply[B](request: ServerRequest, result: F[RequestResult[B]]): F[RequestResult[B]] =
          result.map { requestResult =>
            request.header(Correlation.HeaderName) match {
              case None => requestResult
              case Some(id) =>
                requestResult match {
                  case RequestResult.Response(response, source) =>
                    RequestResult.Response(
                      response.copy(headers =
                        response.headers.filterNot(_.is(Correlation.HeaderName)) :+
                          Header(Correlation.HeaderName, id)
                      ),
                      source
                    )
                  // Nothing matched and no response was built, so there is nothing to stamp. The reject
                  // handler in `libs/http` turns this into a 404 envelope that carries the id itself.
                  case failure => failure
                }
            }
          }
      }
    )
}
