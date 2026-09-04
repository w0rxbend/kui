package kui.ui.topics.detail

import java.time.Instant

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.topic.TopicDetailDto
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.error.ErrorCode
import kui.kernel.{ClusterId, TopicName}
import kui.message.contract.BrowseAddress
import kui.security.rbac.{Action, Resource}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.feature.{FeatureId, FeatureSlots, GuestTabs, PanelContext}
import kui.ui.kernel.state.{PermissionStore, Permissions}
import kui.ui.topics.admin.{EditSettingDialog, TopicAdminPanel}
import kui.ui.topics.{Messages, TopicTab, TopicsCss, TopicsQueries}

/** One topic: what it is made of, how its partitions are laid out, and every setting it carries.
  *
  * ## One request fills the page
  *
  * The screen reads the gateway's `overview`, which returns the topic *and* four sections belonging to
  * services that do not exist yet. That is deliberate: this page gains its Consumers tab in M4 by
  * registration rather than by redesign, and the milestone that adds a tab should be adding a tab, not
  * rewriting a page's data flow.
  *
  * The Settings tab is the exception, and reads its own endpoint. The tab is in the URL, so its query is not
  * issued at all until somebody opens it — which is worth a second request, because most visits to a topic
  * never open it.
  *
  * ## The absent sections render nothing
  *
  * Four of the overview's five sections are `NotConfigured` in every M2 deployment, and `NotConfigured` is
  * hidden (ADR-032, DEVPLAN §10 D10). Four permanent "unavailable" panels on every topic page would train an
  * operator to ignore the colour that matters, including on the day one of them means something.
  *
  * What *is* rendered is an empty container per section, each with a stable `data-testid`, so that M4's
  * registration is visible in a test the moment it lands rather than being asserted-to-exist-later.
  *
  * ## What is deliberately absent
  *
  * No Messages tab and no Produce (M3). No Consumers, ACLs or Connectors tab of this page's own making —
  * those are guests, and this page builds the slot they register into. No Edit settings, no danger zone, no
  * actions dropdown, no statistics tab (M5). And none of them rendered disabled: a tab that promises a
  * milestone is a promise with a date on it (DEVPLAN §10 D13).
  *
  * @param onTab
  *   how a tab click reaches the URL, passed in rather than reached for, so the page is drivable from a suite
  *   with no router.
  * @param features
  *   the features that are *loaded*. A guest that has not been downloaded contributes no tab and is not
  *   fetched: a host page is never a reason to load another feature.
  */
object TopicDetailPage {

