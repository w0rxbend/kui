package kui.ui.kernel.component

/** Whole numbers, rendered so that a column of them can be compared down rather than read across.
  *
  * Digits are grouped in threes with a thin space rather than a comma. A comma is a decimal separator in
  * about half the world, and "1,204" read as a decimal is three orders of magnitude wrong — which, on a lag
  * figure an operator is deciding whether to act on, is the whole decision. The thin space is what the
  * international standard uses for exactly this reason, and it survives a copy-paste into a spreadsheet as a
  * space rather than as a wrong separator.
  *
  * There is no rounding to "1.2M". A lag of 1 204 331 and a lag of 1 249 000 both round to the same three
  * characters and they are not the same problem.
  */
object Numbers {

  /** The separator between groups of three digits: U+2009 THIN SPACE. */
  val GroupSeparator: String = " "

  def grouped(value: Long): String = {
    val digits = math.abs(value).toString
    val sign = if value < 0 then "-" else ""

    // Grouped from the right, which is where the units digit is, so a number of any length lines up under
    // any other.
    val chunks =
      digits.reverse.grouped(3).map(_.reverse).toList.reverse

    sign + chunks.mkString(GroupSeparator)
  }

  /** A rate, at one decimal place, with an explicit sign when it is negative.
    *
    * One decimal, not three: this is a rate sampled over a thirty-second interval, and the digits past the
    * first are noise from where in the interval the two samples happened to land. Printing them would invite
    * an operator to compare two numbers that do not differ.
    *
    * A rate below one tenth of a record per second but not zero renders as `< 0.1` rather than as `0.0`,
    * because a group committing something very slowly and a group committing nothing are the difference
    * between "slow" and "stuck", which is the whole question this column is asked.
    */
  def rate(value: Double): String =
    if value == 0.0 then "0"
    else if math.abs(value) < 0.05 then if value < 0 then "> -0.1" else "< 0.1"
    else {
      val rounded = math.round(math.abs(value) * 10.0) / 10.0
      val sign = if value < 0 then "-" else ""
      val whole = rounded.toLong
      val tenth = math.round((rounded - whole) * 10.0)

      s"$sign${grouped(whole)}.$tenth"
    }

  /** Where `value` sits between zero and `max`, for a magnitude bar.
    *
    * A `max` of zero — every row has caught up — gives zero rather than a division by infinity, so a screen
    * where nothing is behind draws no bars at all instead of drawing every bar full.
    */
  def fraction(value: Long, max: Long): Double =
    if max <= 0L then 0.0
    else math.max(0.0, math.min(1.0, value.toDouble / max.toDouble))
}
