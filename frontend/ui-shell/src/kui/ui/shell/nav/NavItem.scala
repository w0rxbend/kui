package kui.ui.shell.nav

import kui.ui.kernel.feature.Page
import kui.ui.kernel.state.FeatureState

/** One entry in the navigation, with everything needed to draw it.
  *
  * @param page
  *   where it goes. A `Page` and not a URL, so that the router builds the address — which means changing a
  *   route pattern cannot leave a stale link behind.
  * @param testId
  *   also the identity used to match an entry to its element across a re-render, so it must be unique and
  *   stable. Two entries sharing one would make Laminar reuse the wrong element.
  * @param state
  *   what the shell knows about the feature behind this entry, right now. `Ready` for the shell's own
  *   entries, which have no service behind them and therefore cannot be unavailable.
  * @param order
  *   where it sits. Explicit rather than "the order they were registered in", because registration order is a
  *   fact about the code and sidebar order is a product decision. It is also what keeps an entry from jumping
  *   when its state changes.
  */
final case class NavItem(
    label: String,
    page: Page,
    testId: String,
    state: FeatureState = FeatureState.Ready,
    order: Int = 0
)
