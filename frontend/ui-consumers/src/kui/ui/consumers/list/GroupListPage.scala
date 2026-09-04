package kui.ui.consumers.list

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.consumer.contract.dto.{GroupPageDto, GroupsResponse}
import kui.consumer.contract.{ConsumerEndpoints, GroupListParams}
import kui.contracts.Section
import kui.contracts.consumer.{GroupSortField, GroupSummaryDto}
import kui.kernel.error.ErrorCode
import kui.kernel.group.GroupState
import kui.kernel.{ClusterId, GroupId, Sort, SortOrder}
import kui.ui.consumers.{ConsumersCss, ConsumersQueries, GroupStateChip, Messages}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.query.{QueryState, UrlParams}
import kui.ui.kernel.time.Timestamps

/** The consumer-group list: every group on a cluster, how many consumers are in it, and how far behind it is.
  *
  * ## Everything is in the URL, and that is the point
  *
  * The state filter, the search term, the sort and the page all live in the query string, under the same
  * parameter names the API uses, so "the groups that are rebalancing, worst lag first" is a link an operator
  * pastes into a chat window and their colleague sees the same screen. It also means the Back button undoes a
  * filter, which is what a browser's Back button is for.
  *
  * ## Filtering, sorting and paging all refetch
  *
  * Every one of them is a *server* parameter: the server holds every group on the cluster and this screen
  * holds a page of them, so narrowing by state cannot be done against the rows in hand without lying about
  * the count. A refetch dims the rows rather than removing them, so a failed refetch leaves the previous
  * answer readable underneath the overlay and the user loses nothing by having tried.
  *
  * ## The lag column keeps moving on its own
  *
  * Every other number here is a fact about configuration and changes when somebody changes it. Lag is not: it
  * moves continuously and it is the reason the screen is open, so a figure that sat still until the user
  * pressed Refresh would be a number whose age is the only thing that matters about it. `LagPoller` asks the
  * delta endpoint what has changed, on the interval the *server* names in each answer, and `LagFeed` lays the
  * result over the rows this page fetched. It never refetches the page and never reorders it: a lag figure
  * arriving must not move a row out from under somebody's cursor.
  *
  * The snapshot's own age is still on screen through the stale overlay, and the refresh button is still the
  * user's control over everything the poll does not carry.
  *
  * ## What is deliberately absent
  *
  * No delete, no offset reset, no row menu. Those are mutations, they are governed by ADR-045's plan-token
  * confirmation and ADR-047's read-only refusal and audit trail, and no button is rendered for them — not
  * even a disabled one. A disabled control for a capability that is not wired end to end is a promise with a
  * date on it (DEVPLAN §10 D8).
  *
  * @param navigate
  *   how a row click reaches the detail page, passed in rather than reached for, so the page is drivable from
  *   a suite with no router.
  * @param hrefFor
  *   because a row must be a real link: copy, bookmark and open-in-new-tab all have to work, and a suite
  *   asserting that needs to see an `href`.
  *
  * No `using Owner` is taken. Every subscription this page makes is a Laminar binder attached to an element
  * in the tree below, so the element's own lifetime owns them and they are torn down when it is unmounted.
  */
object GroupListPage {

  val DefaultPageSize: Int = ConsumerEndpoints.DefaultPageSize

  def apply(
      cluster: ClusterId,
      queries: ConsumersQueries,
      navigate: (ClusterId, String) => Unit,
      hrefFor: (ClusterId, String) => String,
      zone: Signal[String]
  ): HtmlElement = {

    // --- The state, all of it read out of the URL -------------------------------------------------

    val query: Signal[String] =
      UrlParams.signal(ConsumerEndpoints.QueryParam).map(_.getOrElse(""))

    /** The states to keep, as a set, because the parameter repeats on the wire and a set is what it means.
      *
      * An unrecognised state is dropped rather than fatal: a bookmark can outlive a state name, and showing
      * the unfiltered list is a far better answer than a blank page or a 400.
      */
    val states: Signal[Set[GroupState]] =
      UrlParams
        .signal(ConsumerEndpoints.StateParam)
        .map(_.toList.flatMap(_.split(',').toList).flatMap(raw => GroupState.from(raw.trim).toOption).toSet)

    val page: Signal[Int] =
      UrlParams.signal(ConsumerEndpoints.PageParam).map(_.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1))

    val pageSize: Signal[Int] =
      UrlParams
        .signal(ConsumerEndpoints.PageSizeParam)
        .map(_.flatMap(_.toIntOption).filter(_ >= 1))
        .map(_.getOrElse(DefaultPageSize))

