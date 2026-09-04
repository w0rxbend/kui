package kui.topic.infrastructure

import scala.jdk.CollectionConverters.*

import cats.effect.kernel.Async
import cats.syntax.all.*
import org.apache.kafka.clients.admin.{
  Admin,
  DescribeConfigsOptions,
  ListTopicsOptions,
  OffsetSpec,
  TopicDescription
}
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.{Node, TopicCollection, TopicPartition, TopicPartitionInfo}
import org.typelevel.log4cats.StructuredLogger

import kui.kafka.{AdminClientPool, KafkaErrorMapper, KafkaFutures}
import kui.kernel.cluster.ClusterConnection
import kui.kernel.{BrokerId, ClusterId, PartitionId, TopicName}
import kui.topic.application.InternalTopics
import kui.topic.domain as dom
import kui.topic.domain.{ScrapeResult, TopicAdmin as TopicAdminPort}

/** The topic domain's `TopicAdmin` port, over the raw Kafka `Admin` client.
  *
  * This is the file the port's own scaladoc promises: "one exhaustively-matched file in `infrastructure`,
  * which is where every shape a real cluster produces gets a name". Kafka's vocabulary goes in — a `Node`
  * whose id is `-1` because the partition has no leader, a `KafkaFuture` per topic so that one unreadable
  * topic does not fail the other nine thousand — and the topic domain's vocabulary comes out.
  *
  * ==Why it does not go through `libs/kafka`==
  *
  * It should. The M2 plan's tasks TOP-002…TOP-005 put a `TopicAdmin` beside `ClusterAdmin` and `GroupAdmin`
  * in `libs/kafka`, so that the message service and the consumer service can reuse the same offset and
  * describe calls. That module was not built. Rather than leave the whole topic service unreachable waiting
  * for it, the Kafka calls live here, written against the same `AdminClientPool` that `libs/kafka` uses — so
  * the client lifecycle, the timeouts, the metrics and the reconnect handling are still the shared ones, and
  * only the four call shapes are local. Hoisting them later is a move, not a rewrite.
  *
  * ==Guarantees==
  *
  * Every method is total: a failure is a `TopicError`, never a raised exception. That is the port's contract
  * and `PortContractSuite` asserts it.
  *
  * @param connections
  *   turns a cluster id into connection material. `None` is `TopicError.ClusterNotFound`, which is a 404 at
  *   the edge and never an empty list.
  * @param internalPrefix
  *   `kui.topics.internalPrefix`. This adapter is the one place in the product that holds both halves of
  *   "internal" at once — Kafka's `isInternal` flag comes back on the wire here, and the prefix is pure
  *   configuration — so this is where [[InternalTopics.isInternal]] combines them, exactly once, for both the
  *   list and the detail page. Before this parameter existed the flag was passed through alone and the
  *   configured prefix did nothing at all: a cluster's `_schemas` or `__kui_config` sat in the operator's own
  *   topic list with no way to hide it.
  */
