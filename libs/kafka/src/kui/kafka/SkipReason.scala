package kui.kafka

import kui.kernel.error.ErrorCode

/** Why one key of a batch is missing from the result.
  *
  * It is an ADT rather than a string because the caller acts on it, and acts differently on each case:
  * `NotAuthorized` renders a lock icon, `Unsupported` renders a dash, `NoLeader` renders "offline", and
  * `Failed` renders the error code. A string would be rendered as prose, or — far more likely — ignored,
  * which puts us back where the reference implementations are.
  */
enum SkipReason {
  case NotFound(detail: String)
  case NotAuthorized(detail: String)
  case Unsupported(feature: String)

  /** A partition with no leader. It has its own case because it is not a failure at all: an offset lookup
    * against a leaderless partition retries until the API timeout rather than answering, so the port filters
    * those partitions out and says so here (KAFKA-006).
    */
  case NoLeader

  case Failed(code: ErrorCode, detail: String)

  /** One short sentence, for a tooltip or a log line.
    *
    * `detail` is always KUI's own words — "no DESCRIBE_CONFIGS on this broker" — and never a broker's raw
    * exception text, for the reason `KafkaErrorMapper` documents at length.
    */
  def message: String = this match {
    case NotFound(detail) => s"not found: $detail"
    case NotAuthorized(detail) => s"not authorized: $detail"
    case Unsupported(feature) => s"$feature is not supported by this cluster"
    case NoLeader => "the partition has no leader"
    case Failed(code, detail) => s"${code.wire}: $detail"
  }
}

object SkipReason {
  given CanEqual[SkipReason, SkipReason] = CanEqual.derived
}
