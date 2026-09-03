package kui.ui.kernel.prefs

import java.time.Instant

import com.raquo.airstream.web.WebStorageVar
import munit.FunSuite
import org.scalajs.dom

class TimezoneSuite extends FunSuite {

  private val summer = Instant.parse("2026-07-01T12:00:00Z")

  private def storage(key: String) = {
    dom.window.localStorage.removeItem(key)
    WebStorageVar.localStorage(key, syncOwner = None)
  }

  test("anUnsetPreferenceFollowsTheBrowsersZone") {
    val choice = Timezone.persistedChoice(storage("kui.test.tz.unset"), "Europe/Warsaw")
    assertEquals(choice.now(), "Europe/Warsaw")
  }

  test("aChosenZonePersistsAndIsReadBack") {
    val key = "kui.test.tz.persist"
    Timezone.persistedChoice(storage(key), "UTC").set("Asia/Tokyo")
    // A second `Var` over the same key is the closest a suite gets to a page reload.
    val reloaded = Timezone.persistedChoice(WebStorageVar.localStorage(key, syncOwner = None), "UTC")
    assertEquals(reloaded.now(), "Asia/Tokyo")
  }

  test("anUnknownStoredZoneFallsBackToUtc") {
    val key = "kui.test.tz.bogus"
    dom.window.localStorage.setItem(key, "Mars/Olympus")
    val choice = Timezone.persistedChoice(WebStorageVar.localStorage(key, syncOwner = None), "Europe/Warsaw")
    assertEquals(choice.now(), TimeZoneList.Utc)
  }

  test("theListIsSortedByOffsetThenId") {
    val entries = TimeZoneList.entries(
      Some(List("Asia/Tokyo", "America/New_York", "Europe/Warsaw", "Europe/Berlin")),
      systemZone = "UTC",
      at = summer
    )
    assertEquals(
      entries.map(_._1),
      List("America/New_York", "UTC", "Europe/Berlin", "Europe/Warsaw", "Asia/Tokyo")
    )
  }

  test("theListFallsBackToTheBrowserZoneAndUtcWhenIntlOffersNothing") {
    assertEquals(
      TimeZoneList.entries(None, systemZone = "Asia/Tokyo", at = summer).map(_._1),
      List("UTC", "Asia/Tokyo")
    )
    // An empty answer is treated exactly like no answer, rather than producing an empty dropdown.
    assertEquals(
      TimeZoneList.entries(Some(Nil), systemZone = "Asia/Tokyo", at = summer).map(_._1),
      List("UTC", "Asia/Tokyo")
    )
  }

  test("aZoneTheRuntimeDoesNotKnowIsNotOffered") {
    assertEquals(
      TimeZoneList.entries(Some(List("Mars/Olympus")), systemZone = "UTC", at = summer).map(_._1),
      List("UTC")
    )
  }

  test("labelsHaveTheOffsetAndTheId") {
    assertEquals(TimeZoneList.label("Europe/Warsaw", summer), "UTC+02:00 Europe/Warsaw")
    assertEquals(TimeZoneList.label("UTC", summer), "UTC+00:00 UTC")
    assertEquals(TimeZoneList.label("America/New_York", summer), "UTC-04:00 America/New_York")
  }
}
