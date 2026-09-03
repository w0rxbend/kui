package kui.config.store

import org.scalacheck.{Gen, Prop}

import kui.testkit.KuiSuite

/** That a store key is exactly `<section>/<id>`, that it survives a round trip through text, and that
  * everything else is refused by name.
  *
  * The key is the Kafka record key, so it is the thing compaction keys on: two writers that render the same
  * logical key differently would produce two records that never compact into one.
  */
final class StoreKeySuite extends KuiSuite {

  private val sections: Gen[StoreSection] =
    Gen.oneOf(
      Gen.oneOf(StoreSection.Cluster, StoreSection.Settings, StoreSection.Rbac, StoreSection.Masking, StoreSection.File),
      Gen.identifier.map(raw => StoreSection.Other(raw.toLowerCase))
    )

  private val ids: Gen[String] = {
    val edge = Gen.oneOf(Gen.alphaLowerChar, Gen.numChar)
    val middle = Gen.oneOf(Gen.alphaLowerChar, Gen.numChar, Gen.oneOf('-', '_', '.'))
    for {
      first <- edge
      inner <- Gen.listOfN(6, middle)
      last <- edge
    } yield (first :: inner ::: List(last)).mkString
  }

  property("renderParseRoundTrips") {
    Prop.forAll(sections, ids) { (section, id) =>
      val key = StoreKey(section, id)
      StoreKey.parse(key.render) == Right(key)
    }
  }

  test("rejectsWrongSegmentCount") {
    List("cluster", "cluster/a/b", "", "/x").foreach { raw =>
      StoreKey.parse(raw) match {
        case Left(StoreError.InvalidKey(reported, _)) => assertEquals(reported, raw)
        case other => fail(s"expected an InvalidKey naming '$raw', got $other")
      }
    }
  }

  test("rejectsIdsThatAreNotSlugs") {
    val bad = List("cluster/Prod", "cluster/prod eu", "cluster/-prod", "cluster/" + "a" * 200)
    bad.foreach { raw =>
      assert(StoreKey.parse(raw).isLeft, s"expected '$raw' to be rejected")
    }
  }

  test("unknownSectionParsesToOther") {
    // Forward compatibility, not laxity: a newer KUI writing a section this one does not model must not
    // stop this one's replay. See the comment on `StoreSection`.
    assertEquals(StoreKey.parse("connect/foo"), Right(StoreKey(StoreSection.Other("connect"), "foo")))
  }

  test("theWellKnownKeysRenderAsDocumented") {
    assertEquals(StoreKey.SettingsGlobal.render, "settings/global")
    assertEquals(StoreKey.RbacRoles.render, "rbac/roles")
    assertEquals(StoreKey.cluster("prod-eu").map(_.render), Right("cluster/prod-eu"))
  }
}
