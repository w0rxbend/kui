package kui.observability

import cats.Monad
import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer

/** The one way KUI code obtains a logger.
  *
  * Two things are true of every entry produced through here, and they are the reason it exists rather than
  * each module calling `Slf4jLogger.create` for itself:
  *
  *   - it carries `service.name`, so a log system with every KUI process in it can be filtered to one of
  *     them;
  *   - the context keys are the exact strings in `ARCHITECTURE.md` §13, because they come from
  *     [[ContextKeys]] and never from a string literal at a call site.
  */
object KuiLogger {

  /** A logger for one process. `serviceName` becomes `service.name` on every entry it produces. */
  def make[F[_]: Sync](serviceName: String): F[StructuredLogger[F]] =
    Slf4jLogger
      .create[F]
      .map(logger => StructuredLogger.withContext(logger)(Map(ContextKeys.ServiceName -> serviceName)))

  /** Adds the request-scoped keys to every entry the returned logger produces.
    *
    * Absent fields add no key at all, so a background scheduler's entries are not padded with four nulls. See
    * [[withSpanContext]] for the version that also carries the trace and span ids.
    */
  def withContext[F[_]](logger: StructuredLogger[F], ctx: LogContext): StructuredLogger[F] =
    StructuredLogger.withContext(logger)(ctx.toMap)

  /** [[withContext]] plus `trace_id` and `span_id` for the span that is current when this is called.
    *
    * It is effectful, and has to be: "the current span" is a property of the fiber at the moment of the call,
    * so a pure function could not read it. Callers that are inside a request — which is every caller that has
    * a `LogContext` worth setting — build the logger once at the top of the request and use it throughout.
    */
  def withSpanContext[F[_]: {Monad, Tracer}](
      logger: StructuredLogger[F],
      ctx: LogContext
  ): F[StructuredLogger[F]] =
    MdcBridge
      .currentSpanIds[F]
      .map(spanIds => StructuredLogger.withContext(logger)(ctx.toMap ++ spanIds))
}
