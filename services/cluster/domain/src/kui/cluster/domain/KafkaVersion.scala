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

  /** The same feature level, but one newer than KUI's table. The number is the highest release KUI knows and
    * is therefore a **lower bound**: the cluster is at least this new. The UI must render it as "4.4 or
    * newer", never as a plain "4.4".
    */
  case MetadataVersionAtLeast

  /** The `inter.broker.protocol.version` broker configuration entry: the ZooKeeper-mode fallback. Kafka 4.0
    * removed the setting along with ZooKeeper mode, so this source only appears on 2.8-to-3.x clusters.
    */
  case InterBrokerProtocol

  /** Whether the release number is exact or only a floor. */
  def isExact: Boolean = this match {
    case MetadataVersion | InterBrokerProtocol => true
    case MetadataVersionAtLeast => false
  }
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

  /** What a version cell should say: `3.9`, or `4.4 or newer` when the number is only a lower bound. */
  def display: String = if source.isExact then short else s"$short or newer"
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

  /** A version KUI resolved rather than parsed: the level table produced the numbers, and `raw` is whatever
    * the broker actually said (`level 30`).
    *
    * It exists because the numbers and the broker's own words are not the same string here, and re-parsing
    * the words is how the version cell used to come out empty even when the table had resolved the level:
    * `parse("level 30")` cannot succeed, and it was the only route the adapter had.
    */
  def resolved(
      major: Int,
      minor: Int,
      raw: String,
      source: VersionSource
  ): Either[DomainError, KafkaVersion] =
    if major < 0 || minor < 0 then
      Left(
        DomainError.InvariantViolation(
          s"'$major.$minor' is not a Kafka version",
          List(FieldError.of("version", "major and minor must not be negative"))
        )
      )
    else Right(KafkaVersion(major, minor, raw, source))

  private def refusal(raw: String): DomainError =
    DomainError.InvariantViolation(
      s"'$raw' is not a Kafka version",
      List(FieldError.of("version", "must start with <major>.<minor>, such as '3.9' or '3.9-IV0'"))
    )

  given Ordering[KafkaVersion] = Ordering.by(v => (v.major, v.minor))
  given CanEqual[KafkaVersion, KafkaVersion] = CanEqual.derived
}