final class KafkaTopicAdmin[F[_]: Async](
    pool: AdminClientPool[F],
    connections: ClusterId => Option[ClusterConnection],
    internalPrefix: String,
    logger: StructuredLogger[F]
) extends TopicAdminPort[F] {

  import KafkaTopicAdmin.*

  // ---------------------------------------------------------------------------------- scrape

  def scrape(cluster: ClusterId): F[Either[dom.TopicError, ScrapeResult]] =
    withConnection(cluster) { connection =>
      for {
        listings <- listTopics(connection)
        described <- describeTopics(connection, listings.keys.toList)
        // Offsets are asked for only for the partitions that have a leader. A leaderless partition
        // cannot answer a `listOffsets`, and `PartitionView.from` rejects a leaderless partition that
        // carries offsets anyway — an invariant that exists precisely so that a number nobody could
        // have measured never reaches a screen.
        offsets <- listOffsets(connection, described.values.toList.flatMap(leadPartitions))
        rows = described.toList.map { case (name, description) =>
          dom.TopicSummary.of(
            name = name,
            isInternal = InternalTopics
              .isInternal(name, listings.getOrElse(name, description.isInternal), internalPrefix),
            partitions = description.partitions.asScala.toList.flatMap(partitionView(name, _, offsets))
          )
        }
        incomplete = listings.keySet.diff(described.keySet).map(_ -> UnreadableTopic).toMap
      } yield ScrapeResult(rows.sortBy(_.name.value), incomplete)
    }

  // ---------------------------------------------------------------------------------- detail

  def detail(cluster: ClusterId, topic: TopicName): F[Either[dom.TopicError, dom.TopicDetail]] =
    withConnection(cluster, Some(topic)) { connection =>
      for {
        described <- describeOne(connection, topic)
        offsets <- listOffsets(connection, leadPartitions(described))
        // The Settings tab reads the whole configuration; the detail header needs exactly one key of
        // it, and asking for it here is what lets the header say "compact" without the user opening
        // another tab. A configuration KUI may not read costs the header that one field and nothing
        // else, which is why this is an `attempt`-shaped read rather than a second failure mode.
        policy <- cleanupPolicy(connection, topic)
      } yield dom.TopicDetail.of(
        name = topic,
        isInternal = InternalTopics.isInternal(topic, described.isInternal, internalPrefix),
        partitions = described.partitions.asScala.toList.flatMap(partitionView(topic, _, offsets)),
        cleanupPolicy = policy,
        // Segment counts come from `describeLogDirs`, which is a per-broker call over every partition
        // on the cluster. It is not worth a topic page's latency, and a number that is sometimes there
        // and sometimes not is worse than one that is honestly absent.
        segmentCount = None
      )
    }

  // ---------------------------------------------------------------------------------- config

  def config(cluster: ClusterId, topic: TopicName): F[Either[dom.TopicError, dom.TopicConfigView]] =
    withConnection(cluster, Some(topic)) { connection =>
      describeConfigs(connection, topic).map(entries => dom.TopicConfigView.of(entries))
    }.flatMap {
      // A topic KUI may see and may not describe is `NotPermitted` and never `Forbidden`: a 403 here
      // would take the whole topic page down and the partitions the user *is* entitled to see would
      // vanish with the tab they are not. The port's own scaladoc requires this, and it is the one
      // place in the adapter where a typed failure is deliberately turned back into a value.
      case Left(dom.TopicError.Forbidden(detail)) =>
        dom.TopicConfigView.NotPermitted(detail).asRight[dom.TopicError].pure[F].widen
      case other => other.pure[F]
    }

  // ---------------------------------------------------------------------------------- Kafka calls

  private def listTopics(connection: ClusterConnection): F[Map[TopicName, Boolean]] =
    pool.run(connection, "listTopics") { admin =>
      KafkaFutures
        .fromFuture(Async[F].delay(admin.listTopics(new ListTopicsOptions().listInternal(true)).listings()))
        .map(_.asScala.toList.map(listing => TopicName.unsafe(listing.name) -> listing.isInternal).toMap)
    }

  /** Every topic, one future each, so that a topic KUI may not describe becomes a row in `incomplete` rather
    * than the failure of the whole list.
    *
    * `topicNameValues` and not `all`: `all` is a single future that fails if any one topic fails, which would
    * turn one unauthorized topic into an empty topics screen.
    */
  private def describeTopics(
      connection: ClusterConnection,
      names: List[TopicName]
  ): F[Map[TopicName, TopicDescription]] =
    if names.isEmpty then Map.empty[TopicName, TopicDescription].pure[F]
    else
      names
        .grouped(DescribeBatch)
        .toList
        .flatTraverse { batch =>
          pool.run(connection, "describeTopics") { admin =>
            val result = admin.describeTopics(TopicCollection.ofTopicNames(batch.map(_.value).asJava))

            result.topicNameValues.asScala.toList.traverse { case (raw, future) =>
              KafkaFutures
                .fromFuture(Async[F].delay(future))
                .map(description => Option(TopicName.unsafe(raw) -> description))
                .handleErrorWith(failure =>
                  logger
                    .debug(failure)(s"topic '$raw' could not be described and is reported as incomplete")
                    .as(None)
                )
            }
          }
        }
        .map(_.flatten.toMap)

  private def describeOne(connection: ClusterConnection, topic: TopicName): F[TopicDescription] =
    pool.run(connection, "describeTopic") { admin =>
      KafkaFutures.fromFuture(
        Async[F].delay(
          admin
            .describeTopics(TopicCollection.ofTopicNames(List(topic.value).asJava))
            .topicNameValues
            .get(topic.value)
        )
      )
    }

  /** The earliest and latest offset of every partition given, in one round trip per bound.
    *
    * A failure here costs the counts and not the page: a topic list whose message counts are blank is
    * readable, and one that failed to render is not. So this returns what it managed to read.
    */
  private def listOffsets(
      connection: ClusterConnection,
      partitions: List[TopicPartition]
  ): F[OffsetBounds] =
    if partitions.isEmpty then OffsetBounds.empty.pure[F]
    else
      (
        bound(connection, partitions, OffsetSpec.earliest(), "listOffsets.earliest"),
        bound(connection, partitions, OffsetSpec.latest(), "listOffsets.latest")
      ).tupled.map(OffsetBounds.apply)

  private def bound(
      connection: ClusterConnection,
      partitions: List[TopicPartition],
      spec: OffsetSpec,
      operation: String
  ): F[Map[TopicPartition, Long]] =
    partitions
      .grouped(OffsetBatch)
      .toList
      .flatTraverse { batch =>
        pool
          .run(connection, operation) { admin =>
            KafkaFutures
              .fromFuture(Async[F].delay(admin.listOffsets(batch.map(_ -> spec).toMap.asJava).all()))
              .map(_.asScala.toList.map((partition, info) => partition -> info.offset))
          }
          .handleErrorWith(failure =>
            logger
              .warn(failure)(
                s"cluster ${connection.id.value} did not answer $operation; message counts will be absent"
              )
              .as(Nil)
          )
      }
      .map(_.toMap)

  private def describeConfigs(
      connection: ClusterConnection,
      topic: TopicName
  ): F[List[dom.TopicConfigEntry]] =
    pool.run(connection, "describeConfigs") { admin =>
      val resource = new ConfigResource(ConfigResource.Type.TOPIC, topic.value)
      val options = new DescribeConfigsOptions().includeSynonyms(true).includeDocumentation(true)

      KafkaFutures
        .fromFuture(Async[F].delay(admin.describeConfigs(List(resource).asJava, options).all()))
        .map(_.asScala.get(resource).toList.flatMap(_.entries.asScala.toList.map(configEntry)))
    }

  private def cleanupPolicy(connection: ClusterConnection, topic: TopicName): F[Option[String]] =
    describeConfigs(connection, topic)
      .map(_.find(_.name == CleanupPolicy).flatMap(_.value))
      .handleError(_ => None)

  // ---------------------------------------------------------------------------------- plumbing

  /** Resolves the cluster, runs the call, and turns anything thrown into a `TopicError`. */
  private def withConnection[A](
      cluster: ClusterId,
      topic: Option[TopicName] = None
  )(call: ClusterConnection => F[A]): F[Either[dom.TopicError, A]] =
    connections(cluster) match {
      case None => dom.TopicError.ClusterNotFound(cluster).asLeft[A].pure[F]
      case Some(connection) =>
        call(connection).attempt.map(_.leftMap(topicError(_, topic)))
    }

  /** @param topic
    *   the topic the call was about, when it was about one. It is what lets `UnknownTopicOrPartition` become
    *   a `NotFound` that names the topic the user asked for; a scrape is about no topic in particular, so the
    *   same exception there is reported as an unreachable cluster rather than as a 404 for a name nobody
    *   typed.
    */
  private def topicError(failure: Throwable, topic: Option[TopicName]): dom.TopicError =
    KafkaErrorMapper.classify(failure) match {
      case KafkaErrorMapper.FailureClass.NotAuthorized =>
        dom.TopicError.Forbidden(KafkaTopicAdmin.describe(failure))
      case KafkaErrorMapper.FailureClass.NotFound =>
        topic.fold(dom.TopicError.Unreachable(KafkaTopicAdmin.describe(failure), retryable = true))(
          dom.TopicError.NotFound.apply
        )
      case KafkaErrorMapper.FailureClass.Unsupported =>
        dom.TopicError.Unreachable(KafkaTopicAdmin.describe(failure), retryable = false)
      case _ =>
        dom.TopicError.Unreachable(KafkaTopicAdmin.describe(failure), retryable = true)
    }
}

