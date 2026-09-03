package kui.kernel

import scala.util.matching.Regex

/** The identifiers KUI passes between its services, its browser client and Kafka.
  *
  * Every one of them is an `opaque type`: at compile time it is a distinct type that a bare `String` or `Int`
  * cannot be substituted for, and at run time it is exactly the underlying primitive, so wrapping costs
  * nothing. The point is that a method taking `(TopicName, GroupId)` cannot be called with its two arguments
  * swapped, which is a mistake no test suite reliably catches.
  *
  * Each companion offers the same four things:
  *
  *   - `from(raw)`, the smart constructor, which returns `Left(ValidationError)` for a value that breaks the
  *     documented rule. This is the only way to build one from untrusted input.
  *   - `unsafe(raw)`, which skips the check. It is for values that were already validated somewhere else — a
  *     configuration file that was loaded and checked at startup, a literal in a test. It is deliberately
  *     named so that it stands out in a review.
  *   - the `value` extension, which unwraps back to the primitive at the edges (a Kafka client call, a JSON
  *     codec, a log line).
  *   - an `Ordering`, because these end up as map keys and in sorted lists constantly.
  *
  * The validation rules are decided in task KERN-001 and are binding on every service: a `TopicName` means
  * the same thing in the gateway and in `kui-message-service`, or the type is worthless.
  */

/** KUI's own identifier for a configured cluster: a URL-safe slug derived from the cluster's configured name
  * (ADR-031). It appears in every path (`/clusters/{clusterId}`), in RBAC role definitions, in cache keys and
  * in signed cursors. Renaming a cluster produces a new id, which is documented behaviour rather than an
  * accident.
  */
opaque type ClusterId = String

object ClusterId {
  private val Field: String = "clusterId"
  private val Pattern: Regex = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$".r
  private val Expected: String =
    "a lowercase slug of 1 to 64 letters, digits and dashes, starting and ending with a letter " +
      "or a digit"

  def from(raw: String): Either[ValidationError, ClusterId] =
    Checks.matching(Field, Pattern, Expected)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): ClusterId = raw

  extension (id: ClusterId) def value: String = id

  given Ordering[ClusterId] = Ordering.String
  given CanEqual[ClusterId, ClusterId] = CanEqual.derived
}

/** The cluster id the brokers report through `describeCluster` — a random 22-character string Kafka generates
  * when the cluster is first formed. KUI records it to warn when two configured entries point at the same
  * physical cluster, and pairs it with `BrokerId` in cache keys so that a reused broker id cannot collide
  * across clusters. Not every deployment exposes it, which is why the cluster snapshot holds an `Option` of
  * it.
  */
opaque type KafkaClusterId = String

object KafkaClusterId {
  private val Field: String = "kafkaClusterId"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, KafkaClusterId] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): KafkaClusterId = raw

  extension (id: KafkaClusterId) def value: String = id

  given Ordering[KafkaClusterId] = Ordering.String
  given CanEqual[KafkaClusterId, KafkaClusterId] = CanEqual.derived
}

/** The name of a Kafka topic. Kafka's own rule, reproduced here so a bad name is refused before it reaches a
  * broker: at least one and at most 249 characters drawn from letters, digits, `.`, `_` and `-`, and neither
  * `.` nor `..` on their own, because those two are meaningful in the directory layout Kafka stores
  * partitions in.
  */
opaque type TopicName = String

object TopicName {
  private val Field: String = "topicName"
  private val Pattern: Regex = "^[a-zA-Z0-9._-]+$".r
  private val Expected: String =
    "1 to 249 characters from letters, digits, '.', '_' and '-', and not '.' or '..'"
  private val Reserved: Set[String] = Set(".", "..")

  def from(raw: String): Either[ValidationError, TopicName] =
    if raw.length > 249 || Reserved.contains(raw) then Left(ValidationError.Format(Field, Expected, raw))
    else Checks.matching(Field, Pattern, Expected)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): TopicName = raw

  extension (id: TopicName) def value: String = id

  given Ordering[TopicName] = Ordering.String
  given CanEqual[TopicName, TopicName] = CanEqual.derived
}

/** A consumer group id. Kafka accepts almost any non-empty string here, so KUI only bounds the length rather
  * than inventing a shape the broker would have allowed.
  */
opaque type GroupId = String

object GroupId {
  private val Field: String = "groupId"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, GroupId] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): GroupId = raw

  extension (id: GroupId) def value: String = id

  given Ordering[GroupId] = Ordering.String
  given CanEqual[GroupId, GroupId] = CanEqual.derived
}

/** A Schema Registry subject: the name under which a schema's versions are registered, usually `<topic>-key`
  * or `<topic>-value`.
  */
opaque type Subject = String

object Subject {
  private val Field: String = "subject"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, Subject] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): Subject = raw

  extension (id: Subject) def value: String = id

  given Ordering[Subject] = Ordering.String
  given CanEqual[Subject, Subject] = CanEqual.derived
}

/** The name KUI's configuration gives to one Kafka Connect cluster, so that a connector can be addressed as
  * (connect cluster, connector) rather than by URL.
  */
opaque type ConnectName = String

object ConnectName {
  private val Field: String = "connectName"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, ConnectName] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): ConnectName = raw

  extension (id: ConnectName) def value: String = id

  given Ordering[ConnectName] = Ordering.String
  given CanEqual[ConnectName, ConnectName] = CanEqual.derived
}

/** The name of a connector running on a Connect cluster.
  */
