package kui.kernel

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all.*

/** Building one valid value out of several fields, reporting *every* problem at once.
  *
  * The smart constructors return `Either`, which stops at the first failure. That is the right answer when
  * one value is being built, and the wrong answer when a form is being validated: a user who typed three bad
  * fields should be told about three, not about the first one and then, after another round trip, the second.
  *
  * `Validation` is `Either`'s accumulating sibling. Combine several of them and the failures pile up into a
  * `NonEmptyList[ValidationError]`, which is exactly the list the `details` array of the HTTP error envelope
  * wants (ADR-034).
  *
  * {{{
  * val topic   = TopicName.from(raw.topic).toValidation
  * val cluster = ClusterId.from(raw.cluster).toValidation
  * (topic, cluster).mapN(BrowseKey.apply)   // Invalid(both errors) when both are wrong
  * }}}
  */
type Validation[+A] = ValidatedNel[ValidationError, A]

object Validation {

  def valid[A](a: A): Validation[A] = Validated.validNel(a)

  def invalid[A](error: ValidationError): Validation[A] = Validated.invalidNel(error)

  /** Turns the accumulated failures back into an `Either`, for a caller that only reports the list. */
  def toEither[A](validation: Validation[A]): Either[NonEmptyList[ValidationError], A] =
    validation.toEither
}

extension [A](either: Either[ValidationError, A]) {

  /** Moves a smart constructor's result into the accumulating world, so it can be combined with others
    * through `mapN` or `traverse`.
    */
  def toValidation: Validation[A] = either.toValidatedNel
}
