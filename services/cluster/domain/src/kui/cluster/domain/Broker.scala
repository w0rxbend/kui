package kui.cluster.domain

import cats.data.NonEmptyList

import kui.kernel.error.{DomainError, FieldError}
import kui.kernel.{BrokerId, Host, KafkaClusterId, Port, ValidationError}

/** The rack a broker is placed in, when the operator configured `broker.rack`.
  *
  * Always `Option[BrokerRack]` and never `""`. The Java client's `Node.rack()` is nullable, and an empty
  * string reaching the UI renders as a blank cell that looks like a rendering bug rather than like "this
  * cluster is not rack-aware". The smart constructor refuses blank input for the same reason.
  */
opaque type BrokerRack = String

object BrokerRack {

  private val Field: String = "rack"

  def from(raw: String): Either[ValidationError, BrokerRack] = {
    val trimmed = raw.trim

    if trimmed.isEmpty then Left(ValidationError.Format(Field, "a non-blank rack name", raw))
    else Right(trimmed)
  }

  def unsafe(raw: String): BrokerRack = raw

  extension (r: BrokerRack) def value: String = r

  given Ordering[BrokerRack] = Ordering.String
  given CanEqual[BrokerRack, BrokerRack] = CanEqual.derived
}

/** One Kafka server process, as cluster metadata reports it. */
final case class Broker(id: BrokerId, host: Host, port: Port, rack: Option[BrokerRack])

object Broker {
  given Ordering[Broker] = Ordering.by(_.id.value)
  given CanEqual[Broker, Broker] = CanEqual.derived
}

/** How this cluster manages its metadata.
  *
  * `Unknown` is a real answer and not a placeholder: a cluster that refused `describeMetadataQuorum` with a
  * `ClusterAuthorizationException` has a mode KUI is not allowed to learn, and reporting `ZooKeeper` because
  * the call failed would be a guess presented as a fact.
  */
enum ControllerMode {
  case ZooKeeper, KRaft, Unknown
}

object ControllerMode {
  given CanEqual[ControllerMode, ControllerMode] = CanEqual.derived
}

/** What `describeCluster` reported. */
final case class ClusterDescription private (
    kafkaClusterId: Option[KafkaClusterId],
    controller: Option[Broker],
    controllerMode: ControllerMode,
    brokers: NonEmptyList[Broker],
    authorizedOperations: Option[Set[String]]
) {

  def brokerCount: Int = brokers.length

  def broker(id: BrokerId): Option[Broker] = brokers.find(_.id == id)

  /** Broker ids in ascending order, which is the order every list screen and every batched admin call wants.
    */
  def brokerIds: NonEmptyList[BrokerId] = brokers.map(_.id).sortBy(_.value)
}

object ClusterDescription {

  /** Fails only on a duplicate broker id. Everything else that looks wrong here is legal Kafka:
    *
    *   - `controller = None` — `describeCluster().controller()` is `null` during a failover.
    *   - a `controller` that is **not** among `brokers` — in KRaft the active controller can be a dedicated
    *     node with `process.roles=controller`, which never appears in `nodes()`.
    *   - `kafkaClusterId = None` — some managed services do not report one.
    *   - `authorizedOperations = None` — ACLs are disabled, or the broker predates 2.3.
    *
    * Each of those is a case a reference product got wrong at least once, so each has a test.
    */
  def from(
      kafkaClusterId: Option[KafkaClusterId],
      controller: Option[Broker],
      controllerMode: ControllerMode,
      brokers: NonEmptyList[Broker],
      authorizedOperations: Option[Set[String]]
  ): Either[DomainError, ClusterDescription] = {
    val ids = brokers.toList.map(_.id.value)
    val duplicates = ids.diff(ids.distinct).distinct.sorted

    if duplicates.isEmpty then
      Right(
        ClusterDescription(kafkaClusterId, controller, controllerMode, brokers, authorizedOperations)
      )
    else
      Left(
        DomainError.InvariantViolation(
          s"the cluster reported the same broker id more than once: ${duplicates.mkString(", ")}",
          List(
            FieldError.of("brokers", s"broker ids must be unique, got ${duplicates.mkString(", ")}")
          )
        )
      )
  }

  given CanEqual[ClusterDescription, ClusterDescription] = CanEqual.derived
}
