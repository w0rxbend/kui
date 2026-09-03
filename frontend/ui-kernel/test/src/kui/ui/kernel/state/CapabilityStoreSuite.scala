package kui.ui.kernel.state

import java.time.Instant

import scala.collection.mutable

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.contracts.capability.*
import kui.kernel.{ClusterId, ServiceId}
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.Tone
import kui.ui.kernel.feature.FeatureId
import kui.ui.kernel.sse.{SseConnection, SseError, SseHandle}

/** A capability stream the test drives by hand. */
final class StubStream {

  private val frames = new EventBus[Either[SseError, CapabilityEvent]]

  val connection: Var[SseConnection] = Var(SseConnection.Connecting)

  var closed: Boolean = false

  val handle: SseHandle[CapabilityEvent] =
    SseHandle(
      frames.events,
      connection.signal,
      // The real handle reports the close through its connection signal, and a store that reads that
      // back as "the server went away" is exactly the bug `stopSilencesThePollerInsteadOfRestartingIt`
      // is about, so the stub has to do it too.
      () => {
        closed = true
        connection.set(SseConnection.Closed("closed by the client"))
      }
    )

  def send(event: CapabilityEvent): Unit = frames.writer.onNext(Right(event))

  def sendUnreadable(problem: SseError): Unit = frames.writer.onNext(Left(problem))

  def open(): Unit = connection.set(SseConnection.Open)

  def die(reason: String): Unit = connection.set(SseConnection.Closed(reason))
}

class CapabilityStoreSuite extends FunSuite {

  private val at = Instant.parse("2026-09-03T10:11:12Z")

  private val clusterService = ServiceId.unsafe("cluster")

  private val clusterKey = CapabilityKey(clusterService, None)

  private def entry(state: CapabilityState, key: CapabilityKey = clusterKey): CapabilityEntry =
    CapabilityEntry(key, state, at)

  private def snapshot(states: CapabilityEntry*): CapabilityEvent =
    CapabilityEvent.Snapshot(CapabilitySnapshot(states.toList, at))

  private def delta(state: CapabilityState, previous: Option[CapabilityState] = None, key: CapabilityKey = clusterKey)
      : CapabilityEvent =
    CapabilityEvent.Delta(CapabilityChange(entry(state, key), previous))

