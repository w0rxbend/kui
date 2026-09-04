package kui.http

import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger, StructuredLogger}

/** log4cats' factory over the one logger a KUI process already has.
  *
  * `libs/config`'s store components ask for a logger through log4cats' factory rather than taking one as a
  * parameter, and this process builds exactly one logger at startup. Publishing that logger as a factory is
  * what keeps both halves writing the same format: two logging paths in one process is how half the lines end
  * up looking different from the other half, usually discovered while grepping during an incident.
  */
object ProcessLoggerFactory {

  def of[F[_]: cats.Applicative](logger: StructuredLogger[F]): LoggerFactory[F] =
    new SingleLoggerFactory[F](SelfAware.of(logger))

    /** Wraps a plain structured logger as a self-aware one, which is the shape log4cats' factory produces.
      *
      * Every level answers "enabled": the actual filtering is Logback's, configured by the XML in
      * `libs/observability`, and a second filter here would be a second place to look when a line is missing.
      */
  private object SelfAware {
    def of[F[_]: cats.Applicative](logger: StructuredLogger[F]): SelfAwareStructuredLogger[F] =
      new SelfAwareStructuredLogger[F] {
        def isTraceEnabled: F[Boolean] = cats.Applicative[F].pure(true)
        def isDebugEnabled: F[Boolean] = cats.Applicative[F].pure(true)
        def isInfoEnabled: F[Boolean] = cats.Applicative[F].pure(true)
        def isWarnEnabled: F[Boolean] = cats.Applicative[F].pure(true)
        def isErrorEnabled: F[Boolean] = cats.Applicative[F].pure(true)

        def trace(ctx: Map[String, String])(msg: => String): F[Unit] = logger.trace(ctx)(msg)
        def debug(ctx: Map[String, String])(msg: => String): F[Unit] = logger.debug(ctx)(msg)
        def info(ctx: Map[String, String])(msg: => String): F[Unit] = logger.info(ctx)(msg)
        def warn(ctx: Map[String, String])(msg: => String): F[Unit] = logger.warn(ctx)(msg)
        def error(ctx: Map[String, String])(msg: => String): F[Unit] = logger.error(ctx)(msg)

        def trace(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] = logger.trace(ctx, t)(msg)
        def debug(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] = logger.debug(ctx, t)(msg)
        def info(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] = logger.info(ctx, t)(msg)
        def warn(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] = logger.warn(ctx, t)(msg)
        def error(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] = logger.error(ctx, t)(msg)

        def trace(msg: => String): F[Unit] = logger.trace(msg)
        def debug(msg: => String): F[Unit] = logger.debug(msg)
        def info(msg: => String): F[Unit] = logger.info(msg)
        def warn(msg: => String): F[Unit] = logger.warn(msg)
        def error(msg: => String): F[Unit] = logger.error(msg)

        def trace(t: Throwable)(msg: => String): F[Unit] = logger.trace(t)(msg)
        def debug(t: Throwable)(msg: => String): F[Unit] = logger.debug(t)(msg)
        def info(t: Throwable)(msg: => String): F[Unit] = logger.info(t)(msg)
        def warn(t: Throwable)(msg: => String): F[Unit] = logger.warn(t)(msg)
        def error(t: Throwable)(msg: => String): F[Unit] = logger.error(t)(msg)
      }
  }

  /** log4cats' factory over the one logger this process already has.
    *
    * Every name resolves to the same logger, which is correct here rather than lazy: KUI's log lines carry
    * their origin as a structured field, and a per-name logger would put the same information in two places
    * with two formats.
    */
  final private class SingleLoggerFactory[F[_]: cats.Applicative](logger: SelfAwareStructuredLogger[F])
      extends LoggerFactory[F] {
    def getLoggerFromName(name: String): SelfAwareStructuredLogger[F] = logger
    def fromName(name: String): F[SelfAwareStructuredLogger[F]] = cats.Applicative[F].pure(logger)
  }
}
