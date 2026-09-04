package kui.ui.topics.list

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.paging.PageDto
import kui.contracts.topic.TopicRowDto
import kui.kernel.search.SearchMode
import kui.kernel.{ClusterId, PageRequest, PageSize, PositiveInt, Sort, SortOrder}
import kui.topic.contract.{TopicListParams, TopicQueryCodecs, TopicSortField}
import kui.ui.kernel.component.*
import kui.ui.kernel.prefs.{Favourites, PreferenceStore}
import kui.ui.kernel.query.UrlParams
import kui.ui.topics.admin.CreateTopicDialog
import kui.ui.topics.{Messages, TopicsCss, TopicsQueries}

/** The topic list: the screen this milestone is judged on.
  *
  * Ten thousand topics, searchable, sortable, paged, internal topics hidden by default, favourites pinned to
  * the top of the page, and a count that is true.
  *
  * ## Everything is in the URL, and that is the point
  *
  * The search term, the search mode, the internal-topic switch, the sort and the page all live in the query
  * string. An operator's most common act after finding something is to send the link to somebody else, and a
  * link that reproduced only part of the screen would send the recipient looking. It also means the Back
  * button undoes a sort, which is what a browser's Back button is for.
  *
  * The internal-topic switch is additionally remembered in `localStorage`, because an operator who hides
  * internal topics wants them hidden tomorrow — but the URL wins whenever it says anything, so a shared link
  * still shows its recipient what the sender saw. The stored value only seeds the switch when the URL is
  * silent.
  *
  * ## Sorting and paging refetch; searching within held rows does not
  *
  * Sort and page are *server* parameters — the server sorts ten thousand rows this screen has never seen, of
  * which it holds five hundred — so changing either issues a request. That is the one place this screen
  * behaves differently from the dashboard, and it is deliberate: if the refetch fails, the previous rows are
  * still underneath the overlay, so the user loses nothing by having tried.
  *
  * ## Nothing polls
  *
  * The reference product replaces its whole table with a page loader on every refetch, so the table a user is
  * reading disappears under a spinner while they read it. Here the snapshot's `fetchedAt` is on screen, the
  * refresh button is the user's control, and a refetch dims the rows rather than removing them.
  *
  * ## What is deliberately absent
  *
  * No Create, no delete, no purge, no clone, no batch actions, no CSV download, no row menu. Those are M5,
  * with read-only mode and the audit trail behind them. No button is rendered for them, not even a disabled
  * one: a disabled control for a feature that does not exist is a promise with a date on it (DEVPLAN §10 D8),
  * and `noCreateButtonExists` in the suite is what keeps M5's scope out of M2.
  *
  * @param navigate
  *   how a row click reaches the detail page, passed in rather than reached for, so the page is drivable from
  *   a suite with no router.
  * @param hrefFor
  *   because a row must be a real link: copy, bookmark and open-in-new-tab all have to work, and a suite
  *   asserting that needs to see an `href`.
  * @param tableViewportHeight
  *   handed straight to `VirtualizedTable`, which fills it in from the real element in a browser. It is a
  *   parameter for the reason that component's own comment gives: jsdom performs no layout and reports every
  *   element as zero pixels tall, so a suite that could not set it would be looking at a table with an empty
  *   window and would pass while asserting nothing at all.
  *
  * No `using Owner` is taken. Every subscription this page makes is a Laminar binder attached to an element
  * in the tree below, so the element's own lifetime owns them and they are torn down when it is unmounted. An
  * external owner would keep them alive after the page had gone.
  */
object TopicListPage {

  /** The default page size, and the only one the URL omits. `Pagination` offers the rest. */
  val DefaultPageSize: Int = PageSize.Default.value

