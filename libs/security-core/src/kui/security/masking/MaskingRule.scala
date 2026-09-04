package kui.security.masking

import scala.util.matching.Regex

import cats.data.NonEmptyList

/** How much of a value survives masking: this many characters at the front and this many at the back.
  *
  * Kouncil offers `FIRST_5` and `LAST_5` as two fixed policies; this is the same idea with the numbers made
  * parameters, because the useful case — a card number showing its last four digits — is neither of theirs.
  * `KeepEnds(0, 4)` on `4111111111111111` gives `************1111`.
  */
final case class KeepEnds(prefix: Int, suffix: Int)

object KeepEnds {

  /** Nothing survives. The default, because a masking rule that keeps something by default keeps something
    * its author did not think about.
    */
  val none: KeepEnds = KeepEnds(0, 0)

  given CanEqual[KeepEnds, KeepEnds] = CanEqual.derived
}

/** What masking does to a matched field.
  *
  * | Kind      | On a matched field                                                                                                               | Notes                                         |
  * |:----------|:---------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------|
  * | `Remove`  | the key is deleted from the object                                                                                               | in an array, the element is removed           |
  * | `Mask`    | every character replaced, cycling through `replacementChars`, except `keep.prefix` leading and `keep.suffix` trailing characters | **the result is never longer than the input** |
  * | `Replace` | the value becomes the literal `replacement`                                                                                      | the type becomes string                       |
  */
enum MaskingKind {
  case Remove
  case Mask(replacementChars: String, keep: KeepEnds)
  case Replace(replacement: String)
}

object MaskingKind {
  given CanEqual[MaskingKind, MaskingKind] = CanEqual.derived
}

/** One masking policy: what to do, and to which fields of which topics.
  *
  * ## Scoping
  *
  * `fields` and `fieldsNamePattern` are **exclusive**: a rule names the fields it applies to, or it names a
  * pattern they match, and a rule that did both would have two answers to the same question. A rule with
  * neither applies to the **whole value**, which is the "this entire topic is sensitive" case and is the
  * reason neither is required.
  *
  * `topicKeysPattern` and `topicValuesPattern` decide which topics and which half of a record. A rule with
  * neither applies everywhere, which is a blunt instrument and occasionally the right one.
  *
  * ## Two things this deliberately does not do
  *
  * **Masking is never applied on produce.** ADR-023 says so and MSG-022's suite asserts it. Masking a value
  * on the way in would write the mask into the topic, and the original — the thing the mask exists to protect
  * — would be gone. A user who can see a masked field and then produces the record back would silently
  * destroy data.
  *
  * **Masking is not access control.** It hides a field from every reader equally; it does not know who is
  * reading. Per-group policies are DM-002 in M6, and adding the field now would ship an access control
  * nothing enforces, which is worse than not having one.
  *
  * ## What it protects, exactly
  *
  * A masked value is never *longer* than the value it replaced. That is a rule about a side channel: a mask
  * that padded a four-digit field out to sixteen characters would announce that the field was not a card
  * number, and a reader counting characters would learn something the mask was there to hide.
  */
final case class MaskingRule(
    kind: MaskingKind,
    fields: Option[NonEmptyList[String]],
    fieldsNamePattern: Option[Regex],
    topicKeysPattern: Option[Regex],
    topicValuesPattern: Option[Regex]
)

object MaskingRule {

  /** The whole value of every topic, masked. The starting point a scope is narrowed from. */
  def everything(kind: MaskingKind): MaskingRule =
    MaskingRule(kind, None, None, None, None)

  /** Named fields, at any depth, on every topic's value. */
  def onFields(kind: MaskingKind, first: String, rest: String*): MaskingRule =
    MaskingRule(kind, Some(NonEmptyList.of(first, rest*)), None, None, None)
}
