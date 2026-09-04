package kui.schema.domain

import munit.FunSuite

import kui.kernel.{PageRequest, PageSize, PositiveInt, SortOrder, Subject}

/** The paging arithmetic and the version selector, which are the two places in this service where a wrong
  * answer reaches a screen without anything failing.
  */
final class SubjectCatalogSuite extends FunSuite {

  private def subjects(names: String*): List[Subject] = names.toList.map(Subject.unsafe)

  private def request(page: Int, size: Int): PageRequest =
    PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size))

  test("the total is counted after the search, not before it") {
    val all = subjects("orders-key", "orders-value", "payments-value", "shipments-value")
    val page = SubjectCatalog.page(all, SubjectQuery(Some("orders"), SortOrder.Asc, request(1, 25)))

    assertEquals(page.items, subjects("orders-key", "orders-value"))
    // Counting before filtering is the reference product's defect: it reports four, the screen offers a
    // second page, and the second page is empty.
    assertEquals(page.totalItems, Some(2L))
  }

  test("the search is case-insensitive and is a substring, not a prefix") {
    val all = subjects("prod.orders-value", "ORDERS-key")
    val page = SubjectCatalog.page(all, SubjectQuery(Some("Orders"), SortOrder.Asc, request(1, 25)))

    assertEquals(page.items.map(_.value).toSet, Set("prod.orders-value", "ORDERS-key"))
  }

  test("a blank search is no search at all") {
    val all = subjects("a", "b")
    val page = SubjectCatalog.page(all, SubjectQuery(Some("   "), SortOrder.Asc, request(1, 25)))

    assertEquals(page.items, all)
  }

  test("descending order reverses the names and keeps the count") {
    val page = SubjectCatalog.page(subjects("a", "b", "c"), SubjectQuery(None, SortOrder.Desc, request(1, 2)))

    assertEquals(page.items, subjects("c", "b"))
    assertEquals(page.totalItems, Some(3L))
  }

  test("a page past the end is empty rather than an error") {
    val page = SubjectCatalog.page(subjects("a", "b"), SubjectQuery(None, SortOrder.Asc, request(9, 25)))

    assertEquals(page.items, Nil)
    assertEquals(page.totalItems, Some(2L))
  }

  test("the default subject convention is the topic, a dash, and key or value") {
    assertEquals(SubjectCatalog.subjectFor("orders", SubjectTarget.Key).value, "orders-key")
    assertEquals(SubjectCatalog.subjectFor("orders", SubjectTarget.Value).value, "orders-value")
  }
}

/** `latest` is not a number, and a typo is not `latest`. */
final class VersionSelectorSuite extends FunSuite {

  test("'latest' parses to the selector the registry spells the same way") {
    assertEquals(VersionSelector.parse("latest"), Right(VersionSelector.Latest))
    assertEquals(VersionSelector.Latest.path, "latest")
  }

  test("a version number parses and renders as itself") {
    assertEquals(VersionSelector.parse("3").map(_.path), Right("3"))
  }

  test("a typo is refused rather than silently treated as latest") {
    // This is the whole reason the type exists. A fall-back to `latest` would show an operator the wrong
    // schema with nothing anywhere saying so.
    assert(VersionSelector.parse("latset").isLeft)
    assert(VersionSelector.parse("").isLeft)
  }

  test("version 0 and -1 are refused; -1 is the registry's own spelling of latest") {
    assert(VersionSelector.parse("0").isLeft)
    assert(VersionSelector.parse("-1").isLeft)
  }
}

/** The registry omits `schemaType` when it means Avro, and may send a word KUI has never heard of. */
final class SchemaFormatSuite extends FunSuite {

  test("an absent or empty schema type means Avro") {
    assertEquals(SchemaFormat.fromRegistry(None), SchemaFormat.Avro)
    assertEquals(SchemaFormat.fromRegistry(Some("")), SchemaFormat.Avro)
    assertEquals(SchemaFormat.fromRegistry(Some("avro")), SchemaFormat.Avro)
  }

  test("an unknown type keeps the registry's word instead of failing") {
    val format = SchemaFormat.fromRegistry(Some("THRIFT"))

    assertEquals(format.label, "THRIFT")
    assertEquals(format.isKnown, false)
  }
}

/** The seven levels' wire spellings are the registry's and must survive a rename of the case. */
final class CompatibilityLevelSuite extends FunSuite {

  test("every level round-trips through its wire spelling") {
    CompatibilityLevel.values.foreach(level =>
      assertEquals(CompatibilityLevel.fromWire(level.wire), Some(level))
    )
  }

  test("the transitive levels are spelled with an underscore, as the registry spells them") {
    assertEquals(CompatibilityLevel.BackwardTransitive.wire, "BACKWARD_TRANSITIVE")
    assertEquals(CompatibilityLevel.FullTransitive.wire, "FULL_TRANSITIVE")
  }

  test("an unknown level is not guessed at") {
    assertEquals(CompatibilityLevel.fromWire("BAKCWARD"), None)
  }
}