  private val down =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "Connection refused.", at)

  /** A store wired to stubs, plus the levers a test pulls on it. */
  private final class Fixture(pollAnswers: List[Either[ApiError, CapabilitySnapshot]] = Nil) {
    val streams: mutable.ListBuffer[StubStream] = mutable.ListBuffer.empty
    val raised: mutable.ListBuffer[Notification] = mutable.ListBuffer.empty
    val timers: mutable.ListBuffer[() => Unit] = mutable.ListBuffer.empty
    var polls: Int = 0

    private var remainingPolls = pollAnswers

    val store: Capabilities = new Capabilities(
      openStream = () => {
        val created = new StubStream
        streams.append(created): Unit
        created.handle
      },
      poll = () => {
        polls += 1
        val answer = remainingPolls.headOption.getOrElse(Left(ApiError.Timeout))
        remainingPolls = remainingPolls.drop(1)
        EventStream.fromValue(answer)
      },
      notifications = notification => raised.append(notification): Unit,
      schedule = (_, action) => timers.append(action): Unit
    )

    def start(): StubStream = {
      store.start()
      streams.last
    }

    /** Runs whatever the store asked to have run later. */
    def fireTimers(): Unit = {
      val due = timers.toList
      timers.clear()
      due.foreach(action => action())
    }
  }

  private val owner = new ManualOwner

  override def afterAll(): Unit = owner.killSubscriptions()

  private def current[A](signal: Signal[A]): A = signal.observe(using owner).now()

  test("seedsFromTheSnapshotEventAndThenAppliesDeltas") {
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()

    stream.send(snapshot(entry(CapabilityState.Available)))
    assertEquals(current(fixture.store.states), Map(clusterKey -> CapabilityState.Available))

    stream.send(delta(down, previous = Some(CapabilityState.Available)))
    assertEquals(current(fixture.store.states), Map[CapabilityKey, CapabilityState](clusterKey -> down))
  }

  test("aDeltaForAnUnknownKeyIsAddedAndOneForAKnownKeyReplaces") {
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    val schemaKey = CapabilityKey(ServiceId.unsafe("schema"), Some(ClusterId.unsafe("prod-eu")))

    stream.send(snapshot(entry(CapabilityState.Available)))
    stream.send(delta(CapabilityState.NotConfigured, key = schemaKey))
    assertEquals(
      current(fixture.store.states),
      Map(clusterKey -> CapabilityState.Available, schemaKey -> CapabilityState.NotConfigured)
    )

    stream.send(delta(CapabilityState.Available, key = schemaKey))
    assertEquals(current(fixture.store.states)(schemaKey), CapabilityState.Available)
  }

  test("aMalformedEventDoesNotClearTheStore") {
    // The worst possible failure mode: one unreadable frame blanking the sidebar, so that every
    // feature goes from "working" to "unknown" because of a typo in one delta.
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    stream.send(snapshot(entry(CapabilityState.Available)))

    stream.sendUnreadable(SseError.Decode("capabilities", "not JSON"))

    assertEquals(current(fixture.store.states), Map(clusterKey -> CapabilityState.Available))
  }

  test("pushesExactlyOneToastPerAvailableToUnavailableTransitionEvenAcrossARapidFlap") {
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    stream.send(snapshot(entry(CapabilityState.Available)))

    stream.send(delta(down, previous = Some(CapabilityState.Available)))
    stream.send(delta(down, previous = Some(down)))
    stream.send(delta(down, previous = Some(down)))

    val losses = fixture.raised.filter(_.tone == Tone.Danger)
    assertEquals(losses.size, 1, "'it is still down' is not news and must not be a toast")
    assertEquals(losses.head.title, "cluster is unavailable")
    assertEquals(losses.head.message, Some("Connection refused."))
    // The key names the capability, not the moment, so that `Notifications` collapses repeats of a
    // flapping service inside its window (ADR-032).
    assertEquals(losses.head.dedupKey, Some("capability-lost:cluster/-"))
  }

  test("recoveryPushesASuccessToast") {
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    stream.send(snapshot(entry(CapabilityState.Available)))
    stream.send(delta(down, previous = Some(CapabilityState.Available)))
    stream.send(delta(CapabilityState.Available, previous = Some(down)))

    val recoveries = fixture.raised.filter(_.tone == Tone.Success)
    assertEquals(recoveries.size, 1)
    assertEquals(recoveries.head.title, "cluster is back")
  }

  test("theFirstSnapshotIsNotATransitionSoAColdStartIsSilent") {
    // Opening KUI while a service is already down is not an event; it is a state. Toasting it would
    // greet every user of a partly-degraded deployment with a wall of red on every page load.
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    stream.send(snapshot(entry(down)))

    assertEquals(fixture.raised.toList, Nil)
  }

  test("fallsBackToPollingWhenTheStreamCannotConnectAndSwitchesBackWhenItDoes") {
    val recovered = CapabilitySnapshot(List(entry(CapabilityState.Available)), at)
    val fixture = new Fixture(pollAnswers = List(Right(recovered)))
    val stream = fixture.start()

    stream.die("the connection was lost and will not be retried")
    assertEquals(fixture.polls, 1, "a dead stream must not leave the sidebar frozen")
    assertEquals(current(fixture.store.states), Map(clusterKey -> CapabilityState.Available))

    // Each tick is also another go at the stream. When one opens, the poller stands down.
    fixture.fireTimers()
    val replacement = fixture.streams.last
    replacement.open()

    val pollsBefore = fixture.polls
    fixture.fireTimers()
    assertEquals(fixture.polls, pollsBefore, "the poller must stop once the stream is back")
  }

  test("aFailedPollLeavesTheLastKnownPictureAloneAndSaysSoThroughConnection") {
    // Both the stream and the poller being down means the picture is stale, which `connection`
    // reports. It does not mean every feature is broken: marking them so would take a working product
    // off the air because one endpoint is unreachable.
    val fixture = new Fixture(pollAnswers = List(Left(ApiError.Unreachable("offline"))))
    val stream = fixture.start()
    stream.open()
    stream.send(snapshot(entry(CapabilityState.Available)))
    stream.die("lost")

    assertEquals(current(fixture.store.states), Map(clusterKey -> CapabilityState.Available))
    assertEquals(current(fixture.store.connection), SseConnection.Closed("lost"))
  }

  test("featureStateFoldsTheCapabilityTogetherWithPermission") {
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()
    val permitted = Var(true)
    val state = fixture.store.featureState(FeatureId.Clusters, None, permitted.signal)

    stream.send(snapshot(entry(CapabilityState.Available)))
    assertEquals(current(state), FeatureState.Ready)

    permitted.set(false)
    assertEquals(current(state), FeatureState.Forbidden)
  }

  test("aFeatureNobodyHasReportedYetIsDegradedStarting") {
    val fixture = new Fixture
    fixture.start(): Unit
    val state = fixture.store.featureState(FeatureId.Clusters, None, Var(true).signal)

    current(state) match {
      case FeatureState.Degraded(reason) => assertEquals(reason.code, ReasonCode.Starting)
      case other => fail(s"expected Degraded(Starting) before the first snapshot, got $other")
    }
  }

  test("decodingTheServerGoldenFixtureProducesTheExpectedStates") {
    // The bytes are the gateway's own golden file, compiled in by `build.mill`. This is the check that
    // makes "the browser and the gateway agree about this document" a fact rather than a hope.
    val fixture = new Fixture
    val stream = fixture.start()
    stream.open()

    CapabilityEvent.decodeFrame(CapabilityFixtures.snapshot) match {
      case Right(decoded) =>
        stream.send(decoded)
        assertEquals(
          current(fixture.store.states),
          Map(
            CapabilityKey(clusterService, None) -> CapabilityState.Available,
            CapabilityKey(ServiceId.unsafe("schema"), Some(ClusterId.unsafe("prod-eu"))) ->
              CapabilityState.NotConfigured
          )
        )
      case Left(problem) => fail(s"the gateway's golden snapshot did not decode: $problem")
    }
  }

  test("aFrameThatIsNeitherASnapshotNorADeltaIsADecodeFailureRatherThanACrash") {
    CapabilityEvent.decodeFrame("""{"nothing":"useful"}""") match {
      case Left(SseError.Decode(event, _)) => assertEquals(event, CapabilityStore.EventName)
      case other => fail(s"expected a decode failure, got $other")
    }
  }

  test("aDeltaFrameDecodesAsADelta") {
    val json =
      """{"entry":{"key":{"service":"cluster","cluster":null},"state":{"status":"available"},
        |"updatedAt":"2026-09-03T10:11:12.000Z"},"previous":null}""".stripMargin.replace("\n", "")

    CapabilityEvent.decodeFrame(json) match {
      case Right(CapabilityEvent.Delta(change)) => assertEquals(change.entry.key, clusterKey)
      case other => fail(s"expected a delta, got $other")
    }
  }

  test("theFallbackClosesTheStreamItReplacesRatherThanLeavingItRetrying") {
    // An abandoned handle is unreachable through the store, so `stop()` can never close it, yet the
    // browser keeps it retrying for the life of the tab and every capability delta is applied twice.
    val fixture = new Fixture
    val first = fixture.start()

    first.die("the connection was lost and will not be retried")
    fixture.fireTimers()

    assertEquals(fixture.streams.size, 2)
    assert(first.closed, "the stream the fallback replaced must be closed")
  }

  test("aReplacementStreamThatHasNotOpenedYetDoesNotClearTheStalenessBanner") {
    // `CapabilityBanner` treats only `Closed` as stale. A fresh handle starts in `Connecting`, so
    // letting that through took the banner down while the store was still disconnected and presented
    // capability data up to thirty seconds old as if it were live (ADR-032).
    val fixture = new Fixture
    val first = fixture.start()

    first.die("lost")
    fixture.fireTimers()
    assertEquals(current(fixture.store.connection), SseConnection.Closed("lost"))

    fixture.streams.last.open()
    assertEquals(current(fixture.store.connection), SseConnection.Open)
  }

  test("aFlapDoesNotLeaveASecondPollingChainRunning") {
    // Two chains means two polls and two new streams per interval, for ever, and one more of each for
    // every subsequent flap.
    val fixture = new Fixture
    val first = fixture.start()

    first.die("lost")
    fixture.fireTimers()
    val second = fixture.streams.last
    second.open()
    second.die("lost again")

    val streamsBefore = fixture.streams.size
    val pollsBefore = fixture.polls
    fixture.fireTimers()

    assertEquals(fixture.streams.size - streamsBefore, 1, "only one chain may be reopening the stream")
    assertEquals(fixture.polls - pollsBefore, 1, "only one chain may be polling")
  }

  test("stopSilencesThePollerInsteadOfRestartingIt") {
    // Closing the handle makes it report `Closed`, which is exactly what a lost connection reports. Read
    // back as one, a deliberate teardown left the tab polling and reopening the stream for ever against
    // a gateway the user may no longer be authenticated to.
    val fixture = new Fixture
    val stream = fixture.start()
    stream.die("lost")

    fixture.store.stop()
    val streamsBefore = fixture.streams.size
    val pollsBefore = fixture.polls
    fixture.fireTimers()

    assertEquals(fixture.streams.size, streamsBefore, "a stopped store must not reopen the stream")
    assertEquals(fixture.polls, pollsBefore, "a stopped store must not keep polling")
  }

  test("aClosedStreamStopsFeedingTheStoreOnceItHasBeenReplaced") {
    // The subscriptions used to be owned by the window, which never dies.
    val fixture = new Fixture
    val first = fixture.start()
    first.die("lost")
    fixture.fireTimers()

    first.send(snapshot(entry(down)))

    assertEquals(current(fixture.store.states), Map.empty[CapabilityKey, CapabilityState])
  }

  test("stopClosesTheStream") {
    val fixture = new Fixture
    val stream = fixture.start()
    fixture.store.stop()
    assert(stream.closed)
  }

  test("currentClusterIsNoneInM0") {
    // M0 has no clusters, so the cluster-independent capability key is the one in use. The `Var`
    // exists now so that M1 adds a switcher rather than a concept.
    assertEquals(current(CurrentCluster.signal), None)
  }
}