  def apply(
      cluster: ClusterId,
      topic: TopicName,
      tab: Signal[TopicTab],
      queries: TopicsQueries,
      onTab: TopicTab => Unit,
      zone: Signal[String],
      backHref: String,
      now: () => Instant = () => Instant.now(),
      partitionViewportHeight: Var[Int] = Var(0),
      /** What this session is allowed to do. The application's one store by default; a suite passes its own,
        * so a test can build a user who may delete and a user who may not without touching global state.
        */
      permissions: Permissions = PermissionStore.current
  ): HtmlElement = {

    val key = (cluster, topic)

    // One subscription, read twice. `QueryCache.state` acquires its entry per subscriber, so asking for it
    // in two places issues the request twice — two identical calls on every visit to a topic, invisible from
    // the screen and obvious in a proxy log.
    val overviewState = queries.overview.state(key)

    val overview: Signal[Option[TopicOverviewDto]] = overviewState.map(_.lastGood)

    val topicSection: Signal[Option[Section[TopicDetailDto]]] = overview.map(_.map(_.topic))

    val detail: Signal[Option[TopicDetailDto]] = topicSection.map(_.flatMap(_.toOption))

    val notFound: Signal[Boolean] =
      overviewState.map(_.outcome.exists(_.left.exists(isNotFound)))

    /** The selected tab as `Tabs` wants it: an id, in a `Var` it can write.
      *
      * The URL is authoritative in both directions — the signal below pushes into this `Var`, and this
      * `Var`'s own writes are turned back into `onTab`. Two directions through one place, so the strip and
      * the address bar cannot hold different opinions about which tab is open.
      */
    val selected: Var[String] = Var(TopicTab.Default.id)

    /** Whether this page's topic has been deleted from under it.
      *
      * The page has to know, because everything else about it becomes wrong at once: the overview refetches
      * and answers 404, and the "no such topic" screen that would produce is a worse answer than the receipt
      * saying what was just destroyed. So the deletion receipt wins, and the not-found screen is suppressed
      * while it is on show.
      */
    val deleted: Var[Boolean] = Var(false)

    /** Which setting the edit dialog is holding. The dialog is built once, here, rather than inside the
      * Settings tab's thunk: a dialog rebuilt every time the tab is opened would lose a save in flight.
      */
    val editing: Var[(String, Option[String])] = Var(("", None))
    val editOpen: Var[Boolean] = Var(false)

    /** Built once, for the reason `ResetWizard` records: this element holds the operator's only record of an
      * irreversible action, and an element rebuilt from each new snapshot is one that can be replaced in the
      * same instant its receipt arrives.
      */
    val adminPanel: HtmlElement = TopicAdminPanel(
      topic = topic,
      partitionCount = detail.map(_.map(_.row.partitionCount)),
      planPartitions = count => queries.planPartitions(cluster, topic, count),
      applyPartitions = token => queries.increasePartitions(cluster, topic, token),
      planDeletion = () => queries.planDeletion(cluster, topic),
      applyDeletion = token => queries.deleteTopic(cluster, topic, token),
      planPurge = () => queries.planPurge(cluster, topic),
      applyPurge = token => queries.purge(cluster, topic, token),
      onDeleted = () => deleted.set(true),
      // E4's worked example. Every other write control in the product — create, alter, produce, resend,
      // purge, offset reset, group delete — gates the same way: one call to the store, one `Action`, and
      // the wrapper merges the answer with the capability state.
      deletePermitted = permissions.allows(cluster, Resource.Topic, topic.value, Action.TopicDelete)
    )

    val ownTabs: Signal[List[Tab]] =
      // A thunk per tab, so the Settings tab's query is not issued until somebody opens it. That laziness is
      // `Tabs`' own, and it is the reason the tab is in the URL rather than in a local `Var`.
      Val(
        List(
          Tab(
            TopicTab.Overview.id,
            Messages.TabOverview,
            () => overviewTab(detail, topicSection, partitionViewportHeight)
          ),
          Tab(
            TopicTab.Settings.id,
            Messages.TabSettings,
            () =>
              SettingsTab(
                queries.config.state(key).map(_.lastGood.map(_.config)),
                onEdit = Some { (name, value) =>
                  editing.set((name, value))
                  editOpen.set(true)
                },
                onAdd = Some { () =>
                  editing.set(("", None))
                  editOpen.set(true)
                }
              )
          )
        )
      )

    val guestContext = PanelContext(Some(cluster.value), Map(FeatureSlots.TopicParam -> topic.value))

    val tabs: Signal[List[Tab]] =
      // The strip is built from the *static* registrations, so it is the same on a fresh page load as it is
      // after a detour through another feature. Opening a guest's tab is what downloads that guest.
      GuestTabs.merged(ownTabs, FeatureId.Topics, FeatureSlots.TopicTabs, guestContext)

    div(
      cls := TopicsCss.Page,
      dataAttr("testid") := "page-topic-detail",
      // The heading and the breadcrumbs stay whatever the body turns out to be, so a user looking at an
      // error still knows which topic they were looking at.
      Breadcrumbs(Val(List(Crumb(Messages.Title, Some(backHref)), Crumb(topic.value, None)))),
      h1(dataAttr("testid") := "topic-heading", topic.value),
      // The one way into the message browser.
      //
      // The browser is a separate microfrontend that is downloaded only when its route is opened, and its
      // route needs a topic — so it has no sidebar entry it could point at, and until this link existed the
      // screen could be reached only by typing its address. A plain `a` and not a router link because the
      // destination belongs to another feature: naming that feature's `Page` class here would pull its whole
      // module into `main.js` for every user, which is exactly what `checkBundleShape` forbids. The URL is
      // built from `BrowseAddress`, the cross-compiled constants the message service's contract publishes
      // for this purpose, so a renamed segment breaks the build rather than the link.
      a(
        cls := TopicsCss.BrowseLink,
        // Hidden once the topic has been deleted from this page: the browser it points at would open on a
        // topic that no longer exists, and a link that is certain to fail is worse than no link.
        cls(TopicsCss.Hidden) <-- deleted.signal,
        dataAttr("testid") := "topic-browse-messages",
        href := browseHref(backHref, cluster, topic),
        Messages.BrowseMessages
      ),
      tab --> { current => selected.set(current.id) },
      // Only a *real* change is reported. `Tabs` writes its selection on mount and again whenever the URL
      // pushes one in, and reporting those back would push a history entry for a navigation nobody made —
      // the Back button would then need two presses to leave a page the user had opened once. Comparing
      // against the tab the URL currently names is what tells the two apart; `distinct` alone cannot,
      // because the first event through a stream always passes it.
      // Any id the strip can select is reported, not only the page's own two. A guest's tab carries its
      // feature's id, and filtering to a closed list here is what used to leave the address bar saying
      // Overview while the Consumers tab was on screen.
      selected.signal.changes
        .withCurrentValueOf(tab)
        .collect { case (id, current) if id != current.id => id } --> { id => onTab(TopicTab(id)) },
      child.maybe <-- Signal
        .combine(notFound, deleted.signal)
        .map((missingTopic, wasDeleted) => Option.when(missingTopic && !wasDeleted)(missing(topic))),
      child.maybe <-- Signal
        .combine(notFound, topicSection, deleted.signal)
        .map((missingTopic, section, wasDeleted) =>
          Option.when(!missingTopic && !wasDeleted)(
            section.flatMap(refusal).getOrElse(body(detail, topicSection, tabs, selected, zone, now))
          )
        ),
      EditSettingDialog(
        open = editOpen,
        editing = editing,
        update = request => queries.updateConfig(cluster, topic, request)
      ),
      // The irreversible changes, last on the page and after the tabs, because that is where a reader
      // expects to find the controls they should meet deliberately rather than by accident.
      adminPanel,
      // KU-013's slots. Empty in M2, and present so that a registration is visible the moment it lands.
      div(
        cls := TopicsCss.Panels,
        TopicOverviewDto.sections
          .filterNot(_ == TopicOverviewDto.TopicSection)
          .map(section => div(cls := TopicsCss.Panel, dataAttr("testid") := slotTestId(section)))
      )
    )
  }

