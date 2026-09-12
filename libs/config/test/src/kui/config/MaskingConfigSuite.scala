package kui.config

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.TopicName
import kui.kernel.serde.Target
import kui.security.masking.{KeepEnds, MaskingEngine, MaskingKind}
import kui.testkit.KuiSuite

/** That `kui.clusters[].masking[]` means what ADR-023 says it means (DM-001).
  *
  * The section exists so that `MaskingEngine` — built, unit-tested and callerless since wave 1 — has a rule
  * an operator can write down. So every case below is a sentence an operator would write, and the failures
  * are the mistakes they would make; where a mistake would previously have loaded and protected nothing, the
  * case asserts the key it now names.
  */
final class MaskingConfigSuite extends KuiSuite {

  private def load(yaml: String): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), Map.empty, UrlPolicy.Dev)
      .unsafeRunSync()

  private def cluster(yaml: String): ClusterConfig =
    load(yaml).fold(errors => fail(errors.render), _.clusters.head)

  private def problems(yaml: String): List[ConfigProblem] =
    load(yaml) match {
      case Left(errors) => errors.problems.toList.sortBy(_.key)
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def base(masking: String): String =
    s"""kui:
       |  auth:
       |    type: disabled
       |  clusters:
       |    - name: "Production"
       |      bootstrapServers:
       |        - "kafka:9092"
       |$masking""".stripMargin

  private val payments: TopicName = TopicName.unsafe("payments.v1")

  // ------------------------------------------------------------------ the section is optional

  test("a cluster with no masking section is the cluster this repository had before the section existed") {
    val configured = cluster(base(""))

    assertEquals(configured.masking, MaskingConfig.empty)
    assert(configured.masking.isEmpty)
    // THE FAST PATH, ASSERTED RATHER THAN ASSUMED. Every existing deployment is this one, and the promise
    // made for it is that not a byte is re-walked: `applies` false means the browse never parses, never
    // masks and never counts.
    assert(!MaskingEngine.applies(configured.masking.rules, payments, Target.Value))
    assert(!MaskingEngine.applies(configured.masking.rules, payments, Target.Key))
  }

  test("a file written before this section existed loads as a cluster that masks nothing") {
    // The standing rule for every section this project has added: an existing YAML boots unchanged. Stated
    // over `valid.yaml`, the fixture that predates masking, rather than over an invented document.
    //
    // THE SHIPPED FILES ARE ASSERTED IN `ShippedConfigurationSuite` AND NOT HERE, and the distinction
    // stopped being cosmetic in wave 10: `deployment/quickstart/kui-quickstart.yaml` now configures two
    // rules on purpose, so a sentence claiming that no shipped file has a masking section is false and
    // this case's old name said exactly that while loading one fixture.
    val unmasked = KuiConfigSource
      .loadFrom[IO](
        Nil,
        List(ConfigFixtures.fixture("valid.yaml")),
        Map.empty,
        UrlPolicy.Dev
      )
      .unsafeRunSync()
      .fold(errors => fail(errors.render), identity)

    assert(unmasked.clusters.forall(_.masking.isEmpty))
  }

  // ------------------------------------------------------------------ the three kinds

  test("'hide the card number but keep its last four digits' is five lines") {
    val configured = cluster(
      base("""      masking:
             |        - kind: mask
             |          fields: [cardNumber]
             |          keep:
             |            suffix: 4
             |          topicValuesPattern: "payments\\..*"""".stripMargin)
    )

    assertEquals(configured.masking.rules.size, 1)

    val rule = configured.masking.rules.head
    assertEquals(rule.kind, MaskingKind.Mask("*", KeepEnds(0, 4)): MaskingKind)
    assertEquals(rule.fields.map(_.toList), Some(List("cardNumber")))
    assertEquals(rule.topicKeysPattern.map(_.regex), None)
    assertEquals(rule.topicValuesPattern.map(_.regex), Some("payments\\..*"))

    // The rule as the engine reads it, not only as the file parsed it: the two halves of this feature are
    // a loader and an engine, and a case that stopped at the parse would prove the loader agrees with
    // itself.
    assertEquals(
      MaskingEngine
        .maskJson(
          configured.masking.rules,
          payments,
          Target.Value,
          io.circe.parser.parse("""{"cardNumber":"4111111111111111"}""").toOption.get
        )
        .noSpaces,
      """{"cardNumber":"************1111"}"""
    )
  }

  test("remove and replace read the keys they are documented to read") {
    val configured = cluster(
      base("""      masking:
             |        - kind: remove
             |          fieldsNamePattern: ".*[Pp]assword.*"
             |        - kind: replace
             |          replacement: "<redacted>"
             |          fields: [ssn, taxId]""".stripMargin)
    )

    assertEquals(
      configured.masking.rules.map(_.kind),
      List(MaskingKind.Remove, MaskingKind.Replace("<redacted>"))
    )
    assertEquals(configured.masking.rules.head.fieldsNamePattern.map(_.regex), Some(".*[Pp]assword.*"))
    assertEquals(configured.masking.rules(1).fields.map(_.toList), Some(List("ssn", "taxId")))
  }

  test("the kind is read case-insensitively, because ADR-023 quotes Kafbat's REMOVE in upper case") {
    val configured = cluster(
      base("""      masking:
             |        - kind: REMOVE
             |          fields: [secret]""".stripMargin)
    )

    assertEquals(configured.masking.rules.map(_.kind), List(MaskingKind.Remove: MaskingKind))
  }

  test("rules keep the order the operator wrote them, because a JSON value gets all of them in order") {
    val configured = cluster(
      base("""      masking:
             |        - kind: replace
             |          replacement: "<gone>"
             |          fields: [a]
             |        - kind: mask
             |          fieldsNamePattern: ".*"""".stripMargin)
    )

    assertEquals(
      configured.masking.rules.map(_.kind),
      List(MaskingKind.Replace("<gone>"), MaskingKind.Mask("*", KeepEnds.none))
    )
  }

  test("a mask with nothing else said uses one asterisk, and the file says so rather than the engine") {
    val configured = cluster(
      base("""      masking:
             |        - kind: mask
             |          fields: [secret]""".stripMargin)
    )

    assertEquals(
      configured.masking.rules.head.kind,
      MaskingKind.Mask(MaskingConfig.DefaultReplacementChars, KeepEnds(0, 0)): MaskingKind
    )
  }

  test("the mask character an operator configured is the one the engine draws, and never the default") {
    // `maskingCharsReplacement` is one of the Kafbat keys this loader accepts by name, and until this case
    // existed the string appeared in no test in this repository: modelled in `Draft`, decoded in
    // `KuiConfigSource`, listed in `UnknownKeys.Known`, named in this file's scaladoc -- and exercised by
    // nothing. Both ends are asserted, because each has its own one-line way of going wrong. Dropping the
    // operator's value in `kindOf` masks with `*` and says nothing about it. Dropping the key's
    // `UnknownKeys.Known` entry is worse: a documented key becomes "is not a KUI configuration key" and
    // the deployment that wrote it does not start. Filed as W9-06/F5 and W9-06/F6.
    val configured = cluster(
      base("""      masking:
             |        - kind: mask
             |          fields: [cardNumber]
             |          maskingCharsReplacement: "#"
             |          keep:
             |            suffix: 4""".stripMargin)
    )

    assertEquals(configured.masking.rules.head.kind, MaskingKind.Mask("#", KeepEnds(0, 4)): MaskingKind)

    // As the engine reads it, for the reason the case five tests up gives: a loader agreeing with itself
    // is not the promise. The operator asked for hashes and the screen has to draw hashes.
    assertEquals(
      MaskingEngine
        .maskJson(
          configured.masking.rules,
          payments,
          Target.Value,
          io.circe.parser.parse("""{"cardNumber":"4111111111111111"}""").toOption.get
        )
        .noSpaces,
      """{"cardNumber":"############1111"}"""
    )
  }

  // ------------------------------------------------------------------ the refusals

  test("an uncompilable regex is a startup error naming the key, which is the engine's own promise") {
    // `MaskingEngine`'s scaladoc has said since wave 1 that a bad pattern "is caught at startup, where an
    // unusable regex is a configuration error". Until this section existed there was no startup to catch
    // it at, because there was no way to write one down. This is the case that makes the sentence true.
    val reported = problems(
      base("""      masking:
             |        - kind: remove
             |          fields: [secret]
             |          topicValuesPattern: "payments\\.((" """.stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0.topicValuesPattern"))
    assert(
      reported.head.problem.contains("not a valid regular expression"),
      clue = reported.head.problem
    )
  }

  test("a rule naming both fields and fieldsNamePattern is refused, because the engine reads only one") {
    // THE REFUSAL THAT COSTS THE MOST TO GET WRONG. `MaskingEngine.fieldMatches` matches `(Some(names), _)`
    // first, so a rule carrying both applies the list and never looks at the pattern. Loading it would
    // leave an operator believing a pattern was masking fields it has never seen.
    val reported = problems(
      base("""      masking:
             |        - kind: remove
             |          fields: [ssn]
             |          fieldsNamePattern: ".*[Ss]ecret.*"""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0"))
    assert(reported.head.problem.contains("would mask nothing"), clue = reported.head.problem)
  }

  test("a replace with no replacement is refused rather than silently emptying every matched field") {
    val reported = problems(
      base("""      masking:
             |        - kind: replace
             |          fields: [ssn]""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0"))
    assert(reported.head.problem.contains("empty string"), clue = reported.head.problem)
  }

  test("a key this kind does not read is refused, because it would be configuration with no effect") {
    val reported = problems(
      base("""      masking:
             |        - kind: remove
             |          fields: [ssn]
             |          replacement: "<redacted>"
             |          keep:
             |            suffix: 4""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0"))
    assert(reported.head.problem.contains("`replacement` is only read by"), clue = reported.head.problem)
    assert(reported.head.problem.contains("`keep` is only read by"), clue = reported.head.problem)
  }

  test("a misspelled kind names the kinds that exist") {
    val reported = problems(
      base("""      masking:
             |        - kind: obfuscate
             |          fields: [ssn]""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0.kind"))
    assert(reported.head.problem.contains("remove, mask, replace"), clue = reported.head.problem)
  }

  test("a keep that would reveal more than it hides is bounded") {
    // `keep` is the one knob that makes a mask reveal *more*. A `suffix: 44` on a sixteen-digit card
    // number reveals all of it while still looking like a masking rule in the file, and
    // `maskKeepingEnds` would comply: it clamps to the input length and returns the text unmasked.
    val reported = problems(
      base("""      masking:
             |        - kind: mask
             |          fields: [cardNumber]
             |          keep:
             |            suffix: 44""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0.keep.suffix"))
    assert(reported.head.problem.contains(s"above the maximum of ${MaskingConfig.MaxKeep}"))
  }

  test("a keep whose two ends together reveal the field is refused") {
    // THE RULE THIS PACKET OWNS, AND IT IS A RULE ABOUT A PAIR OF LEGAL VALUES. `readKeep` bounds each end
    // at `MaxKeep` and cannot see the other one, so `prefix: 20` passes, `suffix: 20` passes, and the rule
    // they form keeps forty characters of a sixteen-character card number -- all of it. Wave 9 measured the
    // shipped behaviour with a throw-away suite and got `4111111111111111` back from a rule that loaded and
    // looked correct in the file (W9-06/F4). The bound's own stated reason -- "a `suffix: 44` reveals the
    // whole of a card number while still looking like a masking rule" -- was defeated by writing 20 twice.
    //
    // The refusal is reported against the ENTRY and not against either key, because neither key is wrong:
    // the combination is. That is the same shape as `fields` beside `fieldsNamePattern` two cases up.
    val reported = problems(
      base("""      masking:
             |        - kind: mask
             |          fields: [cardNumber]
             |          keep:
             |            prefix: 20
             |            suffix: 20""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0"))
    assert(reported.head.problem.contains("keeps 40 characters"), clue = reported.head.problem)
    assert(reported.head.problem.contains(s"at most ${MaskingConfig.MaxKeep}"), clue = reported.head.problem)
  }

  test("a keep that sums to exactly the maximum still loads, because the bound is on what it reveals") {
    // The other side of the refusal above, and the reason it is a `>` rather than a `>=`: `MaxKeep` is the
    // most a rule may reveal, and a rule revealing exactly that much is a rule an operator wrote on
    // purpose. A bound that refused its own stated maximum would be a different bound with the same name.
    val configured = cluster(
      base("""      masking:
             |        - kind: mask
             |          fields: [iban]
             |          keep:
             |            prefix: 8
             |            suffix: 12""".stripMargin)
    )

    assertEquals(configured.masking.rules.head.kind, MaskingKind.Mask("*", KeepEnds(8, 12)): MaskingKind)
  }

  test("a keep bounded at both ends still hides a field shorter than the two ends together") {
    // THE HALF THE LOADER CANNOT SEE, asserted here because the two halves of this repair belong together.
    // A rule may be legal and still meet a value too short for it -- `keep: {suffix: 4}` is an honest rule
    // and a four-character `last4` is an honest value -- and the engine used to hand such a value back
    // untouched. It now masks the whole of it: these numbers bound what a mask may REVEAL, so the
    // arithmetic running out has to fail towards hiding.
    val configured = cluster(
      base("""      masking:
             |        - kind: mask
             |          fields: [last4]
             |          keep:
             |            suffix: 4""".stripMargin)
    )

    assertEquals(
      MaskingEngine
        .maskJson(
          configured.masking.rules,
          payments,
          Target.Value,
          io.circe.parser.parse("""{"last4":"4242"}""").toOption.get
        )
        .noSpaces,
      """{"last4":"****"}"""
    )
  }

  test("an empty fields list is refused rather than read as a rule that matches nothing by name") {
    val reported = problems(
      base("""      masking:
             |        - kind: remove
             |          fields: []""".stripMargin)
    )

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.0.fields"))
  }

  test("a gap in the rule index is refused, because renumbering would change which rule runs first") {
    // Written through the environment and not through YAML, because a YAML sequence cannot have a hole:
    // the shape this refusal exists for is `KUI_CLUSTERS_0_MASKING_0_*` and `..._2_*` with no `1` — a
    // deleted entry or a mistyped variable in a container's environment, which is exactly where a
    // deployment's masking rules are most likely to be written.
    //
    // Silently renumbering would make rule 2 run second. Order decides the outcome: a JSON value gets
    // every matching rule in configuration order, so "replace this field" then "mask everything else"
    // composes and the other order does not.
    val reported = KuiConfigSource
      .loadFrom[IO](
        Nil,
        List(ConfigFixtures.yaml(base(""))),
        Map(
          "KUI_CLUSTERS_0_MASKING_0_KIND" -> "remove",
          "KUI_CLUSTERS_0_MASKING_0_FIELDS" -> "a",
          "KUI_CLUSTERS_0_MASKING_2_KIND" -> "remove",
          "KUI_CLUSTERS_0_MASKING_2_FIELDS" -> "c"
        ),
        UrlPolicy.Dev
      )
      .unsafeRunSync() match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("a masking list numbered 0 and 2 was accepted")
    }

    assertEquals(reported.map(_.key), List("kui.clusters.0.masking.2"))
    assert(reported.head.problem.contains("no gaps"), clue = reported.head.problem)
  }

  test("a masking key nobody models is refused by name, like every other key in this loader") {
    val reported = problems(
      base("""      masking:
             |        - kind: remove
             |          field: [ssn]""".stripMargin)
    )

    // `field.0` and not `field`, because the leaf of a YAML sequence is what the unknown-key check walks
    // to. Naming the leaf is what the operator needs: it is where the typo is.
    assert(
      reported.exists(_.key.startsWith("kui.clusters.0.masking.0.field")),
      clue = reported.map(_.key).mkString(", ")
    )
  }

  // ------------------------------------------------------------------ what a diagnostic may print

  // ------------------------------------------------------------------ the operator page, read at last

  test("the operator's masking page names the kinds this loader accepts, and no others") {
    // W11-05/3, and the reason it is here rather than in a document linter. `docs/operations/masking.md`
    // was read by NOTHING: deleting the whole file left `./scripts/run-tests.sh` green at 4,350 cases,
    // while `MaskingEngine.scala:13` and `MaskingConfig.scala:231` both assert in prose that it exists.
    // W10-A2 closed the same hole for `observability.md` by comparing its metric table against
    // `MetricNames.all`; this is the same move over the half of this page that is checkable without
    // inventing a language.
    //
    // Resolving the file is itself half the gate: a page that is deleted or renamed fails here, which is
    // what makes those two production sentences true rather than merely plausible.
    val kinds = kindTableRows

    assertEquals(
      kinds,
      MaskingConfig.Kind.All.map(_.wire),
      "docs/operations/masking.md's kind table and MaskingConfig.Kind disagree about what a rule may do"
    )
  }

  test("the keep bound the operator page publishes is the bound this loader enforces") {
    // Four figures on that page, all of them MaxKeep, none of them read by anything until now. The one
    // that matters is the sum: wave 9 measured `keep: {prefix: 20, suffix: 20}` loading and returning a
    // sixteen-digit card number in full, and the page carries the migration note for it. A page that
    // published 40 where the loader enforces 20 would send an operator to write a rule that cannot start.
    val published = keepBoundFigures

    assertEquals(
      published.size,
      4,
      s"masking.md's four keep-bound figures read as ${published.size}: $published"
    )
    published.foreach(figure => assertEquals(figure, MaskingConfig.MaxKeep, clue = published.toString))
  }

  test("every refusal the operator page lists is a refusal this loader actually makes") {
    // The sharper direction, and the one a document linter cannot do: each row of "When the file is wrong"
    // is driven as YAML here and required to fail the load. A refusal removed from the loader turns its
    // fixture green and this case red; a row added to the page with no fixture behind it fails the count.
    //
    // Written as fixtures rather than by parsing the rows into YAML, because the row is English and the
    // mapping from English to a file is exactly the judgement a reviewer is supposed to make. What is
    // mechanical is the count, and the count is what caught the drift everywhere else in this repository.
    val documented = refusalTableRows

    assertEquals(
      documented.size,
      DocumentedRefusals.size,
      s"masking.md lists ${documented.size} refusals and this suite drives ${DocumentedRefusals.size}:\n" +
        documented.mkString("\n")
    )

    DocumentedRefusals.foreach { (what, yaml) =>
      val reported = load(base(yaml))
      assert(
        reported.isLeft,
        s"masking.md says KUI refuses '$what' and this file loaded:\n${base(yaml)}"
      )
    }
  }

  /** One YAML fragment per row of `masking.md`'s "When the file is wrong" table, in the order printed.
    *
    * The name beside each is the row it stands for, so a reader comparing the two can do it by eye; the
    * assertion above compares only how many there are, because the rest is judgement.
    */
  private val DocumentedRefusals: List[(String, String)] = List(
    "a pattern that will not compile" ->
      """      masking:
        |        - kind: remove
        |          fields: [secret]
        |          topicValuesPattern: "payments\\.((" """.stripMargin,
    "fields beside fieldsNamePattern" ->
      """      masking:
        |        - kind: remove
        |          fields: [secret]
        |          fieldsNamePattern: ".*[Ss]ecret.*"""".stripMargin,
    "kind: replace with no replacement" ->
      """      masking:
        |        - kind: replace
        |          fields: [email]""".stripMargin,
    "a key this kind does not read" ->
      """      masking:
        |        - kind: mask
        |          fields: [cardNumber]
        |          replacement: "<redacted>"""".stripMargin,
    "a keep end above the maximum" ->
      s"""      masking:
         |        - kind: mask
         |          fields: [cardNumber]
         |          keep:
         |            suffix: ${MaskingConfig.MaxKeep + 1}""".stripMargin,
    "a keep whose two ends together exceed the maximum" ->
      s"""      masking:
         |        - kind: mask
         |          fields: [cardNumber]
         |          keep:
         |            prefix: ${MaskingConfig.MaxKeep}
         |            suffix: ${MaskingConfig.MaxKeep}""".stripMargin
  )

  /** The first column of `masking.md`'s "The three kinds" table, body rows only.
    *
    * Sliced to that section and taken after the `| --- |` separator, so the column header (`kind`) is not
    * read as a kind and neither is any other table on the page — "Where masking happens" puts prose in its
    * first cell, and "When the file is wrong" puts a phrase there.
    */
  private def kindTableRows: List[String] =
    tableBody("## The three kinds").flatMap(row =>
      "^\\| `([a-z]+)` \\|".r.findFirstMatchIn(row).map(_.group(1))
    )

  /** The body rows of `masking.md`'s refusal table. */
  private def refusalTableRows: List[String] = tableBody("## When the file is wrong")

  /** The rows of the first table under `heading`, after its `| --- |` separator and before the blank line or
    * next heading that ends it.
    */
  private def tableBody(heading: String): List[String] = {
    val page = maskingPage
    val start = page.indexOf(heading)
    assert(start >= 0, s"docs/operations/masking.md has no section '$heading'")

    val section = page.substring(start).linesIterator.drop(1).takeWhile(!_.startsWith("## ")).toList
    val rows = section.dropWhile(!_.startsWith("| ---")).drop(1).takeWhile(_.startsWith("|"))

    assert(rows.nonEmpty, s"'$heading' in masking.md holds no table rows; the reader matched nothing")
    rows
  }

  /** Every figure `masking.md` publishes for the `keep` bound, in the order printed.
    *
    * Read off the whitespace-normalised page because two of the four are split across a line wrap, and a
    * reader that only saw the unwrapped ones would pass while the wrapped ones drifted.
    */
  private def keepBoundFigures: List[Int] = {
    val flat = maskingPage.replaceAll("\\s+", " ")

    List(
      "each end may be at most \\*{0,2}(\\d+)".r,
      "the two ends together may be at most \\*{0,2}(\\d+)".r,
      "a `keep` end above \\*{0,2}(\\d+)".r,
      "two ends together exceed \\*{0,2}(\\d+)".r
    ).flatMap(pattern => pattern.findFirstMatchIn(flat).map(_.group(1).toInt))
  }

  /** `docs/operations/masking.md`, which `MaskingEngine` and `MaskingConfig` both name in prose. */
  private def maskingPage: String = {
    val start = java.nio.file.Path.of("").toAbsolutePath
    val root = Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => java.nio.file.Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))

    val file = root.resolve("docs/operations/masking.md")
    if java.nio.file.Files.isRegularFile(file) then java.nio.file.Files.readString(file)
    else
      fail(
        "docs/operations/masking.md is missing, and MaskingEngine.scala and MaskingConfig.scala both " +
          "tell an operator to read it"
      )
  }

  test("a cluster's diagnostic prints how many rules it has and not one of their field names") {
    val configured = cluster(
      base("""      masking:
             |        - kind: remove
             |          fields: [nationalInsuranceNumber]""".stripMargin)
    )

    val rendered = configured.toString

    // The roster of masked fields is itself a map of where this cluster's secrets are. `docker logs` is
    // not the place for one, and `ClusterConfig.toString` is read into exactly that.
    assert(rendered.contains("masking=MaskingConfig(1 rule(s))"), clue = rendered)
    assert(!rendered.contains("nationalInsuranceNumber"), clue = rendered)
  }
}
