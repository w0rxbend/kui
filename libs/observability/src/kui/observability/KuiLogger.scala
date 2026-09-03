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

  /** The logger a process should actually run with: [[make]] plus the trace and span ids.
    *
    * One call rather than two so that a composition root cannot get a logger that silently drops the ids,
    * which is what happened while [[withSpanContext]] was the only bridge. `instrumentation` is the tracer
    * name, the same `kui.<context>` string `KuiInterceptors` uses, so the lookup finds the spans the server
    * interceptors created.
    */
  def traced[F[_]: Sync](
      serviceName: String,
      telemetry: Telemetry[F],
      instrumentation: String
  ): F[StructuredLogger[F]] =
    for {
      logger <- make[F](serviceName)
      tracer <- telemetry.tracer(instrumentation)
    } yield {
      given Tracer[F] = tracer
      spanAware[F](logger)
    }

  /** Wraps a logger so that every entry it produces carries `trace_id` and `span_id` for the span that is
    * current *at the moment of the log call*.
    *
    * This is the difference that makes the bridge usable. [[withSpanContext]] reads the span once, when the
    * logger is built, so it only works for a caller that rebuilds its logger inside every span — which is why
    * no production call site ever managed to use it and no production log line ever carried a trace id. A
    * logger built once at startup and passed down, which is how every KUI process actually works, gets the
    * right ids from this one because the lookup happens per call.
    *
    * The ids are merged *after* the caller's own context, so a caller cannot accidentally overwrite them, and
    * an entry produced outside any span gets no keys at all rather than a link to a trace that does not
    * exist.
    */
  def spanAware[F[_]: {Sync, Tracer}](logger: StructuredLogger[F]): StructuredLogger[F] =
    new SpanAwareLogger[F](logger)

  final private class SpanAwareLogger[F[_]: {Sync, Tracer}](under: StructuredLogger[F])
      extends StructuredLogger[F] {

    private def withIds(ctx: Map[String, String])(emit: Map[String, String] => F[Unit]): F[Unit] =
      MdcBridge.currentSpanIds[F].flatMap(ids => emit(ctx ++ ids))

    def trace(ctx: Map[String, String])(msg: => String): F[Unit] = withIds(ctx)(under.trace(_)(msg))
    def debug(ctx: Map[String, String])(msg: => String): F[Unit] = withIds(ctx)(under.debug(_)(msg))
    def info(ctx: Map[String, String])(msg: => String): F[Unit] = withIds(ctx)(under.info(_)(msg))
    def warn(ctx: Map[String, String])(msg: => String): F[Unit] = withIds(ctx)(under.warn(_)(msg))
    def error(ctx: Map[String, String])(msg: => String): F[Unit] = withIds(ctx)(under.error(_)(msg))

    def trace(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withIds(ctx)(under.trace(_, t)(msg))
    def debug(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withIds(ctx)(under.debug(_, t)(msg))
    def info(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withIds(ctx)(under.info(_, t)(msg))
    def warn(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withIds(ctx)(under.warn(_, t)(msg))
    def error(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
      withIds(ctx)(under.error(_, t)(msg))

    def trace(msg: => String): F[Unit] = trace(Map.empty[String, String])(msg)
    def debug(msg: => String): F[Unit] = debug(Map.empty[String, String])(msg)
    def info(msg: => String): F[Unit] = info(Map.empty[String, String])(msg)
    def warn(msg: => String): F[Unit] = warn(Map.empty[String, String])(msg)
    def error(msg: => String): F[Unit] = error(Map.empty[String, String])(msg)

    def trace(t: Throwable)(msg: => String): F[Unit] = trace(Map.empty[String, String], t)(msg)
    def debug(t: Throwable)(msg: => String): F[Unit] = debug(Map.empty[String, String], t)(msg)
    def info(t: Throwable)(msg: => String): F[Unit] = info(Map.empty[String, String], t)(msg)
    def warn(t: Throwable)(msg: => String): F[Unit] = warn(Map.empty[String, String], t)(msg)
    def error(t: Throwable)(msg: => String): F[Unit] = error(Map.empty[String, String], t)(msg)
  }

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
