package kui.security.masking

import cats.data.NonEmptyList
import io.circe.{parser, Json}
import munit.FunSuite

import kui.kernel.serde.Target
import kui.kernel.TopicName

/** Two rules of the engine's *reach* that `MaskingEngineSuite` leaves open: which scope a header rule is
  * read under, and what a masked number comes back as.
  *
  * Both were found by mutation against `./mill libs.securityCore.jvm.test` (93 cases, 5 suites), which
  * stayed green for each:
  *
  *   - `maskHeaders` filters its rules with `scopeMatches(_, topic, Target.Value)`, and the scaladoc argues
  *     for it: *"A rule scoped to `topicKeysPattern` does not apply: headers belong to the record, not to
  *     its key or its value, so only value-scoped and unscoped rules reach them."* Reading the same rules
  *     under `Target.Key` instead left 93/93 green — the two header cases in `MaskingEngineSuite` use
  *     unscoped rules, which match under either scope.
  *   - `maskLeaf` turns a masked number into a JSON **string**, because *"masking a number and keeping it a
  *     number would either change its magnitude or fail to hide it"*. Returning the number untouched left
  *     93/93 green: every masked fixture in that suite holds a string.
  *
  * These matter for DM-001 rather than in the abstract. The engine has no production caller yet
  * (`docs/FEATURE_MATRIX.md` DM-001), so the first thing that wires it will be wiring against whatever these
  * functions do on the day — and an operator's `topicKeysPattern` rule silently reaching every header, or a
  * `cardNumber` held as a number coming back in full, are both failures the wiring would not reveal.
  */
final class MaskingReachSuite extends FunSuite {

  private val orders: TopicName = TopicName.unsafe("orders")

  private val stars: MaskingKind = MaskingKind.Mask("*", KeepEnds.none)

  private def json(text: String): Json =
    parser.parse(text).fold(error => fail(s"the fixture is not JSON: $error"), identity)

  private def onField(kind: MaskingKind, name: String): MaskingRule =
    MaskingRule(kind, Some(NonEmptyList.of(name)), None, None, None)

  // -- headers are value-scoped -----------------------------------------------------------------------

  /** Named `authorization` on every topic, but scoped to record **keys**. */
  private val keyScoped: MaskingRule =
    MaskingRule(stars, Some(NonEmptyList.of("authorization")), None, Some("orders".r), None)

  /** The same rule, scoped to record **values**. */
  private val valueScoped: MaskingRule =
    MaskingRule(stars, Some(NonEmptyList.of("authorization")), None, None, Some("orders".r))

  private val headers: Map[String, String] = Map("authorization" -> "Bearer abc", "trace-id" -> "xyz")

  test("aKeyScopedRuleNeverReachesAHeader") {
    // A header is neither half of the record. An operator who wrote `topicKeysPattern` asked about keys,
    // and masking their headers as well is the "masking too much" failure the scoping comment names — it
    // is silent, and it hides fields nobody asked to have hidden.
    assertEquals(MaskingEngine.maskHeaders(List(keyScoped), orders, headers), headers)
  }

  test("aValueScopedRuleDoesReachAHeader") {
    // The other direction, so the case above cannot pass against a `maskHeaders` that masks nothing.
    assertEquals(
      MaskingEngine.maskHeaders(List(valueScoped), orders, headers),
      Map("authorization" -> "**********", "trace-id" -> "xyz")
    )
  }

  test("aKeyScopedRuleStillMasksTheKeyItWasWrittenFor") {
    // And the rule itself is a working rule, so "never reaches a header" is about the header and not about
    // a rule that matches nothing at all.
    val keyDocument = json("""{"authorization":"abc"}""")

    assertEquals(
      MaskingEngine.maskJson(List(keyScoped), orders, Target.Key, keyDocument).noSpaces,
      """{"authorization":"***"}"""
    )
  }

  // -- a masked scalar is a string --------------------------------------------------------------------

  test("aMaskedNumberComesBackAsAStringOfTheSameLength") {
    // `4111111111111111` masked has to stop being a number. Left as one it is either unchanged — the whole
    // failure — or re-rendered at a different magnitude, which is a value nobody measured.
    val masked =
      MaskingEngine.maskJson(List(onField(stars, "card")), orders, Target.Value, json("""{"card":4111}"""))

    assertEquals(masked.noSpaces, """{"card":"****"}""")
    assertEquals(masked.hcursor.get[String]("card").toOption, Some("****"))
  }

  test("aMaskedBooleanComesBackAsAStringToo") {
    val masked =
      MaskingEngine.maskJson(List(onField(stars, "flag")), orders, Target.Value, json("""{"flag":true}"""))

    assertEquals(masked.noSpaces, """{"flag":"****"}""")
  }

  test("aMaskedNumberKeepingItsEndsKeepsThemAsDigitsAndHidesTheRest") {
    // The Kouncil case, over a number rather than a string: the last four survive and everything before
    // them is replaced, at the same length, as a string.
    val keepFour = MaskingKind.Mask("*", KeepEnds(0, 4))
    val card = json("""{"card":4111111111111111}""")
    val masked = MaskingEngine.maskJson(List(onField(keepFour, "card")), orders, Target.Value, card)

    assertEquals(masked.noSpaces, """{"card":"************1111"}""")
  }
}