    val sortField: Signal[GroupSortField] =
      UrlParams
        .signal(ConsumerEndpoints.SortParam)
        .map(_.flatMap(GroupSortField.from(_).toOption).getOrElse(GroupSortField.Default))

    val direction: Signal[SortOrder] =
      UrlParams
        .signal(ConsumerEndpoints.DirectionParam)
        .map(_.flatMap(SortOrder.fromWire).getOrElse(SortOrder.Asc))

    /** What `DataTable` reads and writes. It carries the column id, which is the wire's field name.
      *
      * A `Var` rather than the signals above, because the table writes to it; the write is turned into a URL
      * update below, and the URL is then what everything reads back. Two directions through one place, so the
      * URL and the table can never hold different opinions.
      */
    val tableSort: Var[Option[Sort[String]]] = Var(None)

    val params: Signal[GroupListParams] =
      Signal
        .combine(states, query, sortField, direction, page, pageSize)
        .map((keep, q, field, order, pageNumber, size) =>
          GroupListParams(
            states = keep,
            q = Option(q.trim).filter(_.nonEmpty),
            sort = field,
            direction = order,
            page = pageNumber,
            pageSize = size
          )
        )

    // --- The response, and the renderings it can produce -------------------------------------------

    val state: Signal[QueryState[GroupsResponse]] =
      params.flatMapSwitch(current => queries.groups.state((cluster, current)))

    /** The freshness envelope the server sent, which is what says whether these numbers are still true.
      *
      * It is not the same question as "did the last request fail". A request can succeed perfectly and still
      * answer with rows from before the cluster went away — that is exactly what the snapshot is for — and
      * until this section existed the screen had no way to tell the two apart. Lag is the field that made
      * that unacceptable: a dead broker makes it freeze rather than climb, so a frozen lag column reads as
      * consumers that have caught up.
      */
    val section: Signal[Option[Section[GroupPageDto]]] = state.map(_.lastGood.map(_.groups))

    val answer: Signal[Option[GroupPageDto]] = section.map(_.flatMap(_.toOption))

    val fetched: Signal[List[GroupSummaryDto]] = answer.map(_.map(_.items).getOrElse(Nil))

    /** The groups the poller asks about: exactly the ones on screen, and nothing else.
      *
      * Derived from the fetched rows rather than from the request, because a page of twenty-five is what the
      * user can see and asking about a cluster's four thousand groups to repaint twenty-five cells would make
      * the poll the most expensive request in the product.
      */
    val onScreen: Signal[Set[GroupId]] = fetched.map(_.map(_.groupId).toSet).distinct

    val poller = new LagPoller(
      cluster = cluster,
      groups = onScreen,
      poll = (id, ids, since) => queries.lagSince(id, ids, since)
    )

    /** What is drawn: the page the list endpoint sent, with the poller's later figures laid over it.
      *
      * Laid over rather than refetched, so a lag figure arriving cannot reorder the table under somebody's
      * cursor. The sort, the filter and the paging are the server's and they stay where they were until the
      * user changes one.
      */
    val rows: Signal[List[GroupSummaryDto]] =
      fetched.combineWith(poller.view).map(LagFeed.applyTo)

    val total: Signal[Option[Long]] = answer.map(_.flatMap(_.page.totalItems))

    // Derived from `totalItems` and the page size on both sides, so the count and the page count cannot
    // disagree with each other or with the rows that were sent.
    val pageCount: Signal[Int] = answer.map(_.flatMap(_.page.pageCount).getOrElse(0))

    /** Scaled against the largest lag among the rows *currently displayed*, per the design. */
    val largestLag: Signal[Long] = rows.map(_.flatMap(_.totalLag).maxOption.getOrElse(0L))

    /** Why the rows on screen are not being refreshed, from either of the two things that can say so.
      *
      * The server's own section comes first, because it is the stronger statement: the server *knows* the
      * scrape behind these rows failed, whereas a failed request only tells the browser that this one call
      * did not get through. A section marked stale therefore wins over a request that happened to succeed,
      * and a failed request still marks the table when the last good answer was itself fine.
      */
    val stale: Signal[Option[StaleReason]] =
      state.map { current =>
        val fromServer = current.lastGood.map(_.groups).flatMap(staleReason)
        val fromRequest =
          Option.when(current.isStale)(
            StaleReason.lastRequestFailed(
              current.outcome.flatMap(_.left.toOption).map(_.userMessage).getOrElse(Messages.NotRefreshed)
            )
          )
        fromServer.orElse(fromRequest)
      }

