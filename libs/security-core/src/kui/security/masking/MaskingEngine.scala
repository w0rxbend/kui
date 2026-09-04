package kui.security.masking

import io.circe.{Json, JsonObject}

import kui.kernel.TopicName
import kui.kernel.serde.Target

/** The masking rules, applied. Pure functions over `Json` and `String`, with no effect and no failure path.
  *
  * Every function here is total and the identity is a legal result, which is why none of them returns an
  * `Either`. The one dangerous case — a rule that matches nothing because of a typo — cannot be caught here
  * at all; it is caught at startup, where an unusable regex is a configuration error, and
  * `docs/operations/masking.md` tells the operator to check a rule against a real record before trusting it.
  *
  * ## The application order
  *
  * A **JSON** value gets **every** matching rule, in configuration order. Two rules on the same field
  * compose, which is what lets an operator write "replace this field" and "mask everything else" and get
  * both.
  *
  * A **non-JSON** value gets the **first** matching rule's string form only. There is no meaningful way to
  * compose "remove" and "keep the last four characters" over a payload with no structure, and applying rules
  * in sequence to flat text produces results that depend on rule order in ways nobody can predict from
  * reading the configuration.
  *
  * This is Kafbat's order, kept so that a migrating operator's policies behave the way their old tool
  * behaved.
  *
  * ## Where it runs
  *
  * After deserialization and before any DTO leaves the service, including `originalValue` (ADR-023). This
  * object does not decide that — the browse use case does — but the placement is the entire point: a masked
  * field that is still readable in one response shape is not masked.
  */
object MaskingEngine {

  /** Whether any rule could apply to this topic and target.
    *
    * The fast path for the overwhelmingly common case of no rules at all. Without it every record would be
    * re-parsed and re-walked to discover there was nothing to do, which is the difference between masking
    * costing nothing on an unmasked topic and costing a JSON round trip per record.
    */
  def applies(rules: List[MaskingRule], topic: TopicName, target: Target): Boolean =
    rules.exists(scopeMatches(_, topic, target))

  /** All matching rules, in configuration order, applied to a JSON document. */
  def maskJson(rules: List[MaskingRule], topic: TopicName, target: Target, json: Json): Json =
    rules.filter(scopeMatches(_, topic, target)).foldLeft(json)((document, rule) => apply(rule, document))

  /** The first matching rule's string form, for a payload that is not JSON. */
  def maskText(rules: List[MaskingRule], topic: TopicName, target: Target, text: String): String =
    rules.find(scopeMatches(_, topic, target)).fold(text)(rule => maskString(rule.kind, text))

  /** Header values, masked by header name against the same field rules.
    *
    * Header names are matched the way field names are, because a header called `authorization` is exactly as
    * sensitive as a field called `authorization` and an operator should not have to say so twice. A rule
    * scoped to `topicKeysPattern` does not apply: headers belong to the record, not to its key or its value,
    * so only value-scoped and unscoped rules reach them.
    */
  def maskHeaders(
      rules: List[MaskingRule],
      topic: TopicName,
      headers: Map[String, String]
  ): Map[String, String] = {
    val applicable = rules.filter(scopeMatches(_, topic, Target.Value))
    if applicable.isEmpty then headers
    else
      headers.flatMap { (name, value) =>
        applicable.filter(rule => fieldMatches(rule, name)) match {
          case Nil => Some(name -> value)
          case matched =>
            // `Remove` on a header drops the header entirely, matching what it does to an object key.
            if matched.exists(_.kind == MaskingKind.Remove) then None
            else Some(name -> matched.foldLeft(value)((current, rule) => maskString(rule.kind, current)))
        }
      }
  }

  // -------------------------------------------------------------------------------------------
  // scope
  // -------------------------------------------------------------------------------------------

  /** Which topics and which half of a record a rule reaches.
    *
    * The two patterns behave as a pair, not independently:
    *
    *   - **Neither set** — the rule applies to keys and values of every topic. That is what makes "mask
    *     `cardNumber` wherever it appears" writable in one line, which matters because the topics a field
    *     appears in are exactly the ones the operator has not thought of yet.
    *   - **One set** — the rule applies to that half only, on the topics the pattern names. An operator who
    *     wrote `topicKeysPattern` and nothing else meant the keys; reading the absent `topicValuesPattern` as
    *     "every value everywhere" would mask far more than they asked for, and masking too much is a silent,
    *     hard-to-notice kind of wrong.
    *   - **Both set** — each half is scoped by its own pattern.
    */
  private def scopeMatches(rule: MaskingRule, topic: TopicName, target: Target): Boolean = {
    val unscoped = rule.topicKeysPattern.isEmpty && rule.topicValuesPattern.isEmpty
    val pattern = target match {
      case Target.Key => rule.topicKeysPattern
      case Target.Value => rule.topicValuesPattern
    }
    unscoped || pattern.exists(regex => regex.matches(topic.value))
  }

  /** Whether a rule applies to a field of this name.
    *
    * A rule with neither `fields` nor `fieldsNamePattern` matches nothing *by name* — it applies to the whole
    * value instead, which [[apply]] handles before it ever walks into the document.
    */
  private def fieldMatches(rule: MaskingRule, name: String): Boolean =
    (rule.fields, rule.fieldsNamePattern) match {
      case (Some(names), _) => names.exists(_ == name)
      case (None, Some(regex)) => regex.matches(name)
      case (None, None) => false
    }

