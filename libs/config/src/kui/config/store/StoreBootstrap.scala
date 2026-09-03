package kui.config.store

import scala.jdk.CollectionConverters.*

import cats.effect.Async
import cats.syntax.all.*
import fs2.kafka.KafkaAdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.{
  TopicAuthorizationException,
  TopicExistsException,
  UnknownTopicOrPartitionException
}
import org.typelevel.log4cats.LoggerFactory

/** Creates the store's topics on first start, validates them on every start, and never touches them again.
  *
  * The second half is the part that matters. KUI does not call `incrementalAlterConfigs` on a topic it did
  * not just create: an operator's retention setting is the operator's decision, and a management tool that
  * silently rewrites it is one nobody trusts with a production cluster again. So a topic that exists with
  * settings KUI cannot work with is a start-up failure that names the topic, the setting, the value KUI
  * expected and the value it found — which is a named exit criterion of this milestone.
  */
object StoreBootstrap {

  /** What `describeTopics` says about a topic that exists. */
  final private case class TopicShape(partitions: Int, replicationFactor: Int)

  private object TopicShape {

    /** The replication factor is the replica count of the first partition. Kafka has no topic-level
      * replication factor after creation — it is a property of each partition's assignment — and the store's
      * topics have exactly one partition, so there is nothing to reconcile.
      */
    def of(description: org.apache.kafka.clients.admin.TopicDescription): TopicShape =
      TopicShape(
        partitions = description.partitions.size,
        replicationFactor = description.partitions.asScala.headOption.map(_.replicas.size).getOrElse(0)
      )
  }

  def ensureTopics[F[_]: {Async, LoggerFactory}](
      admin: KafkaAdminClient[F],
      topics: StoreTopics,
      replicationFactor: Short,
      bootstrapServers: String
  ): F[Either[StoreError, Unit]] = {
    val logger = LoggerFactory[F].getLogger
    val wanted = topics.managedNow

    def describe(names: List[String]): F[Either[StoreError, Map[String, TopicShape]]] =
      names
        .traverse(name =>
          admin
            .describeTopics(List(name))
            .map(_.get(name).map(description => Option(name -> TopicShape.of(description))))
            .recover {
              // A topic that is not there is the normal first-start case, not a failure.
              case _: UnknownTopicOrPartitionException => Some(None)
            }
            .attempt
            .map {
              case Right(Some(found)) => Right(found.toList)
              case Right(None) => Right(Nil)
              case Left(error) => Left(classify(error, bootstrapServers))
            }
        )
        .map(_.sequence.map(_.flatten.toMap))

    for {
      existing <- describe(wanted.map(_.name))
      result <- existing match {
        case Left(error) => Async[F].pure(Left(error))
        case Right(found) =>
          for {
            created <- wanted.filterNot(topic => found.contains(topic.name)).traverse { topic =>
              create(admin, topic, replicationFactor, bootstrapServers) <*
                logger.info(
                  s"store topic created: topic=${topic.name} partitions=${topic.partitions} " +
                    s"replicationFactor=$replicationFactor"
                )
            }
            // Re-describe after creating. A concurrent replica may have won the race, and the topic that
            // now exists is not necessarily the one this replica asked for.
            afterCreate <-
              if created.exists(_.isLeft) then
                Async[F].pure(
                  created
                    .collectFirst { case Left(error) =>
                      Left(error)
                    }
                    .getOrElse(Right(Map.empty[String, TopicShape]))
                )
              else describe(wanted.map(_.name))
            validated <- afterCreate match {
              case Left(error) => Async[F].pure(Left(error))
              case Right(shapes) => validate(admin, wanted, shapes, replicationFactor, bootstrapServers)
            }
            _ <- validated match {
              case Right(_) => logger.info(s"store topics validated: ${wanted.map(_.name).mkString(", ")}")
              case Left(error) => logger.error(s"${error.code.wire}: ${error.message}")
            }
          } yield validated
      }
    } yield result
  }

  /** Creates one topic. A concurrent `TopicExistsException` is a success: two KUI replicas starting together
    * is the normal case, not a race anybody has to lose.
    */
  private def create[F[_]: Async](
      admin: KafkaAdminClient[F],
      topic: StoreTopic,
      replicationFactor: Short,
      bootstrapServers: String
  ): F[Either[StoreError, Unit]] =
    admin
      .createTopic(
        new NewTopic(topic.name, topic.partitions, replicationFactor)
          .configs(topic.creationConfig.asJava)
      )
      .attempt
      .map {
        case Right(_) => Right(())
        case Left(_: TopicExistsException) => Right(())
        case Left(error) => Left(classify(error, bootstrapServers))
      }