  def apply(
      cluster: ClusterId,
      queries: TopicsQueries,
      favourites: Favourites,
      navigate: (ClusterId, String) => Unit,
      hrefFor: (ClusterId, String) => String,
      zone: Signal[String],
      now: () => Instant = () => Instant.now(),
      store: PreferenceStore = PreferenceStore.browser,
      tableViewportHeight: Var[Int] = Var(0)
  ): HtmlElement = {

    // --- The state, all of it read out of the URL -------------------------------------------------

    val query: Signal[String] = UrlParams.signal(TopicQueryCodecs.QParam).map(_.getOrElse(""))

    val mode: Signal[SearchMode] =
      UrlParams
        .signal(TopicQueryCodecs.ModeParam)
        .map(_.flatMap(SearchMode.fromWire).getOrElse(SearchMode.Default))

    /** The URL wins when it says anything; the stored preference seeds the switch when it is silent. */
    val showInternal: Signal[Boolean] =
      UrlParams
        .signal(TopicQueryCodecs.ShowInternalParam)
        .map(_.map(_ == "true").getOrElse(storedShowInternal(store, cluster)))

    val page: Signal[Int] =
      UrlParams.signal(TopicQueryCodecs.PageParam).map(_.flatMap(_.toIntOption).filter(_ >= 1).getOrElse(1))

    val pageSize: Signal[Int] =
      UrlParams
        .signal(TopicQueryCodecs.PageSizeParam)
        .map(_.flatMap(_.toIntOption).filter(size => size >= 1 && size <= PageSize.Max.value))
        .map(_.getOrElse(DefaultPageSize))

    val sort: Signal[Option[Sort[TopicSortField]]] =
      UrlParams.signal(TopicQueryCodecs.SortParam).map(_.flatMap(parseSort))

    /** What `VirtualizedTable` reads and writes. It carries the column id, which is the wire's field name.
      *
      * A `Var` rather than the signal above, because the table writes to it; the write is turned into a URL
      * update below, and the URL is then what everything reads back. Two directions through one place, so the
      * URL and the table can never hold different opinions.
      */
    val tableSort: Var[Option[Sort[String]]] = Var(None)

    val params: Signal[TopicListParams] =
      Signal
        .combine(query, mode, showInternal, sort, page, pageSize)
        .map((q, searchMode, internal, sorted, pageNumber, size) =>
          TopicListParams(
            q = Option(q.trim).filter(_.nonEmpty),
            mode = searchMode,
            showInternal = internal,
            sort = sorted,
            page = PageRequest(
              PositiveInt.from(pageNumber).getOrElse(PositiveInt.One),
              PageSize.from(size).getOrElse(PageSize.Default)
            )
          )
        )

    // --- The response, and the five renderings it can produce -------------------------------------

    val section: Signal[Option[Section[PageDto[TopicRowDto]]]] =
      params.flatMapSwitch(current => queries.topics.state((cluster, current))).map(_.lastGood.map(_.topics))

    val favouriteNames: Signal[Set[String]] = favourites.signal(cluster.value)

    val rows: Signal[List[TopicRow]] =
      section
        .combineWith(favouriteNames)
        .map((current, names) =>
          TopicRow.pin(current.flatMap(_.toOption).map(TopicRow.of(_, names)).getOrElse(Nil))
        )

    val total: Signal[Option[Long]] = section.map(_.flatMap(_.toOption).flatMap(_.page.totalItems))

    // Derived on both sides from `totalItems` and the page size, and `totalItems` is counted after every
    // filter — including the internal-topic one. That ordering is the whole reason this screen's count and
    // its page count cannot disagree, and it is the reference product's bug not reproduced.
    val pageCount: Signal[Int] =
      section.map(_.flatMap(_.toOption).flatMap(_.page.pageCount).getOrElse(0))

    /** Scaled against the largest value among the rows *currently displayed*, per the design. */
    val largestMessageCount: Signal[Long] = rows.map(_.flatMap(_.messages).maxOption.getOrElse(0L))
    val largestSize: Signal[Long] = rows.map(_.flatMap(_.sizeBytes).maxOption.getOrElse(0L))

    val stale: Signal[Option[StaleReason]] = section.map(_.flatMap(staleReason))

    val fetchedAt: Signal[Option[Instant]] = section.map(_.flatMap(fetchedAtOf))

    /** Enabled only while the data is current. See the class comment for why. */
    val refreshEnabled: Signal[Boolean] = section.map(_.exists(_.isInstanceOf[Section.Ok[?]]))

    // --- Writing the state back ------------------------------------------------------------------

    def setParams(updates: Map[String, Option[String]]): Unit = UrlParams.set(updates)

    /** Any change to what is being searched for resets the page.
      *
      * Without it, typing a term while on page 9 asks for page 9 of a list that now has one page, and the
      * screen goes blank for a search that matched plenty.
      */
    def setFilter(updates: Map[String, Option[String]]): Unit =
      setParams(updates + (TopicQueryCodecs.PageParam -> None))

    val toUrl: Observer[Option[Sort[String]]] = Observer { chosen =>
      setFilter(Map(TopicQueryCodecs.SortParam -> chosen.flatMap(renderSort)))
    }

    val table = VirtualizedTable[TopicRow](
      rows = rows,
      columns = TopicColumns.all(
        cluster = cluster,
        largestMessageCount = largestMessageCount,
        largestSize = largestSize,
        hrefFor = hrefFor,
        onOpen = navigate,
        onToggleFavourite = name => favourites.toggle(cluster.value, name)
      ),
      rowKey = _.name,
      sort = tableSort,
      emptyState = () => EmptyState(Messages.EmptyTitle, description = Some(Messages.EmptyDescription)),
      viewportHeight = tableViewportHeight,
      testId = Some("topics-table")
    )

    div(
      cls := TopicsCss.Page,
      dataAttr("testid") := "page-topics-list",
      div(
        cls := TopicsCss.Heading,
        h1(Messages.Title),
        // Beside the heading rather than in the control bar below: the bar is about narrowing what is on
        // screen, and creating a topic is not a filter.
        CreateTopicDialog(
          create = request => queries.createTopic(cluster, request),
          // Straight to the new topic. The list is invalidated by the call itself, but a list is not what
          // somebody who has just created a topic wants to look at — and landing on the topic is also the
          // proof that it exists.
          onCreated = created => navigate(cluster, created.value)
        )
      ),
      // The URL is authoritative, so the table's sort is pushed *into* it whenever the URL changes, and the
      // table's own writes are turned back into URL updates. Both directions pass through here.
      sort --> { current => tableSort.set(current.map(s => Sort(s.field.wire, s.order))) },
      tableSort.signal.changes --> toUrl,
      TopicListControls(
        query = query,
        onQuery = raw => setFilter(Map(TopicQueryCodecs.QParam -> Option(raw.trim).filter(_.nonEmpty))),
        mode = mode,
        onMode = chosen =>
          setFilter(
            Map(
              TopicQueryCodecs.ModeParam ->
                Option.when(chosen != SearchMode.Default)(chosen.wire)
            )
          ),
        showInternal = showInternal,
        onShowInternal = on => {
          rememberShowInternal(store, cluster, on)
          setFilter(Map(TopicQueryCodecs.ShowInternalParam -> Some(on.toString)))
        },
        total = total,
        refreshEnabled = refreshEnabled,
        onRefresh = () => queries.invalidateCluster(cluster)
      ),
      // An unavailable section with nothing held is an explanation with a retry, never an empty table: a
      // table with no rows in it is a claim that the cluster has no topics.
      child.maybe <-- section.map(_.flatMap(refusal(() => queries.invalidateCluster(cluster)))),
      child.maybe <-- section.map(current =>
        Option.when(current.exists(_.toOption.isDefined))(
          StaleDataOverlay(
            content = table,
            stale = stale,
            fetchedAt = fetchedAt,
            zone = zone,
            now = now,
            testId = Some("topics-table-region")
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
            onPage = wanted => setParams(Map(TopicQueryCodecs.PageParam -> Some(wanted.toString))),
            onPageSize = wanted => setFilter(Map(TopicQueryCodecs.PageSizeParam -> Some(wanted.toString))),
            testId = Some("topics-pagination")
          )
        )
    )
  }

  /** `sort=<field>:<direction>`, the contract's own spelling, parsed leniently.
    *
    * An unrecognised field is *no sort* rather than a default one. The server refuses an unknown field with a
    * 400, so sending one on the user's behalf because their bookmark named a column that has since gone would
    * turn a stale link into an error page.
    */
  private[list] def parseSort(raw: String): Option[Sort[TopicSortField]] =
    raw.split(':') match {
      case Array(field, direction) =>
        for {
          parsedField <- TopicSortField.fromWire(field)
          parsedOrder <- SortOrder.fromWire(direction)
        } yield Sort(parsedField, parsedOrder)
      case _ => None
    }

  /** The inverse. `None` for a column that is not a sort field — the star column — so that clicking one could
    * never put an unknown field into the URL.
    */
  private[list] def renderSort(sort: Sort[String]): Option[String] =
    TopicSortField.fromWire(sort.field).map(field => s"${field.wire}:${sort.order.wire}")

  private def storedShowInternal(store: PreferenceStore, cluster: ClusterId): Boolean =
    store.read(showInternalKey(cluster)).contains("true")

  private def rememberShowInternal(store: PreferenceStore, cluster: ClusterId, on: Boolean): Unit =
    store.write(showInternalKey(cluster), on.toString)

  /** Per cluster, because "hide internal topics" is a statement about a cluster's noise, and a user watching
    * a busy production cluster and a quiet test one may reasonably want different answers.
    */
  private def showInternalKey(cluster: ClusterId): String = s"kui.topics.showInternal.${cluster.value}"

  private def fetchedAtOf(section: Section[PageDto[TopicRowDto]]): Option[Instant] =
    section match {
      case Section.Ok(_, at) => Some(at)
      case Section.Stale(_, at, _) => Some(at)
      case _ => None
    }

  /** Why the rows on screen are not current, when they are not.
    *
    * `Unavailable` while previous rows are held counts as stale: the rows *are* old, and the overlay is what
    * says so. Without this, a service that went down would leave the last good rows on screen looking
    * perfectly current.
    */
  private def staleReason(section: Section[PageDto[TopicRowDto]]): Option[StaleReason] =
    section match {
      case Section.Stale(_, _, reason) =>
        Some(StaleReason(Messages.StaleState, Some(reason.sentence), code = Some(reason.wire)))
      case Section.Unavailable(_, message, _) => Some(StaleReason.unavailable(message))
      case _ => None
    }

  /** The rendering for a section that has no rows to show at all, and nothing held from before.
    *
    * `Forbidden` is not an error and must not be drawn as one (ADR-032): the user's request worked, and the
    * answer is that they may not see this. An error region with a "Try again" would invite them to press a
    * button that will do exactly the same thing.
    */
  private def refusal(retry: () => Unit)(section: Section[PageDto[TopicRowDto]]): Option[HtmlElement] =
    section match {
      case Section.Forbidden =>
        Some(
          EmptyState(
            Messages.ForbiddenTitle,
            description = Some(Messages.ForbiddenDescription),
            testId = Some("topics-forbidden")
          )
        )
      case Section.Unavailable(reason, message, _) =>
        Some(
          div(
            cls := TopicsCss.Error,
            dataAttr("testid") := "topics-error",
            role := "alert",
            p(Messages.unavailable(reason.wire, message)),
            Button(
              label = Val(Messages.TryAgain),
              onClick = Observer[Unit](_ => retry()),
              variant = ButtonVariant.Primary,
              testId = Some("topics-retry")
            )
          )
        )
      case Section.NotConfigured =>
        // Nothing at all. A permanent "unavailable" panel for a service this deployment does not have would
        // train an operator to ignore the colour that matters (DEVPLAN §10 D10).
        None
      case _ => None
    }
}
