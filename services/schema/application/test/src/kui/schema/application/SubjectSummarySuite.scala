package kui.schema.application

import scala.concurrent.duration.DurationInt

import cats.effect.IO

import kui.kernel.{PageRequest, PageSize, PositiveInt, SortOrder}
import kui.schema.domain.*
import kui.testkit.KuiIOSuite

/** What a subject list row costs, and what it says when it could not be filled in.
  *
  * Three promises are defended here, and none of them is about arithmetic:
  *
  *   - **the page bounds the calls.** A registry with two thousand subjects must not become two thousand
  *     calls because somebody opened a list, so the enrichment runs over the page and not over the registry.
  *     A counting fake is the only way to see that: the rows look identical either way.
  *   - **a row never disappears because a secondary call failed.** The list call is the one that decides
  *     whether there are rows; everything after it decorates one row, and losing a decoration must cost that
  *     row three cells and not its existence. A subject that vanished from a screen is a subject an operator
  *     goes looking for.
  *   - **a page with no rows costs one call.** The empty page and the count-only request both short-circuit
  *     the enrichment, and the registry-wide compatibility call is the half of that a row count cannot see:
  *     an empty page enriches no rows whether the short-circuit fires or not. `FakeRegistry.globalReads` is
  *     what makes the difference observable, and without it this suite stayed green with the short-circuit
  *     turned off.
  */
final class SubjectSummarySuite extends KuiIOSuite {

  private val orders = "orders-value"
  private val payments = "payments-value"

  private def query(page: Int, size: Int): SubjectQuery =
    SubjectQuery(None, SortOrder.Asc, PageRequest(PositiveInt.unsafe(page), PageSize.unsafe(size)))

  /** The badge's request: the total, and no rows to pay for. The page request it carries is never used to cut
    * anything, which is why it can be the default.
    */
  private val countOnly: SubjectQuery =
    SubjectQuery(None, SortOrder.Asc, PageRequest.Default, countOnly = true)

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
      globalReads <- registry.globalReads.get
    } yield {
      assertEquals(result.map(_.items), Right(List.empty[SubjectSummary]))
      assertEquals(asked, Nil)
      // "Nothing beyond the list" is two calls, not one, and the second is the one the row count cannot
      // see: the registry-wide compatibility level. Without this line the short-circuit `enrich` opens
      // with can be turned off — `if page.isEmpty` to `if false` — and every other assertion in this case
      // still holds, because a page with no rows enriches no rows either way.
      assertEquals(globalReads, 0)
    }
  }

  test("a count-only request answers the total, sends no rows and enriches nothing") {
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects)
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, countOnly)
      asked <- registry.enrichments.get
      globalReads <- registry.globalReads.get
    } yield {
      // What the drawer's schema badge is for: the number of subjects, and no page of rows behind it.
      assertEquals(result.map(_.totalItems), Right(Some(40L)))
      assertEquals(result.map(_.items), Right(List.empty[SubjectSummary]))
      assertEquals(result.map(_.pageSize), Right(0))
      // One request to the registry — the subject list — and none of the five a page of one used to cost.
      assertEquals(asked, Nil)
      assertEquals(globalReads, 0)
    }
  }

  test("a count-only request counts what the search matched, not the whole registry") {
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects)
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, countOnly.copy(search = Some("orders-1")))
    } yield
      // orders-10-value through orders-19-value. A count taken before the filter is the reference
      // product's defect, and a count-only answer is nothing *but* that number.
      assertEquals(result.map(_.totalItems), Right(Some(10L)))
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
      globalReads <- registry.globalReads.get
    } yield {
      val rows = rowsOf(result)

      // Both halves matter, exactly as they do for one subject: the level so the row can say something,
      // the flag so a reader can tell a pinned subject from one that follows the registry.
      assertEquals(
        rows.head.compatibility,
        Some(SubjectCompatibility.inherited(CompatibilityLevel.ForwardTransitive))
      )
      assertEquals(rows.last.compatibility, Some(SubjectCompatibility.own(CompatibilityLevel.Full)))
      // "Read once for the page" is the other half of this case's name, and it is the half that the rows
      // cannot show: two rows inheriting the same level look identical whether it was fetched once or twice.
      assertEquals(globalReads, 1)
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

  test("a registry-wide level that could not be read leaves the inheriting rows with none") {
    // Not the registry's documented default. `BACKWARD` is what a registry applies when nobody has set a
    // level, and printing it for a registry that would not answer is a guess presented as a fact on the
    // screen an operator uses to decide whether a breaking change is allowed. `globalLevel` here is
    // deliberately something other than the default, so a case that accepted either could not pass.
    for {
      registry <- SchemaRig.registry(
        subjects = Map(orders -> List(1, 2), payments -> List(1)),
        globalLevel = CompatibilityLevel.FullTransitive,
        subjectLevels = Map(payments -> CompatibilityLevel.None),
        globalFails = true
      )
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, SubjectQuery.Default)
    } yield {
      val rows = rowsOf(result)

      // The page still arrives: one unreadable registry-wide call costs the inheriting rows their level
      // and costs the page nothing, which is the same rule a failed row decoration follows.
      assertEquals(rows.map(_.subject.value), List(orders, payments))
      assertEquals(rows.head.compatibility, None)
      assertEquals(rows.head.versionCount, Some(2))
      // A subject with a level of its own still has one: nothing was inherited, so nothing was lost.
      assertEquals(rows.last.compatibility, Some(SubjectCompatibility.own(CompatibilityLevel.None)))
    }
  }

  test("a page enriches at most eight rows at once, whatever the page holds") {
    // The bulkhead in front of a Schema Registry is deliberately narrow — it is a single-writer JVM — and
    // `SubjectListUseCase.MaxConcurrentRows` is half of it so that one list page can never fill it. The
    // rows are identical whether they were fetched eight at a time or twenty-five at once, so the peak
    // number in flight is the only thing that can see this. Eight is written out rather than read from the
    // constant, because a case that reads the constant passes at any value of it.
    for {
      registry <- SchemaRig.registry(subjects = fortySubjects, enrichmentDelay = 20.millis)
      useCase <- listing(registry)
      result <- useCase.list(SchemaRig.WithRegistry, query(1, 25))
      peak <- registry.peakInFlight.get
    } yield {
      assertEquals(result.map(_.items.size), Right(25))
      assert(clue(peak) <= 8, "more rows were in flight at once than the registry bulkhead allows for")
      // And the fan-out is real: a use case that enriched one row at a time would be twenty-five rounds
      // of latency for one screen, which is the other half of why the number is eight and not one.
      assert(clue(peak) > 1, "the page's rows were fetched one at a time")
    }
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
