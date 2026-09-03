package kui.kernel

import scala.util.matching.Regex

/** Why a value was refused by a smart constructor.
  *
  * Validation failures are values, never exceptions: a caller that builds a `TopicName` from something a user
  * typed gets an `Either` back and has to decide what to do about the bad half. The three cases exist because
  * they are the three shapes an operator-facing message needs — "that is not the right shape", "that number
  * is out of bounds", "those two fields disagree" — and because `libs/contracts-core` renders each of them
  * into the `details` array of the HTTP error envelope (ADR-034).
  */
enum ValidationError {

  /** The value has the wrong shape. `expected` describes the shape in words an operator can act on, not as a
    * bare regular expression.
    */
  case Format(field: String, expected: String, got: String)

  /** The value is a number outside the allowed interval. `min` and `max` are `None` when that end is
    * unbounded, and are strings so that the same case can describe an `Int`, a `Long` or a size.
    */
  case Range(field: String, min: Option[String], max: Option[String], got: String)

  /** Two or more values are individually fine but disagree with each other, such as an offset range whose
    * start is after its end.
    */
  case Invariant(field: String, rule: String)

  /** One sentence, safe to show a user. It never echoes anything but the value that was rejected, which the
    * caller supplied in the first place.
    */
  def message: String = this match {
    case Format(field, expected, got) => s"$field must be $expected, got '$got'"
    case Range(field, min, max, got) =>
      val bounds = (min, max) match {
        case (Some(low), Some(high)) => s"between $low and $high"
        case (Some(low), None) => s"at least $low"
        case (None, Some(high)) => s"at most $high"
        case (None, None) => "a valid number"
      }
      s"$field must be $bounds, got '$got'"
    case Invariant(field, rule) => s"$field is invalid: $rule"
  }

  /** The field the failure is about, for the `details[].field` of the error envelope.
    *
    * It is not called `field`: all three cases already have a constructor parameter of that name, and a
    * method of the same name on the enum itself would clash with every one of them.
    */
  def fieldName: String = this match {
    case Format(field, _, _) => field
    case Range(field, _, _, _) => field
    case Invariant(field, _) => field
  }
}

object ValidationError {
  given CanEqual[ValidationError, ValidationError] = CanEqual.derived
}

/** The checks the smart constructors in this module share.
  *
  * They live in one place so that "non-empty and at most 255 characters" means exactly the same thing, and
  * produces exactly the same `ValidationError`, for every identifier that says it.
  */
private[kernel] object Checks {

  def matching(field: String, pattern: Regex, expected: String)(
      raw: String
  ): Either[ValidationError, String] =
    if pattern.matches(raw) then Right(raw)
    else Left(ValidationError.Format(field, expected, raw))

  /** Non-empty and no longer than `max` characters: the rule almost every free-form Kafka name follows, where
    * Kafka itself imposes no stricter shape.
    */
  def bounded(field: String, max: Int)(raw: String): Either[ValidationError, String] =
    if raw.nonEmpty && raw.length <= max then Right(raw)
    else Left(ValidationError.Format(field, s"1 to $max characters", raw))

  def nonNegative(field: String)(n: Int): Either[ValidationError, Int] =
    if n >= 0 then Right(n) else Left(ValidationError.Range(field, Some("0"), None, n.toString))

  def nonNegativeLong(field: String)(n: Long): Either[ValidationError, Long] =
    if n >= 0L then Right(n) else Left(ValidationError.Range(field, Some("0"), None, n.toString))

  def positive(field: String)(n: Int): Either[ValidationError, Int] =
    if n > 0 then Right(n) else Left(ValidationError.Range(field, Some("1"), None, n.toString))

  def within(field: String, min: Int, max: Int)(n: Int): Either[ValidationError, Int] =
    if n >= min && n <= max then Right(n)
    else Left(ValidationError.Range(field, Some(min.toString), Some(max.toString), n.toString))
}
