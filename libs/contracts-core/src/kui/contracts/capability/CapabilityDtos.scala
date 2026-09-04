package kui.contracts.capability

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json, KeyDecoder, KeyEncoder}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{ClusterId, ServiceId}

/** Why a feature is not fully available.
  *
  * One vocabulary, shared by `CapabilityState`, by `Section` and by the SSE error event, so that the three
  * can never disagree about what "the upstream is down" is called. The strings are contract and appear as
  * metric label values (`kui.capability.state`), so they are written out rather than derived from the case
  * names.
  *
  * `Unknown` is not a placeholder for laziness: it is what a client decodes when a newer gateway sends a
  * reason this build has never heard of. An older browser must degrade to "something is wrong" rather than
  * fail to parse the response.
  */
enum ReasonCode {
  case UpstreamUnavailable, UpstreamTimeout, CircuitOpen, UpstreamAuth, NotConfigured, Forbidden,
    Starting, Unknown

  def wire: String = this match {
    case UpstreamUnavailable => "UPSTREAM_UNAVAILABLE"
    case UpstreamTimeout => "UPSTREAM_TIMEOUT"
    case CircuitOpen => "CIRCUIT_OPEN"
    case UpstreamAuth => "UPSTREAM_AUTH"
    case NotConfigured => "NOT_CONFIGURED"
    case Forbidden => "FORBIDDEN"
    case Starting => "STARTING"
    case Unknown => "UNKNOWN"
  }
}

object ReasonCode {

  /** Anything unrecognised becomes `Unknown` rather than a decode failure (ADR-032). */
  def fromWire(raw: String): ReasonCode = values.find(_.wire == raw).getOrElse(Unknown)

  given Codec[ReasonCode] =
    Codec.from(Decoder[String].map(fromWire), Encoder[String].contramap(_.wire))

  given Schema[ReasonCode] = Schema.string[ReasonCode].description("Why a capability is not available")

  given CanEqual[ReasonCode, ReasonCode] = CanEqual.derived
}

/** A capability that still works, but not well.
  *
  * The two optional numbers are what make "degraded" actionable instead of decorative: the client is told how
  * often it is worth asking again, and how slow the upstream currently is. Both are milliseconds because JSON
  * has no duration type — the gateway's own `CapabilityRegistry` holds `FiniteDuration`s and converts at this
  * boundary (`ARCHITECTURE.md` §4.5).
  */
final case class DegradedReason(
    code: ReasonCode,
    message: String,
    suggestedPollIntervalMs: Option[Long],
    p95Ms: Option[Long]
)

object DegradedReason {

  given Codec[DegradedReason] = Codec.from(
    (cursor: HCursor) =>
      for {
        code <- cursor.get[ReasonCode]("code")
        message <- cursor.get[String]("message")
        interval <- cursor.get[Option[Long]]("suggestedPollIntervalMs")
        p95 <- cursor.get[Option[Long]]("p95Ms")
      } yield DegradedReason(code, message, interval, p95),
    (reason: DegradedReason) =>
      Json.obj(
        "code" -> reason.code.asJson,
        "message" -> reason.message.asJson,
        "suggestedPollIntervalMs" -> reason.suggestedPollIntervalMs.asJson,
        "p95Ms" -> reason.p95Ms.asJson
      )
  )

  given Schema[DegradedReason] = Schema.derived[DegradedReason]

  given CanEqual[DegradedReason, DegradedReason] = CanEqual.derived
}

/** What a capability is about: one service, optionally on one cluster.
  *
  * `cluster` is `None` for something that does not vary per cluster — the identity service, say — and `Some`
  * for everything that does, which is most of KUI.
  */
final case class CapabilityKey(service: ServiceId, cluster: Option[ClusterId])

object CapabilityKey {

  given Codec[CapabilityKey] = Codec.from(
    (cursor: HCursor) =>
      for {
        service <- cursor.get[ServiceId]("service")
        cluster <- cursor.get[Option[ClusterId]]("cluster")
      } yield CapabilityKey(service, cluster),
    (key: CapabilityKey) => Json.obj("service" -> key.service.asJson, "cluster" -> key.cluster.asJson)
  )

  given Schema[CapabilityKey] = Schema.derived[CapabilityKey]

  given CanEqual[CapabilityKey, CapabilityKey] = CanEqual.derived
}

/** How well one capability is working right now (ADR-032).
  *
  * Four states rather than a boolean, because "is it up" is not a question a user interface can act on.
  * `Degraded` still has data and says how to pace requests; `Unavailable` says since when, so a user can tell
  * a blip from an outage; `NotConfigured` is not a failure at all — this deployment simply has no schema
  * registry on this cluster — and must not be rendered as an error.
  */
