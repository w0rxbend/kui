package kui.ui.kernel.prefs

import scala.collection.mutable
import scala.concurrent.duration.*

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.web.WebStorageVar
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

class RefreshRateSuite extends FunSuite {

  /** A timer the test fires by hand, which also records how many are live at once. */
  final private class FakeTimer {
    private val buses = mutable.Map.empty[FiniteDuration, EventBus[Unit]]
    val started: mutable.ListBuffer[FiniteDuration] = mutable.ListBuffer.empty

    def apply(every: FiniteDuration): EventStream[Unit] = {
      started.append(every): Unit
      buses.getOrElseUpdate(every, new EventBus[Unit]).events
    }

    def tick(every: FiniteDuration): Unit = buses.get(every).foreach(_.writer.onNext(()))
  }

  private def storage(key: String) = {
    dom.window.localStorage.removeItem(key)
    WebStorageVar.localStorage(key, syncOwner = None)
  }

  test("offEmitsNothing") {
    val timer = new FakeTimer
    val choice = Var(RefreshRate.Off)
    given owner: ManualOwner = new ManualOwner
    val ticks = mutable.ListBuffer.empty[Unit]

    RefreshRate.ticksOf(choice.signal, timer.apply).foreach(ticks.append(_): Unit): Unit

    // Not merely "no ticks": no timer was ever asked for. A stream that is running and being
    // filtered would still be a browser waking up on somebody's laptop every thirty seconds.
    assertEquals(timer.started.toList, Nil)
    assertEquals(ticks.toList, Nil)
    owner.killSubscriptions()
  }

  test("changingTheRateRestartsTheTimerAndDoesNotLeaveTheOldOneRunning") {
    val timer = new FakeTimer
    val choice = Var[RefreshRate](RefreshRate.Off)
    given owner: ManualOwner = new ManualOwner
    val ticks = mutable.ListBuffer.empty[Unit]

    RefreshRate.ticksOf(choice.signal, timer.apply).foreach(ticks.append(_): Unit): Unit

    choice.set(RefreshRate.Every30s)
    timer.tick(30.seconds)
    assertEquals(ticks.length, 1)

    choice.set(RefreshRate.Every5m)
    // The 30-second timer is no longer subscribed, so its ticks go nowhere.
    timer.tick(30.seconds)
    assertEquals(ticks.length, 1)

    timer.tick(5.minutes)
    assertEquals(ticks.length, 2)

    choice.set(RefreshRate.Off)
    timer.tick(5.minutes)
    assertEquals(ticks.length, 2)
    owner.killSubscriptions()
  }

  test("anUnknownStoredValueReadsAsOff") {
    assertEquals(RefreshRate.fromStorage("every-second"), RefreshRate.Off)
    assertEquals(RefreshRate.fromStorage(""), RefreshRate.Off)

    val key = "kui.test.rate.bogus"
    dom.window.localStorage.setItem(key, "every-second")
    val choice = RefreshRate.persistedChoice(WebStorageVar.localStorage(key, syncOwner = None))
    assertEquals(choice.now(), RefreshRate.Off)
  }

  test("thePersistedChoiceSurvivesAReadBack") {
    val key = "kui.test.rate.persist"
    RefreshRate.persistedChoice(storage(key)).set(RefreshRate.Every1m)
    val reloaded = RefreshRate.persistedChoice(WebStorageVar.localStorage(key, syncOwner = None))
    assertEquals(reloaded.now(), RefreshRate.Every1m)
  }

  test("theDefaultIsOff") {
    assertEquals(RefreshRate.persistedChoice(storage("kui.test.rate.default")).now(), RefreshRate.Off)
  }
}