  /** The message browser's address for one topic.
    *
    * Derived from `backHref` — the topic list's own URL, which the shell handed in already carrying the
    * deployment prefix — rather than from a `/ui` literal, so a KUI mounted under `/kafka/ui` links
    * correctly.
    */
  private[detail] def browseHref(backHref: String, cluster: ClusterId, topic: TopicName): String = {
    val root = backHref
      .stripSuffix("/")
      .stripSuffix(s"/${BrowseAddress.ClustersSegment}/${cluster.value}/${BrowseAddress.TopicsSegment}")
    s"$root/${BrowseAddress.ClustersSegment}/${cluster.value}/${BrowseAddress.TopicsSegment}/" +
      s"${topic.value}/${BrowseAddress.MessagesSegment}"
  }

  /** `topic-panel-consumer-groups` from `consumerGroups`. The section names are the gateway contract's; the
    * hyphenated form is what a `data-testid` reads as.
    */
  private[detail] def slotTestId(section: String): String =
    s"topic-panel-${section.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase}"

  private def body(
      detail: Signal[Option[TopicDetailDto]],
      section: Signal[Option[Section[TopicDetailDto]]],
      tabs: Signal[List[Tab]],
      selected: Var[String],
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement =
    StaleDataOverlay(
      content = div(
        TopicIndicators(detail.map(_.map(TopicIndicators.of).getOrElse(Nil))),
        Tabs(tabs, selected, testId = Some("topic-tabs"))
      ),
      stale = section.map(_.flatMap(staleReason)),
      fetchedAt = section.map(_.flatMap(fetchedAtOf)),
      zone = zone,
      now = now,
      testId = Some("topic-detail-region")
    )

  private def overviewTab(
      detail: Signal[Option[TopicDetailDto]],
      section: Signal[Option[Section[TopicDetailDto]]],
      partitionViewportHeight: Var[Int]
  ): HtmlElement =
    PartitionTable(
      detail.map(_.map(_.partitions).getOrElse(Nil)),
      partitionViewportHeight,
      // Staleness is read from the same section the badge above the table is drawn from, so the two
      // can never disagree about whether this page is showing live data.
      section.map(_.exists(staleReason(_).isDefined))
    )

  /** A topic that is not there is a different fact from a service that could not answer, and it gets a
    * different screen: a sentence naming the topic, and no retry, because trying again will not create it.
    */
  private def missing(topic: TopicName): HtmlElement =
    EmptyState(
      Messages.NoSuchTopicTitle,
      description = Some(Messages.noSuchTopic(topic.value)),
      testId = Some("topic-not-found")
    )

  private def refusal(section: Section[TopicDetailDto]): Option[HtmlElement] =
    section match {
      case Section.Forbidden =>
        Some(
          EmptyState(
            Messages.TopicForbiddenTitle,
            description = Some(Messages.TopicForbiddenDescription),
            testId = Some("topic-forbidden")
          )
        )
      case Section.Unavailable(reason, message, _) =>
        Some(
          div(
            cls := TopicsCss.Error,
            dataAttr("testid") := "topic-error",
            role := "alert",
            p(Messages.unavailable(reason.wire, message))
          )
        )
      case _ => None
    }

  private def fetchedAtOf(section: Section[TopicDetailDto]): Option[Instant] =
    section match {
      case Section.Ok(_, at) => Some(at)
      case Section.Stale(_, at, _) => Some(at)
      case _ => None
    }

  private def staleReason(section: Section[TopicDetailDto]): Option[StaleReason] =
    section match {
      case Section.Stale(_, _, reason) =>
        Some(StaleReason(Messages.StaleState, Some(reason.sentence), code = Some(reason.wire)))
      case _ => None
    }

  /** "This topic does not exist" means the overview answered with that code and no other.
    *
    * Read from the error *code* and not from a status: by the time a caller holds an `ApiError` the status is
    * gone, and ADR-034 makes the code the stable thing. A cluster that does not exist has its own code, and
    * it is deliberately not treated as a missing topic — the two send an operator to different places.
    *
    * Every other failure leaves whatever was last known on screen under the staleness overlay, because a
    * topic that was there a minute ago has not stopped existing because a request timed out.
    */
  private def isNotFound(error: ApiError): Boolean =
    error match {
      case ApiError.Envelope(code, _, _, _, _) => code == ErrorCode.TopicNotFound.wire
      case _ => false
    }
}
