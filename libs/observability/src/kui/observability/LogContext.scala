package kui.observability

import kui.kernel.{ClusterId, CorrelationId}

/** The names of the context keys every KUI log entry and span attribute uses.
  *
  * These exact strings are a contract (`ARCHITECTURE.md` §13), not a convention. An operator searching a log
  * system types `correlation.id:abc123` and expects every service to have used the same spelling; one module
  * writing `correlationId` instead would be invisible to that search and would only be discovered during an
  * incident. They live here as constants so that no call site spells one by hand, and `KuiLoggerSuite`
  * asserts the exact set.
  */
object ContextKeys {
  val CorrelationId: String = "correlation.id"
  val UserId: String = "user.id"
  val ClusterId: String = "cluster.id"
  val ServiceName: String = "service.name"
  val Operation: String = "operation"

  /** The trace and span ids bridged from the current OpenTelemetry span.
    *
    * Underscores rather than dots, because these two are the names the OpenTelemetry logging conventions use
    * and the names a trace-search backend already indexes.
    */
  val TraceId: String = "trace_id"
  val SpanId: String = "span_id"

  /** The five KUI keys, in the order they are documented. */
  val all: List[String] = List(CorrelationId, UserId, ClusterId, ServiceName, Operation)
}

/** What is true of the request a log entry belongs to.
  *
  * Everything is optional because the same logger is used before a request exists (startup), during one (all
  * four set) and in a background scheduler (no user, no correlation id). A field that is absent produces no
  * key at all rather than a key with a null value: `"user.id": null` on every line from every scheduler is
  * noise that makes the real entries harder to find.
  *
  * @param userId
  *   already hashed by the caller when `kui.telemetry.hashUserIds` is on. This type does not hash, because
  *   the salt is a deployment concern and passing it down here would make every caller carry it.
  */
final case class LogContext(
    correlationId: Option[CorrelationId],
    userId: Option[String],
    clusterId: Option[ClusterId],
    operation: Option[String]
) {

  def toMap: Map[String, String] =
    List(
      correlationId.map(id => ContextKeys.CorrelationId -> id.value),
      userId.map(ContextKeys.UserId -> _),
      clusterId.map(id => ContextKeys.ClusterId -> id.value),
      operation.map(ContextKeys.Operation -> _)
    ).flatten.toMap

  def withCorrelationId(id: CorrelationId): LogContext = copy(correlationId = Some(id))
  def withOperation(name: String): LogContext = copy(operation = Some(name))
}

object LogContext {
  val empty: LogContext = LogContext(None, None, None, None)

  def of(correlationId: CorrelationId, operation: String): LogContext =
    LogContext(Some(correlationId), None, None, Some(operation))

  given CanEqual[LogContext, LogContext] = CanEqual.derived
}
