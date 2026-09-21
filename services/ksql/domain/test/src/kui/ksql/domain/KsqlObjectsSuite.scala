package kui.ksql.domain

import munit.FunSuite

/** What one ksqlDB cluster named, and the two rules the answer is built with.
  *
  * Both rules are invisible in an answer of four objects on a laptop and both bite on a shared cluster: the
  * ordering is what stops §3.16's pane reordering itself between polls, and the bound is what stops a
  * `SHOW TOPICS` over two thousand topics becoming a document the browser cannot draw.
  */
final class KsqlObjectsSuite extends FunSuite {

  private def stream(name: String): KsqlObject = KsqlObject.Stream(name, name.toLowerCase, Some("JSON"))
  private def table(name: String): KsqlObject = KsqlObject.Table(name, name.toLowerCase, None, false)
  private def query(id: String): KsqlObject = KsqlObject.Query(id, Nil, "SELECT 1;")
  private def topic(name: String): KsqlObject = KsqlObject.Topic(name, 3, 3)

  test("every kind carries its own wire word, and the four are distinct") {
    assertEquals(KsqlObjectKind.values.map(_.wire).toList, List("stream", "table", "query", "topic"))
    KsqlObjectKind.values.foreach(kind => assertEquals(KsqlObjectKind.fromWire(kind.wire), Some(kind)))
    assertEquals(KsqlObjectKind.fromWire("STREAM"), None)
  }

  test("an object's kind and name come off the case rather than being passed in beside it") {
    assertEquals(stream("ORDERS").kind, KsqlObjectKind.Stream)
    assertEquals(table("USERS").kind, KsqlObjectKind.Table)
    assertEquals(query("CSAS_0").kind, KsqlObjectKind.Query)
    assertEquals(topic("orders").kind, KsqlObjectKind.Topic)

    // A query has an id rather than a name, and the id is the string ksqlDB accepts in `TERMINATE`, so it
    // is the one that identifies it.
    assertEquals(query("CSAS_0").name, "CSAS_0")
  }

  test("the answer is ordered by kind and then by name, whatever order the server iterated in") {
    // ksqlDB answers `SHOW STREAMS` in its metastore's hash order, which changes when an object is created
    // or dropped. A pane whose rows move between polls is one an operator cannot click accurately.
    val shuffled = List(topic("zeta"), query("CSAS_9"), table("users"), stream("PAYMENTS"), stream("Orders"))

    assertEquals(
      KsqlObjects.of(shuffled, Nil).items.map(item => s"${item.kind.wire}:${item.name}"),
      List("stream:Orders", "stream:PAYMENTS", "table:users", "query:CSAS_9", "topic:zeta")
    )
  }

  test("the name ordering is case-insensitive, so ORDERS and orders do not end up apart") {
    val mixed = List(stream("beta"), stream("Alpha"), stream("ALPHA2"))

    assertEquals(KsqlObjects.of(mixed, Nil).items.map(_.name), List("Alpha", "ALPHA2", "beta"))
  }

  test("an answer past the bound is cut and says how much it cut") {
    // The bound has a stated cost and the cost is on the wire. A list that quietly stopped is how an
    // operator concludes a stream does not exist; `truncated` is what lets a screen say "showing 500 of
    // 2,314" instead.
    val many = (1 to KsqlObjects.MaxObjects + 14).toList.map(index => stream(f"S$index%05d"))
    val bounded = KsqlObjects.of(many, Nil)

    assertEquals(bounded.items.size, KsqlObjects.MaxObjects)
    assertEquals(bounded.truncated, 14)
    assertEquals(bounded.items.size + bounded.truncated, many.size)
    assert(bounded.partial)
  }

  test("the bound is five hundred, and the literal is what says so") {
    // Measured, not assumed: `500 -> 2` left the case above **green**, because `(1 to MaxObjects + 14)` is
    // written relative to the constant and therefore compares the value with itself. That is
    // `ConnectWiringSuite`'s lesson one service over — "asserting `config.maxConcurrent ==
    // ConnectWiring.MaxConcurrentPerWorker` compares the value with itself" — and this is the case that
    // makes a change to the bound a red rather than three confusing ordering failures.
    assertEquals(KsqlObjects.MaxObjects, 500)
    assertEquals(KsqlObjects.of((1 to 501).toList.map(index => stream(f"S$index%05d")), Nil).items.size, 500)
    assertEquals(KsqlObjects.of((1 to 501).toList.map(index => stream(f"S$index%05d")), Nil).truncated, 1)
  }

  test("an answer inside the bound reports a truncation of zero, which is a measured zero") {
    val few = List(stream("ORDERS"), table("USERS"))
    val whole = KsqlObjects.of(few, Nil)

    assertEquals(whole.truncated, 0)
    assertEquals(whole.items.size, 2)
    assert(!whole.partial)
  }

  test("the bound cuts the *ordered* answer, so what survives is the front of the list a screen draws") {
    // Cutting before sorting would drop whichever objects the server happened to iterate last, which is a
    // different set on every poll — and a pane that showed a different 500 each time is worse than one
    // that showed 500 and said so.
    val many = (1 to KsqlObjects.MaxObjects + 1).toList.reverse.map(index => stream(f"S$index%05d"))
    val bounded = KsqlObjects.of(many, Nil)

    assertEquals(bounded.items.head.name, "S00001")
    assertEquals(bounded.items.last.name, f"S${KsqlObjects.MaxObjects}%05d")
  }

  test("a row the server would not describe makes the answer partial without emptying it") {
    val answer = KsqlObjects.of(List(stream("ORDERS")), List("(a table the ksqlDB cluster did not name)"))

    assertEquals(answer.items.map(_.name), List("ORDERS"))
    assertEquals(answer.unreadable.size, 1)
    assert(answer.partial)
  }

  test("objects can be filtered by kind, which is what a pane grouped by kind needs") {
    val answer = KsqlObjects.of(List(stream("A"), stream("B"), table("C"), topic("d")), Nil)

    assertEquals(answer.of(KsqlObjectKind.Stream).map(_.name), List("A", "B"))
    assertEquals(answer.of(KsqlObjectKind.Table).map(_.name), List("C"))
    assertEquals(answer.of(KsqlObjectKind.Query), Nil)
  }

  test("the empty answer is empty in all three fields") {
    assertEquals(KsqlObjects.empty, KsqlObjects(Nil, Nil, 0))
    assert(!KsqlObjects.empty.partial)
  }
}
