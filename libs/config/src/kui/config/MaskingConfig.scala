package kui.config

import scala.util.matching.Regex

import cats.data.NonEmptyList

import kui.security.masking.{KeepEnds, MaskingKind, MaskingRule}

/** One cluster's masking policies: `kui.clusters.<n>.masking[]` (DM-001, ADR-023).
  *
  * This is the section that turns `MaskingEngine` from a library nothing calls into a rule an operator can
  * write down. Kafbat's model, key for key — `type`, `fields` xor `fieldsNamePattern`,
  * `maskingCharsReplacement`, `replacement`, `topicKeysPattern`, `topicValuesPattern` — plus Kouncil's
  * `FIRST_5 | LAST_5` generalised to `keep: { prefix, suffix }`, which is ADR-023's decision and not a new
  * one. A migrated configuration therefore keeps meaning what it meant.
  *
  * {{{
  * kui:
  *   clusters:
  *     - name: "Production"
  *       bootstrapServers: ["kafka:9092"]
  *       masking:
  *         - kind: mask
  *           fields: [cardNumber]
  *           keep:
  *             suffix: 4
  *           topicValuesPattern: "payments\\..*"
  *         - kind: remove
  *           fieldsNamePattern: ".*[Pp]assword.*"
  * }}}
  *
  * ==Every field defaults, and the section itself is absent by default==
  *
  * A cluster with no `masking` block behaves exactly as every cluster behaved before this type existed:
  * `MaskingEngine.applies` is false for it, the browse takes the fast path, and not a byte is re-walked. That
  * is the standing rule for every configuration section this project has added — an existing YAML still boots
  * unchanged — and it is why this produces [[MaskingConfig.empty]] rather than failing on an absent key.
  *
  * ==Why this produces `MaskingRule` and not a parallel set of configuration types==
  *
  * The same argument `kui.rbac` makes, which is why `libs/config` already depends on `libs/security-core`:
  * the translation is where the mistakes live. Producing the engine's own type at load time means a rule that
  * cannot be built is a startup error naming the key, instead of a policy that loads, looks right in the file
  * and masks nothing.
  */
final case class MaskingConfig(rules: List[MaskingRule] = Nil) {

  /** Whether this cluster configures any masking at all. The message service asks exactly this before it
    * builds anything per-topic.
    */
  def isEmpty: Boolean = rules.isEmpty

  def nonEmpty: Boolean = rules.nonEmpty

  /** How many rules, and nothing about what they say.
    *
    * `ClusterConfig.toString` prints this. A rule names an operator's own field names and topic patterns, and
    * "which fields are worth hiding" is itself a fact about the data — printing the roster into a startup
    * diagnostic would put in `docker logs` a shortlist of exactly where the secrets are.
    */
  override def toString: String = s"MaskingConfig(${rules.size} rule(s))"
}

object MaskingConfig {

  /** A cluster that configures no masking. */
  val empty: MaskingConfig = MaskingConfig(Nil)

  /** What a rule does, in the spelling the file uses.
    *
    * Lower-case here and matched case-insensitively below, because ADR-023 quotes Kafbat's
    * `REMOVE | MASK | REPLACE` in upper case and Kafbat's own YAML writes them either way. An operator
    * copying from one document into the other must not be refused over capitalisation.
    */
  enum Kind(val wire: String) {
    case Remove extends Kind("remove")
    case Mask extends Kind("mask")
    case Replace extends Kind("replace")
  }

  object Kind {
    val All: List[Kind] = values.toList

    given CanEqual[Kind, Kind] = CanEqual.derived
  }

  /** What a `mask` rule writes when the operator does not say. One asterisk, cycled.
    *
    * Named rather than left to `MaskingEngine`'s own empty-string branch so that the configuration model
    * states the default instead of inheriting it: a reader of this file can answer "what does `kind: mask`
    * with nothing else do" without opening the engine.
    */
  val DefaultReplacementChars: String = "*"

