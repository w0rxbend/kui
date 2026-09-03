package kui.cluster.domain

import org.scalacheck.Prop.forAll

import kui.testkit.KuiSuite

/** Parsing what a broker calls itself.
  *
  * The parser is total by design: it is fed a string a managed service invented, and a version it cannot read
  * must become "unknown" rather than an exception on a background refresh fiber.
  */
final class KafkaVersionSuite extends KuiSuite {

  private def parse(raw: String) = KafkaVersion.parse(raw, VersionSource.MetadataVersion)

  private def parsed(raw: String): KafkaVersion =
    parse(raw).fold(error => fail(s"'$raw' should parse: ${error.message}"), identity)

  test("parsesAMetadataVersionWithAnIvSuffix") {
    assertEquals((parsed("3.9-IV0").major, parsed("3.9-IV0").minor), (3, 9))
    assertEquals(parsed("3.9-IV0").raw, "3.9-IV0", "the broker's own spelling is kept verbatim")
  }

  test("parsesATwoComponentVersion") {
    assertEquals((parsed("4.0").major, parsed("4.0").minor), (4, 0))
  }

  test("parsesAThreeComponentVersion") {
    assertEquals((parsed("3.9.1").major, parsed("3.9.1").minor), (3, 9))
  }

  test("parsesAnOlderThreeComponentVersion") {
    assertEquals((parsed("2.8.2").major, parsed("2.8.2").minor), (2, 8))
  }

  test("rejectsGarbage") {
    List("unknown", "", "IV0", "x.y").foreach { raw =>
      assert(parse(raw).isLeft, s"'$raw' must not parse")
    }
  }

  test("meetsMinimumBoundary") {
    assert(!parsed("2.7").meetsMinimum, "2.7 is below the supported minimum")
    assert(parsed("2.8").meetsMinimum, "2.8 is the supported minimum")
  }

  test("orderingIsMajorThenMinor") {
    val sorted = List(parsed("3.9"), parsed("2.8"), parsed("4.0"), parsed("3.10")).sorted

    assertEquals(sorted.map(_.short), List("2.8", "3.9", "3.10", "4.0"))
  }

  test("sourceIsPreserved") {
    val fallback = KafkaVersion.parse("3.4", VersionSource.InterBrokerProtocol)

    assertEquals(fallback.map(_.source), Right(VersionSource.InterBrokerProtocol))
  }

  property("parseIsTotalOverArbitraryStrings") {
    forAll { (raw: String) =>
      parse(raw) match {
        case Right(version) => assertEquals(version.raw, raw)
        case Left(_) => ()
      }
    }
  }
}
