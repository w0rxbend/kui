package kui.consumer.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.consumer.contract.dto.ConsumerCodecs.given
import kui.kernel.GroupId
import kui.kernel.group.GroupState

/** What changed about one group since the browser last asked.
  *
  * Five fields, not a whole group: the list page repaints a lag cell, a state chip and a member count, and
  * sending the rest — members, assignments, per-partition offsets — would make a poll that runs every few
  * seconds the most expensive request in the product.
  */
final case class LagUpdateDto(
    groupId: GroupId,
    totalLag: Option[Long],
    pace: Option[Double],
    state: GroupState,
    members: Int
)

object LagUpdateDto {

  given Codec[LagUpdateDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        groupId <- cursor.get[GroupId]("groupId")
        totalLag <- cursor.get[Option[Long]]("totalLag")
        pace <- cursor.get[Option[Double]]("pace")
        state <- cursor.get[GroupState]("state")
        members <- cursor.get[Int]("members")
      } yield LagUpdateDto(groupId, totalLag, pace, state, members),
    (dto: LagUpdateDto) =>
      Json.obj(
        "groupId" -> dto.groupId.asJson,
        "totalLag" -> dto.totalLag.asJson,
        "pace" -> dto.pace.asJson,
        "state" -> dto.state.asJson,
        "members" -> dto.members.asJson
      )
  )

  given Schema[LagUpdateDto] =
    Schema.derived[LagUpdateDto].description("One group's changed lag, pace, state and member count")

  given CanEqual[LagUpdateDto, LagUpdateDto] = CanEqual.derived
}

/** The answer to "what has changed since the token I gave you?".
  *
  * Three decisions are visible in these five fields, and each is in the plan for a reason.
  *
  * **`token` is issued by the server and opaque to the client.** The reference implementation sends the
  * browser's own `lastUpdate` timestamp back to the server, which makes correctness depend on two clocks
  * agreeing: skew one way silently drops updates, skew the other way replays them, and neither failure is
  * visible to anyone (M4 DEVPLAN §10 D9, risk R-12). This token carries the snapshot version it was cut from,
  * and a token the server does not recognise — expired, from a restarted service, tampered with — is answered
  * with a **full** payload and a fresh token, never with an error.
  *
  * **`full` says which of those two answers this is.** Without it a client cannot tell "nothing changed" from
  * "here is everything again", and would have to guess whether to merge the rows or replace them. Merging a
  * full payload leaves deleted groups on screen forever.
  *
  * **`gone` is separate from `changed`.** A group that was deleted is not a group with zero lag.
  *
  * @param nextPollMs
  *   how long the client should wait before asking again. Server-issued so that a degraded consumer service
  *   can slow every browser down at once — this is the field exit criterion 4 asserts the poller obeys
  *   instead of its own default (ADR-039's `suggestedPollIntervalMs`, arriving here)
  */
final case class LagDeltaDto(
    changed: List[LagUpdateDto],
    gone: List[GroupId],
    token: String,
    nextPollMs: Long,
    full: Boolean
)

object LagDeltaDto {

  given Codec[LagDeltaDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        changed <- cursor.get[List[LagUpdateDto]]("changed")
        gone <- cursor.get[List[GroupId]]("gone")
        token <- cursor.get[String]("token")
        nextPollMs <- cursor.get[Long]("nextPollMs")
        full <- cursor.get[Boolean]("full")
      } yield LagDeltaDto(changed, gone, token, nextPollMs, full),
    (dto: LagDeltaDto) =>
      Json.obj(
        "changed" -> dto.changed.asJson,
        "gone" -> dto.gone.asJson,
        "token" -> dto.token.asJson,
        "nextPollMs" -> dto.nextPollMs.asJson,
        "full" -> dto.full.asJson
      )
  )

  given Schema[LagDeltaDto] = Schema
    .derived[LagDeltaDto]
    .description("Groups whose lag changed since the given token, the groups that are gone, and a new token")

  given CanEqual[LagDeltaDto, LagDeltaDto] = CanEqual.derived
}
