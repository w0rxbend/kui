package kui.gateway.api.search

import kui.contracts.Section
import kui.kernel.ServiceId
import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

/** Reading a freshness section as a search source has to read one.
  *
  * Two of the three list endpoints answer inside a `Section`, which has five states and only two of them
  * carry rows. `Stale` is used exactly like `Ok`: the names in it are the last ones that arrived, and a
  * search over slightly old names is far better than a search that says a cluster holds nothing.
  *
  * The other three carry no rows, and a source must not turn them into an empty result. "This service had
  * nothing to say about that cluster" and "nothing matched" look identical once they are both an empty list,
  * and the first is the state `partial` exists to report — so a data-less section becomes a `Left` and the
  * fold names the service. The error itself is never rendered: `SearchUseCase` reads only which service the
  * failure came from.
  */
private[search] object SearchSections {

  def data[A](service: ServiceId, what: String, section: Section[A]): Either[KuiError, A] =
    section match {
      case Section.Ok(value, _) => Right(value)
      case Section.Stale(value, _, _) => Right(value)
      case Section.Unavailable(_, message, _) => Left(refusal(message))
      case Section.Forbidden => Left(refusal(s"${service.value} did not permit reading $what"))
      case Section.NotConfigured => Left(refusal(s"${service.value} does not provide $what here"))
    }

  /** The upstream answered and had nothing to give. `Remote` rather than `Unreachable`, because the service
    * was reached: inventing a transport failure would put a false sentence in a log for a service that is
    * running perfectly.
    */
  private def refusal(message: String): KuiError =
    InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, message, Nil)
}
