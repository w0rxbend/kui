package kui.kafka.admin

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.testkit.KuiSuite

/** The forms a broker actually reports, and the ordering the version gate depends on. */
final class KafkaVersionSuite extends KuiSuite {

  test("parsesTheDocumentedForms") {
    val table = List(
      "3.9" -> KafkaVersion(3, 9, 0),
      "3.9.1" -> KafkaVersion(3, 9, 1),
      "2.8" -> KafkaVersion(2, 8, 0),
      // The `-IVn` suffix is an inter-broker-protocol level, not a patch: 2.8-IV1 is Kafka 2.8.
      "2.8-IV1" -> KafkaVersion(2, 8, 0),
      "3.9-IV0" -> KafkaVersion(3, 9, 0),
      "4.0.0" -> KafkaVersion(4, 0, 0),
      "3.9.1-SNAPSHOT" -> KafkaVersion(3, 9, 1),
      " 3.7.0 " -> KafkaVersion(3, 7, 0)
    )

    table.foreach((raw, expected) => assertEquals(KafkaVersion.parse(raw), Some(expected), raw))
  }

  test("rejectsGarbageWithoutThrowing") {
    List("", "x", "3", "three.nine", "-1.2", "3.", "..", "IV1").foreach(raw =>
      assertEquals(KafkaVersion.parse(raw), None, raw)
    )
  }

  test("minimumSupportedIsTwoEight") {
    assertEquals(KafkaVersion.minimumSupported, KafkaVersion(2, 8, 0))
  }

  test("rendersTheWayKafkaNamesARelease") {
    assertEquals(KafkaVersion(3, 9, 0).render, "3.9")
    assertEquals(KafkaVersion(3, 9, 1).render, "3.9.1")
  }

  private val genVersion: Gen[KafkaVersion] = for {
    major <- Gen.chooseNum(0, 9)
    minor <- Gen.chooseNum(0, 20)
    patch <- Gen.chooseNum(0, 9)
  } yield KafkaVersion(major, minor, patch)

  property("orderingIsBySemanticFields") {
    forAll(genVersion, genVersion) { (left, right) =>
      val expected =
        if left.major != right.major then left.major < right.major
        else if left.minor != right.minor then left.minor < right.minor
        else left.patch < right.patch

      assertEquals(left < right, expected, s"$left vs $right")
    }
  }

  property("aParsedVersionRoundTripsThroughRender") {
    forAll(genVersion)(version => assertEquals(KafkaVersion.parse(version.render), Some(version)))
  }
}
