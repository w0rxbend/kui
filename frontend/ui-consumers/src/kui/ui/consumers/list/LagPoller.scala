package kui.ui.consumers.list

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.consumer.contract.ConsumerEndpoints
import kui.consumer.contract.dto.LagDeltaDto
import kui.kernel.{ClusterId, GroupId}
import kui.ui.kernel.api.ApiError

/** Keeps the lag column moving without the user pressing anything.
  *
  * ## Why the list needs one at all
  *
  * Every other number on the group list is a fact about configuration and changes when somebody changes it.
  * Lag is not: it moves continuously, it is the one figure an operator is watching, and it is the whole
  * reason they have the screen open. A page that showed a lag of 40 000 until the user pressed Refresh would
  * be showing a number whose age is the only thing that matters about it.
  *
  * ## Why the *server* sets the interval
  *
  * Every answer carries `nextPollMs`, and this obeys it rather than holding an interval of its own. That is
  * the field that lets a consumer service under load slow every open browser down at once, which is the only
  * mechanism in the product that can — a client-side interval means the moment a service starts struggling is
  * the moment its traffic stays exactly the same.
  *
  * A failed poll waits [[LagPoller.RetryAfter]] instead, because a server that did not answer did not say how
  * long to wait, and hammering a service that just failed is how a slow service becomes a dead one.
  *
  * ## Why the token is a `var` and not a signal
  *
  * The token is an input to the *next* request and nothing renders it. As a signal it would be part of the
  * combination that triggers a request, so storing a new token would immediately fire another poll, which
  * would store another token: a loop with no timer in it. It is read at the moment a request is built and
  * written when one comes back.
  *
  * ## What a poll does not do
  *
  * It never changes which rows are on screen or their order. The list endpoint sorts, filters and pages; this
  * repaints cells in the rows that endpoint sent. A lag figure that arrived while somebody was reaching for a
  * row must not move that row out from under them, so the merge happens in `LagFeed` on top of the fetched
  * page rather than by refetching it.
  *
  * @param groups
  *   which groups are on screen. It changes when the user pages, filters or searches, and a change triggers
  *   an immediate poll rather than waiting out the current interval — the rows are new and have no lag on
  *   them yet.
  * @param poll
  *   the request, passed in rather than reached for, so the suite can drive this with no server and no
  *   browser.
  * @param timer
  *   how the wait is done. A parameter for the same reason: a state machine with a real clock in it can only
  *   be tested by waiting real seconds, and a test nobody runs protects nothing.
  */
final class LagPoller(
    cluster: ClusterId,
    groups: Signal[Set[GroupId]],
    poll: (ClusterId, Set[GroupId], Option[String]) => EventStream[Either[ApiError, LagDeltaDto]],
    timer: FiniteDuration => EventStream[Unit] = LagPoller.realTimer
) {

  private val viewVar: Var[LagView] = Var(LagView.Empty)

  /** Bumped to ask for another poll. The value is meaningless; only the change matters. */
  private val tick: Var[Int] = Var(0)

  private val waits: EventBus[FiniteDuration] = new EventBus[FiniteDuration]

  private var token: Option[String] = None

  /** What the poller has learned. The page lays it over the rows the list endpoint sent. */
  val view: Signal[LagView] = viewVar.signal

  /** Everything this poller subscribes to, as one modifier the page puts on an element.
    *
    * On the element rather than under an `Owner` the page passes in, so the polling is bound to the lifetime
    * of the thing on screen: navigating away unmounts the element and the poll stops with it. A poller owned
    * by anything longer-lived would keep asking about a screen nobody is looking at.
    */
  val binder: Modifier[HtmlElement] = List[Modifier[HtmlElement]](
    // `flatMapSwitch`, so a change of page or filter abandons the request in flight rather than racing it:
    // the answer to "what changed for the groups that were on screen a moment ago" is not an answer about
    // the groups that are on screen now.
    tick.signal
      .combineWith(groups)
      .flatMapSwitch((_, onScreen) => poll(cluster, asked(onScreen), token)) --> Observer[
      Either[ApiError, LagDeltaDto]
    ] {
      case Right(delta) =>
        token = Some(delta.token)
        viewVar.update(LagFeed.merge(_, delta))
        waits.writer.onNext(LagPoller.intervalOf(delta.nextPollMs))
      case Left(_) =>
        // The rows on screen are still the rows the list endpoint sent, and they are still true as of when
        // it sent them. A failed poll is not a reason to blank them, and it is not reported as an outage
        // either: the screen already has the snapshot's own age on it.
        waits.writer.onNext(LagPoller.RetryAfter)
    },
    waits.events.flatMapSwitch(timer) --> Observer[Unit](_ => tick.update(_ + 1))
  )

  /** The groups to ask about, bounded by what fits in a URL.
    *
    * `MaxLagGroups` is the contract's own limit, and it exists because a longer query string is truncated by
    * some proxies and refused with a 414 by others — and from the browser both failures look identical to a
    * lag column that has simply stopped moving. A page larger than the limit is asked about in part rather
    * than not at all; the rows that were not asked about keep the figures the list endpoint gave them.
    */
  private def asked(onScreen: Set[GroupId]): Set[GroupId] =
    if onScreen.size <= ConsumerEndpoints.MaxLagGroups then onScreen
    else onScreen.toList.sortBy(_.value).take(ConsumerEndpoints.MaxLagGroups).toSet
}

object LagPoller {

  /** How long to wait after a poll that failed. The server did not say, so this is the client's own number,
    * and it is deliberately longer than any healthy `nextPollMs`.
    */
  val RetryAfter: FiniteDuration = 30.seconds

  /** The shortest interval this will obey, whatever the server asks for.
    *
    * A `nextPollMs` of zero — a bug, a misconfiguration, a service answering from a default — would otherwise
    * turn every open browser into a tight loop against the thing that sent it.
    */
  val MinimumInterval: FiniteDuration = 1.second

  /** And the longest. A server asking for an hour has effectively turned the poller off with no way for the
    * user to tell; capping it keeps the screen live and still lets the server slow it down a long way.
    */
  val MaximumInterval: FiniteDuration = 5.minutes

  def intervalOf(nextPollMs: Long): FiniteDuration =
    if nextPollMs <= MinimumInterval.toMillis then MinimumInterval
    else if nextPollMs >= MaximumInterval.toMillis then MaximumInterval
    else nextPollMs.millis

  def realTimer(after: FiniteDuration): EventStream[Unit] = EventStream.delay(after.toMillis.toInt, ())
}
