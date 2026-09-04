package kui.topic.domain

/** The fields a topic list may be sorted by.
  *
  * An enum and not a string, so that an unknown `sort` parameter becomes a decode failure at the edge — a 400
  * naming the parameter — instead of a silently ignored instruction that leaves the user looking at a list
  * they did not ask for and cannot tell is wrong.
  */
enum TopicSortField {
  case Name, Partitions, ReplicationFactor, OutOfSyncReplicas, Size, MessageCount

  /** The token the query string uses (`sort=messageCount:desc`).
    *
    * Spelled out rather than derived from the case name, so that renaming a case is a local edit rather than
    * a silent change to a URL contract that browsers have bookmarked.
    */
  def wire: String = this match {
    case Name => "name"
    case Partitions => "partitions"
    case ReplicationFactor => "replicationFactor"
    case OutOfSyncReplicas => "outOfSyncReplicas"
    case Size => "size"
    case MessageCount => "messageCount"
  }
}

object TopicSortField {

  /** What a request that names no sort field means. */
  val Default: TopicSortField = Name

  /** Reads a field back from a query string. `None` for anything else, never a silent fall back to
    * [[Default]]: see `SearchMode.fromWire`, which refuses for the same reason.
    */
  def fromWire(raw: String): Option[TopicSortField] = values.find(_.wire == raw)

  given CanEqual[TopicSortField, TopicSortField] = CanEqual.derived
}