    /** When the rows were fetched *from Kafka*, which is the server's `fetchedAt` and not the moment this
      * browser's request returned. A cached snapshot answered in a millisecond is not fresh data, and
      * stamping the badge with the request time would say it was.
      */
    val fetchedAt: Signal[Option[Instant]] =
      state.map(current =>
        current.lastGood
          .map(_.groups)
          .flatMap(fetchedAtOf)
          .orElse(current.lastGoodAt.map(Timestamps.instantOf))
      )

    /** Nothing held and the request failed: the whole screen is the explanation, and there is no table.
      *
      * A table with no rows in it is a claim that the cluster has no consumer groups, which is exactly what a
      * failed request must never be allowed to say.
      */
    val refusal: Signal[Option[ApiError]] =
      state.map(current =>
        if current.lastGood.flatMap(_.groups.toOption).isEmpty then
          current.outcome.flatMap(_.left.toOption).orElse(current.lastGood.map(_.groups).flatMap(refusalOf))
        else None
      )

    // --- Writing the state back --------------------------------------------------------------------

    def setParams(updates: Map[String, Option[String]]): Unit = UrlParams.set(updates)

    /** Any change to what is being *searched for* resets the page.
      *
      * Without it, filtering while on page 9 asks for page 9 of a list that now has one page, and the screen
      * goes blank for a filter that matched plenty.
      */
    def setFilter(updates: Map[String, Option[String]]): Unit =
      setParams(updates + (ConsumerEndpoints.PageParam -> None))

    val sortToUrl: Observer[Option[Sort[String]]] = Observer { chosen =>
      setFilter(
        Map(
          ConsumerEndpoints.SortParam -> chosen
            .flatMap(s => GroupSortField.from(s.field).toOption)
            .map(_.wire),
          ConsumerEndpoints.DirectionParam -> chosen.map(_.order.wire)
        )
      )
    }

    def toggleState(state: GroupState, chosen: Set[GroupState]): Unit = {
      val next = if chosen.contains(state) then chosen - state else chosen + state
      setFilter(
        Map(
          ConsumerEndpoints.StateParam ->
            Option.when(next.nonEmpty)(GroupState.All.filter(next.contains).map(_.wire).mkString(","))
        )
      )
    }

    val table = DataTable[GroupSummaryDto](
      columns = GroupColumns.all(
        cluster = cluster,
        largestLag = largestLag,
        hrefFor = hrefFor,
        onOpen = navigate
      ),
      rows = rows,
      rowKey = _.groupId.value,
      sort = tableSort,
      empty = () => EmptyState(Messages.EmptyTitle, description = Some(Messages.EmptyDescription)),
      testId = Some("groups-table")
    )

