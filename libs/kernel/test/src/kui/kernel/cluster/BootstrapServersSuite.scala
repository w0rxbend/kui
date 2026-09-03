package kui.kernel.cluster

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.testkit.{ClusterGenerators, KuiSuite}

/** What `BootstrapServers` accepts, what it refuses, and which field it blames.
  *
  * The `fieldName` assertions are not decoration. CFGOP-001 accumulates every configuration failure
  * into one startup message keyed by field, so an error that blames the wrong field sends an
  * operator to the wrong line of their YAML.
  */
final class BootstrapServersSuite extends KuiSuite {

  /** The result reduced to two comparable strings: the joined value on the right, the blamed field
    * on the left. Comparing that keeps a failure message readable and keeps the opaque type out of
    * the assertion.
    */
  private def parsed(raw: String): Either[String, String] =
    BootstrapServers.from(raw).left.map(_.fieldName).map(_.value)

  private def hosts(raw: String): Either[String, List[String]] =
    BootstrapServers.from(raw).left.map(_.fieldName).map(_.hosts)

  test("acceptsAHostPortList") {
    assertEquals(parsed("a:9092,b:9093"), Right("a:9092,b:9093"))
  }

  test("trimsWhitespaceAroundEntries") {
    assertEquals(hosts("a:9092, b:9092 ,\tc:9092"), Right(List("a:9092", "b:9092", "c:9092")))
  }

  test("preservesOrder") {
    assertEquals(hosts("z:9092,a:9092"), Right(List("z:9092", "a:9092")))
  }

  test("rejectsEmpty") {
    assertEquals(parsed(""), Left("bootstrapServers"))
    assertEquals(parsed("   "), Left("bootstrapServers"))
  }

  test("rejectsEntryWithoutAPort") {
    assertEquals(parsed("a"), Left("bootstrapServers"))
    assertEquals(parsed("a:9092,b"), Left("bootstrapServers"))
    assertEquals(parsed("a:"), Left("bootstrapServers"))
  }

  test("rejectsPortOutOfRange") {
    assertEquals(parsed("a:0"), Left("bootstrapServers"))
    assertEquals(parsed("a:65536"), Left("bootstrapServers"))
    assertEquals(parsed("a:notanumber"), Left("bootstrapServers"))
  }

  test("rejectsDuplicates") {
    assertEquals(parsed("a:9092,a:9092"), Left("bootstrapServers"))
  }

  test("acceptsABracketedIpv6Literal") {
    assertEquals(hosts("[::1]:9092"), Right(List("[::1]:9092")))
  }

  property("valueRoundTripsThroughFromForEveryGeneratedList") {
    forAll(ClusterGenerators.genBootstrapServers) { servers =>
      assertEquals(parsed(servers.value), Right(servers.value))
    }
  }

  property("everyRejectionBlamesTheBootstrapServersField") {
    val bad: Gen[String] = Gen.oneOf("", "  ", "a", "a:", "a:0", "a:70000", "a:9092,a:9092", ":9092")

    forAll(bad)(raw => assertEquals(parsed(raw), Left("bootstrapServers")))
  }
}
