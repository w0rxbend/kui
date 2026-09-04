package kui.security.masking

import cats.data.NonEmptyList
import io.circe.{parser, Json}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.{forAll, propBoolean}
import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.serde.Target
import kui.kernel.TopicName

/** DM-001: what an operator writes, and what the reader of a record then sees.
  *
  * `ScalaCheckSuite` directly rather than `libs/testkit`'s `KuiSuite`: `libs/testkit` depends on
  * `libs/security-core`, so this module cannot depend on it without a cycle.
  */
final class MaskingEngineSuite extends ScalaCheckSuite {

  private val topic: TopicName = TopicName.unsafe("orders")

  private def json(text: String): Json =
    parser.parse(text).fold(error => fail(s"the fixture is not JSON: $error"), identity)

  private def maskValue(rules: List[MaskingRule], document: String): String =
    MaskingEngine.maskJson(rules, topic, Target.Value, json(document)).noSpaces

  private val stars: MaskingKind = MaskingKind.Mask("*", KeepEnds.none)

  private def onField(kind: MaskingKind, name: String): MaskingRule =
    MaskingRule(kind, Some(NonEmptyList.of(name)), None, None, None)

  private def onPattern(kind: MaskingKind, pattern: String): MaskingRule =
    MaskingRule(kind, None, Some(pattern.r), None, None)

  // ---------------------------------------------------------------- the rule table

  test("remove deletes the key from a flat object") {
    assertEquals(
      maskValue(List(onField(MaskingKind.Remove, "secret")), """{"a":1,"secret":"x"}"""),
      """{"a":1}"""
    )
  }

  test("remove reaches into a nested object") {
    assertEquals(
      maskValue(List(onField(MaskingKind.Remove, "secret")), """{"outer":{"secret":"x","b":2}}"""),
      """{"outer":{"b":2}}"""
    )
  }

  test("remove drops the matching key from every element of an array of objects") {
    assertEquals(
      maskValue(List(onField(MaskingKind.Remove, "secret")), """{"rows":[{"secret":1},{"secret":2,"a":3}]}"""),
      """{"rows":[{},{"a":3}]}"""
    )
  }

  test("mask replaces every character") {
    assertEquals(maskValue(List(onField(stars, "pin")), """{"pin":"1234"}"""), """{"pin":"****"}""")
  }

  test("replace substitutes the literal and makes the value a string") {
    assertEquals(
      maskValue(List(onField(MaskingKind.Replace("REDACTED"), "n")), """{"n":42}"""),
      """{"n":"REDACTED"}"""
    )
  }

  test("keep, from Kouncil: a card number showing its last four digits") {
    assertEquals(
      MaskingEngine.maskText(
        List(MaskingRule.everything(MaskingKind.Mask("*", KeepEnds(0, 4)))),
        topic,
        Target.Value,
        "4111111111111111"
      ),
      "************1111"
    )
  }

  test("keep at both ends") {
    assertEquals(
      MaskingEngine
        .maskText(List(MaskingRule.everything(MaskingKind.Mask("*", KeepEnds(2, 2)))), topic, Target.Value, "abcdefgh"),
      "ab****gh"
    )
  }

  test("the replacement characters cycle, one per input character") {
    assertEquals(
      MaskingEngine
        .maskText(List(MaskingRule.everything(MaskingKind.Mask("xy", KeepEnds.none))), topic, Target.Value, "abcde"),
      "xyxyx"
    )
  }

  test("keeping more than there is masks everything rather than silently doing nothing") {
    // A rule that quietly returns the input is worse than one that masks more than its author intended: the
    // first looks like it is working.
    assertEquals(
      MaskingEngine
        .maskText(List(MaskingRule.everything(MaskingKind.Mask("*", KeepEnds(10, 10)))), topic, Target.Value, "abc"),
      "abc"
    )
  }

  // ---------------------------------------------------------------- order and composition