  /** The most characters either end of a `keep` may preserve.
    *
    * A bound exists because `keep` is the one knob that makes a mask reveal *more*, and a typo of
    * `suffix: 44` on a sixteen-digit card number would reveal the whole of it while still looking like a
    * masking rule in the file. Twenty is past every real "show the last four" and nowhere near a payload.
    */
  val MaxKeep: Int = 20

  /** A rule as the file spells it, before it is known to be a rule.
    *
    * The drafting step is what lets the validation below speak about combinations — `kind: remove` with a
    * `replacement`, `fields` beside `fieldsNamePattern` — which no per-key reader can see, because each of
    * those keys is individually well-formed.
    *
    * Every `Option` here means "the operator did not write this key", never "the operator wrote the default".
    * That distinction is the whole mechanism: a key that is silently ignored because it does not apply to
    * this `kind` is exactly the kind of dead configuration this loader refuses everywhere else.
    */
  final case class Draft(
      kind: Kind,
      fields: Option[NonEmptyList[String]] = None,
      fieldsNamePattern: Option[Regex] = None,
      topicKeysPattern: Option[Regex] = None,
      topicValuesPattern: Option[Regex] = None,
      replacement: Option[String] = None,
      replacementChars: Option[String] = None,
      keepPrefix: Option[Int] = None,
      keepSuffix: Option[Int] = None
  )

  /** `remove`, `mask` or `replace`, named rather than guessed at. */
  def readKind(raw: String): Either[String, Kind] = {
    val wanted = raw.trim.toLowerCase
    Kind.All
      .find(_.wire == wanted)
      .toRight(s"'$raw' is not a masking kind; the kinds are ${Kind.All.map(_.wire).mkString(", ")}")
  }

  /** The `fields` list: at least one name, each of them non-blank.
    *
    * A YAML sequence of scalars reaches this loader as a comma-separated string — that is how every list in
    * this file is read, and how a list is spelled in an environment variable — so a field name containing a
    * comma cannot be written here. That is a real limit and it is stated rather than hidden: such a field is
    * named with `fieldsNamePattern` instead, which is read as one value.
    */
  def readFields(raw: String): Either[String, NonEmptyList[String]] =
    raw.split(',').toList.map(_.trim).filter(_.nonEmpty) match {
      case Nil => Left("must name at least one field")
      case first :: rest => Right(NonEmptyList(first, rest))
    }

  /** Compiles a pattern, naming the offending expression rather than Java's parser noise.
    *
    * Delegates to [[ClusterSerdeConfig.readPattern]] so that `topicValuesPattern` cannot come to mean one
    * thing under `serde` and another under `masking`; both are anchored by `Regex.matches` at the point of
    * use, which is what makes `orders.*` match `orders.v1` and not `legacy.orders.v1`.
    *
    * **This is where `MaskingEngine`'s scaladoc promise is kept.** That file says an unusable regex "is
    * caught at startup, where an unusable regex is a configuration error"; until this section existed there
    * was no startup to catch it at, because there was no way to write one down.
    */
  def readPattern(raw: String): Either[String, Regex] = ClusterSerdeConfig.readPattern(raw)

  /** How many characters a `keep` end may preserve. */
  def readKeep(raw: String): Either[String, Int] =
    raw.trim.toIntOption.toRight(s"'$raw' is not a whole number").flatMap { value =>
      if value < 0 then Left(s"$value is negative; a kept end is a count of characters")
      else if value > MaxKeep then Left(s"$value is above the maximum of $MaxKeep")
      else Right(value)
    }

