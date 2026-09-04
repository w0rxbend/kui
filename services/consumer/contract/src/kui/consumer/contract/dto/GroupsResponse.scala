package kui.consumer.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.Section

/** One page of the consumer-group list, with how much of the truth it is showing.
  *
  * ==Why the page is wrapped==
  *
  * `groups` is a [[kui.contracts.Section]] rather than a bare page, exactly as the topic list's is. Before
  * this envelope existed the list answered a bare `200` carrying the rows of the last successful scrape
  * whether or not the cluster was still answering, and the browser had no way to tell the two apart.
  *
  * That is a worse failure here than anywhere else in KUI, because of what this list is read for. Lag is the
  * one number on the screen that is supposed to move on its own, and an operator reads it to decide whether
  * their consumers are keeping up. A broker that has gone away makes it stop moving rather than climb — so
  * the screen quietly showed the shape of a healthy, caught-up cluster at the exact moment the cluster was
  * unreachable. A number that is wrong and looks right is worse than no number at all.
  *
  * `Ok` and `Stale` both carry the rows; `Stale` adds the time they were fetched and a reason code, which is
  * what the browser dims the table and stamps the badge from. `Unavailable` carries no rows, and the screen
  * renders an explanation with a retry rather than an empty table — an empty table is a claim that the
  * cluster has no consumer groups.
  *
  * @param incompleteCoordinators
  *   how many group coordinators did not answer the pass this page was cut from. It sits **outside** the
  *   section deliberately, for the same reason `TopicsResponse.incompleteTopics` does: it is a fact about the
  *   rows that *are* being shown, so it is reported for a stale section too, and it is what lets the screen
  *   say "this list may be short" instead of silently holding fewer groups
  */
final case class GroupsResponse(groups: Section[GroupPageDto], incompleteCoordinators: Int)

object GroupsResponse {

  given Codec[GroupsResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        groups <- cursor.get[Section[GroupPageDto]]("groups")
        // Absent means none, which is the overwhelmingly common case. `groups` is required, so a
        // truncated document still fails to decode rather than arriving as an empty list.
        incompleteCoordinators <- cursor.getOrElse[Int]("incompleteCoordinators")(0)
      } yield GroupsResponse(groups, incompleteCoordinators),
    (response: GroupsResponse) =>
      Json.obj(
        "groups" -> response.groups.asJson,
        "incompleteCoordinators" -> response.incompleteCoordinators.asJson
      )
  )

  given Schema[GroupsResponse] = Schema
    .derived[GroupsResponse]
    .description("One page of the consumer-group list, plus how many coordinators did not answer")

  given CanEqual[GroupsResponse, GroupsResponse] = CanEqual.derived
}