    div(
      cls := ConsumersCss.Page,
      dataAttr("testid") := "page-consumers-list",
      h1(Messages.Title),
      // The poller's subscriptions hang off this element, so navigating away unmounts it and the polling
      // stops with it. Anything longer-lived would go on asking about a screen nobody is looking at.
      poller.binder,
      // The URL is authoritative, so the table's sort is pushed *into* it whenever the URL changes, and the
      // table's own writes are turned back into URL updates. Both directions pass through here.
      Signal.combine(sortField, direction).map((field, order) => Sort(field.wire, order)) --> { chosen =>
        tableSort.set(Some(chosen))
      },
      tableSort.signal.changes --> sortToUrl,
      controls(
        query = query,
        onQuery = raw => setFilter(Map(ConsumerEndpoints.QueryParam -> Option(raw.trim).filter(_.nonEmpty))),
        states = states,
        onState = toggleState,
        total = total,
        onRefresh = () => queries.invalidateCluster(cluster)
      ),
      child.maybe <-- refusal.map(
        _.map(error => errorPanel(error, () => queries.invalidateCluster(cluster)))
      ),
      child.maybe <-- refusal.map(current =>
        Option.when(current.isEmpty)(
          StaleDataOverlay(
            content = table,
            stale = stale,
            fetchedAt = fetchedAt,
            zone = zone,
            testId = Some("groups-table-region")
          )
        )
      ),
      child <-- Signal
        .combine(page, pageCount, pageSize)
        .map((current, count, size) =>
          Pagination(
            page = Val(current),
            pageCount = Val(count),
            pageSize = Val(size),
            onPage = wanted => setParams(Map(ConsumerEndpoints.PageParam -> Some(wanted.toString))),
            onPageSize = wanted => setFilter(Map(ConsumerEndpoints.PageSizeParam -> Some(wanted.toString))),
            testId = Some("groups-pagination")
          )
        )
    )
  }

  /** Search, the state chips, the count and refresh, on one bar.
    *
    * The state chips are toggles rather than a dropdown because the set is six long and fixed, an operator
    * wants two of them at once ("preparing" and "completing" are one question), and a chip that is on is
    * visible without opening anything.
    */
  private def controls(
      query: Signal[String],
      onQuery: String => Unit,
      states: Signal[Set[GroupState]],
      onState: (GroupState, Set[GroupState]) => Unit,
      total: Signal[Option[Long]],
      onRefresh: () => Unit
  ): HtmlElement =
    div(
      cls := ConsumersCss.Controls,
      dataAttr("testid") := "groups-controls",
      SearchBox(
        value = query,
        onQuery = onQuery,
        placeholder = Messages.SearchPlaceholder,
        testId = Some("groups-search")
      ),
      div(
        cls := ConsumersCss.States,
        role := "group",
        aria.label := Messages.StateFilterLabel,
        GroupState.All.map { state =>
          button(
            tpe := "button",
            cls := ConsumersCss.StateChip,
            cls(ConsumersCss.StateChipOn) <-- states.map(_.contains(state)),
            dataAttr("testid") := s"groups-state-${state.wire}",
            title := state.description,
            // `aria-pressed` rather than a class alone: a toggle whose state is only a colour is invisible to
            // a screen reader and to anyone who cannot see the tint.
            aria.pressed <-- states.map(_.contains(state).toString),
            GroupStateChip.label(state),
            // The current set is sampled from the signal at the moment of the click rather than closed
            // over, because the set changes as other chips are pressed and a stale closure would undo them.
            onClick.compose(_.sample(states)) --> { chosen => onState(state, chosen) }
          )
        }
      ),
      span(
        cls := ConsumersCss.Count,
        dataAttr("testid") := "groups-count",
        text <-- total.map(_.fold("")(Messages.groupCount))
      ),
      Button(
        label = Val(Messages.Refresh),
        onClick = Observer[Unit](_ => onRefresh()),
        variant = ButtonVariant.Ghost,
        testId = Some("groups-refresh")
      )
    )

  /** The rendering for a failed request with nothing held from before. */
  private def errorPanel(error: ApiError, retry: () => Unit): HtmlElement =
    div(
      cls := ConsumersCss.Error,
      dataAttr("testid") := "groups-error",
      role := "alert",
      p(error.userMessage),
      Button(
        label = Val(Messages.TryAgain),
        onClick = Observer[Unit](_ => retry()),
        variant = ButtonVariant.Primary,
        testId = Some("groups-retry")
      )
    )

  /** When the rows in a section were fetched. `Unavailable` has no rows and therefore no time. */
  private def fetchedAtOf(section: Section[GroupPageDto]): Option[Instant] = section match {
    case Section.Ok(_, at) => Some(at)
    case Section.Stale(_, at, _) => Some(at)
    case Section.Unavailable(_, _, _) | Section.Forbidden | Section.NotConfigured => None
  }

  /** The badge's words, from a section that carries rows.
    *
    * The reason is rendered as a sentence and the wire code goes in the badge's tooltip. A user reading
    * `Stale: UPSTREAM_UNAVAILABLE` learns nothing they can act on; the code still belongs in a support
    * conversation, so it stays reachable rather than being thrown away.
    *
    * A section with no rows produces nothing here, because there is no table to mark — that case is the
    * refusal panel's.
    */
  private def staleReason(section: Section[GroupPageDto]): Option[StaleReason] = section match {
    case Section.Stale(_, _, reason) =>
      Some(StaleReason(Messages.StaleState, Some(reason.sentence), code = Some(reason.wire)))
    case Section.Ok(_, _) | Section.Unavailable(_, _, _) | Section.Forbidden | Section.NotConfigured => None
  }

  /** A section that carries no rows at all, as the failure the screen explains.
    *
    * A table with no rows in it is a claim that the cluster has no consumer groups, which is what a section
    * with nothing in it must never be allowed to say.
    */
  private def refusalOf(section: Section[GroupPageDto]): Option[ApiError] = section match {
    case Section.Unavailable(reason, _, _) =>
      Some(ApiError.Envelope(reason.wire, reason.sentence, Nil, "", retryable = true))
    case Section.Forbidden =>
      Some(ApiError.Envelope(ErrorCode.Forbidden.wire, Messages.Forbidden, Nil, "", retryable = false))
    case Section.NotConfigured =>
      Some(ApiError.Envelope(ErrorCode.Unsupported.wire, Messages.NotConfigured, Nil, "", retryable = false))
    case Section.Ok(_, _) | Section.Stale(_, _, _) => None
  }
}