  /** Checks every required setting of every wanted topic, and logs the advisory differences.
    *
    * The first difference found is returned, not all of them. This is a deliberate departure from the
    * accumulate-everything rule that governs configuration loading, and it is stated here so a reviewer does
    * not read it as an oversight: an operator fixes one topic setting at a time anyway, and accumulating
    * would mean building a second accumulation mechanism next to the configuration loader's for a case with
    * at most a handful of entries.
    */
  private def validate[F[_]: {Async, LoggerFactory}](
      admin: KafkaAdminClient[F],
      wanted: List[StoreTopic],
      shapes: Map[String, TopicShape],
      replicationFactor: Short,
      bootstrapServers: String
  ): F[Either[StoreError, Unit]] = {
    val logger = LoggerFactory[F].getLogger
    val resources = wanted.map(topic => new ConfigResource(ConfigResource.Type.TOPIC, topic.name))
    admin
      .describeConfigs(resources)
      .attempt
      .flatMap {
        case Left(error) => Async[F].pure(Left(classify(error, bootstrapServers)))
        case Right(described) =>
          val byTopic = described.map((resource, entries) =>
            resource.name -> entries.map(entry => entry.name -> entry.value).toMap
          )
          wanted
            .traverse(topic =>
              checkOne(topic, shapes, byTopic.getOrElse(topic.name, Map.empty), replicationFactor, logger)
            )
            .map(_.sequence.void)
      }
  }

  private def checkOne[F[_]: Async](
      topic: StoreTopic,
      shapes: Map[String, TopicShape],
      settings: Map[String, String],
      replicationFactor: Short,
      logger: org.typelevel.log4cats.Logger[F]
  ): F[Either[StoreError, Unit]] = {
    val shape = shapes.get(topic.name)

    val partitionProblem = shape.flatMap { found =>
      Option.when(found.partitions != topic.partitions)(
        StoreError.TopicIncompatible(
          topic.name,
          StoreTopics.PartitionsSetting,
          topic.partitions.toString,
          found.partitions.toString
        )
      )
    }

    // The replication factor of an *existing* topic is not validated, only reported. An operator who ran
    // KUI on one broker and later grew the cluster has a perfectly valid RF-1 topic and an RF-3 setting,
    // and refusing to start would punish them for the upgrade.
    val replicationWarning = shape.filter(_.replicationFactor != replicationFactor.toInt).map { found =>
      s"store topic ${topic.name} has replicationFactor=${found.replicationFactor}, " +
        s"kui.store.replicationFactor is $replicationFactor; KUI does not change an existing topic"
    }

    // Ordered, so that the reported difference is stable across runs rather than depending on a map's
    // iteration order — an operator comparing two start-up logs should see the same line.
    val settingProblem = topic.required.toList.sortBy(_._1).collectFirst {
      case (setting, expected)
          if !settings.get(setting).forall(StoreTopics.satisfies(setting, expected, _)) =>
        StoreError.TopicIncompatible(topic.name, setting, expected, settings.getOrElse(setting, "<unset>"))
    }

    val advisoryDifferences = topic.advisory.toList.sortBy(_._1).collect {
      case (setting, expected) if settings.get(setting).exists(_ != expected) =>
        s"store topic ${topic.name} has $setting=${settings(setting)}, KUI would have set $expected; leaving it alone"
    }

    replicationWarning.traverse_(logger.warn(_)) *>
      advisoryDifferences.traverse_(logger.info(_)) *>
      Async[F].pure(partitionProblem.orElse(settingProblem).toLeft(()))
  }

  /** Turns an admin-client failure into a store error that says which layer to look at.
    *
    * The distinction that earns its keep is authorization. A topic KUI cannot create because its principal
    * lacks `Create` looks exactly like an unreachable cluster to anyone reading "the store could not be
    * used", and an operator debugging the network for an hour when the answer is an ACL is a real cost. The
    * two are trivially distinguishable here, so they are distinguished.
    */
  private def classify(error: Throwable, bootstrapServers: String): StoreError =
    error match {
      case _: TopicAuthorizationException =>
        StoreError.Unreachable(
          bootstrapServers,
          "KUI's principal is not authorized on the __kui_* topics; grant Describe, DescribeConfigs, " +
            "Create, Read and Write as described in docs/operations/metadata-store.md §4.1"
        )
      case other =>
        // The class name, not the message: a client's exception message routinely carries hosts, ports
        // and occasionally a rendered property map.
        //
        // There is no `NonFatal` guard and no rethrow, because there is nothing left to guard against:
        // every caller reaches this through cats-effect's `attempt`, which does not hand over a fatal
        // error — those go to the runtime's uncaught-error handler and never become a value here.
        StoreError.Unreachable(
          bootstrapServers,
          s"the admin client failed with ${other.getClass.getSimpleName}"
        )
    }
}
