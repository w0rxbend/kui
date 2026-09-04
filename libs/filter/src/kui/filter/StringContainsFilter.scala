package kui.filter

import java.util.Locale

import cats.Applicative

/** MS-006: does this record contain this text anywhere?
  *
  * "Anywhere" is the key as text, the value as text, and every header *value*. Header names are excluded on
  * purpose: a search for `trace` should not match every record in a topic whose producer sets a `trace-id`
  * header, which is what searching names would do and what makes such a search useless.
  *
  * Case-insensitive, because a user typing a search box is not choosing a case, and matching case would make
  * `FAILED` and `failed` two different searches over the same data.
  *
  * The empty filter matches everything, and it matches everything **through this code path** rather than by
  * being special-cased away by the caller — the same reason [[MessagePredicate.always]] exists.
  */
object StringContainsFilter {

  def apply[F[_]: Applicative](needle: String): MessagePredicate[F] = {
    // Lower-cased once, at construction, not once per record. On a page of twenty thousand records the
    // difference is twenty thousand string allocations that do nothing.
    val lowered = needle.toLowerCase(Locale.ROOT)

    new MessagePredicate[F] {
      def test(record: FilterableRecord): F[Either[FilterError, Boolean]] =
        Applicative[F].pure(Right(matches(record, lowered)))
    }
  }

  /** The match itself, as a pure function, so it is a table test rather than an effect. */
  def matches(record: FilterableRecord, loweredNeedle: String): Boolean =
    if loweredNeedle.isEmpty then true
    else
      contains(record.keyAsText, loweredNeedle) ||
      contains(record.valueAsText, loweredNeedle) ||
      record.headers.values.exists(contains(_, loweredNeedle))

  private def contains(haystack: String, loweredNeedle: String): Boolean =
    haystack.toLowerCase(Locale.ROOT).contains(loweredNeedle)
}
