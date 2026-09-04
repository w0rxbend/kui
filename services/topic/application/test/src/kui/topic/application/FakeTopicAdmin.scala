package kui.topic.application

import cats.effect.{IO, Ref}

import kui.kernel.{ClusterId, TopicName}
import kui.topic.domain.*

/** A `TopicAdmin` a suite can steer, and count.
  *
  * It records every call, so that "cancelling the request cancels the admin call" and "the second ask does
  * not scrape again" are assertions about what happened rather than about what was returned. It is held to
  * `PortContractSuite` by `FakeTopicAdminContractSuite`, which is the point of that suite existing: a fake
  * that drifts from the live adapter would make every use-case test here agree with a bug.
  */
final class FakeTopicAdmin(
    clusters: Map[ClusterId, Map[TopicName, TopicDetail]],
    configs: Map[TopicName, TopicConfigView],
    incomplete: Map[ClusterId, Map[TopicName, String]],
    val calls: Ref[IO, List[String]],
    scrapeGate: IO[Unit],
    failScrape: Ref[IO, Option[TopicError]]
) extends TopicAdmin[IO] {

  private def record(what: String): IO[Unit] = calls.update(_ :+ what)

  private def known(cluster: ClusterId): Either[TopicError, Map[TopicName, TopicDetail]] =
    clusters.get(cluster).toRight(TopicError.ClusterNotFound(cluster))

  def scrape(cluster: ClusterId): IO[Either[TopicError, ScrapeResult]] =
    record(s"scrape:${cluster.value}") >> (known(cluster) match {
      case Left(error) => IO.pure(Left(error))
      case Right(topics) =>
        scrapeGate >> failScrape.get.map {
          case Some(error) => Left(error)
          case None =>
            Right(
              ScrapeResult(
                topics = topics.values.toList.map(_.summary).sortBy(_.name.value),
                incomplete = incomplete.getOrElse(cluster, Map.empty)
              )
            )
        }
    })

  def detail(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, TopicDetail]] =
    record(s"detail:${cluster.value}/${topic.value}") >> IO.pure(
      known(cluster).flatMap(_.get(topic).toRight(TopicError.NotFound(topic)))
    )

  def config(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, TopicConfigView]] =
    record(s"config:${cluster.value}/${topic.value}") >> IO.pure(
      known(cluster).flatMap(topics =>
        if !topics.contains(topic) then Left(TopicError.NotFound(topic))
        else Right(configs.getOrElse(topic, TopicConfigView.of(Nil)))
      )
    )
}

object FakeTopicAdmin {

  val cluster: ClusterId = ClusterId.unsafe("local")

  def of(
      topics: List[TopicDetail],
      configs: Map[TopicName, TopicConfigView] = Map.empty,
      incomplete: Map[TopicName, String] = Map.empty,
      scrapeGate: IO[Unit] = IO.unit
  ): IO[FakeTopicAdmin] =
    for {
      calls <- Ref.of[IO, List[String]](Nil)
      failing <- Ref.of[IO, Option[TopicError]](None)
    } yield new FakeTopicAdmin(
      clusters = Map(cluster -> topics.map(detail => detail.name -> detail).toMap),
      configs = configs,
      incomplete = if incomplete.isEmpty then Map.empty else Map(cluster -> incomplete),
      calls = calls,
      scrapeGate = scrapeGate,
      failScrape = failing
    )
}
