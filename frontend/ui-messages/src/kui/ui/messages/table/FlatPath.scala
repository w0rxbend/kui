package kui.ui.messages.table

import scala.annotation.tailrec

/** One step of the route from a record's root to a cell.
  *
  * A path is kept as a list of these rather than as a string because the strings a producer puts in its JSON
  * keys are not under KUI's control: `{"a.b": 1}` and `{"a": {"b": 1}}` are different documents that a naive
  * dotted path renders identically, and a column header that means two things is worse than no column at all.
  * Rendering happens once, in [[FlatPath.render]], and it escapes.
  */
enum PathStep {

  /** An object key, exactly as the producer wrote it — unescaped, un-truncated. */
  case Field(name: String)

  /** An array index. */
  case Index(at: Int)

  /** The stand-in for the elements of an array past `maxArrayElements`. It carries no data; the cell it names
    * holds the count.
    */
  case Overflow
}

object PathStep {
  given CanEqual[PathStep, PathStep] = CanEqual.derived
}

/** The rendering and parsing of a column path, and the only place that knows the syntax.
  *
  * Syntax: the first step is always the root field (`H`, `K` or `V`); a later `Field` is `.name`, an `Index`
  * is `[3]`, and the array overflow marker is `[+]`. Inside a field name, `\`, `.`, `[` and `]` are escaped
  * with a backslash, which is what makes [[parse]] the exact inverse of [[render]] — the property the suite
  * checks over keys containing dots, brackets, quotes and unicode.
  */
object FlatPath {

  /** The three roots. They are ordinary field names, so a header actually called `V` is still distinguishable
    * from the value root: it renders as `H.V`.
    */
  val Headers: String = "H"
  val Key: String = "K"
  val Value: String = "V"

  private val Escaped: Set[Char] = Set('\\', '.', '[', ']')

  def render(steps: List[PathStep]): String = {
    val out = new StringBuilder
    steps.zipWithIndex.foreach {
      case (PathStep.Field(name), 0) => out.append(escape(name))
      case (PathStep.Field(name), _) => out.append('.').append(escape(name))
      case (PathStep.Index(at), _) => out.append('[').append(at).append(']')
      case (PathStep.Overflow, _) => out.append("[+]")
    }
    out.toString
  }

  /** The inverse of [[render]]. `None` when the string is not a path this module produced — which, since
    * column paths only ever come from [[render]], means a bug rather than user input.
    */
  def parse(path: String): Option[List[PathStep]] = {
    val steps = List.newBuilder[PathStep]
    val field = new StringBuilder
    var started = false

    // `field` accumulates the characters of the current `Field`; `flushField` turns it into a step. A field
    // may legitimately be empty (`{"": 1}` is valid JSON), so "have we started one" is tracked separately
    // from "is it non-empty".
    def flushField(): Unit =
      if started then {
        steps += PathStep.Field(field.toString)
        field.setLength(0)
        started = false
      }

    @tailrec
    def loop(index: Int): Option[List[PathStep]] =
      if index >= path.length then {
        flushField()
        Some(steps.result())
      } else
        path.charAt(index) match {
          case '\\' if index + 1 < path.length =>
            started = true
            field.append(path.charAt(index + 1))
            loop(index + 2)
          case '\\' => None // A trailing backslash escapes nothing; the string is not a rendered path.
          case '.' =>
            flushField()
            started = true
            loop(index + 1)
          case '[' =>
            flushField()
            val close = path.indexOf(']', index)
            if close < 0 then None
            else {
              val inside = path.substring(index + 1, close)
              val step = if inside == "+" then Some(PathStep.Overflow)
              else inside.toIntOption.map(PathStep.Index.apply)
              step match {
                case Some(value) =>
                  steps += value
                  loop(close + 1)
                case None => None
              }
            }
          case ']' => None
          case character =>
            started = true
            field.append(character)
            loop(index + 1)
        }

    loop(0)
  }

  private def escape(name: String): String = {
    val out = new StringBuilder(name.length)
    name.foreach { character =>
      if Escaped.contains(character) then out.append('\\')
      out.append(character)
    }
    out.toString
  }
}
