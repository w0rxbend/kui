package kui.testkit

/** Assertions for "this secret did not end up here".
  *
  * The tricky part of a leak test is failing without leaking. A plain `assert(!haystack.contains( needle))`
  * that prints the haystack on failure copies the secret into the CI log — which is a public artefact on most
  * projects, and the exact outcome the test exists to prevent. These helpers report *where* the leak is and
  * how much text surrounds it, and never print the needle or the region it was found in.
  */
object RedactionAssertions {

  /** What replaces a secret everywhere in KUI. */
  val Redacted: String = "***"

  /** How much of the haystack the failure message describes, in characters either side. */
  private val Context: Int = 40

  /** Fails when `haystack` contains `needle`, naming the offset and nothing else.
    *
    * The failure message is deliberately unhelpful about the *content*: it says a value was found at a
    * position, in a haystack of a certain length, with the first few characters of the surrounding text
    * replaced. Whoever is debugging has the fixture in front of them and knows what the needle was; a reader
    * of the CI log does not, and should not learn it here.
    */
  def assertNoLeak(haystack: String, needle: String)(using munit.Location): Unit = {
    val at = haystack.indexOf(needle)

    if at >= 0 then {
      val from = math.max(0, at - Context)
      val until = math.min(haystack.length, at + needle.length + Context)
      val region = haystack.substring(from, until).replace(needle, "<the secret>")

      munit.Assertions.fail(
        s"a secret leaked at character $at of ${haystack.length}. " +
          s"The surrounding text, with the secret itself removed, was: $region"
      )
    }
  }

  /** Fails when `haystack` does not contain `***`.
    *
    * Absence of the secret is only half the guarantee. A field that was dropped entirely is also absent, and
    * that is a different bug: the reader of a log line or a configuration view needs to see that a value
    * exists and is being withheld, not that there is no value at all.
    */
  def assertRedacted(haystack: String)(using munit.Location): Unit =
    if !haystack.contains(Redacted) then
      munit.Assertions.fail(
        s"expected a redacted value ($Redacted) somewhere in ${haystack.length} characters, and found " +
          "none — the field may have been dropped rather than redacted"
      )

  /** Both halves at once: the secret is absent and something says so. */
  def assertRedactedAndNoLeak(haystack: String, needle: String)(using munit.Location): Unit = {
    assertNoLeak(haystack, needle)
    assertRedacted(haystack)
  }
}
