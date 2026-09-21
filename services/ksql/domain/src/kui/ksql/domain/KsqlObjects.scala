package kui.ksql.domain

/** What kind of thing ksqlDB named.
  *
  * A closed enum where `ConnectorState` one service over is an open string, and the asymmetry is deliberate:
  * a connector's *state* is a word the worker may extend at any release, while these four are the object
  * kinds ksqlDB's own `SHOW` statements enumerate. A fifth is a ksqlDB release with a new `SHOW`, which is a
  * decision rather than a surprise, and until then an unrecognised row is [[KsqlObjects.unreadable]] rather
  * than a fifth kind invented here.
  *
  * `SCREENS-V4.md` §3.16 is why the kind travels at all: the left pane draws four monospace names and *"the
  * glyph is the only thing that says which"*. A browser that had to infer stream-versus-table from the shape
  * of a row would infer it differently from this service on the first row that is neither.
  *
  * @param wire
  *   the lower-case discriminator on the wire. Written out rather than derived from the case name, because it
  *   is a string the browser switches on and a derived name is a rename away from being a different string.
  */
enum KsqlObjectKind(val wire: String) {
  case Stream extends KsqlObjectKind("stream")
  case Table extends KsqlObjectKind("table")
  case Query extends KsqlObjectKind("query")
  case Topic extends KsqlObjectKind("topic")
}

object KsqlObjectKind {

  def fromWire(raw: String): Option[KsqlObjectKind] = values.find(_.wire == raw)

  given CanEqual[KsqlObjectKind, KsqlObjectKind] = CanEqual.derived
}

/** One thing ksqlDB named, with the facts that kind of thing has and no others.
  *
  * Four cases rather than one record with eight optional fields, because the optionality would be a lie in
  * both directions: a table always has a topic behind it and a query never does, so a `topic: Option[String]`
  * on one type would make "a table with no topic" representable and "a query with a topic" equally so. The
  * *wire* flattens them — `KsqlMapping` does that, and the DTO's scaladoc says which fields belong to which
  * kind — because a browser drawing one list wants one row shape.
  */
enum KsqlObject {

  /** A stream: an unbounded, append-only sequence over a Kafka topic.
    *
    * @param format
    *   the value format ksqlDB reported — `JSON`, `AVRO`, `PROTOBUF`, `KAFKA`. `None` when the server did not
    *   say, which older ksqlDB releases and some `SHOW STREAMS` responses genuinely do not; a defaulted
    *   `JSON` would tell an operator their Avro stream was JSON.
    */
  case Stream(override val name: String, topic: String, format: Option[String])

  /** A table: the latest value per key, over a Kafka topic.
    *
    * @param windowed
    *   whether the table's key is windowed. It is a fact about how the table may be queried — a pull query
    *   against a windowed table must name a window bound — so it travels rather than being re-derived from
    *   the statement that created it.
    */
  case Table(override val name: String, topic: String, format: Option[String], windowed: Boolean)

  /** A persistent or transient query the server is running.
    *
    * @param sinks
    *   the streams and tables this query writes into, as the server named them. A query with no sink is a
    *   transient one — someone's `SELECT` still open — and the empty list says exactly that.
    * @param statement
    *   the query's own SQL, verbatim. It is what §3.16's right-hand pane can show for a selected query, and
    *   it is a slice of what the server said rather than a sentence KUI composed.
    */
  case Query(id: String, sinks: List[String], statement: String)

  /** A Kafka topic ksqlDB can see.
    *
    * Here because `SHOW TOPICS` is one of the four listings and because a stream's `topic` is otherwise a
    * name with nothing behind it. The partition and replication counts are the server's own; KUI reads no
    * broker in this service.
    */
  case Topic(override val name: String, partitions: Int, replication: Int)

  def kind: KsqlObjectKind = this match {
    case Stream(_, _, _) => KsqlObjectKind.Stream
    case Table(_, _, _, _) => KsqlObjectKind.Table
    case Query(_, _, _) => KsqlObjectKind.Query
    case Topic(_, _, _) => KsqlObjectKind.Topic
  }

