package kui.message.domain.ports

import kui.kernel.error.KuiError
import kui.message.domain.{DecodedRecord, FilterRef}

/** What happened when a filter was asked about one record.
  *
  * There are three outcomes and not two, and collapsing them is the defect this type exists to prevent. An
  * expression that throws — a field that is absent, a division by zero — is neither a match nor a non-match,
  * and counting it as "did not match" turns a broken filter into a screen that says "no records found". The
  * user then concludes their data is missing rather than their expression is wrong.
  */
enum FilterVerdict {
  case Matched
  case DidNotMatch

  /** Counted into the `filterErrors` of the `consumed` event and shown; never fatal to the stream (ADR-017).
    */
  case Failed(reason: String)
}

object FilterVerdict {
  given CanEqual[FilterVerdict, FilterVerdict] = CanEqual.derived
}

/** A compiled, reusable predicate over records. */
trait CompiledFilter[F[_]] {
  def test(record: DecodedRecord): F[FilterVerdict]
}

/** Where a smart filter comes from.
  *
  * Compilation is separated from evaluation because compiling is expensive and per request while evaluating
  * is cheap and per record, and because a compile failure is a *different* error with a different code
  * (`KUI-FILTER-COMPILE`) that the browser uses to underline the expression in the editor rather than to show
  * a form error.
  */
trait FilterSource[F[_]] {

  /** Compiles the filter a request named, from the cache if it is there and from the carried source if it is
    * not (ADR-017). `Left(KUI-FILTER-COMPILE)` names the position; `Left(KUI-UNSUPPORTED)` is what a
    * deployment where the filter engine failed to start answers, so that a request naming a filter fails
    * before the stream begins rather than silently returning everything.
    */
  def compile(filter: FilterRef): F[Either[KuiError, CompiledFilter[F]]]
}
