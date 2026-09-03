package kui.ui.shell.nav

import com.raquo.laminar.api.L.*

import kui.ui.kernel.feature.FeatureRoutes
import kui.ui.kernel.state.FeatureState
import kui.ui.kernel.state.FeatureState.*

/** Which entries the sidebar holds, given what every feature's service is currently doing (ADR-032).
  *
  * ## Why this is a list and not a component
  *
  * Deciding *which* entries exist and drawing them are two different jobs with two different failure modes.
  * Deciding is a pure function of capability states, and the interesting cases — a hidden entry, a forbidden
  * one, one that must not move when its state changes — are all decided here and can be checked without a
  * DOM. Drawing is `Sidebar`'s, and the ADR-032 rendering rules live there next to the elements they apply
  * to. Merging the two would give one component with both sets of edge cases and no way to test either
  * without the other.
  *
  * ## Order is fixed, and that is a correctness property rather than a nicety
  *
  * Entries are sorted by their declared order and never by anything that changes at run time. A sidebar whose
  * entries reshuffle when a service goes down is one where the user clicks the wrong thing: they aim at the
  * position their muscle memory learned, and something else has moved into it. So a feature going unavailable
  * changes how its entry *looks* and never where it *is*.
  */
object Navigation {

  /** The shell's own entries.
    *
    * They have no service behind them — they are the frame, not a feature — so they are always `Ready`. The
    * orders leave a wide gap in the middle for features, and put the two development-and-preferences entries
    * at the bottom where a user looks for them.
    */
  val shellItems: List[NavItem] = List(
    NavItem("Dashboard", kui.ui.shell.ShellPage.Home, "nav-home", order = 0),
    NavItem("Components", kui.ui.shell.ShellPage.Gallery, "nav-gallery", order = 9000),
    NavItem("Settings", kui.ui.shell.ShellPage.Settings, "nav-settings", order = 9100)
  )

  /** The whole navigation: the shell's entries and every visible feature's, in order.
    *
    * @param features
    *   each feature's static registration paired with its live state. The registration supplies the label,
    *   the order and where the entry points, all of which are known before the feature has been downloaded;
    *   the signal supplies everything that changes.
    * @param hideForbidden
    *   the `kui.ui.hideForbidden` switch of ADR-032. Some organisations consider the existence of a feature
    *   sensitive; most find a visible-but-disabled entry more helpful than a menu that changes shape per
    *   user, so this is off by default.
    */
  def items(
      features: List[(FeatureRoutes, Signal[FeatureState])],
      hideForbidden: Boolean = false
  ): Signal[List[NavItem]] =
    if features.isEmpty then Val(shellItems.sortBy(_.order))
    else
      Signal
        .combineSeq(features.map((registration, state) => state.map(registration -> _)))
        .map { pairs =>
          val visible = pairs.toList.collect {
            case (registration, state) if !state.isHidden(hideForbidden) =>
              NavItem(
                label = registration.nav.label,
                page = registration.landing,
                testId = s"nav-${registration.id.value}",
                state = state,
                order = registration.nav.order
              )
          }

          (shellItems ++ visible).sortBy(_.order)
        }
}
