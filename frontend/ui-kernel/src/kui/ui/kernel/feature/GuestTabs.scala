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
  * itself; a guest registers a `PanelContribution` carrying a `tabLabel`, and appears.
  *
  * ## Ordering, and why it is not registration order
  *
  * The host's own tabs come first, then the guests in their features' sidebar order. Sidebar order is a
  * product decision; the order two modules happened to finish downloading in is not, and using it would make
  * the tab strip lay itself out differently on a slow connection than on a fast one.
  *
  * ## A guest never causes a download
  *
  * The tab list is derived from the *loaded* features and from nothing else. A feature that has not been
  * downloaded contributes no tab and is not fetched — a host page is never a reason to load another feature.
  * A guest's tab therefore appears once its feature has loaded for some other reason (the user visited it, or
  * the shell preloaded it because its capability is available), and until then the strip is one tab shorter.
  * That is the intended behaviour and not a limitation to work around; it is stated here because it is the
  * first question anyone asks when a tab they registered is not on screen.
  */
object GuestTabs {

  /** The tabs registered by loaded features against one slot of one host's page.
    *
    * A contribution with no `tabLabel` is not a tab and is skipped: the same slot mechanism carries stacked
    * panels elsewhere, and a nameless tab would render as a blank strip entry.
    */
  def of(
      features: Signal[List[KuiFeature]],
      host: FeatureId,
      slot: String,
      context: PanelContext
  ): Signal[List[Tab]] =
    features.map(_.flatMap { feature =>
      feature.panels.collect {
        case panel if panel.host == host && panel.slot == slot && panel.tabLabel.isDefined =>
          Tab(
            // The contributing feature's id, so that a tab is addressable in a URL as `?tab=messages`
            // and stays addressable when the guest's rendering changes.
            id = feature.id.value,
            label = panel.tabLabel.getOrElse(feature.nav.label),
            // A thunk, so the guest's panel is built when its tab is opened and not before. A
            // Consumers tab issues requests when it is created; building every panel up front would
            // fire four screens' worth of traffic for a user who looks at one.
            body = () => panel.render(context)
          )
      }
    })

  /** A host's own tabs followed by its guests'. The one call a host page makes. */
  def merged(
      own: Signal[List[Tab]],
      features: Signal[List[KuiFeature]],
      host: FeatureId,
      slot: String,
      context: PanelContext
  ): Signal[List[Tab]] =
    own.combineWith(of(features, host, slot, context)).map(_ ++ _)
}
