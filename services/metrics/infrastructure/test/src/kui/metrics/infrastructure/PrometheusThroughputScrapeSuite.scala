package kui.metrics.infrastructure

import java.time.Instant

import cats.effect.IO
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.client4.{Backend, Response}
import sttp.model.StatusCode

import kui.config.SafeUrl
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite

/** What the scrape does with each answer an address can give.
  *
  * A stub rather than a running exporter: every promise here is a promise about a *response*, and a real
  * exporter is the slowest way to produce one and cannot be made to produce most of them at all — an
  * ingress's HTML error page, a 401, a body from the wrong exporter entirely.
  */
final class PrometheusThroughputScrapeSuite extends KuiIOSuite {

  private val at = Instant.parse("2026-09-06T12:00:00Z")
  private val url: SafeUrl = SafeUrl.unsafe("http://kafka-metrics:5556/metrics")

  private val served =
    """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 124800.5
      |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 249600.75
      |kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate 1420.75
      |""".stripMargin

  private def answering(status: StatusCode, body: String): PrometheusThroughputScrape[IO] = {
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(_ => IO.pure(ResponseStub.adjust(body, status): Response[StubBody]))

    new PrometheusThroughputScrape[IO](backend, url)
  }

  test("a body the exporter served becomes a sample with both rates") {
    answering(StatusCode.Ok, served).sample(at).map {
      case Left(failure) => fail(s"expected a sample, got $failure")
      case Right(sample) =>
        assertEquals(sample.bytesInPerSecond, Some(124800.5))
        assertEquals(sample.bytesOutPerSecond, Some(249600.75))
        // KUI's clock and not the exporter's: a broker two minutes fast must not file a reading into a
        // bucket that has not happened yet.
        assertEquals(sample.at, at)
    }
  }

  test("the request is built against the root, because failover puts the base path back on") {
    // The defect the schema service already shipped once. `Failover.rebase` replaces a request's scheme
    // and authority and *prefixes the base URL's own path*, so a request built against the full configured
    // URL asks for `/metrics/metrics` and honestly receives a 404.
    var asked: String = ""
    val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF { request =>
        asked = "/" + request.uri.path.mkString("/")
        IO.pure(ResponseStub.adjust(served, StatusCode.Ok): Response[StubBody])
      }

    new PrometheusThroughputScrape[IO](backend, url).sample(at).map(_ => assertEquals(asked, "/"))
  }

  test("a body that cannot be parsed is a Left and no sample") {
    answering(StatusCode.Ok, "<html><body>502 Bad Gateway</body></html>").sample(at).map {
      case Right(sample) => fail(s"expected a refusal, got $sample")
      case Left(failure) =>
        assertEquals(failure.code, ErrorCode.UpstreamUnavailable)
        // The sentence has to say what is wrong with the *answer*, because the address is up and the
        // operator's next move is to look at what it is serving.
        assert(failure.message.contains("could not be understood"), failure.message)
    }
  }

  test("an address that answers 500 is upstream-unavailable and names no URL") {
    answering(StatusCode.InternalServerError, "").sample(at).map {
      case Right(sample) => fail(s"expected a refusal, got $sample")
      case Left(failure) =>
        assertEquals(failure.code, ErrorCode.UpstreamUnavailable)
        // ADR-034: a message that carried the address would put a host and a port in front of whoever
        // opened the dashboard.
        assert(!failure.message.contains("kafka-metrics"), failure.message)
    }
  }

  test("a 401 is an authentication failure and not a parse failure") {
    answering(StatusCode.Unauthorized, "no").sample(at).map {
      case Right(sample) => fail(s"expected a refusal, got $sample")
      // Retrying will not help and the configuration has to change first, which is a different sentence
      // from "the exporter is down".
      case Left(failure) => assertEquals(failure.code, ErrorCode.UpstreamAuth)
    }
  }
}
