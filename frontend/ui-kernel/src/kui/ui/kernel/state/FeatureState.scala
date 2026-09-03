package kui.ui.kernel.state

import java.time.Instant

import kui.contracts.capability.{CapabilityState, DegradedReason, ReasonCode}
import kui.ui.kernel.feature.UnavailableReason

/** What the shell shows for one feature, right now (ADR-032).
  *
  * Five states rather than a boolean, because "is it up" is not a question a user interface can act on. Each
  * one calls for something different on screen, and collapsing any two of them loses information the user
  * needs:
  *
  *   - `Ready` — the entry is normal and the page works.
  *   - `Degraded` — the entry gets an amber dot and the page works, with an inline banner. The reason is
  *     structured, so a lag or metrics screen can slow its polling down rather than making things worse.
  *   - `Unavailable` — the entry is dimmed and *still clickable*, and the route renders the feature's own
  *     fallback panel: what broke, since when, a working retry, and what still works. A disabled link has
  *     nowhere to put any of that, which is why ADR-032 amended the original plan.
  *   - `Forbidden` — the entry is shown disabled with "you do not have permission", or hidden entirely in
  *     deployments that consider the existence of a feature sensitive.
  *   - `NotConfigured` — the entry is hidden. This deployment has no schema registry on this cluster; that is
  *     not a failure and must not be rendered as one, or every operator goes hunting for an outage that does
  *     not exist.
  */
enum FeatureState {
  case Ready
  case Degraded(reason: DegradedReason)
  case Unavailable(reason: ReasonCode, message: String, since: Option[Instant])
  case Forbidden
  case NotConfigured
}

object FeatureState {

  /** The message shown while the gateway has not yet polled an upstream.
    *
    * A sentence rather than "unknown", because the user is being told the truth: the feature is usable and
    * its health has not been established yet.
    */
  val StartingMessage = "Checking this service — it has not been polled yet."

  /** ADR-032's rule, as one pure function.
    *
    * It is pure and total so that it can be tested as a table, one row per input combination, and so that
    * every dimmed sidebar entry and every fallback panel in the product is downstream of code that has been
    * checked exhaustively rather than of conditionals scattered across screens.
    *
    * Two rows deserve their reasoning in the code rather than only in the ADR:
    *
    *   - **`permitted = false` matches first, whatever the capability says** (ADR-032 amendment 1). A user
    *     who may not see the schema registry must not be able to learn from the sidebar whether it is up, how
    *     long it has been down, or what its upstream error said. `Forbidden` outranks every health state
    *     because the alternative leaks information.
    *   - **A capability nobody has reported yet is `Degraded(Starting)`, never `Unavailable`** (amendment 2).
    *     Between the gateway starting and its first readiness poll it has no information, and reporting
    *     `Unavailable` would be a claim it cannot support — it has not asked. Every operator who restarts the
    *     gateway would watch the whole sidebar go red for one polling interval, which trains people to ignore
    *     the colour that is supposed to matter.
    *
    * @param permitted
    *   the RBAC decision. Always `true` in M0, because roles arrive in M6; the parameter and the `Forbidden`
    *   branch exist and are tested now, so that M6 is a change at the call site and not a redesign here.
    */
  def derive(capability: Option[CapabilityState], permitted: Boolean): FeatureState =
    if !permitted then Forbidden
    else
      capability match {
        case None => Degraded(startingReason)
        case Some(CapabilityState.Available) => Ready
        case Some(CapabilityState.Degraded(reason)) => Degraded(reason)
        case Some(CapabilityState.Unavailable(reason, message, since)) =>
          Unavailable(reason, message, Some(since))
        case Some(CapabilityState.NotConfigured) => NotConfigured
      }

  /** The stand-in reason for "we have not looked yet". */
  def startingReason: DegradedReason =
    DegradedReason(
      code = ReasonCode.Starting,
      message = StartingMessage,
      suggestedPollIntervalMs = None,
      p95Ms = None
    )

  extension (state: FeatureState) {

    /** Whether clicking the sidebar entry leads anywhere.
      *
      * `Unavailable` is navigable on purpose: the page it leads to is the feature's fallback panel, which is
      * where the reason, the `since` and the retry live. Making it a dead link would leave the user with a
      * dimmed word and no way to find out anything.
      */
    def isNavigable: Boolean = state match {
      case Ready | Degraded(_) | Unavailable(_, _, _) => true
      case Forbidden | NotConfigured => false
    }

    /** Whether the entry is left out of the navigation entirely.
      *
      * `NotConfigured` always is. `Forbidden` is only when the deployment asks for it: some organisations
      * consider the existence of a feature sensitive, and most find a visible-but-disabled entry more helpful
      * than a menu that changes shape per user.
      *
      * @param hideForbidden
      *   the `kui.ui.hideForbidden` switch of ADR-032.
      */
    def isHidden(hideForbidden: Boolean = false): Boolean = state match {
      case NotConfigured => true
      case Forbidden => hideForbidden
      case Ready | Degraded(_) | Unavailable(_, _, _) => false
    }

    /** Whether the entry is drawn dimmed: reachable, but not currently working. */
    def isDimmed: Boolean = state match {
      case Unavailable(_, _, _) => true
      case Ready | Degraded(_) | Forbidden | NotConfigured => false
    }

    /** The kernel's own reason record, for `KuiFeature.unavailableView`.
      *
      * This is the whole translation layer between the wire and the bottom of the frontend, and it is
      * deliberately *here* rather than in `kui.ui.kernel.feature`. The kernel's primitives must not depend on
      * the shape of any service's response — if the capability DTO gains a field tomorrow, nothing in
      * `KuiFeature` has to change — so the DTO is named in this one file and nowhere below it.
      *
      * `None` for every state that is not `Unavailable`: a feature that is working has no reason to show.
      */
    def unavailableReason: Option[UnavailableReason] = state match {
      case Unavailable(reason, message, since) =>
        Some(UnavailableReason(code = reason.wire, message = message, since = since.map(_.toString)))
      case Ready | Degraded(_) | Forbidden | NotConfigured => None
    }
  }

  given CanEqual[FeatureState, FeatureState] = CanEqual.derived
}
