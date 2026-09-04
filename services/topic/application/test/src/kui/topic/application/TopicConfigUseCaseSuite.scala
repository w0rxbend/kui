package kui.topic.application

import cats.effect.{Deferred, IO}

import kui.kernel.{PartitionId, TopicName}
import kui.testkit.KuiIOSuite
import kui.topic.domain.*

/** The Settings tab's use case, and its two empty states. */
final class TopicConfigUseCaseSuite extends KuiIOSuite {

  private val orders: TopicName = TopicName.unsafe("orders")
  private val locked: TopicName = TopicName.unsafe("locked")

  private val topic: TopicDetail =
    TopicDetail.of(
      orders,
      isInternal = false,
      List(
        PartitionView
          .from(PartitionId.unsafe(0), None, Nil, Nil, None, None, None)
          .fold(error => throw new AssertionError(error.message), identity)
      )
    )

  private val lockedTopic: TopicDetail = TopicDetail.of(locked, isInternal = false, Nil)

  private def entry(
      name: String,
      value: Option[String] = Some("x"),
      sensitive: Boolean = false,
      synonyms: List[ConfigSynonym] = Nil
  ): TopicConfigEntry =
    TopicConfigEntry(name, value, ConfigSource.DynamicTopic, sensitive, isReadOnly = false, None, synonyms)

  private def useCase(configs: Map[TopicName, TopicConfigView]): IO[TopicConfigUseCase[IO]] =
    FakeTopicAdmin.of(List(topic, lockedTopic), configs = configs).map(TopicConfigUseCase.make[IO])

  test("entriesAreSortedByName") {
    val unsorted = TopicConfigView.Entries(List(entry("retention.ms"), entry("cleanup.policy"), entry("max.bytes")))

    useCase(Map(orders -> unsorted)).flatMap(_.config(FakeTopicAdmin.cluster, orders)).map {
      case Right(view) => assertEquals(view.entries.map(_.name), List("cleanup.policy", "max.bytes", "retention.ms"))
      case Left(error) => fail(error.message)
    }
  }

  test("aSensitiveEntryHasNeitherAValueNorADefault") {
    val secret = entry(
      "ssl.key.password",
      value = None,
      sensitive = true,
      synonyms = List(ConfigSynonym("ssl.key.password", Some("hunter2"), ConfigSource.Default))
    )

    useCase(Map(orders -> TopicConfigView.Entries(List(secret))))
      .flatMap(_.config(FakeTopicAdmin.cluster, orders))
      .map {
        case Right(view) =>
          assertEquals(view.entries.head.value, None)
          assertEquals(view.entries.head.defaultValue, None)
          assert(!view.entries.head.isOverridden)
        case Left(error) => fail(error.message)
      }
  }

  test("defaultComesFromTheDefaultSourceSynonym") {
    val retention = entry(
      "retention.ms",
      value = Some("86400000"),
      synonyms = List(
        ConfigSynonym("retention.ms", Some("86400000"), ConfigSource.DynamicTopic),
        ConfigSynonym("log.retention.ms", Some("604800000"), ConfigSource.Default)
      )
    )

    useCase(Map(orders -> TopicConfigView.Entries(List(retention))))
      .flatMap(_.config(FakeTopicAdmin.cluster, orders))
      .map {
        case Right(view) =>
          assertEquals(view.entries.head.defaultValue, Some("604800000"))
          assert(view.entries.head.isOverridden, "a value that is not the default is what the screen bolds")
        case Left(error) => fail(error.message)
      }
  }

  test("noSynonymsMeansNoDefault") {
    useCase(Map(orders -> TopicConfigView.Entries(List(entry("retention.ms")))))
      .flatMap(_.config(FakeTopicAdmin.cluster, orders))
      .map {
        case Right(view) => assertEquals(view.entries.head.defaultValue, None)
        case Left(error) => fail(error.message)
      }
  }

  test("anUnauthorizedReadIsNotPermittedNotAnError") {
    // The whole topic page keeps rendering; only this tab explains why it is empty. A `TopicError.Forbidden`
    // here would 403 the page and take the partitions the user *is* entitled to see with it.
    useCase(Map(locked -> TopicConfigView.NotPermitted("no DESCRIBE_CONFIGS on this topic")))
      .flatMap(_.config(FakeTopicAdmin.cluster, locked))
      .map {
        case Right(TopicConfigView.NotPermitted(detail)) => assert(detail.nonEmpty)
        case other => fail(s"expected a rendered refusal, got $other")
      }
  }

  test("anEmptyConfigurationIsNotTheSameAsARefusal") {
    useCase(Map(orders -> TopicConfigView.Entries(Nil)))
      .flatMap(_.config(FakeTopicAdmin.cluster, orders))
      .map {
        case Right(view) =>
          assert(view.isPermitted, "'the broker reports nothing' and 'you may not read it' are different tabs")
          assertEquals(view.entries, Nil)
        case Left(error) => fail(error.message)
      }
  }

  test("anUnknownTopicIsNotFound") {
    useCase(Map.empty)
      .flatMap(_.config(FakeTopicAdmin.cluster, TopicName.unsafe("gone")))
      .map(result => assertEquals(result, Left(TopicError.NotFound(TopicName.unsafe("gone")))))
  }

  test("cancellingTheRequestCancelsTheAdminCall") {
    for {
      started <- Deferred[IO, Unit]
      blocked <- Deferred[IO, Unit]
      admin = new TopicAdmin[IO] {
        def scrape(cluster: kui.kernel.ClusterId) = IO.pure(Right(ScrapeResult.empty))
        def detail(cluster: kui.kernel.ClusterId, name: TopicName) = IO.never
        def config(cluster: kui.kernel.ClusterId, name: TopicName) =
          started.complete(()).productR(IO.never).guarantee(blocked.complete(()).void)
      }
      fiber <- TopicConfigUseCase.make[IO](admin).config(FakeTopicAdmin.cluster, orders).start
      _ <- started.get
      _ <- fiber.cancel
      // The admin call's finaliser ran, which is what "cancelled" means here: the request did not leak a
      // fiber holding a Kafka client after the caller went away.
      _ <- blocked.get
    } yield ()
  }
}