object KafkaTopicAdmin {

  /** How many topics are described in one admin request.
    *
    * Kafka answers a `describeTopics` of ten thousand names in a single response that the broker has to build
    * in memory, and a request big enough to matter is also a request big enough to time out as a whole.
    * Batching is what turns one all-or-nothing call into a sequence whose failures are localised.
    */
  private val DescribeBatch: Int = 500

  /** The same, for partitions: a cluster with ten thousand topics has far more partitions than topics. */
  private val OffsetBatch: Int = 2000

  private val CleanupPolicy: String = "cleanup.policy"

  /** The sentence shown against a topic that could not be described. Display text, one sentence, safe to show
    * a user — `ScrapeResult.incomplete`'s contract.
    */
  private val UnreadableTopic: String =
    "this topic could not be described; KUI may not be authorized to read it, or it was deleted during " +
      "the scrape"

  /** KUI's words for a failure, from the exception's class and never its message.
    *
    * A Kafka exception's message routinely carries the bootstrap string and, on some SASL paths, the
    * principal. The original goes to the log with its stack trace; the class name goes to the user.
    */
  private def describe(failure: Throwable): String = {
    val name = KafkaFutures.unwrap(failure).getClass.getSimpleName
    if name.endsWith("Exception") then name.dropRight("Exception".length) else name
  }

  /** The earliest and latest offset of each partition, whichever of the two calls answered. */
  final case class OffsetBounds(earliest: Map[TopicPartition, Long], latest: Map[TopicPartition, Long])

