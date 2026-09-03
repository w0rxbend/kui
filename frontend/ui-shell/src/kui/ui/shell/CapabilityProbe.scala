package kui.ui.shell

import com.raquo.laminar.api.L.*

import kui.gateway.contract.CapabilityEndpoints
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.feature.FeatureId

/** The "Retry now" button's other half: asking the gateway to re-check one service.
  *
  * ## Why this lives in the shell and not in the kernel
  *
  * The kernel is the bottom of the frontend and must not know that a gateway exists, let alone the path of
  * one of its endpoints. But the fallback panel's retry has to hit
  * `POST /api/v1/capabilities/{service}/probe` — there is no other way to make the gateway look again — so
  * the shell owns the call and hands the panel an `Observer[Unit]` that performs it. The panel knows only
  * "somebody wants this retried".
  *
  * ## What the button means
  *
  * The probe is synchronous from the caller's point of view: the gateway performs the check and answers with
  * the recomputed entry, so a user who presses the button and sees it finish knows the answer is fresh rather
  * than the one that was already on screen. The recomputed state reaches the sidebar through the capability
  * stream, exactly like every other transition — this class does not write to the store, because two writers
  * to one picture is how a picture ends up disagreeing with itself.
  *
  * @param api
  *   the client. A parameter rather than a global so that a suite can drive the whole thing against a stub.
  */
final class CapabilityProbe(api: ApiClient)(using owner: Owner) {

  private val outstanding = Var(Set.empty[String])

  private val failures = Var(Map.empty[String, String])

  /** Whether a probe for this service is in flight, for the button's spinner. */
  def inFlight(feature: FeatureId): Signal[Boolean] =
    outstanding.signal.map(_.contains(feature.serviceId))

  /** What the last probe for this service failed with, for the inline error under the button. */
  def lastError(feature: FeatureId): Signal[Option[String]] =
    failures.signal.map(_.get(feature.serviceId))

  /** The observer the fallback panel is handed. */
  def observer(feature: FeatureId): Observer[Unit] = Observer[Unit](_ => probe(feature))

  def probe(feature: FeatureId): Unit = {
    val service = feature.serviceId

    // A second press while the first is still outstanding does nothing. Without this a user watching
    // a service that is slow to answer can queue up a dozen probes, each of which makes the gateway
    // call an upstream that is already struggling.
    if !outstanding.now().contains(service) then {
      outstanding.update(_ + service)
      failures.update(_.removed(service))

      api
        .call(CapabilityEndpoints.probe, service)
        .foreach { outcome =>
          outstanding.update(_ - service)
          // The capability endpoints are the shell's own, so a transport failure here is evidence
          // about the gateway itself and is reported as such (`CallScope.Shell`).
          ShellHealth.report(CallScope.Shell, outcome)
          outcome match {
            case Right(_) => ()
            case Left(failure) => failures.update(_.updated(service, CapabilityProbe.describe(failure)))
          }
        }: Unit
    }
  }
}

object CapabilityProbe {

  /** A failed probe, as a fragment a sentence can be built around.
    *
    * Short and free of stack traces: it is appended to "KUI could not re-check the service:" and shown next
    * to the button, where a paragraph of Java exception text would push the retry off the screen.
    */
  def describe(failure: ApiError): String =
    failure match {
      case ApiError.Envelope(_, message, _, _, _) => message
      case ApiError.Timeout => "the gateway did not answer in time"
      case ApiError.Unreachable(_) => "the gateway could not be reached"
      case ApiError.Decoding(_) => "the gateway's answer could not be read"
    }
}