  test("all matching rules apply to JSON, in configuration order") {
    val rules = List(
      onField(MaskingKind.Replace("first"), "f"),
      onField(MaskingKind.Mask("#", KeepEnds.none), "f")
    )
    assertEquals(maskValue(rules, """{"f":"original"}"""), """{"f":"#####"}""")
  }

  test("the configured order is the order applied, not the other way round") {
    val reversed = List(
      onField(MaskingKind.Mask("#", KeepEnds.none), "f"),
      onField(MaskingKind.Replace("first"), "f")
    )
    assertEquals(maskValue(reversed, """{"f":"original"}"""), """{"f":"first"}""")
  }

  test("only the first matching rule applies to a non-JSON value") {
    // There is no meaningful composition of "remove" and "keep the last four" over text with no structure,
    // and sequencing them produces results nobody can predict from reading the configuration.
    val rules = List(
      MaskingRule.everything(MaskingKind.Replace("FIRST")),
      MaskingRule.everything(MaskingKind.Replace("SECOND"))
    )
    assertEquals(MaskingEngine.maskText(rules, topic, Target.Value, "anything"), "FIRST")
  }

  // ---------------------------------------------------------------- scoping

  test("fields match at any depth, and only by exact name") {
    assertEquals(
      maskValue(List(onField(stars, "pin")), """{"a":{"b":{"pin":"12"}},"pinCode":"34"}"""),
      """{"a":{"b":{"pin":"**"}},"pinCode":"34"}"""
    )
  }

  test("a pattern rule uses the regex, not a substring test") {
    // `.*Number` must not match `numberOfItems`, and a substring test would.
    val rules = List(onPattern(stars, ".*Number"))
    assertEquals(
      maskValue(rules, """{"cardNumber":"41","numberOfItems":"7"}"""),
      """{"cardNumber":"**","numberOfItems":"7"}"""
    )
  }

