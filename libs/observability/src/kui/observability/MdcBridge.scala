package kui.observability

import cats.Applicative
import cats.syntax.all.*
import org.typelevel.otel4s.trace.Tracer

/** Puts the current span's ids where a log line can carry them.
  *
  * The problem this solves is ordinary and constant: an operator finds a suspicious log line and wants the
  * trace it belongs to, or finds a slow trace and wants its log lines. Without the ids on the line, the only
  * way across is guessing by timestamp.
  *
  * The ids reach SLF4J's MDC — the per-thread map Logback's JSON encoder writes into every entry — because
  * `log4cats`'s SLF4J backend sets MDC from the context map of a `StructuredLogger` for the duration of the
  * call and clears it afterwards. That "and clears it afterwards" is why this is safe under cats-effect:
  * fibers move between threads, and an MDC that was set once and left behind would attach one request's trace
  * id to another request's log line. Nothing here ever writes MDC directly.
  */
object MdcBridge {

  /** `trace_id` and `span_id` for the span that is current *now*, or nothing outside a span.
    *
    * Returning an empty map rather than entries with placeholder values is deliberate: a log line produced
    * outside any span has no trace, and `trace_id: "00000000..."` on a startup line would be a link to a
    * trace that does not exist.
    */
  def currentSpanIds[F[_]: {Applicative, Tracer}]: F[Map[String, String]] =
    Tracer[F].currentSpanOrNoop.map { span =>
      val context = span.context
      if context.isValid then
        Map(
          ContextKeys.TraceId -> context.traceIdHex,
          ContextKeys.SpanId -> context.spanIdHex
        )
      else Map.empty
    }
}
