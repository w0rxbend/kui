package kui.cluster.domain

import scala.util.matching.Regex

import kui.kernel.error.{DomainError, FieldError}

/** How the broker version was established.
  *
  * The UI shows it, because "3.9, from the inter-broker protocol version" and "3.9, from the finalized
  * metadata version" are answers of different quality and an operator chasing a compatibility problem needs
  * to know which one they are looking at.
  */
enum VersionSource {

  /** `describeFeatures().finalizedFeatures()["metadata.version"]`, mapped through the level table. */
  case MetadataVersion

  /** The `inter.broker.protocol.version` broker configuration entry: the ZooKeeper-mode fallback. */
  case InterBrokerProtocol
}

object VersionSource {
  given CanEqual[VersionSource, VersionSource] = CanEqual.derived
}

/** The broker version KUI detected, and how.
  *
  * `raw` is kept verbatim because the operator has to be able to match it against what the broker itself
  * reports; `major` and `minor` exist because that is all any comparison in KUI needs and parsing a Kafka
  * version's third component is a distraction (`3.9-IV0` has none).
  */
final case class KafkaVersion private (major: Int, minor: Int, raw: String, source: VersionSource) {

  def isAtLeast(otherMajor: Int, otherMinor: Int): Boolean =
    major > otherMajor || (major == otherMajor && minor >= otherMinor)

  /** KUI supports 2.8 and newer. `false` makes the UI show a warning banner; it never blocks a page. */
  def meetsMinimum: Boolean = isAtLeast(KafkaVersion.MinimumMajor, KafkaVersion.MinimumMinor)

  /** `3.9`, for a heading. The raw string is for the detail panel. */
  def short: String = s"$major.$minor"
}

object KafkaVersion {

  val MinimumMajor: Int = 2
  val MinimumMinor: Int = 8

  /** A leading `<int>.<int>`, and then anything: `3.9-IV0`, `3.9.1`, `4.0`, `2.8.2`. */
  private val Leading: Regex = """^\s*(\d{1,4})\.(\d{1,4})(?:[.\-+].*)?\s*$""".r

  /** Parses a broker-reported version string, or says why it could not.
    *
    * Total over arbitrary input: it never throws, and a `Right` always carries the input verbatim in `raw`,
    * so nothing downstream has to reconstruct what the broker actually said.
    */
  def parse(raw: String, source: VersionSource): Either[DomainError, KafkaVersion] =
    raw match {
      case Leading(major, minor) =>
        (major.toIntOption, minor.toIntOption) match {
          case (Some(m), Some(n)) => Right(KafkaVersion(m, n, raw, source))
          case _ => Left(refusal(raw))
        }
      case _ => Left(refusal(raw))
    }

  private def refusal(raw: String): DomainError =
    DomainError.InvariantViolation(
      s"'$raw' is not a Kafka version",
      List(FieldError.of("version", "must start with <major>.<minor>, such as '3.9' or '3.9-IV0'"))
    )

  given Ordering[KafkaVersion] = Ordering.by(v => (v.major, v.minor))
  given CanEqual[KafkaVersion, KafkaVersion] = CanEqual.derived
}