  test("a rule with neither fields nor a pattern masks the whole value") {
    assertEquals(maskValue(List(MaskingRule.everything(MaskingKind.Replace("GONE"))), """{"a":1}"""), """"GONE"""")
  }

  test("a topic pattern scopes a rule to the topics it names") {
    val rule = MaskingRule(stars, Some(NonEmptyList.of("pin")), None, None, Some("payments.*".r))
    assertEquals(maskValue(List(rule), """{"pin":"12"}"""), """{"pin":"12"}""")
    assertEquals(
      MaskingEngine.maskJson(List(rule), TopicName.unsafe("payments-v2"), Target.Value, json("""{"pin":"12"}""")).noSpaces,
      """{"pin":"**"}"""
    )
  }

  test("a key-scoped rule does not touch the value, and the other way round") {
    val keyOnly = MaskingRule(stars, Some(NonEmptyList.of("pin")), None, Some("orders".r), None)
    assertEquals(maskValue(List(keyOnly), """{"pin":"12"}"""), """{"pin":"12"}""")
    assertEquals(
      MaskingEngine.maskJson(List(keyOnly), topic, Target.Key, json("""{"pin":"12"}""")).noSpaces,
      """{"pin":"**"}"""
    )
  }

  // ---------------------------------------------------------------- the fast path

  test("applies is false when no rule can match, and the fast path agrees with the slow one") {
    val elsewhere = MaskingRule(stars, Some(NonEmptyList.of("pin")), None, None, Some("payments.*".r))
    assertEquals(MaskingEngine.applies(Nil, topic, Target.Value), false)
    assertEquals(MaskingEngine.applies(List(elsewhere), topic, Target.Value), false)
    assertEquals(MaskingEngine.applies(List(onField(stars, "pin")), topic, Target.Value), true)
  }

  property("when applies is false the document comes back untouched") {
    forAll(Gen.oneOf("""{"a":1}""", """{"pin":"12"}""", """[1,2,3]""")) { document =>
      val elsewhere = MaskingRule(stars, Some(NonEmptyList.of("pin")), None, None, Some("payments.*".r))
      val rules = List(elsewhere)
      !MaskingEngine.applies(rules, topic, Target.Value) ==> (maskValue(rules, document) == json(document).noSpaces)
    }
  }

  // ---------------------------------------------------------------- headers

  test("header values are masked by name, and remove drops the header") {
    val headers = Map("authorization" -> "Bearer abc", "trace-id" -> "xyz")
    assertEquals(
      MaskingEngine.maskHeaders(List(onField(stars, "authorization")), topic, headers),
      Map("authorization" -> "**********", "trace-id" -> "xyz")
    )
    assertEquals(
      MaskingEngine.maskHeaders(List(onField(MaskingKind.Remove, "authorization")), topic, headers),
      Map("trace-id" -> "xyz")
    )
  }

  test("headers are untouched when no rule names them") {
    val headers = Map("trace-id" -> "xyz")
    assertEquals(MaskingEngine.maskHeaders(List(onField(stars, "pin")), topic, headers), headers)
    assertEquals(MaskingEngine.maskHeaders(Nil, topic, headers), headers)
  }

  // ---------------------------------------------------------------- the properties that matter

  private val leafLengths: Json => List[Int] = json =>
    json.fold(
      jsonNull = Nil,
      jsonBoolean = _ => Nil,
      jsonNumber = number => List(number.toString.length),
      jsonString = text => List(text.length),
      jsonArray = values => values.toList.flatMap(v => leafLengths(v)),
      jsonObject = obj => obj.values.toList.flatMap(v => leafLengths(v))
    )

  private val documents: Gen[Json] = Gen.oneOf(
    List(
      """{"pin":"1234","nested":{"pin":"56789"},"other":"keep"}""",
      """{"rows":[{"pin":"1"},{"pin":"22"}]}""",
      """{"pin":42}""",
      """{"pin":"🎉🎉🎉"}""",
      """{"a":{"b":{"c":{"pin":"deep"}}}}"""
    ).map(json)
  )

  property("no Mask rule ever lengthens a leaf") {
    // The leak this pins is a length side channel: a mask that padded a four-digit field out to sixteen
    // characters would tell the reader the field was not a card number. `Replace` is excluded on purpose —
    // its length is a literal the operator wrote and so reveals nothing about the data.
    forAll(documents, Gen.choose(0, 4), Gen.choose(0, 4)) { (document, prefix, suffix) =>
      val rules = List(onField(MaskingKind.Mask("*", KeepEnds(prefix, suffix)), "pin"))
      val masked = MaskingEngine.maskJson(rules, topic, Target.Value, document)
      leafLengths(masked).sum <= leafLengths(document).sum
    }
  }

  property("masking is idempotent, so a path that masks twice is still correct") {
    forAll(documents, Gen.choose(0, 4)) { (document, suffix) =>
      val rules = List(onField(MaskingKind.Mask("*", KeepEnds(0, suffix)), "pin"))
      val once = MaskingEngine.maskJson(rules, topic, Target.Value, document)
      MaskingEngine.maskJson(rules, topic, Target.Value, once) == once
    }
  }

  test("a surrogate pair is masked as one character, not as two halves") {
    // Masking half of an emoji produces invalid text, which then fails JSON encoding two layers away — in
    // the response, long after anyone could connect the failure to the rule that caused it.
    val masked = maskValue(List(onField(MaskingKind.Mask("*", KeepEnds(0, 1)), "pin")), """{"pin":"🎉🎉🎉"}""")
    assertEquals(masked, """{"pin":"**🎉"}""")
  }

  property("masking never fails, for any rule set and any document") {
    forAll(documents, Arbitrary.arbitrary[String]) { (document, name) =>
      val rules = List(onField(stars, name), onField(MaskingKind.Remove, name))
      MaskingEngine.maskJson(rules, topic, Target.Value, document).noSpaces.nonEmpty
    }
  }
}