  private def isWholeValueRule(rule: MaskingRule): Boolean =
    rule.fields.isEmpty && rule.fieldsNamePattern.isEmpty

  // -------------------------------------------------------------------------------------------
  // the walk
  // -------------------------------------------------------------------------------------------

  private def apply(rule: MaskingRule, json: Json): Json =
    if isWholeValueRule(rule) then maskLeaf(rule.kind, json).getOrElse(Json.Null)
    else walk(rule, json)

  /** Objects and arrays, to any depth.
    *
    * Recursion terminates without a depth counter because `Json` is a finite tree: circe has no way to
    * express a cycle, so there is no cycle to guard against.
    */
  private def walk(rule: MaskingRule, json: Json): Json =
    json.fold(
      jsonNull = json,
      jsonBoolean = _ => json,
      jsonNumber = _ => json,
      jsonString = _ => json,
      jsonArray = values => Json.fromValues(values.map(walk(rule, _))),
      jsonObject = obj => Json.fromJsonObject(walkObject(rule, obj))
    )

  private def walkObject(rule: MaskingRule, obj: JsonObject): JsonObject =
    JsonObject.fromIterable(
      obj.toList.flatMap { (name, value) =>
        if fieldMatches(rule, name) then maskLeaf(rule.kind, value).map(name -> _)
        else List(name -> walk(rule, value))
      }
    )

  /** One matched value.
    *
    * `None` means "this entry disappears": the key is deleted from its object, or the element is dropped from
    * its array. Every other kind produces a value, and a masked or replaced value is always a JSON string —
    * masking a number and keeping it a number would either change its magnitude or fail to hide it.
    */
  private def maskLeaf(kind: MaskingKind, value: Json): Option[Json] =
    kind match {
      case MaskingKind.Remove => None
      case MaskingKind.Replace(replacement) => Some(Json.fromString(replacement))
      case mask @ MaskingKind.Mask(_, _) =>
        // A matched object or array is masked leaf by leaf rather than flattened to its JSON text: replacing
        // `{"a":"secret"}` with `*************` would tell the reader how long the document was, and would
        // turn an object into a string in a way that breaks the table view's flattener two layers away.
        Some(
          value.fold(
            jsonNull = value,
            jsonBoolean = flag => Json.fromString(maskString(mask, flag.toString)),
            jsonNumber = number => Json.fromString(maskString(mask, number.toString)),
            jsonString = text => Json.fromString(maskString(mask, text)),
            jsonArray = values => Json.fromValues(values.map(v => maskLeaf(mask, v).getOrElse(Json.Null))),
            jsonObject =
              obj => Json.fromJsonObject(obj.mapValues(v => maskLeaf(mask, v).getOrElse(Json.Null)))
          )
        )
    }

  /** The string form of a rule, which is what a non-JSON payload and a header value get.
    *
    * Characters, not bytes, and code points, not `Char`s. Masking half of an emoji produces invalid text,
    * which then fails JSON encoding two layers away — in the response, long after anyone could connect the
    * failure to the rule that caused it.
    */
  private[masking] def maskString(kind: MaskingKind, text: String): String =
    kind match {
      case MaskingKind.Remove => ""
      case MaskingKind.Replace(replacement) => replacement
      case MaskingKind.Mask(chars, keep) => maskKeepingEnds(text, chars, keep)
    }

  private def maskKeepingEnds(text: String, chars: String, keep: KeepEnds): String = {
    val points: Vector[Int] = codePoints(text)
    val total = points.length
    val prefix = keep.prefix.max(0).min(total)
    // If the two kept ends would overlap, the suffix yields: keeping more than the input has would mean
    // returning the input unmasked, and a rule that silently does nothing is worse than one that masks
    // more than its author intended.
    val suffix = keep.suffix.max(0).min(total - prefix)
    val maskedCount = total - prefix - suffix

    if maskedCount <= 0 then text
    else {
      val replacement =
        if chars.isEmpty then "*" * maskedCount
        else {
          val cycle = codePoints(chars)
          // Cycling through the replacement characters is Kafbat's behaviour, and it is one replacement
          // code point per input code point — never more — which is what keeps the result no longer than
          // the input.
          (0 until maskedCount)
            .map(index => new String(Character.toChars(cycle(index % cycle.length))))
            .mkString
        }
      new String(points.take(prefix).flatMap(Character.toChars).toArray) +
        replacement +
        new String(points.takeRight(suffix).flatMap(Character.toChars).toArray)
    }
  }

  /** Code points, not `Char`s. A `Char` is half of an emoji, and half of an emoji is invalid text.
    *
    * Walked with `unfold` rather than `String.codePoints()`. That method returns a
    * `java.util.stream.IntStream` and Scala.js has no `java.util.stream` at all: this module is
    * cross-compiled, so the obvious one-liner compiles perfectly on the JVM and fails at *link* time for the
    * browser build — which is only discovered by running the JS suite, not by compiling. `codePointAt` and
    * `charCount` exist on both platforms.
    */
  private def codePoints(text: String): Vector[Int] =
    Vector.unfold(0) { index =>
      Option.when(index < text.length) {
        val point = text.codePointAt(index)
        (point, index + Character.charCount(point))
      }
    }
}
