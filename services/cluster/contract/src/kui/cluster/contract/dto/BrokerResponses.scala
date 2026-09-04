package kui.cluster.contract.dto

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.Section
import kui.contracts.cluster.{BrokerConfigEntryDto, BrokerDto, LogDirDto, QuorumDto}

/** The brokers of one cluster, or the reason there are none to show.
  *
  * The list is inside a `Section` because it comes from a live admin call. A cluster that cannot be reached
  * answers 200 with `unavailable` and a reason, and one whose last snapshot is old answers `stale` with the
  * data and the time it was fetched — both of which a screen can render. A 5xx would tell the browser only
  * that something went wrong somewhere, which is the answer it can do least with.
  */
/** @param quorum
  *   the KRaft metadata quorum, when this cluster has one and KUI could read it. It travels with the broker
  *   list rather than on an endpoint of its own because it is a fact about the same nodes read in the same
  *   snapshot pass — and because a quorum's lag is computed against a high watermark, so a second call would
  *   be a second *moment* and could produce a lag that never existed.
  *
  * `None` on a ZooKeeper cluster, on a KRaft cluster older than 3.3, and on one that refused
  * `describeMetadataQuorum`. All three mean "there is no quorum information here", which is exactly what a
  * panel that renders nothing needs to know — and none of them is a failure of the brokers call, which is why
  * this is an `Option` beside the section rather than a second section.
  */
final case class BrokersResponse(brokers: Section[List[BrokerDto]], quorum: Option[QuorumDto] = None)

object BrokersResponse {

  given Codec[BrokersResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        brokers <- cursor.get[Section[List[BrokerDto]]]("brokers")
        // Read leniently, so a recorded document from before the quorum existed still decodes and lands
        // on "no quorum panel" rather than on a decode failure.
        quorum <- cursor.getOrElse[Option[QuorumDto]]("quorum")(None)
      } yield BrokersResponse(brokers, quorum),
    (response: BrokersResponse) =>
      Json.obj("brokers" -> response.brokers.asJson, "quorum" -> response.quorum.asJson)
  )

  given Schema[BrokersResponse] =
    Schema.derived[BrokersResponse].description("The cluster's brokers, or why they could not be read")

  given CanEqual[BrokersResponse, BrokersResponse] = CanEqual.derived
}

/** One broker's settings, or the reason they could not be read.
  *
  * A managed service that authenticates but authorizes nothing refuses `describeConfigs` outright; that is a
  * section-level `unavailable` with the refusal as its reason, never an empty list, which a reader would take
  * for "this broker has no settings".
  */
final case class BrokerConfigsResponse(configs: Section[List[BrokerConfigEntryDto]])

object BrokerConfigsResponse {

  given Codec[BrokerConfigsResponse] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[Section[List[BrokerConfigEntryDto]]]("configs").map(BrokerConfigsResponse(_)),
    (response: BrokerConfigsResponse) => Json.obj("configs" -> response.configs.asJson)
  )

  given Schema[BrokerConfigsResponse] =
    Schema.derived[BrokerConfigsResponse].description("One broker's settings, or why they could not be read")

  given CanEqual[BrokerConfigsResponse, BrokerConfigsResponse] = CanEqual.derived
}

/** Log directories, for one broker or for every broker in the cluster.
  *
  * Two levels of failure live here and they are not the same. The `Section` covers "the call could not be
  * made at all"; each `LogDirDto.error` covers "this one disk is offline while the rest of the answer is
  * good", which is exactly how `describeLogDirs` reports a failed disk.
  */
final case class LogDirsResponse(logDirs: Section[List[LogDirDto]])

object LogDirsResponse {

  given Codec[LogDirsResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[List[LogDirDto]]]("logDirs").map(LogDirsResponse(_)),
    (response: LogDirsResponse) => Json.obj("logDirs" -> response.logDirs.asJson)
  )

  given Schema[LogDirsResponse] =
    Schema
      .derived[LogDirsResponse]
      .description("Log directories, with a per-directory error where there is one")

  given CanEqual[LogDirsResponse, LogDirsResponse] = CanEqual.derived
}
