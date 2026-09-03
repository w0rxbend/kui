package kui.e2e

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

import io.circe.Json
import io.circe.parser.parse

/** The smallest HTTP client that can answer the questions an end-to-end test asks of the API.
  *
  * The suites are browser tests, so why is there an HTTP client here at all? Because two of the assertions
  * are about the *agreement* between what the browser shows and what the API says — E2E-001 checks that
  * `/api/v1/capabilities` reports the sample service available at the same moment the sidebar draws it as
  * normal, and E2E-002 checks that the reason and the `since` timestamp the fallback panel displays are the
  * ones the gateway actually published. A UI that renders a stale or invented value would pass a purely
  * visual test and fail this one.
  *
  * It is the JDK's own client rather than sttp: this module has no other reason to pull a client library in,
  * and everything asked of it here is one blocking GET.
  */
object Http {

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  /** A GET, as a status code and a body. Never throws for a non-2xx answer: an end-to-end test is often
    * asking *whether* something is reachable, and an exception would make "it answered 503" and "nothing is
    * listening" the same observation when they are completely different diagnoses.
    */
  def get(url: String): (Int, String) = {
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  /** A GET whose body is parsed as JSON, or `None` when the call failed or the body was not JSON. */
  def getJson(url: String): Option[Json] =
    scala.util.Try(get(url)) match {
      case scala.util.Success((status, body)) if status >= 200 && status < 300 => parse(body).toOption
      case _ => None
    }

  /** Whether a URL answers with a 2xx. The readiness question, and nothing more. */
  def isUp(url: String): Boolean =
    scala.util.Try(get(url)) match {
      case scala.util.Success((status, _)) => status >= 200 && status < 300
      case _ => false
    }
}
