package kui.kernel.cluster

import kui.kernel.ValidationError

/** A comma-separated `host:port` list, exactly as Kafka's `bootstrap.servers` wants it.
  *
  * It is a type rather than a `String` because the failure it prevents is expensive and late: an address with
  * no port, or an empty list, is accepted by every layer of KUI and then produces a `ConfigException` inside
  * a Kafka client on a background thread, thirty seconds after startup, with no indication of which
  * configured cluster it came from. Validating at construction turns that into a `ValidationError` the
  * configuration loader accumulates with every other bad field into the single startup message M1's exit
  * criteria require.
  */
opaque type BootstrapServers = String

object BootstrapServers {

  private val Field: String = "bootstrapServers"

  private val Expected: String =
    "a comma-separated list of host:port entries, such as 'broker-1:9092,broker-2:9092'"

  private val MinPort: Int = 1
  private val MaxPort: Int = 65535

  /** Accepts `host:port[,host:port]*`.
    *
    * Whitespace around an entry is trimmed, because a YAML list folded across lines produces it and an
    * operator should not have to know that. Everything else is refused: an empty list, an entry with no port,
    * a port outside 1..65535, and a duplicate entry (which is never intentional and silently halves the
    * client's view of the cluster's seed set).
    */
  def from(raw: String): Either[ValidationError, BootstrapServers] =
    fromList(raw.split(",", -1).toList)

  def fromList(entries: List[String]): Either[ValidationError, BootstrapServers] = {
    val trimmed = entries.map(_.trim)

    if trimmed.forall(_.isEmpty) then Left(ValidationError.Format(Field, Expected, entries.mkString(",")))
    else
      for {
        checked <- traverseEntries(trimmed)
        _ <- rejectDuplicates(checked)
      } yield checked.mkString(",")
  }

  /** Wraps a value that has already been validated somewhere else. Never call it on user input. */
  def unsafe(raw: String): BootstrapServers = raw

  private def traverseEntries(entries: List[String]): Either[ValidationError, List[String]] =
    entries.foldLeft[Either[ValidationError, List[String]]](Right(Nil)) { (acc, entry) =>
      for {
        soFar <- acc
        checked <- checkEntry(entry)
      } yield soFar :+ checked
    }

  /** One `host:port`. The port is taken from the last colon so that a bracketed IPv6 literal (`[::1]:9092`)
    * keeps its own colons.
    */
  private def checkEntry(entry: String): Either[ValidationError, String] = {
    val separator = entry.lastIndexOf(':')

    if separator <= 0 || separator == entry.length - 1 then
      Left(ValidationError.Format(Field, Expected, entry))
    else {
      val port = entry.substring(separator + 1)

      if !port.forall(_.isDigit) then Left(ValidationError.Format(Field, Expected, entry))
      else
        port.toIntOption match {
          case Some(number) if number >= MinPort && number <= MaxPort => Right(entry)
          case _ =>
            Left(
              ValidationError.Range(Field, Some(MinPort.toString), Some(MaxPort.toString), port)
            )
        }
    }
  }

  private def rejectDuplicates(entries: List[String]): Either[ValidationError, Unit] = {
    val duplicates = entries.diff(entries.distinct).distinct

    if duplicates.isEmpty then Right(())
    else
      Left(
        ValidationError.Invariant(
          Field,
          s"the same broker is listed more than once: ${duplicates.mkString(", ")}"
        )
      )
  }

  extension (b: BootstrapServers) {

    /** The joined form, ready to be handed to a Kafka client as `bootstrap.servers`. */
    def value: String = b

    /** The individual entries, in the order they were configured. */
    def hosts: List[String] = b.split(",").toList
  }

  given Ordering[BootstrapServers] = Ordering.String
  given CanEqual[BootstrapServers, BootstrapServers] = CanEqual.derived
}
