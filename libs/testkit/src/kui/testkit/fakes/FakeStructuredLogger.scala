package kui.testkit.fakes

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import org.typelevel.log4cats.StructuredLogger

/** One line this logger was asked to write. */
final case class LogEntry(
    level: String,
    message: String,
    context: Map[String, String],
    throwable: Option[Throwable]
)

/** A logger that records instead of printing.
  *
  * KUI's observability requirements are written as "the log line carries `cluster.id`" and "a rejected
  * principal is logged once with its reason". Those are assertions, not aspirations, and this is what lets a
  * suite make them: the entries are values a test can look at, with their structured context intact.
  */
final class FakeStructuredLogger[F[_]: Sync] private (state: Ref[F, Vector[LogEntry]])
    extends StructuredLogger[F] {

  def entries: F[List[LogEntry]] = state.get.map(_.toList)

  /** Every entry whose context has this key, which is the usual shape of an observability assertion: "the
    * cluster id reached the log".
    */
  def entriesWith(key: String): F[List[LogEntry]] = entries.map(_.filter(_.context.contains(key)))

  def reset: F[Unit] = state.set(Vector.empty)

  private def record(
      level: String,
      message: => String,
      context: Map[String, String] = Map.empty,
      throwable: Option[Throwable] = None
  ): F[Unit] =
    state.update(_ :+ LogEntry(level, message, context, throwable))

  def trace(message: => String): F[Unit] = record("trace", message)
  def debug(message: => String): F[Unit] = record("debug", message)
  def info(message: => String): F[Unit] = record("info", message)
  def warn(message: => String): F[Unit] = record("warn", message)
  def error(message: => String): F[Unit] = record("error", message)

  def trace(t: Throwable)(message: => String): F[Unit] = record("trace", message, Map.empty, Some(t))
  def debug(t: Throwable)(message: => String): F[Unit] = record("debug", message, Map.empty, Some(t))
  def info(t: Throwable)(message: => String): F[Unit] = record("info", message, Map.empty, Some(t))
  def warn(t: Throwable)(message: => String): F[Unit] = record("warn", message, Map.empty, Some(t))
  def error(t: Throwable)(message: => String): F[Unit] = record("error", message, Map.empty, Some(t))

  def trace(ctx: Map[String, String])(message: => String): F[Unit] = record("trace", message, ctx)
  def debug(ctx: Map[String, String])(message: => String): F[Unit] = record("debug", message, ctx)
  def info(ctx: Map[String, String])(message: => String): F[Unit] = record("info", message, ctx)
  def warn(ctx: Map[String, String])(message: => String): F[Unit] = record("warn", message, ctx)
  def error(ctx: Map[String, String])(message: => String): F[Unit] = record("error", message, ctx)

  def trace(ctx: Map[String, String], t: Throwable)(message: => String): F[Unit] =
    record("trace", message, ctx, Some(t))
  def debug(ctx: Map[String, String], t: Throwable)(message: => String): F[Unit] =
    record("debug", message, ctx, Some(t))
  def info(ctx: Map[String, String], t: Throwable)(message: => String): F[Unit] =
    record("info", message, ctx, Some(t))
  def warn(ctx: Map[String, String], t: Throwable)(message: => String): F[Unit] =
    record("warn", message, ctx, Some(t))
  def error(ctx: Map[String, String], t: Throwable)(message: => String): F[Unit] =
    record("error", message, ctx, Some(t))
}

object FakeStructuredLogger {

  def apply[F[_]: Sync]: F[FakeStructuredLogger[F]] =
    Ref.of[F, Vector[LogEntry]](Vector.empty).map(new FakeStructuredLogger(_))
}
