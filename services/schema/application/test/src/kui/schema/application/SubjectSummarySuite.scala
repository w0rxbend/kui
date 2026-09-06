package kui.schema.application

import cats.effect.IO

import kui.kernel.{PageRequest, PageSize, PositiveInt, SortOrder}
import kui.schema.domain.*
import kui.testkit.KuiIOSuite

/** What a subject list row costs, and what it says when it could not be filled in.
  *
  * Two promises are defended here, and neither of them is about arithmetic:
  *
  *   - **the page bounds the calls.** A registry with two thousand subjects must not become two thousand
  *     calls because somebody opened a list, so the enrichment runs over the page and not over the registry.
  *     A counting fake is the only way to see that: the rows look identical either way.
  *   - **a row never disappears because a secondary call failed.** The list call is the one that decides
  *     whether there are rows; everything after it decorates one row, and losing a decoration must cost that
  *     row three cells and not its existence. A subject that vanished from a screen is a subject an operator
  *     goes looking for.
  */
final class SubjectSummarySuite extends KuiIOSuite {

  private val orders = "orders-value"
  private val payments = "payments-value"

  private def query(page: Int, size: Int): SubjectQuery =
    SubjectQuery(None, SortOrder.Asc, PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size)))

  /** Forty subjects, three versions each, so that a page of ten is visibly a quarter of the registry. */
  private val fortySubjects: Map[String, List[Int]] =
    (0 until 40).map(n => f"orders-$n%02d-value" -> List(1, 2, 3)).toMap

  private def listing(registry: FakeRegistry): IO[SubjectListUseCase[IO]] =
    SchemaRig.logger.map(SubjectListUseCase.make[IO](SchemaRig.registries(registry), _))

  private def rowsOf(result: Either[kui.kernel.error.KuiError, kui.kernel.Page[SubjectSummary]]) =
    result.toOption.toList.flatMap(_.items)

  test("a page of ten out of forty subjects costs ten enrichment calls, not forty") {
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects)
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, query(1, 10))
      asked <- registry.enrichments.get
    } yield {
      assertEquals(result.map(_.items.size), Right(10))
      // The total is the registry's, so the browser can page; the calls are the page's. Those two numbers
      // being different is the whole point of enriching after the page has been cut.
      assertEquals(result.map(_.totalItems), Right(Some(40L)))
      assertEquals(asked.size, 10)
      assertEquals(asked.toSet, rowsOf(result).map(_.subject.value).toSet)
    }
  }

  test("the second page enriches the second page's subjects and no others") {
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects)
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, query(2, 10))
      asked <- registry.enrichments.get
    } yield {
      // `asked` is a Ref appended to by parTraverseN fibers, so its ORDER is scheduling noise and
      // must not be asserted. What the packet promises -- and all this case ever meant -- is the
      // multiset: exactly the second page's subjects, each once, and nothing else. The page's own
      // first row (not the first fiber to finish) is what pins the offset.
      assertEquals(asked.sorted, rowsOf(result).map(_.subject.value).sorted)
      assertEquals(rowsOf(result).map(_.subject.value).headOption, Some("orders-10-value"))
    }
  }

  test("a page with no rows asks the registry nothing beyond the list itself") {
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects)
      useCase <- listing(registry)
      result <- useCase.list(
        SchemaRig.WithRegistry,
        SubjectQuery(Some("nothing-like-this"), SortOrder.Asc, PageRequest.Default)
      )
      asked <- registry.enrichments.get
    } yield {
      assertEquals(result.map(_.items), Right(List.empty[SubjectSummary]))
      assertEquals(asked, Nil)
    }
  }

  test("one row's enrichment failing costs that row its facts and costs the page nothing") {
    for {
      registry <- SchemaRig.registry(
        subjects = Map(orders -> List(1, 2), payments -> List(1)),
        formats = Map(orders -> SchemaFormat.Avro, payments -> SchemaFormat.Protobuf),
        subjectLevels = Map(payments -> CompatibilityLevel.Full),
        unenrichable = Set(payments)
      )
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield {
      val rows = rowsOf(result)

      assert(result.isRight, "a failed enrichment must not fail the page")
      // The row is still here, and it is still in its place in the sort order.
      assertEquals(rows.map(_.subject.value), List(orders, payments))

      val broken = rows.last
      assertEquals(broken.format, None)
      assertEquals(broken.versionCount, None)
      // Not the global level either: an enrichment that did not answer never established that this
      // subject has no level of its own, and "inherits BACKWARD" would be a claim nobody checked.
      assertEquals(broken.compatibility, None)

      assertEquals(rows.head.format, Some(SchemaFormat.Avro))
      assertEquals(rows.head.versionCount, Some(2))
    }
  }

  test("a subject listed and then deleted keeps its row, its name and nothing else") {
    for {
      registry <- SchemaRig.registry(subjects = Map(orders -> List(1)), vanished = Set(payments))
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield {
      val rows = rowsOf(result)

      assertEquals(rows.map(_.subject.value), List(orders, payments))
      assertEquals(rows.last, SubjectSummary.bare(rows.last.subject))
    }
  }

  test("a subject with its own level keeps it; the rest inherit the global one, read once for the page") {
    for {
      registry <- SchemaRig.registry(
        subjects = Map(orders -> List(1, 2, 3), payments -> List(1)),
        globalLevel = CompatibilityLevel.ForwardTransitive,
        subjectLevels = Map(payments -> CompatibilityLevel.Full)
      )
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield {
      val rows = rowsOf(result)

      // Both halves matter, exactly as they do for one subject: the level so the row can say something,
      // the flag so a reader can tell a pinned subject from one that follows the registry.
      assertEquals(
        rows.head.compatibility,
        Some(SubjectCompatibility.inherited(CompatibilityLevel.ForwardTransitive))
      )
      assertEquals(rows.last.compatibility, Some(SubjectCompatibility.own(CompatibilityLevel.Full)))
    }
  }

  test("a version count is a count and never a zero") {
    for {
      registry <- SchemaRig.registry(subjects = Map(orders -> List(1, 2, 3, 7)))
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield
      // Four versions numbered 1, 2, 3, 7 — a registry where three were deleted. The count is the length
      // of the list and not the largest number in it, which is the difference a soft delete makes.
      assertEquals(rowsOf(result).map(_.versionCount), List(Some(4)))
  }

  test("a registry that does not answer the list fails the page rather than returning bare rows") {
    for {
      registry <- SchemaRig.registry(
        subjects = Map(orders -> List(1)),
        failure = Some(SchemaRig.unreachable)
      )
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
      asked <- registry.enrichments.get
    } yield {
      // An empty or nameless list is a claim about the registry's contents. Only a decoration may be lost.
      assertEquals(result, Left(SchemaRig.unreachable))
      assertEquals(asked, Nil)
    }
  }
}
