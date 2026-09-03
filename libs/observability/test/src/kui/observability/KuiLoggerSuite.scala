package kui.observability

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger as LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import munit.CatsEffectSuite
import org.slf4j.LoggerFactory
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

import kui.kernel.{ClusterId, CorrelationId}

/** That a KUI log entry carries exactly the fields `ARCHITECTURE.md` §13 promises, and that they
  * belong to the request that produced it.
  *
  * These assertions go through the real Logback pipeline rather than a fake logger. That is the
  * point: the fields reach a log line through SLF4J's MDC, and MDC is per-thread while cats-effect
  * fibers move between threads. Only the real path can show that the ids do not leak from one
  * request into another's line.
  */
final class KuiLoggerSuite extends CatsEffectSuite {

  private val serviceName = "kui-test"

  /** Runs `body` with a Logback appender attached to the root logger, and hands back what it
    * recorded.
    */
  private def captured(body: StructuredLogger[IO] => IO[Unit]): IO[List[ILoggingEvent]] =
    for {
      logger <- KuiLogger.make[IO](serviceName)
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

  private def context(event: ILoggingEvent): Map[String, String] =
    event.getMDCPropertyMap.asScala.toMap

  private val fullContext = LogContext(
    correlationId = Some(CorrelationId.unsafe("abc123")),
    userId = Some("hashed-user"),
    clusterId = Some(ClusterId.unsafe("prod-eu")),
    operation = Some("listTopics")
  )

  test("everyEntryCarriesServiceName") {
    captured(_.info("hello")).map { events =>
      assertEquals(events.map(_.getMessage), List("hello"))
      assertEquals(context(events.head).get(ContextKeys.ServiceName), Some(serviceName))
    }
  }

  test("withContextAddsTheFiveStandardKeysAndNothingElse") {
    captured(logger => KuiLogger.withContext(logger, fullContext).info("hello")).map { events =>
      // The exact set, so that a typo such as `correlationId` in place of `correlation.id` fails
      // here instead of quietly making a log search return nothing during an incident.
      assertEquals(context(events.head).keySet, ContextKeys.all.toSet)
    }
  }

  test("the five key names are the strings the architecture document fixes") {
    assertEquals(
      ContextKeys.all,
      List("correlation.id", "user.id", "cluster.id", "service.name", "operation")
    )
  }

  test("userIdIsNotLoggedWhenAbsent") {
    val partial = fullContext.copy(userId = None, clusterId = None)

    captured(logger => KuiLogger.withContext(logger, partial).info("hello")).map { events =>
      val keys = context(events.head).keySet
      assert(!keys.contains(ContextKeys.UserId), keys.toString)
      assert(!keys.contains(ContextKeys.ClusterId), keys.toString)
      assertEquals(keys, Set(ContextKeys.ServiceName, ContextKeys.CorrelationId, ContextKeys.Operation))
    }
  }

  test("mdcCarriesTraceAndSpanIdInsideASpan and mdcIsCleanOutsideOne") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("kui.test").flatMap { tracer =>
        given Tracer[IO] = tracer

        captured { logger =>
          for {
            outside <- KuiLogger.withSpanContext[IO](logger, LogContext.empty)
            _ <- outside.info("outside")
            _ <- tracer
              .span("inside")
              .use(_ =>
                KuiLogger
                  .withSpanContext[IO](logger, LogContext.empty)
                  .flatMap(_.info("inside"))
              )
          } yield ()
        }.map { events =>
          val byMessage = events.map(event => event.getMessage -> context(event)).toMap

          assert(!byMessage("outside").contains(ContextKeys.TraceId), byMessage("outside").toString)
          assert(!byMessage("outside").contains(ContextKeys.SpanId), byMessage("outside").toString)

          assert(byMessage("inside").contains(ContextKeys.TraceId), byMessage("inside").toString)
          assert(byMessage("inside").contains(ContextKeys.SpanId), byMessage("inside").toString)
          assertEquals(byMessage("inside")(ContextKeys.TraceId).length, 32)
          assertEquals(byMessage("inside")(ContextKeys.SpanId).length, 16)
        }
      }
    }
  }

  test("a logger built once outside a span still tags every line with the span it is used in") {
    // The regression this pins: `withSpanContext` freezes the ids when the logger is built, so a
    // process-wide logger built at startup — the only kind KUI has — carried no trace id on any line,
    // and logs could not be joined to traces at all. `spanAware` looks the span up per call.
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("kui.test").flatMap { tracer =>
        given Tracer[IO] = tracer

        captured { startupLogger =>
          val logger = KuiLogger.spanAware[IO](startupLogger)
          for {
            _ <- logger.info("outside")
            _ <- tracer.span("request").use(_ => KuiLogger.withContext(logger, fullContext).info("inside"))
            _ <- tracer.span("failing").use(_ => logger.error(new RuntimeException("nope"))("failed"))
          } yield ()
        }.map { events =>
          val byMessage = events.map(event => event.getMessage -> context(event)).toMap

          assert(!byMessage("outside").contains(ContextKeys.TraceId), byMessage("outside").toString)

          assertEquals(byMessage("inside").get(ContextKeys.TraceId).map(_.length), Some(32))
          assertEquals(byMessage("inside").get(ContextKeys.SpanId).map(_.length), Some(16))
          // The caller's own context survives alongside the ids.
          assertEquals(byMessage("inside").get(ContextKeys.ServiceName), Some(serviceName))
          assertEquals(byMessage("inside").get(ContextKeys.Operation), fullContext.operation)

          // The error overload matters most: it is the line an operator starts from.
          assertEquals(byMessage("failed").get(ContextKeys.TraceId).map(_.length), Some(32))
        }
      }
    }
  }

  test("KuiLogger.traced wires the tracer in, so a composition root cannot forget the ids") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      val telemetry = Telemetry.fromProviders(testkit.tracerProvider, MeterProvider.noop[IO])

      for {
        tracer <- testkit.tracerProvider.get("kui.test")
        logger <- KuiLogger.traced[IO](serviceName, telemetry, "kui.test")
        events <- IO.blocking(attach()).bracket { appender =>
          tracer.span("request").use(_ => logger.info("traced")) *>
            IO.blocking(appender.list.asScala.toList)
        }(appender => IO.blocking(detach(appender)))
      } yield {
        val ctx = context(events.find(_.getMessage == "traced").get)
        assertEquals(ctx.get(ContextKeys.TraceId).map(_.length), Some(32))
        assertEquals(ctx.get(ContextKeys.ServiceName), Some(serviceName))
      }
    }
  }

  test("two concurrent fibers do not see each other's context") {
    val first = fullContext.copy(correlationId = Some(CorrelationId.unsafe("aaaa")))
    val second = fullContext.copy(correlationId = Some(CorrelationId.unsafe("bbbb")))

    captured { logger =>
      val one = KuiLogger.withContext(logger, first).info("one").replicateA_(50)
      val two = KuiLogger.withContext(logger, second).info("two").replicateA_(50)
      (one, two).parTupled.void
    }.map { events =>
      val wrong = events.filter { event =>
        val expected = if event.getMessage == "one" then "aaaa" else "bbbb"
        context(event).get(ContextKeys.CorrelationId) != Some(expected)
      }
      assertEquals(wrong.size, 0, "a log entry carried another fiber's correlation id")
      assertEquals(events.size, 100)
    }
  }
}
