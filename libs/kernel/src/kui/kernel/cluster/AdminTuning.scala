package kui.kernel.cluster

import scala.concurrent.duration.*

import cats.data.NonEmptyList

import kui.kernel.ValidationError

/** The knobs `libs/kafka` reads when it builds an admin client and when it splits a large request into
  * chunks.
  *
  * Every default is a number `research/kafka/admin-capabilities.md` §0 records from the reference
  * implementations rather than a guess, and `AdminTuningSuite` asserts each one, so changing a default is a
  * deliberate act with a failing test attached.
  */
final case class AdminTuning(
    /** `request.timeout.ms`: how long one round trip to a broker may take. */
    requestTimeout: FiniteDuration,
    /** `default.api.timeout.ms`: how long a whole admin call may take, retries included. */
    apiTimeout: FiniteDuration,
    /** How many topics go into one `describeTopics` request. */
    topicChunkSize: Int,
    /** How many partitions go into one `listOffsets` request. */
    partitionChunkSize: Int,
    /** How many groups go into one `describeConsumerGroups` request. */
    groupChunkSize: Int,
    /** How many chunks are in flight at once. */
    parallelism: Int,
    /** How often the cluster topology snapshot is refreshed (`ARCHITECTURE.md` §9). */
    metadataRefresh: FiniteDuration,
    /** How often the feature probe is repeated (`ARCHITECTURE.md` §9). */
    capabilityRefresh: FiniteDuration
) {

  /** Every problem at once, because these arrive from configuration and an operator who set two of them
    * wrongly should be told about both.
    *
    * A `requestTimeout` larger than `apiTimeout` is refused rather than clamped: it means the per-request
    * bound can never be reached, so the number the operator wrote has no effect, and silently ignoring a
    * configured number is worse than refusing it.
    */
  def validate: Either[NonEmptyList[ValidationError], AdminTuning] = {
    val problems = List(
      positive("admin.requestTimeout", requestTimeout),
      positive("admin.apiTimeout", apiTimeout),
      positive("admin.metadataRefresh", metadataRefresh),
      positive("admin.capabilityRefresh", capabilityRefresh),
      positiveCount("admin.topicChunkSize", topicChunkSize),
      positiveCount("admin.partitionChunkSize", partitionChunkSize),
      positiveCount("admin.groupChunkSize", groupChunkSize),
      positiveCount("admin.parallelism", parallelism),
      Option.when(requestTimeout > apiTimeout)(
        ValidationError.Invariant(
          "admin.requestTimeout",
          s"must not be longer than admin.apiTimeout ($requestTimeout > $apiTimeout)"
        )
      )
    ).flatten

    NonEmptyList.fromList(problems).toLeft(this)
  }

  private def positive(field: String, value: FiniteDuration): Option[ValidationError] =
    Option.when(value <= Duration.Zero)(
      ValidationError.Range(field, Some("1ms"), None, value.toString)
    )

  private def positiveCount(field: String, value: Int): Option[ValidationError] =
    Option.when(value <= 0)(ValidationError.Range(field, Some("1"), None, value.toString))
}

object AdminTuning {

  val default: AdminTuning = AdminTuning(
    requestTimeout = 30.seconds,
    apiTimeout = 60.seconds,
    topicChunkSize = 200,
    partitionChunkSize = 200,
    groupChunkSize = 50,
    parallelism = 4,
    metadataRefresh = 30.seconds,
    capabilityRefresh = 1.hour
  )

  given CanEqual[AdminTuning, AdminTuning] = CanEqual.derived
}
