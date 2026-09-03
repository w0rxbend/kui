package kui.http

import java.time.Instant

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger as LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import munit.CatsEffectSuite
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

import kui.contracts.ErrorEnvelope
import kui.contracts.ErrorEnvelope.given
import kui.kernel.error.{ApplicationError, ErrorCode, FieldError}
import kui.kernel.{CorrelationId, Secret}
import kui.observability.KuiLogger
import kui.testkit.RedactionAssertions

/** The milestone-0 exit criterion, proved once, for every sink at once.
  *
  * "A `Secret[String]` field logged, traced, or returned from any endpoint renders as `***`" is a
  * promise about four different pieces of machinery, each maintained by different people at
  * different times. Testing them separately would leave the promise itself untested — nothing would
  * fail if a fifth sink appeared and quietly printed the value.
  *
  * One fixture, `hunter2-DO-NOT-LEAK`, goes through all four here:
  *
  *   1. a JSON log line, through the real Logback encoder;
  *   2. a span attribute, recorded through the real OpenTelemetry SDK;
  *   3. an error envelope, built at a site that had the secret in scope;
  *   4. an HTTP response body, served by a real Tapir endpoint through Circe.
  *
  * Plus a negative control: the same value *without* the `Secret` wrapper does appear, which is
  * what proves the suite is capable of failing.
  *
  * ==If you are adding a fifth sink==
  *
  * A metrics attribute, a health payload, an audit record, a cache key — anything that turns a
  * configuration object into text — add a case here. The guarantee is only as wide as this file.
  *
  * ==Why this works at all==
  *
  * Redaction is a property of the type, not of any encoder (ADR-008). `Secret[A]`'s `toString` is
  * `Secret(***)` and it has no other rendering, so there is no formatter that can be configured
  * wrongly and no scrubbing filter that can be forgotten. That is why one fixture can cover four
  * sinks: they all reach the same `toString`.
  */
final class SecretRedactionSuite extends CatsEffectSuite {

  private val Needle = "hunter2-DO-NOT-LEAK"

  /** A realistic configuration object: a signing key beside the ordinary fields it travels with. */
  private final case class SigningKeyView(kid: String, key: Secret[String], notBefore: Instant)

  private val fixture =
    SigningKeyView("compose-1", Secret(Needle), Instant.parse("2026-01-01T00:00:00Z"))

