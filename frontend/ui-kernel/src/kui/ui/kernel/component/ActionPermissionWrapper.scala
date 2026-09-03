package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.state.FeatureState

/** One wrapper for the two independent reasons a write action can be unavailable (ADR-032).
  *
  * ## Why the two reasons share one wrapper
  *
  * "You may not do this" (RBAC) and "this cannot be done right now" (the service is down) are decided by
  * completely different parts of the system, and both can be true at once. The tempting implementation is two
  * wrappers, one per reason, nested. It produces two tooltips on the same button — the user hovers, reads
  * one, and never learns about the other — and it produces a button whose disabled-ness is decided in two
  * places, which means the day one of them is wrong nobody can tell which.
  *
  * So there is one wrapper, it takes both inputs, and it renders **one** tooltip listing every reason that
  * currently applies. A user who lacks permission *and* whose cluster service is down sees both sentences and
  * knows that fixing the outage will not be enough.
  *
  * ## What is live in M0
  *
  * Only the capability half. Roles arrive in M6, so `permitted` is `Val(true)` at every call site today. The
  * parameter and its branch exist and are tested now so that M6 is a change at the call sites and not a
  * redesign here — the same arrangement `FeatureState.derive` already uses for the same reason.
  *
  * @param action
  *   the control being gated. It is amended in place rather than copied, so a caller keeps the reference it
  *   built and can still bind to it.
  * @param capability
  *   the feature's current state. Anything but `Ready` and `Degraded` blocks the action.
  * @param permitted
  *   the RBAC decision. `false` blocks the action.
  * @param capabilityMessage
  *   what to say about a given capability state, or `None` for "this state does not block anything". A
  *   parameter so a screen can be specific — "you cannot delete a topic while the cluster service is down"
  *   reads better than the default sentence — without every screen having to reimplement the merge.
  */
object ActionPermissionWrapper {

  /** What is said when the RBAC decision is `false`.
    *
    * Deliberately vague about *what* is not permitted. Naming the resource would let a user who cannot see it
    * confirm that it exists, which is the same information leak `FeatureState`'s `Forbidden`-outranks-health
    * rule exists to close.
    */
  val NotPermittedMessage: String = "You do not have permission to do this."

  /** The default sentence per capability state.
    *
    * `Degraded` deliberately does not block: a slow service is still a working service, and disabling every
    * write during a spell of high latency would take the product away from the user at exactly the moment
    * they are trying to fix something.
    */
  def defaultCapabilityMessage(state: FeatureState): Option[String] =
    state match {
      case FeatureState.Ready | FeatureState.Degraded(_) => None
      case FeatureState.Unavailable(_, message, _) => Some(message)
      case FeatureState.Forbidden => Some(NotPermittedMessage)
      case FeatureState.NotConfigured =>
        Some("This is not configured in this deployment, so there is nothing to act on.")
    }

  def apply(
      action: HtmlElement,
      capability: Signal[FeatureState],
      permitted: Signal[Boolean] = Val(true),
      capabilityMessage: FeatureState => Option[String] = defaultCapabilityMessage,
      testId: Option[String] = None
  ): HtmlElement = {
    val tooltipId = Components.nextId("kui-action-reason")
    val hovered = Var(false)

    // Every reason that currently applies, in a fixed order so the sentence does not reshuffle as
    // states change underneath the user's cursor.
    val reasons: Signal[List[String]] =
      permitted
        .combineWith(capability)
        .map { (isPermitted, state) =>
          List(
            Option.when(!isPermitted)(NotPermittedMessage),
            capabilityMessage(state)
          ).flatten.distinct
        }

    val blocked: Signal[Boolean] = reasons.map(_.nonEmpty)

    span(
      cls := KernelCss.TooltipHost,
      cls := KernelCss.ActionGate,
      Components.testIdAttr(testId),
      action.amend(
        // `disabled` stops a `<button>` from firing at all; `aria-disabled` is what a screen reader
        // announces, and it is also the only signal on a control that is not a form element. Both,
        // because either one alone leaves a class of users or a class of elements unhandled.
        L.disabled <-- blocked,
        aria.disabled <-- blocked,
        aria.describedBy <-- blocked.map(isBlocked => if isBlocked then tooltipId else ""),
        onMouseEnter.mapTo(true) --> hovered,
        onMouseLeave.mapTo(false) --> hovered,
        onFocus.mapTo(true) --> hovered,
        onBlur.mapTo(false) --> hovered,
        onKeyDown.filter(_.key == "Escape").mapTo(false) --> hovered
      ),
      // Always in the document, hidden rather than removed: `aria-describedby` has to point at an
      // element that exists, or some screen readers announce nothing at all.
      span(
        idAttr := tooltipId,
        cls := KernelCss.Tooltip,
        cls := KernelCss.TooltipTop,
        role := "tooltip",
        hidden <-- hovered.signal
          .combineWith(blocked)
          .map((isHovered, isBlocked) => !(isHovered && isBlocked)),
        // One tooltip, every reason. Joined with a space so a screen reader reads it as prose rather
        // than as a list with no separators.
        text <-- reasons.map(_.mkString(" "))
      )
    )
  }
}
