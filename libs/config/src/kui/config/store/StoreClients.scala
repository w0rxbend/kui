package kui.config.store

import cats.Parallel
import cats.effect.{Async, Resource}
import fs2.io.file.Files
import fs2.kafka.*

import kui.config.StoreKafkaConfig
import kui.kafka.auth.{ClientPurpose, ConnectionProperties}
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, ClientProperties, ClusterConnection}

/** The store's own Kafka clients: an admin client, a consumer and a producer, built from `kui.store.kafka.*`.
  *
  * They are built with fs2-kafka directly rather than through `libs/kafka`'s `ClusterAdmin` port. That port
  * answers `describeCluster` and friends for a *managed* cluster; the store needs `createTopics`,
  * `describeConfigs`, `assign`/`seek` and a producer, which is a different set for a different purpose.
  * Putting the store's needs into the port that ten services share would make every one of them carry a
  * method only the store uses.
  *
  * The security properties come from `libs/kafka-auth`, exactly as a managed cluster's do. That is the point
  * of the shared renderer: a store on SASL_SSL and a managed cluster on SASL_SSL are configured by the same
  * code, so a quoting bug cannot exist in one and not the other.
  */
object StoreClients {

  /** The id KUI presents itself under. The store's clients are distinguished from a managed cluster's because
    * a broker log or a quota that cannot tell them apart is one an operator cannot act on.
    */
  val StoreClusterId: ClusterId = ClusterId.unsafe("kui-store")

  private def connection(config: StoreKafkaConfig): ClusterConnection =
    ClusterConnection(
      id = StoreClusterId,
      bootstrapServers = config.bootstrapServers,
      security = config.security,
      overrides = ClientProperties.fromRaw(config.properties),
      admin = AdminTuning.default
    )

  /** Renders the client properties, keeping any materialized keystore alive for the client's lifetime.
    *
    * A `ClientProperties` value that outlived its truststore would name a path that is no longer there, and
    * the SSL failure a Kafka client then produces names the missing file rather than the mistake — so the
    * properties and the client share one `Resource`.
    */
  private def properties[F[_]: {Async, Files}](
      config: StoreKafkaConfig,
      purpose: ClientPurpose,
      clientId: String
  ): Resource[F, Map[String, String]] =
    ConnectionProperties.resource[F](connection(config), purpose, clientId).evalMap {
      case Right(rendered) => Async[F].pure(rendered.unsafeValues)
      case Left(error) =>
        Async[F].raiseError(
          new StoreFailure(StoreError.Unreachable(config.bootstrapServers.value, error.message))
        )
    }

  def admin[F[_]: {Async, Files}](
      config: StoreKafkaConfig,
      clientId: String
  ): Resource[F, KafkaAdminClient[F]] =
    properties[F](config, ClientPurpose.Admin, clientId).flatMap { rendered =>
      KafkaAdminClient.resource[F](
        AdminClientSettings(config.bootstrapServers.value).withProperties(rendered)
      )
    }

  /** The consumer that replays and then follows the log.
    *
    * `auto.offset.reset=earliest` because a replay that started at the end would produce a KUI that believes
    * it has no clusters. There is no consumer group and no committed offsets: the store assigns its single
    * partition explicitly (STORE-006), because a group would make two replicas share the partition and each
    * see half the records.
    *
    * A `null` value is a physical tombstone, so the value deserializer produces `Option[String]` and `None`
    * means "this key was deleted".
    */
  def consumer[F[_]: {Async, Files}](
      config: StoreKafkaConfig,
      clientId: String
  ): Resource[F, KafkaConsumer[F, String, Option[String]]] =
    properties[F](config, ClientPurpose.Consumer, clientId).flatMap { rendered =>
      KafkaConsumer.resource(
        ConsumerSettings[F, String, Option[String]]
          .withBootstrapServers(config.bootstrapServers.value)
          .withProperties(rendered)
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withEnableAutoCommit(false)
          .withGroupId(clientId)
      )
    }

  /** The producer that writes records.
    *
    * `acks=all` with the topic's `min.insync.replicas` is what makes "the write was accepted" mean the record
    * survives the loss of a broker. Idempotence is on so that an internal retry cannot write the same version
    * twice, which the optimistic-versioning rule would then see as two writers racing.
    */
  def producer[F[_]: {Async, Files, Parallel}](
      config: StoreKafkaConfig,
      clientId: String
  ): Resource[F, KafkaProducer[F, String, Option[String]]] =
    properties[F](config, ClientPurpose.Producer, clientId).flatMap { rendered =>
      KafkaProducer.resource(
        ProducerSettings[F, String, Option[String]]
          .withBootstrapServers(config.bootstrapServers.value)
          .withProperties(rendered)
          .withAcks(Acks.All)
          .withEnableIdempotence(true)
      )
    }
}