  object OffsetBounds {
    val empty: OffsetBounds = OffsetBounds(Map.empty, Map.empty)
  }

  /** The partitions of a topic that have a leader, as Kafka's own key type. */
  private[infrastructure] def leadPartitions(description: TopicDescription): List[TopicPartition] =
    description.partitions.asScala.toList
      .filter(info => leaderOf(info).isDefined)
      .map(info => new TopicPartition(description.name, info.partition))

  /** A partition's leader, with Kafka's two ways of saying "there isn't one" folded into `None`.
    *
    * `null` and a `Node` whose id is `-1` (`Node.noNode`) both mean leaderless, and code that checks only one
    * of them reports broker `-1` as the leader of an offline partition.
    */
  private[infrastructure] def leaderOf(info: TopicPartitionInfo): Option[Node] =
    Option(info.leader).filter(_.id >= 0)

  /** One partition, in the domain's words, or nothing when the cluster described something impossible.
    *
    * `PartitionView.from` can refuse: a replica listed twice, an in-sync replica that is not a replica, a
    * leader that is not one of the replicas, an earliest offset after the latest. Every one of those is a
    * statement no healthy broker makes, and each has an invariant precisely because rendering it would put a
    * number on a screen that cannot be true — "more replicas in sync than exist" is the example the
    * invariant's own message gives.
    *
    * So a refusal drops that one partition rather than raising. Dropping is visible (the partition table is
    * short and the count beside it does not match) and raising is not (the whole topic list would blank over
    * one bad row). Neither is good; the visible one is the one an operator can act on.
    */
  private[infrastructure] def partitionView(
      topic: TopicName,
      info: TopicPartitionInfo,
      offsets: OffsetBounds
  ): Option[dom.PartitionView] = {
    val key = new TopicPartition(topic.value, info.partition)
    val leader = leaderOf(info).map(node => BrokerId.unsafe(node.id))

    dom.PartitionView
      .from(
        partition = PartitionId.unsafe(info.partition),
        leader = leader,
        replicas = info.replicas.asScala.toList.map(node => BrokerId.unsafe(node.id)),
        inSync = info.isr.asScala.toList.map(node => BrokerId.unsafe(node.id)),
        // Offsets only where there is a leader. A leaderless partition cannot answer a `listOffsets`,
        // and the invariant rejects one that carries offsets anyway — which is how a number nobody
        // could have measured is kept off a screen.
        earliestOffset = leader.flatMap(_ => offsets.earliest.get(key)),
        latestOffset = leader.flatMap(_ => offsets.latest.get(key)),
        sizeBytes = None
      )
      .toOption
  }

  /** Kafka's `ConfigEntry` in the topic domain's words, synonyms included.
    *
    * The synonyms are the point. `TopicConfigEntry.defaultValue` derives the default from the synonym whose
    * source is `DEFAULT_CONFIG` rather than storing it, so "is this setting overridden" is answered from what
    * the broker reported instead of from a table KUI would have to keep in step with every Kafka release.
    */
  private[infrastructure] def configEntry(
      raw: org.apache.kafka.clients.admin.ConfigEntry
  ): dom.TopicConfigEntry =
    dom.TopicConfigEntry(
      name = raw.name,
      value = Option(raw.value),
      source = configSource(raw.source),
      isSensitive = raw.isSensitive,
      isReadOnly = raw.isReadOnly,
      documentation = Option(raw.documentation),
      synonyms = Option(raw.synonyms).toList.flatMap(_.asScala.toList).map { synonym =>
        dom.ConfigSynonym(synonym.name, Option(synonym.value), configSource(synonym.source))
      }
    )

  private[infrastructure] def configSource(
      raw: org.apache.kafka.clients.admin.ConfigEntry.ConfigSource
  ): dom.ConfigSource = {
    import org.apache.kafka.clients.admin.ConfigEntry.ConfigSource as Kafka

    raw match {
      case Kafka.DYNAMIC_TOPIC_CONFIG => dom.ConfigSource.DynamicTopic
      case Kafka.DYNAMIC_BROKER_CONFIG | Kafka.DYNAMIC_DEFAULT_BROKER_CONFIG =>
        dom.ConfigSource.DynamicDefaultBroker
      case Kafka.STATIC_BROKER_CONFIG => dom.ConfigSource.StaticBroker
      case Kafka.DEFAULT_CONFIG => dom.ConfigSource.Default
      case _ => dom.ConfigSource.Unknown
    }
  }

  /** An `Admin` call, for a test that wants to drive this adapter without a pool. */
  private[infrastructure] type Call[F[_], A] = Admin => F[A]
}
