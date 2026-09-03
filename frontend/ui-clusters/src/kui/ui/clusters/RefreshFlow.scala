package kui.ui.clusters

import java.time.Instant

import scala.concurrent.duration.*

import com.raquo.laminar.api.L.*

import kui.kernel.ClusterId
import kui.ui.kernel.api.ApiError

/** What a forced refresh is doing right now. */
enum RefreshStatus {
  case Idle

  /** Accepted by the server; waiting for the snapshot's timestamp to move past `baseline`. */
  case Running(baseline: Option[Instant], attempt: Int)

  /** The snapshot advanced. */
  case Completed(at: Instant)

  /** The schedule ran out with no advance. Not a failure — an unknown, and the difference matters. */
  case TimedOut

  /** The request itself was refused. */
  case Rejected(error: ApiError)
}

object RefreshStatus {
  given CanEqual[RefreshStatus, RefreshStatus] = CanEqual.derived
}

/** The forced-refresh state machine.
  *
  * ## The problem this type exists to solve
  *
  * The server answers a forced refresh with `202 Accepted` — *I have started* — and no body, no completion
  * signal and no estimate. The browser has to work out for itself when the refresh landed.
  *
  * Three ways were available. **Waiting a fixed time and re-reading once** is simple and wrong: too short and
  * the button appears not to work, too long and a fast cluster feels broken, and the right number is a
  * property of somebody's cluster rather than of this product. **Opening a stream so the server can say when
  * it is done** is correct and wildly out of proportion for a button pressed a few times a day.
  *
  * So: **re-read the snapshot on a bounded schedule until its timestamp advances**, and end in a stated
  * outcome either way. Five reads over fifteen seconds, front-loaded because a healthy cluster answers in
  * well under a second and stretched out at the end because a cluster that is slow to describe is exactly the
  * one whose scrape takes seconds.
  *
  * ## Why the comparison is on the timestamp and not on the data
  *
  * Two consecutive scrapes of a cluster where nothing changed produce identical bytes. Comparing payloads
  * would report "nothing happened" for a refresh that worked perfectly, which is the wrong answer to the
  * question the user asked.
  *
  * ## Why this is not the button
  *
  * A state machine with a real clock in it cannot be tested without waiting fifteen real seconds, and a test
  * nobody runs protects nothing. The schedule is a parameter, the clock is Airstream's, and the suite steps
  * both.
  */
final class RefreshFlow(
    cluster: ClusterId,
    queries: ClustersQueries,
    scrapedAt: Signal[Option[Instant]],
    schedule: List[FiniteDuration] = RefreshFlow.DefaultSchedule,
    timer: FiniteDuration => EventStream[Unit] = RefreshFlow.realTimer
)(using owner: Owner) {

  private val state: Var[RefreshStatus] = Var(RefreshStatus.Idle)

  val status: Signal[RefreshStatus] = state.signal

  /** A click is possible when nothing is outstanding, and once a refusal has been permanent it never is again
    * for this session — a button that fails identically every time is worse than one that says so.
    */
  val enabled: Signal[Boolean] = state.signal.map {
    case RefreshStatus.Running(_, _) => false
    case RefreshStatus.Rejected(_) => false
    case RefreshStatus.Idle | RefreshStatus.Completed(_) | RefreshStatus.TimedOut => true
  }

  /** Sends the request. A second call while one is outstanding does nothing. */
  def request(): Unit =
    if isRunning(state.now()) then ()
    else {
      val baseline = scrapedAt.observe(using owner).now()
      state.set(RefreshStatus.Running(baseline, 0))

      queries
        .requestRefresh(cluster)
        .foreach {
          case Left(failure) => state.set(RefreshStatus.Rejected(failure))
          // Nothing on screen changes yet. Showing a new timestamp because a 202 arrived would be
          // inventing data the server has not produced.
          case Right(_) => watchForAdvance(baseline)
          // The subscription is the owner's; the handle is deliberately dropped.
        }: Unit
    }

  /** Re-reads on the schedule, stopping at the first read whose timestamp is past the baseline.
    *
    * Built on Airstream timers rather than `js.timers` on purpose: the subscription belongs to the element's
    * owner, so navigating away kills the remaining reads. A timer that outlives its page is a request nobody
    * is waiting for.
    */
  private def watchForAdvance(baseline: Option[Instant]): Unit = {
    val reads: EventStream[Int] =
      EventStream.merge(schedule.zipWithIndex.map((after, index) => timer(after).mapTo(index + 1))*)

    reads
      .withCurrentValueOf(scrapedAt)
      .foreach { (attempt, current) =>
        state.now() match {
          // A later read that arrives after the flow already finished is dropped rather than reopening it.
          case RefreshStatus.Running(_, _) =>
            current.filter(now => baseline.forall(now.isAfter)) match {
              case Some(advanced) => state.set(RefreshStatus.Completed(advanced))
              case None if attempt >= schedule.length => state.set(RefreshStatus.TimedOut)
              case None =>
                queries.invalidateCluster(cluster)
                state.set(RefreshStatus.Running(baseline, attempt))
            }
          case _ => ()
        }
      }: Unit
  }

  private def isRunning(status: RefreshStatus): Boolean =
    status match {
      case RefreshStatus.Running(_, _) => true
      case _ => false
    }
}

object RefreshFlow {

  /** Front-loaded, then stretched. See the class comment for why it is shaped this way. */
  val DefaultSchedule: List[FiniteDuration] =
    List(1.second, 3.seconds, 6.seconds, 10.seconds, 15.seconds)

  /** One sentence per status, in one place, so that the button and anything else that ever reports a refresh
    * say the same thing.
    */
  def describe(status: RefreshStatus): Option[String] =
    status match {
      case RefreshStatus.Idle => None
      case RefreshStatus.Running(_, _) => Some(Messages.RefreshRunning)
      case RefreshStatus.Completed(_) => Some(Messages.RefreshCompleted)
      // True, does not claim failure, and does not quietly put the screen back as though nothing happened.
      case RefreshStatus.TimedOut => Some(Messages.RefreshTimedOut)
      case RefreshStatus.Rejected(error) => Some(Messages.refreshRejected(describeError(error)))
    }

  private def describeError(failure: ApiError): String =
    failure match {
      case ApiError.Envelope(_, text, _, _, _) => text
      case ApiError.Timeout => "the gateway did not answer in time"
      case ApiError.Unreachable(_) => "the gateway could not be reached"
      case ApiError.Decoding(_) => "the answer could not be read"
    }

  private def realTimer(after: FiniteDuration): EventStream[Unit] =
    EventStream.delay(after.toMillis.toInt, ())
}
