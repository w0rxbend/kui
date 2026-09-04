package kui.message.domain.ports

import cats.Applicative
import cats.syntax.all.*

import kui.kernel.ClusterId
import kui.kernel.error.{ApplicationError, KuiError}
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

/** One record as a filter sees it, for the test endpoint.
  *
  * It is not [[kui.message.domain.DecodedRecord]] because the record being tried against did not come from
  * Kafka: it came from the caller, out of the table they are looking at, and reconstructing a full decoded
  * record — with its serde names, its payload kinds and its per-target decode failures — from a document a
  * browser sent would be inventing five fields nothing reads in order to fill a type.
  *
  * A browse still evaluates against `DecodedRecord`, which is what it has in its hand. The adapter turns both
  * into whatever the engine wants, in one place.
  */
final case class FilterSample(
    partition: Int,
    offset: Long,
    timestampMs: Long,
    keyAsText: String,
    valueAsText: String,
    headers: Map[String, String]
)

object FilterSample {
  given CanEqual[FilterSample, FilterSample] = CanEqual.derived
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
  def compile(cluster: ClusterId, filter: FilterRef): F[Either[KuiError, CompiledFilter[F]]]

  /** Compiles an expression and answers with the handle a browse quotes it by.
    *
    * Registering is how a filter is *validated*: the id comes back only when the expression compiled, so the
    * editor learns that `record.value.staus == 'PAID'` is a typo before a browse over a million records
    * returns nothing and teaches the user their data is missing.
    *
    * The id is a pure function of the source, so registering the same expression twice is free and gives the
    * same answer — which is what lets a browse be re-run, a link be shared, and a second replica answer a
    * request the first one registered.
    */
  def register(cluster: ClusterId, source: String): F[Either[KuiError, String]]

  /** Runs an expression against one record the caller supplied, without touching Kafka.
    *
    * `Left` is a compile failure — the expression is wrong. A `Right` carrying [[FilterVerdict.Failed]] is a
    * *runtime* failure on this particular record, which is a different sentence for the editor to show: the
    * expression is legal and this record does not have the field it names.
    */
  def check(cluster: ClusterId, source: String, record: FilterSample): F[Either[KuiError, FilterVerdict]]
}

object FilterSource {

  /** What a deployment with no filter engine answers.
    *
    * Every method refuses with `KUI-UNSUPPORTED` rather than quietly matching everything. A filter that is
    * silently ignored is the worst available behaviour: the user narrows a browse, sees a million records
    * come back, and has no way to tell that their narrowing did nothing.
    */
  def unsupported[F[_]: Applicative]: FilterSource[F] = new FilterSource[F] {

    private val refusal: KuiError =
      ApplicationError.Unsupported("this deployment has no filter engine, so a smart filter cannot be run")

    def compile(cluster: ClusterId, filter: FilterRef): F[Either[KuiError, CompiledFilter[F]]] =
      refusal.asLeft[CompiledFilter[F]].pure[F]

    def register(cluster: ClusterId, source: String): F[Either[KuiError, String]] =
      refusal.asLeft[String].pure[F]

    def check(cluster: ClusterId, source: String, record: FilterSample): F[Either[KuiError, FilterVerdict]] =
      refusal.asLeft[FilterVerdict].pure[F]
  }
}
