package kui.ui.clusters

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled, or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object ClustersCss {
  val Page = "kui-clusters"
  val Error = "kui-clusters__error"
  val Fallback = "kui-clusters__fallback"

  val Summary = "kui-clusters__summary"
  val SummaryFigure = "kui-clusters__summary-figure"
  val SummaryValue = "kui-clusters__summary-value"
  val SummaryLabel = "kui-clusters__summary-label"
  val SummaryFetched = "kui-clusters__summary-fetched"

  val Toggle = "kui-clusters__toggle"

  val BrokersHeader = "kui-clusters__brokers-header"
}
