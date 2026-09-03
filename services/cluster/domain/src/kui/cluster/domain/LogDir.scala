package kui.cluster.domain

import kui.kernel.error.{DomainError, FieldError}
import kui.kernel.{TopicPartition, ValidationError}

/** The absolute path of one log directory on one broker. */
opaque type LogDirPath = String

object LogDirPath {

  val MaxLength: Int = 4096

  private val Field: String = "logDir"

  def from(raw: String): Either[ValidationError, LogDirPath] = {
    val trimmed = raw.trim

    if trimmed.nonEmpty && trimmed.length <= MaxLength then Right(trimmed)
    else Left(ValidationError.Format(Field, s"1 to $MaxLength non-blank characters", raw))
  }

  def unsafe(raw: String): LogDirPath = raw

  extension (p: LogDirPath) def value: String = p

  given Ordering[LogDirPath] = Ordering.String
  given CanEqual[LogDirPath, LogDirPath] = CanEqual.derived
}

/** Why one log directory is not usable.
  *
  * A closed set, because the UI renders a sentence per case and an open `String` would put a Java exception's
  * `getMessage` — which routinely carries a filesystem path and sometimes a hostname — on the screen.
  */
enum LogDirError {

  /** `KafkaStorageException`: the directory is offline and the broker has failed it out. */
  case Offline

  /** The broker reported an error KUI has no case for. `exceptionClass` is a *class name* and never a
    * message, which the smart constructor enforces so the rule cannot be forgotten at a call site.
    */
  case Other(exceptionClass: String)

  /** Display text, safe for a screen. */
  def describe: String = this match {
    case Offline => "the broker has taken this directory offline"
    case Other(name) => s"the broker reported $name"
  }
}

object LogDirError {

  /** Anything that is not a plain `a.b.C` class name becomes `unknown`, so that no message can reach a screen
    * through this constructor even if a caller passes one by mistake.
    */
  private val ClassName: scala.util.matching.Regex = """^[A-Za-z0-9.$]+$""".r

  def other(exceptionClass: String): LogDirError =
    if ClassName.matches(exceptionClass) then Other(exceptionClass) else Other("unknown")

  given CanEqual[LogDirError, LogDirError] = CanEqual.derived
}

/** One replica living in one log directory on one broker.
  *
  * This is a *disk* fact, not a topic fact: it is what `describeLogDirs` reports, and it is the only
  * partition-shaped value the cluster domain owns.
  */
final case class ReplicaInfo(
    partition: TopicPartition,
    sizeBytes: Long,
    offsetLag: Long,
    isFuture: Boolean
)

object ReplicaInfo {
  given CanEqual[ReplicaInfo, ReplicaInfo] = CanEqual.derived
}

/** One log directory on one broker: whether it is usable, how big it is, and what lives in it. */
final case class LogDir private (
    path: LogDirPath,
    error: Option[LogDirError],
    totalBytes: Option[Long],
    usableBytes: Option[Long],
    replicas: List[ReplicaInfo]
) {

  def isHealthy: Boolean = error.isEmpty

  /** The sum of the replica sizes actually reported.
    *
    * Distinct from `totalBytes - usableBytes`, which is the *filesystem's* view and includes everything on
    * the disk that is not Kafka.
    */
  def usedByKafkaBytes: Long = replicas.map(_.sizeBytes).sum

  /** Only the current replicas. A future replica is a second copy being moved in, and counting it would
    * double-count the partition.
    */
  def currentReplicas: List[ReplicaInfo] = replicas.filterNot(_.isFuture)
}

object LogDir {

  /** Fails on a negative size or lag, or when a directory claims more free space than it has. */
  def from(
      path: LogDirPath,
      error: Option[LogDirError],
      totalBytes: Option[Long],
      usableBytes: Option[Long],
      replicas: List[ReplicaInfo]
  ): Either[DomainError, LogDir] = {
    val problems = List.newBuilder[FieldError]

    totalBytes
      .filter(_ < 0L)
      .foreach(b => problems += FieldError.of("totalBytes", s"must not be negative, got $b"))
    usableBytes
      .filter(_ < 0L)
      .foreach(b => problems += FieldError.of("usableBytes", s"must not be negative, got $b"))

    replicas.foreach { replica =>
      if replica.sizeBytes < 0L then
        problems += FieldError.of(
          "sizeBytes",
          s"${replica.partition.topic.value}-${replica.partition.partition.value} reported a negative size"
        )

      if replica.offsetLag < 0L then
        problems += FieldError.of(
          "offsetLag",
          s"${replica.partition.topic.value}-${replica.partition.partition.value} reported a negative lag"
        )
    }

    (totalBytes, usableBytes) match {
      case (Some(total), Some(usable)) if usable > total =>
        problems += FieldError.of("usableBytes", s"must not exceed totalBytes ($usable > $total)")
      case _ => ()
    }

    val found = problems.result()

    if found.isEmpty then Right(LogDir(path, error, totalBytes, usableBytes, replicas))
    else
      Left(
        DomainError.InvariantViolation(
          s"the broker reported an impossible log directory (${path.value})",
          found
        )
      )
  }

  given Ordering[LogDir] = Ordering.by(_.path.value)
  given CanEqual[LogDir, LogDir] = CanEqual.derived
}
