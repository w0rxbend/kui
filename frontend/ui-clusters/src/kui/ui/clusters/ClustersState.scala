package kui.ui.clusters

import com.raquo.laminar.api.L.*

import kui.cluster.contract.dto.PingResponse
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.state.{CallScope, HealthReporting}

/** This feature's state: a plain class holding `Var`s, created by the page and owned by nothing else.
  *
  * ## Why it is not an object
  *
  * A global singleton would be shared by every instance of the page and would outlive all of them, so two
  * tabs of the same feature would fight over one list and a test would inherit the previous test's results.
  * PLAN §21 makes feature state per-instance for that reason, and it is also what makes this class testable
  * without a browser: the client and the owner are parameters, so a suite hands it a stub and a `ManualOwner`
  * and drives it in memory.
  *
  * ## The stale-data rule, implemented
  *
  * A failed call sets `lastError` and **does not touch** `pings`. ADR-032 is explicit about this: what was
  * fetched successfully stays on screen, greyed and labelled, rather than being cleared. Emptying the table
  * when a request fails destroys the only information the user still had, and it does it at exactly the
  * moment they most need it — while they are trying to work out what is broken.
  *
  * @param api
  *   the kernel's client. Every request goes through it, so this class names no header, no URL and no
  *   backend.
  */
final class ClustersState(api: ApiClient)(using owner: Owner) {

  /** Every reply so far, newest first. Never emptied by a failure. */
  val pings: Var[List[PingResponse]] = Var(Nil)

  /** What the last failed call said, or `None` if the last one succeeded. */
  val lastError: Var[Option[ApiError]] = Var(None)

  private val outstanding = Var(0)

  /** Whether a call is in flight, for the button's spinner. */
  val inFlight: Signal[Boolean] = outstanding.signal.map(_ > 0)

  /** Whether what is on screen is known to be older than the last thing that happened. */
  val stale: Signal[Boolean] =
    lastError.signal
      .combineWith(pings.signal)
      .map((failure, results) => failure.isDefined && results.nonEmpty)

  /** Sends one ping.
    *
    * Concurrent calls are counted rather than flagged, so two overlapping requests leave `inFlight` true
    * until *both* have finished. A boolean would be cleared by whichever answered first, and the button would
    * come back to life while a request was still outstanding.
    *
    * Replies are prepended in the order they *arrive*, which is the honest order for a list titled "newest
    * first": it is what the browser actually saw, and a slow reply that overtakes a fast one is a fact about
    * the service worth being able to see.
    */
  def ping(message: String): Unit = {
    outstanding.update(_ + 1)

    api
      .call(ClustersApi.ping, message)
      .foreach { outcome =>
        outstanding.update(_ - 1)
        // `Feature`, never `Shell`. A failure here means this feature cannot show its data; it must not
        // be able to take the whole application away from the user (ADR-032). A *success* still counts
        // as evidence that the gateway is reachable, which is why the outcome is reported either way.
        HealthReporting.report(CallScope.Feature, outcome)

        outcome match {
          case Right(reply) =>
            lastError.set(None)
            pings.update(reply :: _)
          // Deliberately no `pings.set(Nil)` here. See the class comment.
          case Left(failure) => lastError.set(Some(failure))
        }
      }: Unit
  }
}