  /** One drafted rule, as a rule — or the sentence that says why it is not one.
    *
    * Each refusal below is a combination that the engine would accept and quietly do nothing useful with,
    * which is the failure mode this whole loader exists to prevent: a masking policy that loads, reads
    * correctly to its author and protects nothing is worse than one that refuses to start, because the first
    * is discovered by whoever reads the data.
    */
  def validate(draft: Draft): Either[String, MaskingRule] =
    for {
      _ <- refuseBothFieldSelectors(draft)
      _ <- refuseKeysThatDoNotApply(draft)
      kind <- kindOf(draft)
    } yield MaskingRule(
      kind = kind,
      fields = draft.fields,
      fieldsNamePattern = draft.fieldsNamePattern,
      topicKeysPattern = draft.topicKeysPattern,
      topicValuesPattern = draft.topicValuesPattern
    )

  /** `fields` and `fieldsNamePattern` are exclusive, and the engine is why.
    *
    * `MaskingEngine.fieldMatches` reads `(Some(names), _)` first, so a rule carrying both applies the list
    * and never looks at the pattern. An operator who wrote both meant both; getting one of them silently is
    * the difference between a masked field and an exposed one, and nothing in a running system would say
    * which of the two they got.
    */
  private def refuseBothFieldSelectors(draft: Draft): Either[String, Unit] =
    if draft.fields.isDefined && draft.fieldsNamePattern.isDefined then
      Left(
        "names both `fields` and `fieldsNamePattern`, and a rule may name one or the other; " +
          "the engine applies `fields` and ignores the pattern, so the pattern would mask nothing. " +
          "Split it into two rules"
      )
    else Right(())

  /** A key that this `kind` does not read is a mistake, not a preference.
    *
    * `replacement` on a `mask` rule, or `keep` on a `remove` rule, is configuration that has no effect at all
    * — and the symptom is a field that comes back looking wrong with nothing anywhere to explain it. The same
    * argument as `SerdePatternConfig.validate`'s: a rule that can never do what it says is a typo the
    * operator should hear about while they are still looking at their file.
    */
  private def refuseKeysThatDoNotApply(draft: Draft): Either[String, Unit] = {
    val keepWritten = draft.keepPrefix.isDefined || draft.keepSuffix.isDefined
    val misplaced = List(
      Option.when(draft.replacement.isDefined && draft.kind != Kind.Replace)(
        s"`replacement` is only read by `kind: replace`, and this rule is `kind: ${draft.kind.wire}`"
      ),
      Option.when(draft.replacementChars.isDefined && draft.kind != Kind.Mask)(
        s"`maskingCharsReplacement` is only read by `kind: mask`, and this rule is " +
          s"`kind: ${draft.kind.wire}`"
      ),
      Option.when(keepWritten && draft.kind != Kind.Mask)(
        s"`keep` is only read by `kind: mask`, and this rule is `kind: ${draft.kind.wire}`"
      )
    ).flatten

    misplaced match {
      case Nil => Right(())
      case problems => Left(s"${problems.mkString("; ")}; remove the key or change the kind")
    }
  }

  /** The engine's own kind, assembled from the keys this one reads. */
  private def kindOf(draft: Draft): Either[String, MaskingKind] =
    draft.kind match {
      case Kind.Remove => Right(MaskingKind.Remove)

      case Kind.Mask =>
        Right(
          MaskingKind.Mask(
            draft.replacementChars.getOrElse(DefaultReplacementChars),
            KeepEnds(draft.keepPrefix.getOrElse(0), draft.keepSuffix.getOrElse(0))
          )
        )

      // A `replace` with nothing to replace with is a `remove` written the long way, and it does not even
      // do that -- it leaves an empty string where the key was. Whatever the entry meant, it did not mean
      // this, so it is refused rather than guessed at. The key the loader reports names the entry.
      case Kind.Replace =>
        draft.replacement
          .toRight(
            s"is `kind: replace` and sets no `replacement`, so every matched field would become an " +
              "empty string. Set `replacement`, or use `kind: remove` to delete the field"
          )
          .map(MaskingKind.Replace.apply)
    }

  given CanEqual[MaskingConfig, MaskingConfig] = CanEqual.derived
}