enum CapabilityState {
  case Available
  case Degraded(reason: DegradedReason)
  case Unavailable(reason: ReasonCode, message: String, since: Instant)
  case NotConfigured

  /** The `status` discriminator on the wire. These four strings are contract: they are what the browser
    * switches on and what the `kui.capability.state` metric is labelled with.
    */
  def status: String = this match {
    case Available => "available"
    case Degraded(_) => "degraded"
    case Unavailable(_, _, _) => "unavailable"
    case NotConfigured => "not_configured"
  }
}

object CapabilityState {

  given Codec[CapabilityState] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("status").flatMap {
        case "available" => Right(Available)
        case "not_configured" => Right(NotConfigured)
        case "degraded" => cursor.get[DegradedReason]("reason").map(Degraded.apply)
        case "unavailable" =>
          for {
            reason <- cursor.get[ReasonCode]("reason")
            message <- cursor.get[String]("message")
            since <- cursor.get[Instant]("since")
          } yield Unavailable(reason, message, since)
        case other =>
          Left(io.circe.DecodingFailure(s"'$other' is not a capability status", cursor.history))
      },
    (state: CapabilityState) =>
      state match {
        case Available => Json.obj("status" -> Json.fromString(state.status))
        case NotConfigured => Json.obj("status" -> Json.fromString(state.status))
        case Degraded(reason) =>
          Json.obj("status" -> Json.fromString(state.status), "reason" -> reason.asJson)
        case Unavailable(reason, message, since) =>
          Json.obj(
            "status" -> Json.fromString(state.status),
            "reason" -> reason.asJson,
            "message" -> message.asJson,
            "since" -> since.asJson
          )
      }
  )

  given Schema[CapabilityState] = Schema.derived[CapabilityState]

  given CanEqual[CapabilityState, CapabilityState] = CanEqual.derived
}

/** One capability and its state, with the moment the gateway last decided it.
  *
  * @param name
  *   what a person calls the thing this entry is about — a cluster's display name, as its operator wrote it
  *   in the configuration. `None` for a service-wide entry, which is about a service and not about anything a
  *   person named, and for a cluster whose service has not reported a name.
  *
  * It is here because the browser's shell renders the cluster switcher from the capability stream and from
  * nothing else, on purpose: the shell holds no cluster data, so that the cluster contract's decoders stay
  * out of the bundle every user downloads. Without a name on the stream the switcher had nothing to show but
  * the slug — `prod-eu-1` where the operator wrote `Production EU (primary)` — which is exactly the string
  * the switcher exists to stop people misreading.
  */
final case class CapabilityEntry(
    key: CapabilityKey,
    state: CapabilityState,
    updatedAt: Instant,
    name: Option[String] = None
)

object CapabilityEntry {

  given Codec[CapabilityEntry] = Codec.from(
    (cursor: HCursor) =>
      for {
        key <- cursor.get[CapabilityKey]("key")
        state <- cursor.get[CapabilityState]("state")
        updatedAt <- cursor.get[Instant]("updatedAt")
        // Absent rather than null in every frame an older gateway sends, so it decodes as "no name
        // was given" instead of failing the whole frame and blanking the sidebar.
        name <- cursor.getOrElse[Option[String]]("name")(None)
      } yield CapabilityEntry(key, state, updatedAt, name),
    (entry: CapabilityEntry) =>
      Json.obj(
        "key" -> entry.key.asJson,
        "state" -> entry.state.asJson,
        "updatedAt" -> entry.updatedAt.asJson,
        "name" -> entry.name.asJson
      )
  )

  given Schema[CapabilityEntry] = Schema.derived[CapabilityEntry]

  given CanEqual[CapabilityEntry, CapabilityEntry] = CanEqual.derived
}

/** Everything the gateway currently believes, sent once when a client connects to the capability stream.
  * Deltas follow (ADR-032's snapshot-then-deltas), which is why the snapshot carries the instant it was
  * generated: a client can tell how stale its picture is if the stream drops.
  */
final case class CapabilitySnapshot(entries: List[CapabilityEntry], generatedAt: Instant)

object CapabilitySnapshot {

  given Codec[CapabilitySnapshot] = Codec.from(
    (cursor: HCursor) =>
      for {
        entries <- cursor.getOrElse[List[CapabilityEntry]]("entries")(Nil)
        generatedAt <- cursor.get[Instant]("generatedAt")
      } yield CapabilitySnapshot(entries, generatedAt),
    (snapshot: CapabilitySnapshot) =>
      Json.obj(
        "entries" -> snapshot.entries.asJson,
        "generatedAt" -> snapshot.generatedAt.asJson
      )
  )

