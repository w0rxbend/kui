package kui.ui.messages.table

import java.time.Instant

import kui.message.contract.MessageDto
import kui.ui.kernel.file.Csv

/** The records on screen, as a CSV file (MS-011).
  *
  * ## What "the records on screen" means, and why it is the right export
  *
  * Exactly what the table is showing: the same page, in the same order, after the same filters, decoded by
  * the same serdes. Asking the server to read the topic again would produce a *different* file — records
  * written in the meantime would be in it, and records a filter removed would come back — and the word
  * "export" would stop meaning "this, as a file". A person exports because they want the thing they are
  * looking at somewhere else.
  *
  * ## Two shapes, because there are two views
  *
  * The list view exports the record: partition, offset, timestamp, key, value, headers as one JSON object.
  * The table view exports its grid — every column the flattener produced, exactly as the screen has them,
  * hidden ones included or not as the user left them. Exporting the grid as the record's raw JSON would throw
  * away the work the user did in choosing those columns, and exporting the list view as a hundred flattened
  * columns would produce a file that has nothing to do with the screen it came from.
  */
object RecordCsv {

  /** The list view's columns. `headers` is one cell holding a JSON object rather than a column per header
    * name: header names differ per record, and a union of them across a page would make a file whose width
    * depends on which records happened to be on screen.
    */
  val ListHeader: List[String] =
    List(
      "partition",
      "offset",
      "timestamp",
      "timestampType",
      "key",
      "value",
      "headers",
      "keySize",
      "valueSize"
    )

  def ofRecords(records: List[MessageDto]): String =
    Csv.render(ListHeader, records.map(cells))

  private def cells(record: MessageDto): List[String] =
    List(
      record.partition.value.toString,
      record.offset.value.toString,
      // ISO-8601 in UTC, not the screen's zone. A timestamp in a file has no screen to explain which zone it
      // is in, and an unqualified local time is the ambiguity that makes two exports impossible to compare.
      record.timestamp.toString,
      record.timestampType,
      record.key.text,
      record.value.text,
      headersJson(record.headers),
      record.keySize.toString,
      record.valueSize.toString
    )

  /** The table view's grid: the three fixed columns and then every path column, in the order on screen.
    *
    * A record with nothing for a column exports an empty cell, for the same reason it renders one — a row
    * whose cells slid one place left would be a file that says a record's status is its total.
    */
  def ofGrid(records: List[MessageDto], paths: List[String], limits: FlattenLimits): String = {
    val rows =
      records.map { record =>
        val flat = JsonFlattener.flatten(RecordSource.of(record), limits)

        List(
          record.partition.value.toString,
          record.offset.value.toString,
          record.timestamp.toString
        ) ++ paths.map(path => flat.cells.getOrElse(path, ""))
      }

    Csv.render(List("partition", "offset", "timestamp") ++ paths, rows)
  }

  /** The headers as one JSON object, written by hand.
    *
    * By hand rather than through circe because the values are already strings and the only thing that needs
    * care is escaping — and because a header name is arbitrary bytes from a producer, so it goes through the
    * same escaping the value does rather than being trusted as a key.
    */
  private def headersJson(headers: Map[String, String]): String =
    if headers.isEmpty then ""
    else
      headers.toList
        .sortBy(_._1)
        .map((name, value) => s"${quoted(name)}:${quoted(value)}")
        .mkString("{", ",", "}")

  private def quoted(raw: String): String =
    "\"" + raw.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""

  /** What the file is called.
    *
    * The topic and the moment, because a person who exports the same topic twice in an afternoon ends up with
    * two files in one folder and no way to tell which is which. The instant is trimmed to seconds and its
    * colons removed, since a colon is not a legal filename character on Windows.
    */
  def fileName(topic: String, at: Instant): String = {
    val stamp = at.toString.takeWhile(_ != '.').replace(":", "").replace("-", "")
    s"${safe(topic)}-$stamp.csv"
  }

  private def safe(topic: String): String =
    topic.map(c => if c.isLetterOrDigit || c == '-' || c == '_' || c == '.' then c else '_')
}
