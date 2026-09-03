package kui.ui.shell.feature

import com.raquo.laminar.api.L.*

import kui.contracts.capability.ReasonCode
import kui.ui.kernel.component.Icon
import kui.ui.kernel.feature.{LazyFeature, LoadState, Page, UnavailableReason}
import kui.ui.kernel.state.FeatureState
import kui.ui.shell.{Messages, ShellCss}

/** What sits between a feature's route and the feature itself.
  *
  * ## The promise this component keeps
  *
  * ADR-012's central claim is that an unavailable or unconfigured feature is **never downloaded**. That is
  * not an optimisation — it is what stops a broken service from also costing every user a failed request and
  * a stalled route. The claim is only true if exactly one place decides when the import starts, and this is
  * that place: the import is triggered when the capability state is anything but `NotConfigured`,
  * `Unavailable` or `Forbidden`, and at no other time. Clicking a dimmed sidebar entry lands here and renders
  * the fallback panel without a byte being fetched.
  *
  * ## And never a blank frame
  *
  * Every combination of capability state and load state maps to something visible. The state machine has a
  * gap in it the moment one does not — the classic one being "the import is in flight", which without a
  * branch of its own renders as an empty content area for as long as the network takes. Users read a blank
  * page as a broken page, and reload, which throws away everything the application had. So `Loading` has a
  * named spinner: not "loading…" but "Loading Clusters…", because a user on a slow connection otherwise
  * cannot tell whether the thing they clicked is the thing that is loading.
  *
  * @param probe
  *   asks the gateway to re-check the service. Passed in from the shell because the kernel may not name a
  *   gateway endpoint, and the panel may not construct one.
  */
object FeatureGate {

  def apply(
      feature: LazyFeature,
      featureLabel: String,
      state: Signal[FeatureState],
      page: Signal[Page],
      probe: Observer[Unit],
      whatStillWorks: Signal[List[String]],
      retryInFlight: Signal[Boolean] = Val(false),
      retryError: Signal[Option[String]] = Val(None)
  ): Signal[HtmlElement] =
    state
      .combineWith(feature.state, page)
      .map { (capability, load, currentPage) =>
        capability match {
          case FeatureState.NotConfigured =>
            notice(
              featureLabel,
              "This is not configured in this deployment, so there is nothing to show here."
            )

          case FeatureState.Forbidden =>
            // No retry offered: a permission decision does not change because the user pressed a
            // button, and a button that cannot help is worse than no button.
            notice(featureLabel, Messages.notPermitted(featureLabel))

          case FeatureState.Unavailable(code, message, since) =>
            fallback(
              featureLabel,
              code,
              message,
              since,
              probe,
              whatStillWorks,
              // If the module already happens to be loaded — the user was using the feature when the
              // service died — the feature gets to add its own paragraph about what it can still do.
              featureContentOf(load, UnavailableReason(code.wire, message, since.map(_.toString)), probe),
              retryInFlight,
              retryError
            )

          case FeatureState.Ready | FeatureState.Degraded(_) =>
            // Idempotent: `LazyFeature.load` imports at most once however often it is called, which is
            // what makes it safe to trigger from a mapping that re-runs on every capability change.
            load match {
              case LoadState.NotLoaded =>
                feature.load()
                spinner(featureLabel)
              case LoadState.Loading => spinner(featureLabel)
              case LoadState.Loaded(loaded) => loaded.render(currentPage)
              case LoadState.Failed(cause) =>
                // A failed import is a network failure, not an unhealthy service, so the retry re-runs
                // the *import* rather than probing the gateway.
                fallback(
                  featureLabel,
                  ReasonCode.Unknown,
                  s"${Messages.moduleFailed(featureLabel)} ($cause)",
                  None,
                  Observer[Unit](_ => feature.retry()),
                  whatStillWorks,
                  None,
                  Val(false),
                  Val(None)
                )
            }
        }
      }

  private def featureContentOf(
      load: LoadState[kui.ui.kernel.feature.KuiFeature],
      reason: UnavailableReason,
      probe: Observer[Unit]
  ): Option[HtmlElement] =
    load match {
      case LoadState.Loaded(loaded) => Some(loaded.unavailableView(reason, probe))
      case LoadState.NotLoaded | LoadState.Loading | LoadState.Failed(_) => None
    }

  private def fallback(
      featureLabel: String,
      code: ReasonCode,
      message: String,
      since: Option[java.time.Instant],
      retry: Observer[Unit],
      whatStillWorks: Signal[List[String]],
      featureContent: Option[HtmlElement],
      retryInFlight: Signal[Boolean],
      retryError: Signal[Option[String]]
  ): HtmlElement =
    FeatureFallbackPanel(
      featureLabel = featureLabel,
      reason = code,
      message = message,
      since = since,
      retry = retry,
      whatStillWorks = whatStillWorks,
      featureContent = featureContent,
      retryInFlight = retryInFlight,
      retryError = retryError
    )

  private def spinner(featureLabel: String): HtmlElement =
    div(
      cls := ShellCss.FeatureLoading,
      dataAttr("testid") := "feature-loading",
      // `role="status"` announces the label once it appears, without stealing focus from wherever
      // the user was.
      role := "status",
      aria.live := "polite",
      span(cls := ShellCss.FeatureLoadingIcon, aria.hidden := true, Icon.spinner),
      span(cls := ShellCss.FeatureLoadingLabel, Messages.loading(featureLabel))
    )

  private def notice(featureLabel: String, message: String): HtmlElement =
    sectionTag(
      cls := ShellCss.Fallback,
      dataAttr("testid") := "feature-notice",
      aria.label := featureLabel,
      h1(cls := ShellCss.FallbackTitle, featureLabel),
      p(cls := ShellCss.FallbackReason, message)
    )
}
