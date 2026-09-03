package kui.ui.clusters

import com.raquo.laminar.api.L.*

import kui.cluster.contract.dto.PingResponse
import kui.ui.kernel.api.ApiError
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState

/** The clusters page.
  *
  * M1 replaces the body of this with the real cluster list; what stays is the shape. It is here now because a
  * chain nobody exercises is a chain that breaks quietly, and this page exercises all of it in one click: the
  * contract client, `ApiClient`, `DataTable`, `ActionPermissionWrapper` and the feature's own state, against
  * a real service, through the real gateway.
  */
object ClustersPage {

  /** @param capability
    *   this feature's current state, so the Ping button can be disabled with an explanation when the cluster
    *   service is down while the page is open. The dimmed-entry case never reaches here — the shell renders
    *   the fallback panel instead — but a service that dies *while* the page is open does, and that is the
    *   ADR-032 stale-data case this page is the worked example of.
    */
  def apply(state: ClustersState, capability: Signal[FeatureState] = Val(FeatureState.Ready)): HtmlElement = {
    val message = Var("hello")

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters",
      h1(Messages.Title),
      p(cls := ClustersCss.Lead, Messages.Lead),
      div(
        cls := ClustersCss.Form,
        TextInput(
          value = message,
          label = Messages.MessageLabel,
          hint = Some(Messages.MessageHint),
          testId = Some("ping-message")
        ),
        ActionPermissionWrapper(
          action = Button(
            label = state.inFlight.map(busy => if busy then Messages.PingInFlight else Messages.PingButton),
            onClick = Observer[Unit](_ => state.ping(message.now())),
            variant = ButtonVariant.Primary,
            loading = state.inFlight,
            testId = Some("ping-button")
          ),
          capability = capability,
          testId = Some("ping-gate")
        )
      ),
      child.maybe <-- state.lastError.signal.map(_.map(errorLine)),
      child.maybe <-- state.stale.map(
        Option.when(_)(
          p(cls := ClustersCss.Stale, dataAttr("testid") := "stale-notice", Messages.StaleResults)
        )
      ),
      div(
        // The whole table is dimmed while its contents are known to be old, rather than one cell being
        // marked: the user's question is "can I trust what I am looking at", and the answer applies to
        // every row.
        cls(ClustersCss.TableStale) <-- state.stale,
        DataTable[PingResponse](
          columns = columns,
          rows = state.pings.signal,
          rowKey = reply => s"${reply.at}-${reply.message}",
          empty = () => EmptyState(Messages.EmptyTitle, description = Some(Messages.EmptyDescription)),
          testId = Some("ping-table")
        )
      )
    )
  }

  private def columns: List[Column[PingResponse]] =
    List(
      Column("message", Messages.ColumnMessage, reply => reply.message),
      // The instant as the service reported it, not as this browser reformatted it: an operator comparing
      // this against a log needs the two to be the same string.
      Column("at", Messages.ColumnAt, reply => reply.at.toString),
      Column("service", Messages.ColumnService, reply => reply.service)
    )

  private def errorLine(failure: ApiError): HtmlElement =
    p(
      cls := ClustersCss.Error,
      dataAttr("testid") := "ping-error",
      role := "alert",
      Messages.failed(describe(failure))
    )

  /** A failure as a fragment a sentence can be built around — never a stack trace. */
  private def describe(failure: ApiError): String =
    failure match {
      case ApiError.Envelope(_, text, _, _, _) => text
      case ApiError.Timeout => "the gateway did not answer in time"
      case ApiError.Unreachable(_) => "the gateway could not be reached"
      case ApiError.Decoding(_) => "the answer could not be read"
    }
}

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object ClustersCss {
  val Page = "kui-clusters"
  val Lead = "kui-clusters__lead"
  val Form = "kui-clusters__form"
  val Error = "kui-clusters__error"
  val Stale = "kui-clusters__stale"
  val TableStale = "kui-clusters__table--stale"
  val Fallback = "kui-clusters__fallback"
}
