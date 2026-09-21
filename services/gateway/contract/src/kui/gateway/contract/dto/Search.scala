package kui.gateway.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.{ClusterId, GroupId, ServiceId, Subject, TopicName}

/** One topic the search matched, and the cluster it is on.
  *
  * The cluster travels with every hit because the search spans every configured cluster at once: the top bar
  * is one field for the whole product, and a result the browser cannot address is a result nobody can click.
  */
final case class TopicHitDto(cluster: ClusterId, name: TopicName)

object TopicHitDto {

  given Codec[TopicHitDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[ClusterId]("cluster")
        name <- cursor.get[TopicName]("name")
      } yield TopicHitDto(cluster, name),
    (dto: TopicHitDto) => Json.obj("cluster" -> dto.cluster.asJson, "name" -> dto.name.asJson)
  )

  given Schema[TopicHitDto] = Schema.derived[TopicHitDto].description("A topic the query matched")

  given CanEqual[TopicHitDto, TopicHitDto] = CanEqual.derived
}

/** One consumer group the search matched, and the cluster it is on. */
final case class GroupHitDto(cluster: ClusterId, groupId: GroupId)

object GroupHitDto {

  given Codec[GroupHitDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[ClusterId]("cluster")
        groupId <- cursor.get[GroupId]("groupId")
      } yield GroupHitDto(cluster, groupId),
    (dto: GroupHitDto) => Json.obj("cluster" -> dto.cluster.asJson, "groupId" -> dto.groupId.asJson)
  )

  given Schema[GroupHitDto] = Schema.derived[GroupHitDto].description("A consumer group the query matched")

  given CanEqual[GroupHitDto, GroupHitDto] = CanEqual.derived
}

/** One schema-registry subject the search matched, and the cluster whose registry holds it. */
final case class SubjectHitDto(cluster: ClusterId, subject: Subject)

object SubjectHitDto {

  given Codec[SubjectHitDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[ClusterId]("cluster")
        subject <- cursor.get[Subject]("subject")
      } yield SubjectHitDto(cluster, subject),
    (dto: SubjectHitDto) => Json.obj("cluster" -> dto.cluster.asJson, "subject" -> dto.subject.asJson)
  )

  given Schema[SubjectHitDto] = Schema.derived[SubjectHitDto].description("A subject the query matched")

  given CanEqual[SubjectHitDto, SubjectHitDto] = CanEqual.derived
}

/** What one query found, by kind.
  *
  * All three lists are always present, empty included. A category the search could not ask about is empty
  * *and* named in [[SearchAnswerDto.partial]]; a category that was asked and matched nothing is empty and not
  * named. A document that omitted the key instead would make those two indistinguishable in the browser,
  * which is the whole distinction the endpoint exists to preserve.
  */
final case class SearchResultsDto(
    topics: List[TopicHitDto],
    groups: List[GroupHitDto],
    subjects: List[SubjectHitDto]
)

object SearchResultsDto {

  val Empty: SearchResultsDto = SearchResultsDto(Nil, Nil, Nil)

  val TopicsField: String = "topics"
  val GroupsField: String = "groups"
  val SubjectsField: String = "subjects"

  /** Concatenation, kind by kind. The fold builds one of these per cluster per service and adds them up, so
    * the combination is stated here once rather than spelled out at each of the three call sites.
    */
  def combine(left: SearchResultsDto, right: SearchResultsDto): SearchResultsDto =
    SearchResultsDto(
      left.topics ++ right.topics,
      left.groups ++ right.groups,
      left.subjects ++ right.subjects
    )

  /** The first `limit` of each kind, counted per kind rather than over the total.
    *
    * Per kind because the three lists are drawn as three separate groups in the result panel: a query that
    * matched forty topics must not push every group and every subject off the end of a shared budget.
    */
  def take(results: SearchResultsDto, limit: Int): SearchResultsDto =
    SearchResultsDto(
      results.topics.take(limit),
      results.groups.take(limit),
      results.subjects.take(limit)
    )

  given Codec[SearchResultsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topics <- cursor.getOrElse[List[TopicHitDto]](TopicsField)(Nil)
        groups <- cursor.getOrElse[List[GroupHitDto]](GroupsField)(Nil)
        subjects <- cursor.getOrElse[List[SubjectHitDto]](SubjectsField)(Nil)
      } yield SearchResultsDto(topics, groups, subjects),
    (dto: SearchResultsDto) =>
      Json.obj(
        TopicsField -> dto.topics.asJson,
        GroupsField -> dto.groups.asJson,
        SubjectsField -> dto.subjects.asJson
      )
  )

  given Schema[SearchResultsDto] =
    Schema.derived[SearchResultsDto].description("What the query found, grouped by kind")

  given CanEqual[SearchResultsDto, SearchResultsDto] = CanEqual.derived
}

/** The answer to one cross-entity search.
  *
  * @param partial
  *   the services the gateway could not ask, by id. It is a list of ids and not a boolean because the remedy
  *   differs per service and the browser has to be able to say which row is missing: a deployment that routes
  *   no schema service is a normal deployment whose subject results will never arrive, while a topic service
  *   that timed out is an outage somebody can go and look at. A boolean would collapse those two into one
  *   grey sentence.
  *
  * An empty list is the ordinary answer and means every service answered — including on a deployment with no
  * clusters at all, where all three services are routed and askable and there is nothing to ask them about.
  * That case is deliberately not named here: `partial` says "we could not ask", and saying it about a service
  * that was never in any difficulty is the one thing this field cannot afford to do. `SearchUseCase` carries
  * the argument and what it costs.
  */
final case class SearchAnswerDto(results: SearchResultsDto, partial: List[ServiceId])

object SearchAnswerDto {

  val ResultsField: String = "results"
  val PartialField: String = "partial"

  given Codec[SearchAnswerDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        results <- cursor.getOrElse[SearchResultsDto](ResultsField)(SearchResultsDto.Empty)
        partial <- cursor.getOrElse[List[ServiceId]](PartialField)(Nil)
      } yield SearchAnswerDto(results, partial),
    (dto: SearchAnswerDto) => Json.obj(ResultsField -> dto.results.asJson, PartialField -> dto.partial.asJson)
  )

  given Schema[SearchAnswerDto] = Schema
    .derived[SearchAnswerDto]
    .description("Everything one query found, with the services that could not be asked named")

  given CanEqual[SearchAnswerDto, SearchAnswerDto] = CanEqual.derived
}
