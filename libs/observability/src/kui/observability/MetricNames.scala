package kui.observability

/** Every metric KUI emits, as a constant.
  *
  * A metric name is an interface. Someone builds a dashboard on `kui.upstream.duration`, and a rename — or a
  * second module inventing `kui.upstream.latency` for the same thing — breaks it silently: the panel does not
  * fail, it just goes empty, and nobody notices until an incident. Names living here as constants means a
  * rename is a compile error at every call site, and `MetricNamesSuite` asserts this list against the one in
  * PLAN §30 and `ARCHITECTURE.md` §13, so the plan and the code cannot drift apart either.
  *
  * The milestone in a comment is when the metric starts being *emitted*. Every name is declared now, because
  * the alternative is discovering during M3 that the obvious name was already taken by something else.
  */
object MetricNames {

  /** How long a request took, by route and status. The one metric that answers "is KUI slow". */
  val HttpServerDuration: String = "kui.http.server.duration" // {service, route, status}

  /** How long a call to another system took, and how it ended. */
  val UpstreamDuration: String = "kui.upstream.duration" // {service, upstream, outcome}

  /** Whether an upstream's circuit breaker is closed, open or half-open. */
  val UpstreamCircuitState: String = "kui.upstream.circuit.state" // {upstream}

  val KafkaAdminDuration: String = "kui.kafka.admin.duration" // {cluster, operation, outcome} (M1)
  val KafkaConsumeRecords: String = "kui.kafka.consume.records" // {cluster, topic}              (M3)
  val KafkaConsumeBytes: String = "kui.kafka.consume.bytes" // {cluster, topic}                  (M3)

  val CacheHits: String = "kui.cache.hits" // {cache}
  val CacheMisses: String = "kui.cache.misses" // {cache}

  /** What the UI is allowed to show, per service and cluster (ADR-039). */
  val CapabilityState: String = "kui.capability.state" // {service, cluster, state}

  val StreamEvents: String = "kui.stream.events" // {service, stream, event}

  /** Open streams. A gauge that never returns to zero is a leak, which is exactly what it is for. */
  val StreamActive: String = "kui.stream.active" // {service, stream}

  val CursorRejected: String = "kui.cursor.rejected" // {reason}                                 (M3)
  val PrincipalRejected: String = "kui.principal.rejected" // {reason}
  val ConfigVersion: String = "kui.config.version" // {section}                                  (M1)

  /** Every name, in the order PLAN §30 lists them followed by the `ARCHITECTURE.md` §13 additions.
    */
  val all: List[String] = List(
    HttpServerDuration,
    UpstreamDuration,
    UpstreamCircuitState,
    KafkaAdminDuration,
    KafkaConsumeRecords,
    KafkaConsumeBytes,
    CacheHits,
    CacheMisses,
    CapabilityState,
    StreamEvents,
    StreamActive,
    CursorRejected,
    PrincipalRejected,
    ConfigVersion
  )

  /** The attribute keys, for the same reason the metric names are here: a label that drifts from `upstream`
    * to `upstreamName` in one module splits a dashboard's series in two without failing anything.
    */
  object Attr {
    val Service: String = "service"
    val Route: String = "route"
    val Status: String = "status"
    val Upstream: String = "upstream"
    val Outcome: String = "outcome"
    val Cluster: String = "cluster"
    val Operation: String = "operation"
    val Cache: String = "cache"
    val State: String = "state"
    val Stream: String = "stream"
    val Event: String = "event"
    val Reason: String = "reason"
    val Section: String = "section"
    val Topic: String = "topic"

    val all: List[String] = List(
      Service,
      Route,
      Status,
      Upstream,
      Outcome,
      Cluster,
      Operation,
      Cache,
      State,
      Stream,
      Event,
      Reason,
      Section,
      Topic
    )
  }
}

/** How a call to another system ended, as the value of the `outcome` label.
  *
  * An enum rather than free strings because `outcome` is what an operator groups by when an upstream
  * misbehaves, and "timeout" appearing as `timeout`, `Timeout` and `timed_out` in three modules makes that
  * grouping useless.
  */
enum UpstreamOutcome {
  case Success, ClientError, ServerError, Timeout, CircuitOpen, Unreachable

  def wire: String = this match {
    case Success => "success"
    case ClientError => "client_error"
    case ServerError => "server_error"
    case Timeout => "timeout"
    case CircuitOpen => "circuit_open"
    case Unreachable => "unreachable"
  }
}

object UpstreamOutcome {

  /** The outcome for a response that came back, by status. */
  def ofStatus(status: Int): UpstreamOutcome =
    if status >= 500 then ServerError
    else if status >= 400 then ClientError
    else Success

  def fromWire(raw: String): Option[UpstreamOutcome] = values.find(_.wire == raw)

  given CanEqual[UpstreamOutcome, UpstreamOutcome] = CanEqual.derived
}
