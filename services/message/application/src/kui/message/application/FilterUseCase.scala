package kui.message.application

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.message.domain.ports.{FilterSample, FilterSource, FilterVerdict}

/** Registering a smart filter, and trying one against a single record (MS-007).
  *
  * ==Why this exists when it only forwards==
  *
  * Because the two things it forwards to are a *port*, and the API layer may not hold one — a route that
  * called `FilterSource` directly would be a route that knows which engine is deployed. It is also where the
  * two operations acquire names a reader recognises: "register this expression" and "try it against this
  * record" are the two things a filter editor does, and neither is called `compile`.
  *
  * ==Neither touches Kafka==
  *
  * Registering compiles a string. Testing evaluates a program against a record the caller already had. So
  * neither goes through `MutationGuard`, neither is refused on a read-only cluster, and neither writes an
  * audit record — there is nothing to audit, because nothing about the cluster changed.
  */
trait FilterUseCase[F[_]] {

  /** Compiles the expression and answers with the id a browse quotes it by.
    *
    * This is also the validation step. The id comes back only when the expression compiled, so an editor
    * learns about a typo here rather than from a browse that read a million records and matched none.
    */
  def register(cluster: ClusterId, source: String): F[Either[KuiError, String]]

  /** Runs the expression against one supplied record.
    *
    * `Left` means the expression is wrong. A `Right` carrying [[FilterVerdict.Failed]] means the expression
    * is legal and threw on this particular record — usually a field the record does not have — which is a
    * different sentence and needs to read as one.
    */
  def test(cluster: ClusterId, source: String, record: FilterSample): F[Either[KuiError, FilterVerdict]]
}

object FilterUseCase {

  def make[F[_]](filters: FilterSource[F]): FilterUseCase[F] = new FilterUseCase[F] {

    def register(cluster: ClusterId, source: String): F[Either[KuiError, String]] =
      filters.register(cluster, source)

    def test(cluster: ClusterId, source: String, record: FilterSample): F[Either[KuiError, FilterVerdict]] =
      filters.check(cluster, source, record)
  }
}
