package kui.ui.kernel.feature

import com.raquo.laminar.api.L.*

/** A place on one feature's page where another feature can put something.
  *
  * ## The problem this solves
  *
  * The topic page wants a "Consumers" tab, and the consumers feature is the only code that knows how to draw
  * one. The obvious implementation — the topics feature importing the consumers feature — is exactly what
  * ADR-012 forbids, and for a concrete reason: it would make every visit to a topic page download the
  * consumers feature, for every user, including users who have no permission to see consumer groups and
  * deployments where the consumers service is not configured at all.
  *
  * So the dependency is inverted. The host declares a named slot; contributing features register a
  * `PanelContribution` for `(host, slot)`; and the host renders whatever turns up without ever naming it.
  *
  * ## Panels never cause a download
  *
  * This renders from the *loaded* features only. A feature that has not been downloaded contributes nothing
  * and is not fetched — the host page is not a reason to load another feature, and a naive implementation
  * that asked the registry for "whoever contributes to this slot" would make every topic page pull in the
  * consumers module.
  *
  * The consequence is worth stating plainly: a panel appears once its feature is loaded for some other reason
  * (the user visited it, or the shell preloaded it because its capability is available), and until then the
  * slot is simply empty. That is the intended behaviour, not a limitation to work around.
  */
object FeaturePanel {

  /** Everything registered for one slot, in a stable order.
    *
    * Ordered by the contributing feature's nav order so that two panels in one slot do not swap places
    * depending on which module happened to finish downloading first.
    */
  def contributions(
      features: Signal[List[KuiFeature]],
      host: FeatureId,
      slot: String
  ): Signal[List[PanelContribution]] =
    features.map(
      _.flatMap(feature => feature.panels.filter(panel => panel.host == host && panel.slot == slot))
    )

  /** Renders the slot. An empty slot renders an empty element and never throws. */
  def apply(
      features: Signal[List[KuiFeature]],
      host: FeatureId,
      slot: String,
      context: PanelContext
  ): HtmlElement =
    div(
      dataAttr("kui-slot") := slot,
      children <-- contributions(features, host, slot).map(_.map(_.render(context)))
    )
}
