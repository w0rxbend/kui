package kui.connect.contract

import scala.io.Source
import scala.util.Using

import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

import kui.connect.contract.dto.*
import kui.contracts.Section
import kui.contracts.capability.ReasonCode

/** That each response document is exactly the file committed beside it, and that the file decodes back into
  * the value it was rendered from.
  *
  * ==Why the round trip is over the encoder's own output==
  *
  * House rule 12, and it is wave 5's most expensive lesson written as a test. `producers.data.topics` and
  * `producers.data.entries` were two hand-written shapes on two sides of one wire, each with a suite that
  * asserted its own literal, both green, and the card drew "the metrics source named no producers" over a
  * source that named five. A decode assertion against a hand-written literal proves that the decoder matches
  * the literal. A decode assertion against `value.asJson` proves that the decoder matches the **encoder**,
  * which is the only thing anybody wanted to know.
  *
  * The golden files are the third leg: they are what a reviewer reads and what a browser fixture is cut from,
  * so a field rename is a diff in a file rather than an expectation quietly edited in passing. A file that is
  * missing **fails loudly** rather than being skipped, which is the difference between a gate and a habit.
  */
final class ConnectResponsesSuite extends FunSuite {

  import ConnectDocuments.*

  private def golden(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/golden/$name")).getOrElse {
        fail(s"golden/$name is missing from the test resources")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)
      .stripLineEnd

  private def assertGolden(name: String, encoded: io.circe.Json): Unit =
    assertNoDiff(
      encoded.spaces2,
      parse(golden(name)).fold(failure => fail(s"$name is not JSON: ${failure.message}"), _.spaces2)
    )

  all.foreach { (name, document) =>
    test(s"$name is exactly what the encoder rendered") {
      assertGolden(name, document)
    }
  }

  test("a listing is its golden document and decodes back into the value it came from") {
    assertGolden("connectors-response.json", listing.asJson)
    assertEquals(listing.asJson.as[ConnectorListResponse], Right(listing))
  }

  test("every task's own state reaches the wire, and a two-task connector says 1 of 2 are running") {
    // The required case, at the wire's own boundary: a worker that names connectors and their task states
    // reaches the browser with each task's state, rather than with a connector state and a count.
    val workers = listing.connectors.toOption.map(_.workers).getOrElse(Nil)
    val payments = workers.head.connectors.toOption.map(_.items).getOrElse(Nil)

    assertEquals(payments.map(_.name), List("archive-sink", "elastic-sink", "orders-source"))
    assertEquals(
      payments.flatMap(_.tasks.map(task => s"${task.id}:${task.state}")),
      List("0:PAUSED", "0:RUNNING", "1:FAILED", "0:RUNNING", "1:RUNNING", "2:RUNNING")
    )
    assertEquals(
      payments.map(connector => (connector.runningTasks, connector.taskCount)),
      List((0, 1), (1, 2), (3, 3))
    )
  }

  test("a failed task carries the worker's own trace, and the reason is its first line") {
    val failed = elastic.tasks.find(_.state == "FAILED").getOrElse(fail("the fixture has no failed task"))

    assertEquals(failed.trace, Some(elasticTrace))
    assertEquals(failed.reason, Some(elasticTrace.linesIterator.next()))
    // Never a word KUI chose: the sentence on the card is a slice of the worker's own text.
    assert(clue(elasticTrace).startsWith(failed.reason.getOrElse("")))
  }

  test("a rebalancing worker is a section of its own, and it is not an outage") {
    assertEquals(rebalancingSection.status, "unavailable")
    rebalancingSection match {
      case Section.Unavailable(reason, message, _) =>
        assertEquals(reason, ReasonCode.Starting)
        assertNotEquals(reason, ReasonCode.UpstreamUnavailable)
        assert(clue(message).contains("rebalancing"))
      case other => fail(s"expected an unavailable section, got $other")
    }
  }

  test("a deployment with no Kafka Connect is not_configured, and carries no data at all") {
    assertGolden("connectors-not-configured.json", notConfigured.asJson)
    assertEquals(notConfigured.connectors.status, "not_configured")
    assertEquals(notConfigured.connectors.toOption, None)
    assertEquals(notConfigured.asJson.as[ConnectorListResponse], Right(notConfigured))
  }

  test("a connector the worker would not describe is named rather than dropped") {
    assertEquals(partial.asJson.as[ConnectorListResponse], Right(partial))

    val worker = partial.connectors.toOption.map(_.workers).getOrElse(Nil).head

    assertEquals(worker.connectors.toOption.map(_.unreadable), Some(List("elastic-sink")))
    assertEquals(worker.connectors.toOption.map(_.items.map(_.name)), Some(List("orders-source")))
  }

  test("an accepted operation carries no connector state") {
    // The absence is the contract: Connect answers 202 with an empty body, so a state here could only be
    // the state *before* the call wearing the result's clothes.
    assertEquals(restarted.asJson.as[ConnectorOperationDto], Right(restarted))
    assertEquals(
      restarted.asJson.asObject.map(_.keys.toList.sorted),
      Some(List("acceptedAt", "connect", "connector", "operation"))
    )
  }

  test("an absent field decodes to the same value an explicit null does") {
    // A document written by an older build that omitted `workerId`, `reason`, `trace` or `unreadable` must
    // land on the "nothing here" rendering rather than on a decode failure.
    val sparse = parse("""{"id":4,"state":"UNASSIGNED"}""")

    assertEquals(
      sparse.flatMap(_.as[ConnectorTaskDto]),
      Right(ConnectorTaskDto(4, "UNASSIGNED", None, None, None))
    )
    assertEquals(
      parse("""{"items":[]}""").flatMap(_.as[ConnectorsDto]),
      Right(ConnectorsDto(Nil, Nil))
    )
  }

  test("a status the browser does not know is a decode failure and not a silent ok") {
    assert(parse("""{"connectors":{"status":"maybe"}}""").flatMap(_.as[ConnectorListResponse]).isLeft)
  }
}
