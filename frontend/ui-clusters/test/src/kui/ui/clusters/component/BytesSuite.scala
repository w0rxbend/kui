package kui.ui.clusters.component

import munit.FunSuite

import kui.ui.kernel.component.DataTable

class BytesSuite extends FunSuite {

  test("formatsBinaryUnitsAtEveryBoundary") {
    val cases = List(
      0L -> "0 B",
      1L -> "1 B",
      1023L -> "1023 B",
      1024L -> "1.0 KiB",
      1024L * 1024 - 1 -> "1.0 MiB",
      1024L * 1024 -> "1.0 MiB",
      1024L * 1024 * 1024 -> "1.0 GiB",
      1024L * 1024 * 1024 * 1024 -> "1.0 TiB",
      1536L * 1024 * 1024 -> "1.5 GiB"
    )
    cases.foreach((bytes, expected) => assertEquals(Bytes.format(Some(bytes)), expected, s"$bytes bytes"))
  }

  test("noneFormatsAsTheMissingMarker") {
    // One constant governs every em dash in the product, so a cell here and a cell in the kernel's own
    // table cannot drift into two different characters.
    assertEquals(Bytes.format(None), DataTable.missing)
  }

  test("aClusterThatCouldNotBeReadIsNotAClusterWithEmptyDisks") {
    assertNotEquals(Bytes.format(None), Bytes.format(Some(0L)))
  }

  test("fractionOfAZeroMaxIsZeroNotNaN") {
    val fraction = Bytes.fraction(Some(0L), 0L)
    assert(!fraction.isNaN, "an all-empty cluster produced a NaN bar width")
    assertEquals(fraction, 0.0)
    assertEquals(Bytes.fraction(None, 100L), 0.0)
  }

  test("fractionIsClampedToTheTrack") {
    assertEquals(Bytes.fraction(Some(50L), 100L), 0.5)
    assertEquals(Bytes.fraction(Some(500L), 100L), 1.0)
  }
}