opaque type ConnectorName = String

object ConnectorName {
  private val Field: String = "connectorName"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, ConnectorName] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): ConnectorName = raw

  extension (id: ConnectorName) def value: String = id

  given Ordering[ConnectorName] = Ordering.String
  given CanEqual[ConnectorName, ConnectorName] = CanEqual.derived
}

/** The identifier that ties one request to everything it caused: the log lines, the trace span, and the
  * `correlationId` field of the error envelope an operator reads (ADR-034). The gateway mints it per request
  * and passes it to every service it calls.
  */
opaque type CorrelationId = String

object CorrelationId {
  private val Field: String = "correlationId"
  private val Pattern: Regex = "^[A-Za-z0-9-]{1,64}$".r
  private val Expected: String = "1 to 64 letters, digits and dashes"

  def from(raw: String): Either[ValidationError, CorrelationId] =
    Checks.matching(Field, Pattern, Expected)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): CorrelationId = raw

  extension (id: CorrelationId) def value: String = id

  given Ordering[CorrelationId] = Ordering.String
  given CanEqual[CorrelationId, CorrelationId] = CanEqual.derived
}

/** Which KUI service something belongs to — `"cluster"`, `"topic"`, `"message"` and so on. The capability
  * registry keys its state by it, and the signed principal header binds a token to one audience so that a
  * token minted for one service cannot be replayed against another (ADR-020).
  */
opaque type ServiceId = String

object ServiceId {
  private val Field: String = "serviceId"
  private val Pattern: Regex = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$".r
  private val Expected: String =
    "a lowercase slug of 1 to 64 letters, digits and dashes, starting and ending with a letter " +
      "or a digit"

  def from(raw: String): Either[ValidationError, ServiceId] =
    Checks.matching(Field, Pattern, Expected)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): ServiceId = raw

  extension (id: ServiceId) def value: String = id

  given Ordering[ServiceId] = Ordering.String
  given CanEqual[ServiceId, ServiceId] = CanEqual.derived
}

/** The name of a person or machine account KUI has authenticated. It is the `sub` claim of the signed
  * principal header and the actor recorded in audit entries.
  */
opaque type UserName = String

object UserName {
  private val Field: String = "userName"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, UserName] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): UserName = raw

  extension (id: UserName) def value: String = id

  given Ordering[UserName] = Ordering.String
  given CanEqual[UserName, UserName] = CanEqual.derived
}

/** The name of an RBAC role. Roles are matched by name against the roles a principal holds, so this is a key,
  * not display text.
  */
opaque type RoleName = String

object RoleName {
  private val Field: String = "roleName"
  private val MaxLength: Int = 255

  def from(raw: String): Either[ValidationError, RoleName] = Checks.bounded(Field, MaxLength)(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): RoleName = raw

  extension (id: RoleName) def value: String = id

  given Ordering[RoleName] = Ordering.String
  given CanEqual[RoleName, RoleName] = CanEqual.derived
}

/** Which partition of a topic. Partitions are numbered from zero and are dense, so the id is also the index.
  */
opaque type PartitionId = Int

object PartitionId {
  def from(raw: Int): Either[ValidationError, PartitionId] = Checks.nonNegative("partitionId")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): PartitionId = raw

  extension (id: PartitionId) def value: Int = id

  given Ordering[PartitionId] = Ordering.Int
  given CanEqual[PartitionId, PartitionId] = CanEqual.derived
}

/** A broker's `node.id`. Ids are reused when a broker is decommissioned and a new one takes its number, so a
  * broker id alone is not a permanent identity: pair it with the cluster it belongs to.
  */
opaque type BrokerId = Int

object BrokerId {
  def from(raw: Int): Either[ValidationError, BrokerId] = Checks.nonNegative("brokerId")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): BrokerId = raw

  extension (id: BrokerId) def value: Int = id

  given Ordering[BrokerId] = Ordering.Int
  given CanEqual[BrokerId, BrokerId] = CanEqual.derived
}

/** The globally unique id a Schema Registry assigns to one registered schema. It is what the Confluent wire
  * format embeds in the first five bytes of a record, which is how a consumer knows which schema to decode
  * with.
  */
opaque type SchemaId = Int

object SchemaId {
  def from(raw: Int): Either[ValidationError, SchemaId] = Checks.nonNegative("schemaId")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): SchemaId = raw

  extension (id: SchemaId) def value: Int = id

  given Ordering[SchemaId] = Ordering.Int
  given CanEqual[SchemaId, SchemaId] = CanEqual.derived
}

/** Which task of a connector. Connect numbers a connector's tasks from zero.
  */
opaque type TaskId = Int

object TaskId {
  def from(raw: Int): Either[ValidationError, TaskId] = Checks.nonNegative("taskId")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Int): TaskId = raw

  extension (id: TaskId) def value: Int = id

  given Ordering[TaskId] = Ordering.Int
  given CanEqual[TaskId, TaskId] = CanEqual.derived
}

/** A record's position within one partition. Offsets start at zero, increase by one per record, and are
  * meaningful only together with the partition they belong to.
  */
opaque type Offset = Long

object Offset {
  def from(raw: Long): Either[ValidationError, Offset] = Checks.nonNegativeLong("offset")(raw)

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: Long): Offset = raw

  extension (id: Offset) def value: Long = id

  given Ordering[Offset] = Ordering.Long
  given CanEqual[Offset, Offset] = CanEqual.derived
}
