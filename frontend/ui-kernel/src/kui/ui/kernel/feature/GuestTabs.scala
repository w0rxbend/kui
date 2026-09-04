package kui.ui.kernel.feature

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.Tab

/** The host half of a tab-shaped feature slot: a host's own tabs, plus one tab per registered guest.
  *
  * ## What this exists to make impossible
  *
  * The topic page has an Overview tab and a Settings tab of its own. M3 wants a Messages tab on it and M4
  * wants a Consumers tab, and neither milestone may edit a file in the topics feature — that is the whole
  * point of ADR-012's inversion. Left to itself, each would invent its own arrangement, and the two would
  * differ in ways nobody could see from inside either one.
  *
  * So the arrangement is written here, once, and both the host and every guest use it. The host asks for the
  * merged tab list and renders it with [[kui.ui.kernel.component.Tabs]] as if it had written every tab
  * itself; a guest declares a [[GuestTab]] in its static registration, and appears.
  *
  * ## The strip does not depend on what has been downloaded
  *
  * The tabs come from the **static** registrations — the same data the sidebar is drawn from, available on
  * first paint — and not from the features that happen to have loaded. That is a correction of how this
  * worked before, and the reason for it is what the old behaviour did to a user: the Consumers tab existed on
  * a topic page only if the consumers feature had already been downloaded for some other reason, so opening a
  * topic in a fresh browser tab showed Overview and Settings, and opening the same topic after a detour
  * through Consumers showed Overview, Settings and Consumers. Whether a screen exists is not something a
  * product may decide from the user's browsing history.
  *
  * ## Opening a tab is still the only thing that downloads a feature
  *
  * The heading is data — a few words in `main.js` — and the panel behind it is not. Nothing is fetched while
  * the strip is drawn; the guest's module is imported when its tab is first *opened*, and the tab shows a
  * loading state and then the panel. So the promise ADR-012 actually makes — that a user who never looks at
  * consumer groups never downloads the consumers feature — is kept, while a user who wants to look at them
  * can find out that they are there.
  *
  * ## Ordering, and why it is not registration order
  *
  * The host's own tabs come first, then the guests in their features' sidebar order. Sidebar order is a
  * product decision; the order two modules happened to finish downloading in is not, and using it would make
  * the tab strip lay itself out differently on a slow connection than on a fast one.
  */
object GuestTabs {

  /** How a guest's panel is produced once its tab is opened: the guest's id, the host and slot it is filling,
    * and the context the host handed over. [[lazyBody]] is the one the application uses.
    */
  type Body = (FeatureId, FeatureId, String, PanelContext) => HtmlElement

  /** The tabs registered against one slot of one host's page.
    *
    * @param statics
    *   the static registrations, in sidebar order. A parameter so a suite can supply its own; the application
    *   passes `FeatureRegistry.staticRoutes`, which is what the sidebar is drawn from.
    * @param body
    *   how a guest's panel is produced once its tab is opened. Also a parameter, because the kernel must not
    *   decide on its own that opening a tab starts a download — [[lazyBody]] is that decision, and it is
    *   named at the call site so it is visible there.
    */
  def of(
      statics: List[FeatureRoutes],
      host: FeatureId,
      slot: String,
      body: GuestTabs.Body,
      context: PanelContext
  ): List[Tab] =
    statics.flatMap(registration =>
      registration.guestTabs.collect {
        case guest if guest.host == host && guest.slot == slot =>
          Tab(
            // The contributing feature's id, so that a tab is addressable in a URL as `?tab=messages`
            // and stays addressable when the guest's rendering changes.
            id = registration.id.value,
            label = guest.label,
            // A thunk, so nothing happens until the tab is opened. That is what keeps the download on
            // the user's click rather than on the host page's first paint.
            body = () => body(registration.id, host, slot, context)
          )
      }
    )

  /** The ids of the tabs registered against one slot, without building any of them.
    *
    * A host page needs these separately from the tabs themselves, because its *router* has to recognise a
    * guest's tab in a URL and the router runs in `main.js`, before any guest module exists. The ids are the
    * contributing features' own ids, which is the same value [[of]] gives each `Tab`, so a URL segment and a
    * tab id cannot drift apart.
    */
  def idsOf(statics: List[FeatureRoutes], host: FeatureId, slot: String): List[String] =
    statics.flatMap(registration =>
      registration.guestTabs.collect {
        case guest if guest.host == host && guest.slot == slot => registration.id.value
      }
    )

  /** A host's own tabs followed by its guests'. The one call a host page makes. */
  def merged(
      own: Signal[List[Tab]],
      host: FeatureId,
      slot: String,
      context: PanelContext,
      statics: => List[FeatureRoutes] = FeatureRegistry.staticRoutes,
      body: GuestTabs.Body = lazyBody
  ): Signal[List[Tab]] =
    own.map(_ ++ of(statics, host, slot, body, context))

  /** One guest's panel, downloading the guest if this is the first time anybody has asked for it.
    *
    * The load is started when the element is *mounted*, not when this function is called, so a tab that is
    * built and never shown fetches nothing. Every state the import can be in has a rendering: a failed
    * download says so and offers a retry, because a dynamic import is an ordinary HTTP request made minutes
    * after the page loaded and it does fail.
    */
  def lazyBody(guest: FeatureId, host: FeatureId, slot: String, context: PanelContext): HtmlElement = {
    val feature = FeatureRegistry.lazyFeature(guest)

    div(
      dataAttr("kui-guest-tab") := guest.value,
      onMountCallback(_ => feature.load()),
      child <-- feature.state.map {
        case LoadState.NotLoaded | LoadState.Loading =>
          div(cls := "kui-guest-tab-loading", role := "status", "Loading…")

        case LoadState.Loaded(loaded) =>
          loaded.panels.find(panel => panel.host == host && panel.slot == slot) match {
            case Some(panel) => panel.render(context)
            // The feature loaded and contributes nothing here. That is a registration that has drifted —
            // a static tab with no dynamic panel behind it — and saying so beats an empty tab.
            case None =>
              div(role := "status", s"The ${guest.value} feature has nothing to show on this page.")
          }

        case LoadState.Failed(cause) =>
          div(
            role := "alert",
            p(s"This tab could not be loaded: $cause"),
            button(tpe := "button", "Try again", onClick --> { _ => feature.retry() })
          )
      }
    )
  }
}
