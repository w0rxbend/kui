package kui.observability

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

/** That the id which ties a response to its logs and its trace is always present, always usable in
  * a header, and points at the trace whenever there is one.
  */
final class CorrelationSuite extends CatsEffectSuite {

  test("the header name is the one every service and the browser agree on") {
    assertEquals(Correlation.HeaderName, "X-Kui-Correlation-Id")
  }

  test("outside a span a random id is generated, and it is a valid CorrelationId") {
    Correlation.newRandom[IO].replicateA(100).map { ids =>
      assertEquals(ids.distinct.size, 100, "the generator repeated itself")
      ids.foreach(id => assertEquals(id.value.length, 16))
    }
  }

  test("inside a span the id is the span id, so the two can be pasted into each other") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      testkit.tracerProvider.get("kui.test").flatMap { tracer =>
        given Tracer[IO] = tracer

        tracer
          .span("request")
          .use(span => Correlation.fromSpanOrRandom[IO].map(_ -> span.context.spanIdHex))
          .map((correlationId, spanId) => assertEquals(correlationId.value, spanId))
      }
    }
  }

  test("outside a span fromSpanOrRandom still produces an id") {
    given Tracer[IO] = Tracer.noop[IO]

    Correlation.fromSpanOrRandom[IO].map(id => assertEquals(id.value.length, 16))
  }

  test("a caller-supplied id is accepted only when it is safe to echo") {
    assertEquals(Correlation.accept("abc-123").map(_.value), Some("abc-123"))
    // A header value reaches a response header and a log line. An unchecked one is how a newline,
    // or 4 KB of someone else's choosing, gets into a log file.
    assertEquals(Correlation.accept("abc\n123"), None)
    assertEquals(Correlation.accept(""), None)
    assertEquals(Correlation.accept("x" * 65), None)
  }
}