  /** The name a row is drawn under and sorted by. A query has an id rather than a name, and the id is what
    * the server accepts in `TERMINATE`, so it is the one string that identifies it.
    */
  def name: String = this match {
    case Stream(name, _, _) => name
    case Table(name, _, _, _) => name
    case Query(id, _, _) => id
    case Topic(name, _, _) => name
  }
}

object KsqlObject {
  given CanEqual[KsqlObject, KsqlObject] = CanEqual.derived
}

/** Everything one ksqlDB cluster named, in one answer.
  *
  * ==One read, four listings==
  *
  * §3.16 draws one pane over streams and tables, and §4.15's voice line counts `4 objects` across the lot. So
  * this is one value produced by one call, for `ConnectEndpoints`' reason: three requests for one screen is
  * how three panels come to disagree about how many objects there are.
  *
  * @param items
  *   every object, in [[KsqlObjects.ordering]]'s order. The order is a rule rather than an accident — see
  *   [[KsqlObjects.of]].
  * @param unreadable
  *   the rows the server returned and KUI could not describe, spelled the way the server spelled them, in the
  *   order it returned them. They are reported rather than dropped for `ConnectorFacts.unreadable`'s reason:
  *   a row missing from a list is indistinguishable from a row that is not there, and "ksqlDB named something
  *   KUI cannot describe" is a sentence a screen can show and an operator can act on.
  * @param truncated
  *   how many objects were dropped to keep the answer inside [[KsqlObjects.MaxObjects]]. Zero is the ordinary
  *   case. It is a count rather than a flag because a screen that says *"showing 500 of 2,314"* is honest and
  *   one that says *"there are more"* is not a figure anybody can act on.
  */
final case class KsqlObjects(items: List[KsqlObject], unreadable: List[String], truncated: Int) {

  def partial: Boolean = unreadable.nonEmpty || truncated > 0

  def of(kind: KsqlObjectKind): List[KsqlObject] = items.filter(_.kind == kind)
}

object KsqlObjects {

  /** The most objects one answer carries.
    *
    * ksqlDB's `SHOW TOPICS` on a shared cluster routinely names thousands, and a document with all of them
    * costs the browser more than the pane can draw — §3.16's left pane is a list a person scrolls, not a
    * search index. Five hundred is past the point where the list stops being readable and well inside what
    * one JSON document should carry.
    *
    * It is a **bound with a stated cost**: what is dropped is reported in [[KsqlObjects.truncated]] rather
    * than silently absent, because a list that quietly stops is how an operator concludes a stream does not
    * exist. `KsqlObjectsSuite`'s *"an answer past the bound is cut and says how much it cut"* is what holds
    * both halves.
    */
  val MaxObjects: Int = 500

  /** Kind first, then name, case-insensitively.
    *
    * ksqlDB answers `SHOW STREAMS` in whatever order its metastore iterates, which is a hash order that
    * changes when an object is created or dropped. A pane whose rows move between polls is one an operator
    * cannot click accurately, and it is the same defect `ConnectHttp`'s three ordering rules exist for one
    * service over. Kind first because the pane is grouped by kind; name second because that is the only
    * stable tie-break the server gives us.
    */
  val ordering: Ordering[KsqlObject] =
    Ordering.by(item => (item.kind.ordinal, item.name.toLowerCase, item.name))

  /** The answer, ordered and bounded. The only constructor callers use, so that neither rule can be skipped
    * by a caller that assembles the case class itself.
    */
  def of(items: List[KsqlObject], unreadable: List[String]): KsqlObjects = {
    val sorted = items.sorted(using ordering)

    KsqlObjects(
      items = sorted.take(MaxObjects),
      unreadable = unreadable,
      truncated = math.max(0, sorted.size - MaxObjects)
    )
  }

  val empty: KsqlObjects = KsqlObjects(Nil, Nil, 0)

  given CanEqual[KsqlObjects, KsqlObjects] = CanEqual.derived
}
