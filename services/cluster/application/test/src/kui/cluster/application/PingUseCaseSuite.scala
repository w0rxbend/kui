package kui.cluster.application

import java.time.Instant

import cats.effect.IO

import kui.cluster.domain.{ClockPort, Ping}
import kui.kernel.ClusterId
import kui.kernel.error.ErrorCode
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.{FakeClock, FakeStructuredLogger, LogEntry}

/** What the sample use case promises: the clock's answer, one structured log line, and a refusal that is a
  * value rather than an incident.
  *
  * The clock is faked so that the assertion can be an exact instant instead of "roughly now", and the logger
  * is faked because the observability requirements ("logs one entry with `operation`", "a client mistake is
  * WARN") are assertions, not documentation.
  */
final class PingUseCaseSuite extends KuiIOSuite {

  private val at: Instant = Instant.parse("2026-09-03T10:11:12Z")

  /** The use case, its clock and its logger, built together so each test starts from a clean log. */
  private def fixture: IO[(PingUseCase[IO], FakeStructuredLogger[IO])] =
    for {
      clock <- FakeClock[IO](at)
      logger <- FakeStructuredLogger[IO]
      port = new ClockPort[IO] {
        def now: IO[Instant] = clock.now
      }
    } yield (PingUseCase.make[IO](port, logger), logger)

  private def onlyEntry(entries: List[LogEntry]): LogEntry = entries match {
    case one :: Nil => one
    case other => fail(s"expected exactly one log entry, got ${other.size}: $other")
  }

  test("a valid message comes back with the instant the clock reported") {
    for {
      (useCase, _) <- fixture
      result <- useCase.ping("hello")
    } yield assertEquals(result.map(ping => (ping.message, ping.at)), Right(("hello", at)))
  }

  test("one structured entry is logged, carrying the operation and the service name") {
    for {
      (useCase, logger) <- fixture
      _ <- useCase.ping("hello")
      entries <- logger.entries
    } yield {
      val entry = onlyEntry(entries)
      assertEquals(entry.level, "info")
      assertEquals(entry.context.get("operation"), Some("kui.cluster.ping"))
      assertEquals(entry.context.get("service.name"), Some("cluster"))
    }
  }

  test("a message the domain refuses comes back as a Left, with the field named") {
    for {
      (useCase, _) <- fixture
      result <- useCase.ping("")
    } yield result match {
      case Right(ping) => fail(s"an empty message should not have produced $ping")
      case Left(error) =>
        assertEquals(error.code, ErrorCode.Validation)
        assertEquals(error.details.flatMap(_.field), List("message"))
    }
  }

  test("a refusal is logged at WARN: a client mistake is not a server incident") {
    for {
      (useCase, logger) <- fixture
      _ <- useCase.ping("x" * (Ping.MaxMessageLength + 1))
      entries <- logger.entries
    } yield {
      val entry = onlyEntry(entries)
      assertEquals(entry.level, "warn")
      assertEquals(entry.context.get("operation"), Some("kui.cluster.ping"))
    }
  }

  test("the M0 capability report says configured, featureless and available for every cluster") {
    val ids = Set(ClusterId.unsafe("prod-eu"), ClusterId.unsafe("staging"))

    CapabilityReportUseCase.constant[IO](ids).report.map { report =>
      assertEquals(report.clusters.keySet, ids)
      assertEquals(
        report.clusters.values.toSet,
        Set(ClusterCapabilityReport(configured = true, Set.empty, available = true))
      )
    }
  }

  test("a deployment with no clusters configured reports an empty map, not a failure") {
    CapabilityReportUseCase.constant[IO](Set.empty).report.map { report =>
      assertEquals(report, CapabilityReport(Map.empty))
    }
  }
}
