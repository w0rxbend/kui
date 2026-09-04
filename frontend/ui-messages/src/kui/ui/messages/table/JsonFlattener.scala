package kui.ui.messages.table

import io.circe.Json

/** The three parts of one record that the table view spreads across columns.
  *
  * It is deliberately *not* the message DTO. The flattener is the one piece of the table view that is pure,
  * total and worth property-testing, and keeping it free of the wire type means the suite can generate
  * arbitrary JSON directly instead of building a plausible record around it. The screen converts a
  * `MessageDto` into one of these in a single function.
  *
  * A payload that is not JSON arrives here as `Json.fromString(text)` — a scalar at the root, which flattens
  * to exactly one `V` column holding the text, which is the documented degraded behaviour and needs no
  * special case anywhere in this file.
  */
final case class FlatSource(headers: Vector[(String, String)], key: Json, value: Json)

object FlatSource {

  val empty: FlatSource = FlatSource(Vector.empty, Json.Null, Json.Null)

  given CanEqual[FlatSource, FlatSource] = CanEqual.derived
}

/** One record, ready to render: the columns it fills and what it puts in each of them.
  *
  * `cells` is a map rather than a list because the table asks it a column at a time, and a record that has
  * nothing for a column must render an empty cell rather than shift its neighbours along.
  */
final case class FlatRow(source: FlatSource, cells: Map[String, String], order: Vector[String])

object FlatRow {
  given CanEqual[FlatRow, FlatRow] = CanEqual.derived
}

/** Kouncil's `json-grid` algorithm (DC-H8), reimplemented as a total function.
  *
  * Every record becomes a set of `path -> text` cells: `H.<header>` for headers, `K...` for the key and
  * `V...` for the value, with dotted field steps and `[i]` array steps. Three things bound it — a depth cap,
  * an array-element cap and, at the table level, a row cap — and each of them leaves a visible marker rather
  * than silently discarding data:
  *
  *   - past `maxDepth`, the remaining subtree becomes **one** cell holding its compact JSON, so the user can
  *     see there is more and open the record in the list view;
  *   - past `maxArrayElements`, the remaining elements become one `[+]` cell reading `+N more`.
  *
  * The distinction matters: a truncated subtree is still fully readable in its cell, while a collapsed array
  * tail genuinely is not, and only the second is a loss. Both are labelled.
  */
object JsonFlattener {

  /** Flattens one record. Total: every JSON value, however deep or however strange, produces a row. */
  def flatten(source: FlatSource, limits: FlattenLimits): FlatRow = {
    val cells = Map.newBuilder[String, String]
    val order = Vector.newBuilder[String]

    def emit(steps: List[PathStep], text: String): Unit = {
      val path = FlatPath.render(steps)
      cells += (path -> text)
      order += path
    }

    // Headers are one level deep by definition — a Kafka header value is bytes, which the service has
    // already decoded to text — so they never reach the recursion below.
    source.headers.foreach { (name, headerValue) =>
      emit(List(PathStep.Field(FlatPath.Headers), PathStep.Field(name)), headerValue)
    }

    walk(source.key, List(PathStep.Field(FlatPath.Key)), 0, limits, emit)
    walk(source.value, List(PathStep.Field(FlatPath.Value)), 0, limits, emit)

    FlatRow(source, cells.result(), order.result().distinct)
  }

  /** Flattens a page of records, keeping at most `maxRows` of them.
    *
    * The cap is applied here rather than by the caller so that there is one answer to "how many rows can be
    * on screen", which is the same reason `FlattenLimits` exists at all.
    */
  def flattenAll(sources: Vector[FlatSource], limits: FlattenLimits): Vector[FlatRow] =
    sources.take(limits.maxRows.max(0)).map(source => flatten(source, limits))

  /** The columns of the table: every path any row filled, in the order it was first seen, capped.
    *
    * First-seen order — rather than Kouncil's group-by-prefix ordering — is what makes the columns *stable*:
    * `columns(rows)` is always a prefix of `columns(rows ++ more)`, so a row arriving from a live stream adds
    * columns on the right and never shuffles the ones the user is reading. The natural grouping survives
    * anyway, because each row emits its headers, then its key, then its value, so the first record seeds the
    * table in that order.
    */
  def columns(rows: Vector[FlatRow], limits: FlattenLimits): Vector[String] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    rows.foreach(row => row.order.foreach(path => seen.add(path): Unit))
    seen.toVector.take(limits.maxColumns.max(0))
  }

  /** The text of a leaf. A JSON string is shown as its characters, not as a quoted literal: a column of
    * `"orders"` with the quotes visible is noise in every row of the table. Everything else is its compact
    * JSON, which for numbers, booleans and `null` is what a reader expects to see.
    */
  private def scalarText(json: Json): String = json.asString.getOrElse(json.noSpaces)

  private def walk(
      json: Json,
      steps: List[PathStep],
      depth: Int,
      limits: FlattenLimits,
      emit: (List[PathStep], String) => Unit
  ): Unit =
    json.fold(
      jsonNull = emit(steps, scalarText(json)),
      jsonBoolean = _ => emit(steps, scalarText(json)),
      jsonNumber = _ => emit(steps, scalarText(json)),
      jsonString = _ => emit(steps, scalarText(json)),
      jsonArray = elements =>
        if depth >= limits.maxDepth then emit(steps, json.noSpaces)
        else if elements.isEmpty then emit(steps, "[]")
        else {
          val kept = limits.maxArrayElements.max(0)
          elements.take(kept).zipWithIndex.foreach { (element, index) =>
            walk(element, steps :+ PathStep.Index(index), depth + 1, limits, emit)
          }
          val dropped = elements.size - kept
          if dropped > 0 then emit(steps :+ PathStep.Overflow, s"+$dropped more")
        },
      jsonObject = fields =>
        if depth >= limits.maxDepth then emit(steps, json.noSpaces)
        else if fields.isEmpty then emit(steps, "{}")
        else
          fields.toIterable.foreach { (name, field) =>
            walk(field, steps :+ PathStep.Field(name), depth + 1, limits, emit)
          }
    )
}
