package kui.http.upstream

import java.util.concurrent.TimeoutException

import cats.effect.IO
import cats.effect.kernel.Ref
import io.opentelemetry.api.trace.SpanKind as OtelSpanKind
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.client4.{basicRequest, Backend, GenericRequest}
import sttp.model.{StatusCode, Uri}

import kui.observability.{Telemetry, UpstreamInstrumentation, UpstreamOutcome}

/** The client-side half of the observability standard, which had no suite anywhere.
  *
  * `UpstreamInstrumentation` is constructed by exactly one caller — `UpstreamClient`, which is itself
  * constructed by composition roots — and `UpstreamClientSuite` asserts what came *back* from a call rather
  * than what the call left behind. Three rules the file argues for at length were therefore held by nothing:
  * the six-value outcome, the span kind, and the caller's own `traceparent`. Each was reversed with
  * `libs.http.test` at 150 of 150 and every other `libs` suite green.
  *
  * It lives in `libs/http/test` rather than beside its source because this is the module whose test
  * dependencies already carry an sttp stub, an in-memory trace exporter and a cats `MonadError` — and
  * `libs/http` is `libs/observability`'s consumer, so the dependency runs the right way.
  */
final class UpstreamInstrumentationSuite extends CatsEffectSuite {

  private val target: Uri = Uri.unsafeParse("http://registry:8081/subjects")

  /** A backend that answers once and keeps every request it was handed. */
  private def recording(seen: Ref[IO, List[GenericRequest[?, ?]]]): Backend[IO] =
    BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
      .thenRespondF(sent => seen.update(_ :+ sent).as(ResponseStub.adjust("{}", StatusCode.Ok)))

  private def wrapped(
      testkit: TracesTestkit[IO],
      seen: Ref[IO, List[GenericRequest[?, ?]]]
  ): IO[Backend[IO]] =
    UpstreamInstrumentation.wrap[IO](
      recording(seen),
      Telemetry.fromProviders(testkit.tracerProvider, MeterProvider.noop[IO]),
      "kui-schema",
      "schema-registry"
    )

  // -----------------------------------------------------------------------------------------------

  test("a call that timed out and a call that was refused are two different outcomes") {
    // The whole argument for `outcome` existing instead of a status code: "a dashboard grouped by HTTP
    // status cannot distinguish 'the upstream refused the connection' from 'we gave up waiting', and
    // those have different causes and different fixes." Collapsing `Timeout` into `Unreachable` left
    // 835 cases over nine `libs` modules green, and an operator's dashboard then says "unreachable"
    // about a system that answered every packet and was merely slow.
    assertEquals(
      UpstreamInstrumentation.outcomeOf(Left(new TimeoutException("no answer in 2s"))),
      UpstreamOutcome.Timeout
    )
    assertEquals(
      UpstreamInstrumentation.outcomeOf(Left(new java.net.ConnectException("refused"))),
      UpstreamOutcome.Unreachable
    )
    // And the three the status decides, so the six values are not two.
    assertEquals(UpstreamInstrumentation.outcomeOf(Right(204)), UpstreamOutcome.Success)
    assertEquals(UpstreamInstrumentation.outcomeOf(Right(404)), UpstreamOutcome.ClientError)
    assertEquals(UpstreamInstrumentation.outcomeOf(Right(503)), UpstreamOutcome.ServerError)
  }

  test("a call that leaves the process is a client span, not an internal one") {
    // A trace backend lays a trace out from the kind. Drawn as `Internal`, the call KUI made to another
    // system becomes a step inside KUI's own work, and the picture answers "was it us or them" wrongly —
    // which is the one question this instrumentation exists for.
    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        seen <- Ref.of[IO, List[GenericRequest[?, ?]]](Nil)
        backend <- wrapped(testkit, seen)
        _ <- basicRequest.get(target).response(sttp.client4.asStringAlways).send(backend)
        spans <- testkit.finishedSpans
      } yield {
        assertEquals(spans.map(_.getName), List("GET schema-registry"))
        assertEquals(spans.map(_.getKind), List(OtelSpanKind.CLIENT))
      }
    }
  }

  test("the outgoing request carries this span's traceparent") {
    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        seen <- Ref.of[IO, List[GenericRequest[?, ?]]](Nil)
        backend <- wrapped(testkit, seen)
        _ <- basicRequest.get(target).response(sttp.client4.asStringAlways).send(backend)
        sent <- seen.get
        spans <- testkit.finishedSpans
      } yield {
        val header = sent.head.header(UpstreamInstrumentation.TraceparentHeader)
        val span = spans.head
        // W3C: version, trace id, span id, flags — and the two ids are this span's, which is what
        // joins KUI's span to the upstream's own trace.
        assertEquals(
          header,
          Some(s"00-${span.getTraceId}-${span.getSpanId}-01")
        )
      }
    }
  }

  test("a traceparent the caller already set is not overwritten") {
    // The caller is the one that knows which trace this call belongs to. Overwriting its header
    // re-parents the upstream's spans under KUI's own, and the two traces then cannot be joined at
    // all — the failure is invisible from inside KUI and only shows up in the other system's backend.
    val supplied = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

    TracesTestkit.inMemory[IO]().use { testkit =>
      for {
        seen <- Ref.of[IO, List[GenericRequest[?, ?]]](Nil)
        backend <- wrapped(testkit, seen)
        _ <- basicRequest
          .get(target)
          .header(UpstreamInstrumentation.TraceparentHeader, supplied)
          .response(sttp.client4.asStringAlways)
          .send(backend)
        sent <- seen.get
      } yield assertEquals(
        sent.head.header(UpstreamInstrumentation.TraceparentHeader),
        Some(supplied)
      )
    }
  }
}
