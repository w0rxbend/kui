package kui.consumer.application

import scala.concurrent.duration.*

import cats.effect.IO

import kui.consumer.domain.{GroupListingPage, GroupSummary}
import kui.kernel.group.GroupState
import kui.kernel.search.NameIndex
import kui.testkit.KuiIOSuite

/** Two rules of the group list that `GroupListUseCaseSuite` leaves open, both found by mutation against
  * `./mill -k services.cluster.__.test + services.consumer.__.test + services.schema.__.test`, which stayed
  * green for each.
  *
  *   - `GroupListView.stateCounts` is documented as *"computed **before** the state filter is applied, so a
  *     filter chip can show how many groups filtering it in would reveal"*. Computing it from the filtered
  *     rows instead changed nothing anywhere: the field reaches no response today — `ConsumerRoutes` sends
  *     `groups` and `incompleteCoordinators` and nothing else — so the rule had no reader and no gate.
  *   - `sortRows`' `if query.sort == GroupSortField.Id && query.descending then ordered.reverse` is the only
  *     descending direction the `Id` sort has; the other four go through `stable`, which reverses the
  *     `Ordering` instead. Deleting the branch left every existing case green, because none of them asks for
  *     a descending `Id` sort — and `sort=id&order=desc` would then answer in ascending order and say
  *     nothing about having ignored the parameter, which is the defect `GroupSortField` exists to prevent.
  */
final class GroupListViewSuite extends KuiIOSuite {

  private def row(id: String, state: GroupState, members: Int): GroupSummary =
    ConsumerRig
      .group(id, state = state, members = math.max(1, members))
      .summary
      .copy(memberCount = members)

  private val rows: Vector[GroupSummary] = Vector(
    row("orders", GroupState.Stable, members = 3),
    row("audit", GroupState.Empty, members = 0),
    row("billing", GroupState.Stable, members = 2),
    row("shipping", GroupState.Dead, members = 0)
  )

  private val index: NameIndex = NameIndex.of(rows.map(_.groupId.value).toList)

  /** The use case over a snapshots component that has completed one pass over [[rows]].
    *
    * The pass runs in the background, so the first read can land before it; the poll below is bounded so a
    * snapshot that never arrives fails the case rather than hanging it.
    */
  private def viewOf(query: GroupQuery): IO[GroupListView] =
    for {
      profiles <- ConsumerRig.profiles()
      port <- ConsumerRig.port(
        ConsumerRig.PortState.Empty.copy(listing = Right(GroupListingPage(rows.toList, 0)))
      )
      view <- ConsumerRig
        .snapshots(port, profiles)
        .use(snapshots => loaded(GroupListUseCase.make[IO](snapshots), query).timeout(5.seconds))
    } yield view

  private def loaded(useCase: GroupListUseCase[IO], query: GroupQuery): IO[GroupListView] =
    useCase.list(ConsumerRig.Cluster, query).flatMap {
      case Right(view) if view.freshness.isFresh => IO.pure(view)
      case Right(_) => IO.sleep(10.millis) *> loaded(useCase, query)
      case Left(error) => IO.raiseError(new AssertionError(s"the list failed: $error"))
    }

  test("theStateCountsAreOfTheWholeClusterAndNotOfTheFilteredPage") {
    // The chip says "Empty (1)" while the page shows only the stable groups. Counting after the filter
    // makes every chip read as the number of rows already on screen, which is the one number a chip that
    // offers to widen the filter must not be.
    viewOf(GroupQuery.Default.copy(states = Set(GroupState.Stable))).map { view =>
      assertEquals(view.page.items.map(_.groupId.value), List("billing", "orders"))
      assertEquals(
        view.stateCounts,
        Map(GroupState.Stable -> 2, GroupState.Empty -> 1, GroupState.Dead -> 1)
      )
    }
  }

  test("aDescendingSortByIdReversesTheList") {
    val ascending =
      GroupListUseCase.applyQuery(rows, GroupQuery.Default, index).items.map(_.groupId.value)
    val descending = GroupListUseCase
      .applyQuery(rows, GroupQuery.Default.copy(descending = true), index)
      .items
      .map(_.groupId.value)

    assertEquals(ascending, List("audit", "billing", "orders", "shipping"))
    assertEquals(descending, ascending.reverse)
  }

  test("aDescendingSortByIdStillPagesFromTheRightEnd") {
    // The reverse has to happen before `Page.of` cuts, or page one of a descending list holds the ascending
    // list's first rows under a descending heading.
    val page = GroupListUseCase.applyQuery(
      rows,
      GroupQuery.Default.copy(descending = true, page = 1, pageSize = 2),
      index
    )

    assertEquals(page.items.map(_.groupId.value), List("shipping", "orders"))
    assertEquals(page.totalItems, Some(4L))
  }
}
