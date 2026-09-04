package kui.kernel.search

/** How a `q` parameter is matched.
  *
  * Two values rather than the reference product's boolean `fts`. That boolean's meaning depended on two
  * server-side settings a client could not see, so the same request produced a substring match on one
  * deployment and an n-gram match on another, and neither the caller nor the person reading the screen could
  * tell which they had got (ADR-038; DEVPLAN §10 D4).
  */
enum SearchMode {
  case Plain
  case Fts

  /** The lowercase name the query string uses.
    *
    * Spelled out rather than derived from the case name, so that renaming a case is a local edit rather than
    * a silent change to a URL contract that browsers have bookmarked.
    */
  def wire: String = this match {
    case Plain => "plain"
    case Fts => "fts"
  }
}

object SearchMode {

  /** What a request that names no mode means. Substring matching: it is the one a user can predict. */
  val Default: SearchMode = Plain

  /** Reads a mode back from a query string.
    *
    * `None` for anything else, never a silent fall back to [[Default]]: an unrecognised `mode` is a typo or a
    * client from a future version, and both are better answered with a 400 naming the parameter than with a
    * page of results produced by a different rule from the one that was asked for.
    */
  def fromWire(raw: String): Option[SearchMode] = raw match {
    case "plain" => Some(Plain)
    case "fts" => Some(Fts)
    case _ => None
  }

  given CanEqual[SearchMode, SearchMode] = CanEqual.derived
}
