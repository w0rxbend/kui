package kui.ui.clusters

import java.time.Instant

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.cluster.contract.dto.PingResponse
import kui.contracts.ErrorEnvelope
import kui.ui.kernel.api.{ApiClient, ApiError}
import sttp.tapir.{Endpoint, PublicEndpoint}

/** The feature's state, driven against a stub client.
  *
  * No browser and no server: `ClustersState` takes its client and its owner as parameters, so the whole state
  * machine — including the two things that are easy to get wrong, the stale-data rule and overlapping calls —
  * can be exercised in memory, deterministically, one step at a time.
  */
class ClustersStateSuite extends FunSuite {

  private given owner: ManualOwner = new ManualOwner

  /** A client whose answers the test hands out by hand, one call at a time.
    *
    * A queue of pending replies rather than a canned answer, because the interesting cases are all about
    * *when* a reply arrives: a second call starting before the first has finished, or a failure arriving
    * after a success.
    */
  private final class StubClient extends ApiClient {
    var calls: List[Any] = Nil
    private var buses: List[EventBus[Either[ApiError, Any]]] = Nil

    def call[I, O](
        endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
        input: I
    ): EventStream[Either[ApiError, O]] = {
      calls = calls :+ input
      val bus = new EventBus[Either[ApiError, Any]]
      buses = buses :+ bus
      bus.events.map(_.map(_.asInstanceOf[O]))
    }

    def callSecure[A, I, O](
        endpoint: Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] = call(endpoint.asInstanceOf[PublicEndpoint[I, ErrorEnvelope, O, Any]], input)

    /** Answers the n-th outstanding call, counting from zero. */
    def answer(index: Int, outcome: Either[ApiError, PingResponse]): Unit =
      buses(index).writer.onNext(outcome.map(identity))
  }

  /** The current value of a derived signal.
    *
    * A `Signal`'s `now()` is only available to Airstream itself, and deliberately: a signal that nobody is
    * observing has no current value to report. `observe` gives the suite an observed view whose value is
    * defined, which is also what the real page does by binding it into the DOM.
    */
  private def current[A](signal: Signal[A]): A = signal.observe.now()

  private def replyOf(message: String, at: String = "2026-09-03T10:00:00Z"): PingResponse =
    PingResponse(message, Instant.parse(at), "cluster")

  private val failure = ApiError.Unreachable("Failed to fetch")

  private def stateWith(): (ClustersState, StubClient) = {
    val client = new StubClient
    (new ClustersState(client), client)
  }

  test("pingAppendsTheResponseToTheList") {
    val (state, client) = stateWith()

    state.ping("hello")
    assertEquals(client.calls, List("hello"))
    assertEquals(state.pings.now(), Nil)

    client.answer(0, Right(replyOf("hello")))
    assertEquals(state.pings.now().map(_.message), List("hello"))
    assertEquals(state.lastError.now(), None)
  }

  test("aFailedPingSetsLastErrorAndDoesNotClearThePreviousResults") {
    // ADR-032's stale-data rule. Clearing the table on a failure destroys the only information the
    // user still had, at exactly the moment they are trying to work out what is broken.
    val (state, client) = stateWith()

    state.ping("first")
    client.answer(0, Right(replyOf("first")))

    state.ping("second")
    client.answer(1, Left(failure))

    assertEquals(state.pings.now().map(_.message), List("first"))
    assertEquals(state.lastError.now(), Some(failure))
    assertEquals(current(state.stale), true)
  }

  test("aLaterSuccessClearsTheErrorAndTheStaleMarker") {
    val (state, client) = stateWith()

    state.ping("first")
    client.answer(0, Left(failure))
    assertEquals(state.lastError.now(), Some(failure))
    // Nothing has ever succeeded, so there is nothing stale to warn about — an empty table is not
    // out-of-date data, and saying it is would be noise.
    assertEquals(current(state.stale), false)

    state.ping("second")
    client.answer(1, Right(replyOf("second")))
    assertEquals(state.lastError.now(), None)
    assertEquals(current(state.stale), false)
  }

  test("inFlightIsTrueOnlyDuringTheCall") {
    val (state, client) = stateWith()

    assertEquals(current(state.inFlight), false)
    state.ping("hello")
    assertEquals(current(state.inFlight), true)
    client.answer(0, Right(replyOf("hello")))
    assertEquals(current(state.inFlight), false)
  }

  test("inFlightStaysTrueUntilEveryOutstandingCallHasAnswered") {
    // Counted, not flagged. A boolean would be cleared by whichever call answered first and the
    // button would come back to life while a request was still outstanding.
    val (state, client) = stateWith()

    state.ping("first")
    state.ping("second")
    assertEquals(current(state.inFlight), true)

    client.answer(1, Right(replyOf("second")))
    assertEquals(current(state.inFlight), true, "one call is still outstanding")

    client.answer(0, Right(replyOf("first")))
    assertEquals(current(state.inFlight), false)
  }

  test("concurrentPingsAreNotInterleavedIncorrectly") {
    // Two calls, answered out of order. Both replies are kept, in the order they arrived, and neither
    // overwrites the other — the failure mode this guards against is a slow first reply landing last
    // and replacing the list rather than joining it.
    val (state, client) = stateWith()

    state.ping("slow")
    state.ping("fast")

    client.answer(1, Right(replyOf("fast", "2026-09-03T10:00:01Z")))
    client.answer(0, Right(replyOf("slow", "2026-09-03T10:00:02Z")))

    assertEquals(state.pings.now().map(_.message), List("slow", "fast"))
    assertEquals(state.lastError.now(), None)
  }

  test("aFailureAmongConcurrentCallsDoesNotDiscardTheOtherReply") {
    val (state, client) = stateWith()

    state.ping("good")
    state.ping("bad")

    client.answer(0, Right(replyOf("good")))
    client.answer(1, Left(failure))

    assertEquals(state.pings.now().map(_.message), List("good"))
    assertEquals(state.lastError.now(), Some(failure))
  }
}