  given Schema[CapabilitySnapshot] = Schema.derived[CapabilitySnapshot]

  given CanEqual[CapabilitySnapshot, CapabilitySnapshot] = CanEqual.derived
}

/** One delta on the capability stream.
  *
  * `previous` is included so a client can react to the transition rather than only to the new state: "the
  * schema registry just came back" is worth a toast, "it is still down" is not.
  */
final case class CapabilityChange(entry: CapabilityEntry, previous: Option[CapabilityState])

object CapabilityChange {

  given Codec[CapabilityChange] = Codec.from(
    (cursor: HCursor) =>
      for {
        entry <- cursor.get[CapabilityEntry]("entry")
        previous <- cursor.get[Option[CapabilityState]]("previous")
      } yield CapabilityChange(entry, previous),
    (change: CapabilityChange) =>
      Json.obj("entry" -> change.entry.asJson, "previous" -> change.previous.asJson)
  )

  given Schema[CapabilityChange] = Schema.derived[CapabilityChange]

  given CanEqual[CapabilityChange, CapabilityChange] = CanEqual.derived
}

/** What one service says about one cluster when the gateway asks it.
  *
  * `configured` and `status` answer different questions and both are needed: a schema registry that is not
  * configured for this cluster is not broken, and a configured one that cannot be reached is not absent.
  * `features` is a list of strings rather than an enum because each service names its own features and the
  * gateway only passes them through.
  */
/** @param name
  *   the cluster's display name as its operator wrote it, when the service knows one. It travels here because
  *   this document is the only thing the gateway asks a service about its clusters, and the browser's cluster
  *   switcher is drawn from what the gateway learned.
  * @param reason
  *   why this cluster is not `available`, in the service's own words. Without it the gateway can only report
  *   "the service reports itself degraded", which tells an operator nothing they did not already see.
  */
final case class ClusterCapability(
    configured: Boolean,
    features: List[String],
    status: String,
    name: Option[String] = None,
    reason: Option[String] = None
)

object ClusterCapability {

  given Codec[ClusterCapability] = Codec.from(
    (cursor: HCursor) =>
      for {
        configured <- cursor.getOrElse[Boolean]("configured")(false)
        features <- cursor.getOrElse[List[String]]("features")(Nil)
        status <- cursor.get[String]("status")
        // Both optional and both defaulted, so a service built before they existed still decodes.
        name <- cursor.getOrElse[Option[String]]("name")(None)
        reason <- cursor.getOrElse[Option[String]]("reason")(None)
      } yield ClusterCapability(configured, features, status, name, reason),
    (capability: ClusterCapability) =>
      Json.obj(
        "configured" -> capability.configured.asJson,
        "features" -> capability.features.asJson,
        "status" -> capability.status.asJson,
        "name" -> capability.name.asJson,
        "reason" -> capability.reason.asJson
      )
  )

  given Schema[ClusterCapability] = Schema.derived[ClusterCapability]

  given CanEqual[ClusterCapability, ClusterCapability] = CanEqual.derived
}

/** The body of a service's own `GET /capabilities` (`ARCHITECTURE.md` §6), keyed by cluster. */
final case class ServiceCapabilities(service: ServiceId, clusters: Map[ClusterId, ClusterCapability])

object ServiceCapabilities {

  /** A cluster id is a JSON object key here, which needs its own pair of instances: a JSON key is always a
    * string, so this is where the identifier is unwrapped and re-validated.
    */
  private given KeyEncoder[ClusterId] = KeyEncoder.encodeKeyString.contramap(_.value)

  private given KeyDecoder[ClusterId] = KeyDecoder.instance(ClusterId.from(_).toOption)

  given Codec[ServiceCapabilities] = Codec.from(
    (cursor: HCursor) =>
      for {
        service <- cursor.get[ServiceId]("service")
        clusters <- cursor.getOrElse[Map[ClusterId, ClusterCapability]]("clusters")(Map.empty)
      } yield ServiceCapabilities(service, clusters),
    (capabilities: ServiceCapabilities) =>
      Json.obj(
        "service" -> capabilities.service.asJson,
        "clusters" -> capabilities.clusters.asJson
      )
  )

  /** Tapir needs to be told how an object key is spelled before it can describe a map keyed by anything but a
    * `String`; a cluster id is spelled as itself.
    */
  private given Schema[Map[ClusterId, ClusterCapability]] =
    Schema.schemaForMap[ClusterId, ClusterCapability](_.value)

  given Schema[ServiceCapabilities] = Schema.derived[ServiceCapabilities]

  given CanEqual[ServiceCapabilities, ServiceCapabilities] = CanEqual.derived
}
