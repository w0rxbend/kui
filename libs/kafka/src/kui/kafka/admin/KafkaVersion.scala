package kui.kafka.admin

import scala.util.matching.Regex

/** A Kafka release number, comparable.
  *
  * Parses `3.9`, `3.9.1`, `3.9.1-SNAPSHOT`, `2.8-IV1` and `3.9-IV0`. The `-IVn` suffix is an
  * inter-broker-protocol level rather than a patch number, so it is dropped after the release part has been
  * read — `2.8-IV1` and `2.8-IV0` are both Kafka 2.8.
  */
final case class KafkaVersion(major: Int, minor: Int, patch: Int) extends Ordered[KafkaVersion] {

  def compare(that: KafkaVersion): Int =
    if major != that.major then major.compare(that.major)
    else if minor != that.minor then minor.compare(that.minor)
    else patch.compare(that.patch)

  /** `3.9.1`, or `3.9` when the patch is zero — which is how Kafka itself names a release. */
  def render: String = if patch == 0 then s"$major.$minor" else s"$major.$minor.$patch"

  override def toString: String = render
}

object KafkaVersion {

  /** ADR-030's minimum. Below it KUI warns and keeps working; it does not refuse. */
  val minimumSupported: KafkaVersion = KafkaVersion(2, 8, 0)

  /** `<major>.<minor>[.<patch>]`, with anything after it — `-IV1`, `-SNAPSHOT`, `-ccs` — ignored. */
  private val Pattern: Regex = """^\s*(\d+)\.(\d+)(?:\.(\d+))?(?:[-.].*)?\s*$""".r

  def parse(raw: String): Option[KafkaVersion] = raw match {
    case Pattern(major, minor, patch) =>
      for {
        maj <- major.toIntOption
        min <- minor.toIntOption
        pat <- Option(patch).fold(Option(0))(_.toIntOption)
      } yield KafkaVersion(maj, min, pat)
    case _ => None
  }

  given Ordering[KafkaVersion] = Ordering.fromLessThan(_ < _)
  given CanEqual[KafkaVersion, KafkaVersion] = CanEqual.derived
}
