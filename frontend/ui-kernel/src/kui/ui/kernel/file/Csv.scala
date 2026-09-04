package kui.ui.kernel.file

/** Comma-separated values, written properly, once (OT-002).
  *
  * ## Why this is a shared file and not four lines in one screen
  *
  * Because "join the cells with commas" is wrong for real data and is wrong in a way nobody notices until
  * somebody exports the record that breaks it. A Kafka value contains commas, quotation marks and newlines
  * routinely — a JSON document contains all three — and a naive join produces a file that opens with the
  * columns silently shifted, which is worse than no export at all: the user acts on data that is not what
  * they exported. Four screens will want an export before this milestone list is finished, and one of them
  * getting the quoting right is not enough.
  *
  * ## The rules, and where they come from
  *
  * RFC 4180, with the two additions every real consumer of a CSV file needs:
  *
  *   - a field is quoted when it contains a comma, a quotation mark, a carriage return or a newline, and a
  *     quotation mark inside a quoted field is doubled;
  *   - rows end with CRLF, which is what the RFC says and what stops a file written on this machine being
  *     read as one long line on another;
  *   - a field that begins with `=`, `+`, `-` or `@` is prefixed with a single quotation mark. That is not
  *     cosmetic: a spreadsheet treats such a field as a *formula*, so a Kafka header whose value happens to
  *     start with `=` becomes executable content in whatever opens the file. The prefix is the standard
  *     defence and costs one character in a cell nobody was going to compute with anyway.
  */
object Csv {

  /** The line ending RFC 4180 specifies. */
  val LineEnd: String = "\r\n"

  private val Special: Set[Char] = Set(',', '"', '\n', '\r')

  private val FormulaStarts: Set[Char] = Set('=', '+', '-', '@')

  /** One whole document: a header row and then one row per record. */
  def render(header: List[String], rows: List[List[String]]): String =
    (header :: rows).map(row).mkString("", LineEnd, LineEnd)

  /** One row, already escaped. */
  def row(cells: List[String]): String = cells.map(field).mkString(",")

  /** One cell, quoted exactly when it has to be.
    *
    * Quoting only when necessary rather than always, because a file where every cell is quoted is harder for
    * a person to read in a text editor — and reading the file in a text editor is what somebody does when the
    * spreadsheet has mangled it.
    */
  def field(raw: String): String = {
    val guarded = if raw.headOption.exists(FormulaStarts.contains) then s"'$raw" else raw

    if guarded.exists(Special.contains) then "\"" + guarded.replace("\"", "\"\"") + "\""
    else guarded
  }
}
