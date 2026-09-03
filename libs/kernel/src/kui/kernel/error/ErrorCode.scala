package kui.kernel.error

/** The stable machine-readable name of a failure, its HTTP status, and whether retrying it can help.
  *
  * Three things live on one enum on purpose (ADR-034):
  *
  *   - `wire` is what an operator sees in a log line and what the browser switches on. It is a string rather
  *     than a number because `KUI-TOPIC-NOT-FOUND` is searchable and `4007` is not, and it is a contract:
  *     renaming one is a breaking change reviewed like any other.
  *   - `httpStatus` is here rather than in a lookup table beside the enum so that the code and the status it
  *     is served with cannot drift apart. There is exactly one mapping, and it is this one.
  *   - `description` is the sentence `docs/api/error-codes.md` prints. It is a constructor parameter, so the
  *     compiler refuses a new case that has no description and the generated document can never fall behind
  *     the enum (ADR-034 amendment 2, task KERN-008).
  *
  * `retryable` answers "would the same request work later, unchanged?". A missing topic is not retryable —
  * the request is fine, the topic is not there. An upstream that is rebalancing is.
  */
enum ErrorCode(
    val wire: String,
    val httpStatus: Int,
    val retryable: Boolean,
    val description: String
) {

  case ClusterNotFound
      extends ErrorCode(
        "KUI-CLUSTER-NOT-FOUND",
        404,
        false,
        "No cluster with this id is configured in KUI."
      )

  case TopicNotFound
      extends ErrorCode(
        "KUI-TOPIC-NOT-FOUND",
        404,
        false,
        "The topic does not exist on this cluster."
      )

  case SchemaNotFound
      extends ErrorCode(
        "KUI-SCHEMA-NOT-FOUND",
        404,
        false,
        "The subject or schema version does not exist in the schema registry."
      )

  case Validation
      extends ErrorCode(
        "KUI-VALIDATION",
        400,
        false,
        "The request is not valid; the details array names each field and what it must satisfy."
      )

  case ReadOnly
      extends ErrorCode(
        "KUI-READ-ONLY",
        405,
        false,
        "The cluster is configured read-only, so no operation that changes it is accepted."
      )

  case ConnectRebalancing
      extends ErrorCode(
        "KUI-CONNECT-REBALANCING",
        409,
        true,
        "The Kafka Connect cluster is rebalancing and cannot answer yet; retry shortly."
      )

  case InvalidState
      extends ErrorCode(
        "KUI-INVALID-STATE",
        409,
        false,
        "The target is not in a state where this operation is allowed."
      )

  case Timeout
      extends ErrorCode(
        "KUI-TIMEOUT",
        408,
        true,
        "The operation did not finish inside its time budget."
      )

  case FilterCompile
      extends ErrorCode(
        "KUI-FILTER-COMPILE",
        400,
        false,
        "The smart filter expression could not be compiled."
      )

  case ConnectorOffsets
      extends ErrorCode(
        "KUI-CONNECTOR-OFFSETS",
        400,
        false,
        "The connector offsets in the request are not valid for this connector."
      )

  case UpstreamKsql
      extends ErrorCode(
        "KUI-UPSTREAM-KSQL",
        502,
        true,
        "ksqlDB answered with an error or an unusable response."
      )

  case UpstreamAuth
      extends ErrorCode(
        "KUI-UPSTREAM-AUTH",
        502,
        false,
        "KUI's credentials for the upstream system were rejected; the configuration needs fixing."
      )

  case UpstreamUnavailable
      extends ErrorCode(
        "KUI-UPSTREAM-UNAVAILABLE",
        503,
        true,
        "The upstream system could not be reached, or its circuit breaker is open."
      )

  case Unsupported
      extends ErrorCode(
        "KUI-UNSUPPORTED",
        501,
        false,
        "This cluster or upstream does not support the requested feature."
      )

  case Forbidden
      extends ErrorCode(
        "KUI-FORBIDDEN",
        403,
        false,
        "The authenticated principal does not hold a role that permits this operation."
      )

  case Unauthenticated
      extends ErrorCode(
        "KUI-UNAUTHENTICATED",
        401,
        false,
        "The request carried no valid identity; sign in, or check the signed principal header."
      )

  case CursorExpired
      extends ErrorCode(
        "KUI-CURSOR-EXPIRED",
        400,
        false,
        "The paging cursor is past its expiry; start the listing again."
      )

  case CursorInvalid
      extends ErrorCode(
        "KUI-CURSOR-INVALID",
        400,
        false,
        "The paging cursor is malformed, was not signed by this deployment, or is for another topic."
      )

  case CursorTooLarge
      extends ErrorCode(
        "KUI-CURSOR-TOO-LARGE",
        400,
        false,
        "The paging cursor exceeds the size limit; narrow the request to fewer partitions."
      )

  case ConfigVersionConflict
      extends ErrorCode(
        "KUI-CONFIG-VERSION-CONFLICT",
        409,
        false,
        "The configuration changed since it was read; re-read it and apply the change again."
      )

  case StoreUnavailable
      extends ErrorCode(
        "KUI-STORE-UNAVAILABLE",
        503,
        true,
        "KUI's metadata store could not be reached; reads are served from the last replayed state and writes are rejected."
      )

  case StoreReplayTimeout
      extends ErrorCode(
        "KUI-STORE-REPLAY-TIMEOUT",
        503,
        true,
        "The metadata store's log could not be replayed to its end within the configured timeout."
      )

  case StoreTopicIncompatible
      extends ErrorCode(
        "KUI-STORE-TOPIC-INCOMPATIBLE",
        500,
        false,
        "An existing metadata-store topic has settings KUI cannot use, and KUI never rewrites an existing topic's configuration."
      )

  case StoreEnvelope
      extends ErrorCode(
        "KUI-STORE-ENVELOPE",
        500,
        false,
        "A record in the metadata store is not a readable envelope for this version of KUI."
      )

  case StoreCrypto
      extends ErrorCode(
        "KUI-STORE-CRYPTO",
        500,
        false,
        "An encrypted field in the metadata store could not be decrypted with any configured key."
      )

  case StoreNotConfigured
      extends ErrorCode(
        "KUI-STORE-NOT-CONFIGURED",
        501,
        false,
        "No metadata store is configured, so this change cannot be persisted; configure kui.store.kafka.* to enable it."
      )

  case RouteNotFound
      extends ErrorCode(
        "KUI-ROUTE-NOT-FOUND",
        404,
        false,
        "No endpoint matches this method and path."
      )

  case Internal
      extends ErrorCode(
        "KUI-INTERNAL",
        500,
        false,
        "KUI failed unexpectedly; the correlation id ties this response to the server-side log."
      )

  /** The `<AREA>` of `KUI-<AREA>-<NAME>`: the second dash-separated segment of the wire string.
    *
    * It exists so that the generated error-code document can group the table, and it is derived rather than
    * declared so that it cannot disagree with the code it groups.
    */
  def area: String = wire.split('-').lift(1).getOrElse("GENERAL")
}

object ErrorCode {

  /** Reads a code back from the wire. `None` for anything unrecognised — a client talking to a newer KUI must
    * fall back to the envelope's `message` rather than fail to parse the response.
    */
  def fromWire(raw: String): Option[ErrorCode] = values.find(_.wire == raw)

  given CanEqual[ErrorCode, ErrorCode] = CanEqual.derived
}