  /** The encoder a configuration view would use. It renders the secret through `toString`, which is
    * the only rendering `Secret` has.
    */
  private given Encoder[SigningKeyView] = Encoder.instance { view =>
    Json.obj(
      "kid" -> view.kid.asJson,
      "key" -> view.key.toString.asJson,
      "notBefore" -> view.notBefore.toString.asJson
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 1. A log line
  // ---------------------------------------------------------------------------------------------

  test("secretDoesNotAppearInAJsonLogLine") {
    captured(logger => logger.info(Map("config" -> fixture.asJson.noSpaces))("loaded configuration"))
      .map { events =>
        val rendered = events.map(event => event.getFormattedMessage + event.getMDCPropertyMap).mkString("\n")

        RedactionAssertions.assertRedactedAndNoLeak(rendered, Needle)
      }
  }

  test("the configuration is logged once at INFO, as the task's observability section requires") {
    captured(logger => logger.info(Map("config" -> fixture.asJson.noSpaces))("loaded configuration"))
      .map { events =>
        assertEquals(events.map(_.getLevel.toString), List("INFO"))
        assertEquals(events.map(_.getFormattedMessage), List("loaded configuration"))
      }
  }

  // ---------------------------------------------------------------------------------------------
  // 2. A span attribute
  // ---------------------------------------------------------------------------------------------

  test("secretDoesNotAppearInAnySpanAttribute") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        tracer <- testkit.tracerProvider.get("kui.test")
        _ <- tracer
          .span("loadConfiguration")
          .use(span =>
            span.addAttributes(
              Attribute("config.key.kid", fixture.kid),
              // The documented helper: a secret reaches an attribute through `toString`, exactly as
              // it reaches a log line.
              Attribute("config.key", fixture.key.toString)
            )
          )
        spans <- testkit.finishedSpans
      } yield {
        val rendered = spans
          .flatMap(_.getAttributes.asMap.asScala.map((key, value) => s"${key.getKey}=$value"))
          .mkString("\n")

        RedactionAssertions.assertRedactedAndNoLeak(rendered, Needle)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 3. An error envelope
  // ---------------------------------------------------------------------------------------------

  test("secretDoesNotAppearInAnErrorEnvelope") {
    // The construction site has the secret in scope and describes the field it belongs to, which is
    // the realistic way a leak would happen: someone interpolates the value into a message meaning
    // to be helpful.
    val error = ApplicationError.Invalid(
      s"the signing key ${fixture.kid} was rejected",
      List(FieldError.of("kui.gateway.principalKeys.0.key", s"${fixture.key} is not a valid key"))
    )

    val (_, envelope) = ErrorInterceptor.render(error, CorrelationId.unsafe("abc123"), fixture.notBefore)

    RedactionAssertions.assertRedactedAndNoLeak(envelope.asJson.noSpaces, Needle)
  }

  // ---------------------------------------------------------------------------------------------
  // 4. An HTTP response body
  // ---------------------------------------------------------------------------------------------

  private val configView: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.get
      .in("config")
      .out(stringBody)
      .errorOut(jsonBody[ErrorEnvelope])
      .name("configView")
      .serverLogicSuccess[IO](_ => IO.pure(fixture.asJson.noSpaces))

  test("secretDoesNotAppearInAnHttpResponseBody") {
    TestServer.resource(List(configView)).use { server =>
      server.get("/config").map { response =>
        RedactionAssertions.assertRedactedAndNoLeak(response.body, Needle)
      }
    }
  }

  test("an error response served by the real stack leaks nothing either") {
    val leaky: ServerEndpoint[Fs2Streams[IO], IO] =
      endpoint.get
        .in("fails")
        .out(stringBody)
        .errorOut(statusCode.and(jsonBody[ErrorEnvelope]))
        .name("fails")
        .serverLogic[IO] { _ =>
          val error = ApplicationError.NotFound("signing key", fixture.key.toString, ErrorCode.ClusterNotFound)
          val (status, envelope) = ErrorInterceptor.render(error, CorrelationId.unsafe("abc123"), fixture.notBefore)
          IO.pure(Left((sttp.model.StatusCode(status), envelope)))
        }

    TestServer.resource(List(leaky)).use { server =>
      server.get("/fails").map { response =>
        RedactionAssertions.assertRedactedAndNoLeak(response.body, Needle)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The negative control
  // ---------------------------------------------------------------------------------------------

  test("negativeControlAnUnwrappedValueDoesAppear") {
    // Without this, every assertion above would still pass if the fixture were empty, if the
    // encoder dropped the field, or if `assertNoLeak` were broken. This is what makes the other
    // five tests mean something.
    val unwrapped = Json.obj("key" -> Json.fromString(Needle)).noSpaces

    assert(unwrapped.contains(Needle))
    val leakReported = intercept[munit.FailException](RedactionAssertions.assertNoLeak(unwrapped, Needle))
    val absenceReported = intercept[munit.FailException](RedactionAssertions.assertRedacted(unwrapped))

    assert(leakReported.getMessage.contains("leaked"), leakReported.getMessage)
    assert(absenceReported.getMessage.contains("redacted"), absenceReported.getMessage)
  }

  test("a leak report says where, and never what") {
    val leaked = s"prefix $Needle suffix"

    val failure = intercept[munit.FailException](RedactionAssertions.assertNoLeak(leaked, Needle))

    assert(!failure.getMessage.contains(Needle), "the failure message printed the secret")
    assert(failure.getMessage.contains("character 7"), failure.getMessage)
  }

  // ---------------------------------------------------------------------------------------------

  private def captured(
      body: org.typelevel.log4cats.StructuredLogger[IO] => IO[Unit]
  ): IO[List[ILoggingEvent]] =
    for {
      logger <- KuiLogger.make[IO]("kui-test")
      events <- IO.blocking(attach()).bracket { appender =>
        body(logger) *> IO.blocking(appender.list.asScala.toList)
      }(appender => IO.blocking(detach(appender)))
    } yield events

  private def attach(): ListAppender[ILoggingEvent] = {
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    root.addAppender(appender)
    appender
  }

  private def detach(appender: ListAppender[ILoggingEvent]): Unit = {
    val _ = root.detachAppender(appender)
    appender.stop()
  }

  private def root: LogbackLogger =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) match {
      case logback: LogbackLogger => logback
      case other => fail(s"expected Logback to be the SLF4J backend, found ${other.getClass.getName}")
    }
}
