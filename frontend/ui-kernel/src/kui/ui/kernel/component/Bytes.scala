package kui.ui.kernel.component

/** Byte counts as a person reads them.
  *
  * It lives in the kernel because four screens in three features draw one: the dashboard's disk totals, the
  * broker list, the topic list's Size column and, from M3, a message's size. It began in `ui-clusters`, where
  * it was written for the first three; the fourth is what moved it, because a feature may not depend on
  * another feature and the alternative was a second copy free to round differently from the first.
  *
  * ## Binary units, and why they are spelled that way
  *
  * Kafka reports disk usage in bytes and every broker setting that bounds a size is a power of two, so a
  * `log.segment.bytes` of 1073741824 has to read as `1 GiB` and not as `1.07 GB`. Writing `GiB` rather than
  * `GB` is not pedantry here: an operator comparing this figure against a setting they typed needs the two to
  * agree, and the two units differ by seven percent at gigabyte scale.
  *
  * ## Why `None` is not zero
  *
  * A cluster that could not be read has no disk usage; a cluster with empty disks has zero. Rendering both as
  * `0 B` would make an outage look like an idle cluster, which is the single worst thing this screen can do.
  * `None` renders `DataTable.missing`, the one em dash the whole product uses.
  */
object Bytes {

  private val Units = List("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

  private val Step = 1024.0

  /** `0 B`, `1023 B`, `1.0 KiB`, `1.4 GiB`; `—` for `None`.
    *
    * Whole bytes are printed without a decimal, because "1023.0 B" is noise; every larger unit keeps one
    * decimal, which is enough to tell 1.4 from 1.5 GiB and few enough digits to scan down a column.
    */
  def format(bytes: Option[Long]): String =
    bytes match {
      case None => DataTable.missing
      case Some(value) if value < 0 => DataTable.missing
      case Some(value) if value < Step.toLong => s"$value ${Units.head}"
      case Some(value) => scaled(value)
    }

  private def scaled(value: Long): String = {
    // The exponent, capped so that an absurd figure renders in the largest unit rather than running off the
    // end of the list.
    val exponent = math.min((math.log(value.toDouble) / math.log(Step)).toInt, Units.length - 1)
    val amount = value.toDouble / math.pow(Step, exponent.toDouble)
    val rounded = math.round(amount * 10) / 10.0
    // Rounding can push a value up a unit: 1048570 bytes is 1023.996 KiB, which prints as 1024.0 KiB unless
    // it is promoted to 1.0 MiB.
    if rounded >= Step && exponent < Units.length - 1 then f"${rounded / Step}%.1f ${Units(exponent + 1)}"
    else f"$rounded%.1f ${Units(exponent)}"
  }

  /** The fraction of the largest value in a set, for a magnitude bar.
    *
    * Zero when there is nothing to compare against, rather than a division by zero: a cluster with no data
    * would otherwise produce a `NaN` bar width, which a browser renders as a bar of no width and a `NaN` in
    * the DOM for anybody reading it.
    */
  def fraction(value: Option[Long], max: Long): Double =
    if max <= 0 then 0.0 else value.fold(0.0)(_.toDouble / max.toDouble).max(0.0).min(1.0)

  /** The same, for counts rather than sizes. */
  def fractionOf(value: Option[Int], max: Int): Double = fraction(value.map(_.toLong), max.toLong)
}
