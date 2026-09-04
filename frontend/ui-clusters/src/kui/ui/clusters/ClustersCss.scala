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

  val SummaryAlarm = "kui-clusters__summary--alarm"

  val Toggle = "kui-clusters__toggle"

  val ScrapedAt = "kui-clusters__scraped-at"

  val Refresh = "kui-clusters__refresh"
  val RefreshStatus = "kui-clusters__refresh-status"

  val BrokersHeader = "kui-clusters__brokers-header"

  val TabBody = "kui-clusters__tab-body"

  val LogDirs = "kui-clusters__log-dirs"
  val LogDirHeader = "kui-clusters__log-dir-header"
  val LogDirPath = "kui-clusters__log-dir-path"
  val LogDirFigures = "kui-clusters__log-dir-figures"
  val LogDirFigure = "kui-clusters__log-dir-figure"

  val ConfigName = "kui-clusters__config-name"
  val ConfigValue = "kui-clusters__config-value"
  val ConfigRedacted = "kui-clusters__config-redacted"

  // --- The administration screen ---------------------------------------------------------------

  val Note = "kui-clusters__note"
  val Notice = "kui-clusters__notice"

  val AdminControls = "kui-clusters__admin-controls"
  val AdminList = "kui-clusters__admin-list"
  val AdminRow = "kui-clusters__admin-row"
  val AdminRowIdentity = "kui-clusters__admin-row-identity"
  val AdminRowName = "kui-clusters__admin-row-name"
  val AdminRowAddress = "kui-clusters__admin-row-address"
  val AdminRowOrigin = "kui-clusters__admin-row-origin"
  val AdminRowActions = "kui-clusters__admin-row-actions"

  val AdminForm = "kui-clusters__admin-form"
  val AdminFormGroup = "kui-clusters__admin-form-group"
  val AdminFormActions = "kui-clusters__admin-form-actions"
  val AdminToggle = "kui-clusters__admin-toggle"

  val VerdictGood = "kui-clusters__verdict kui-clusters__verdict--good"
  val VerdictBad = "kui-clusters__verdict kui-clusters__verdict--bad"

  // --- The KRaft metadata quorum -----------------------------------------------------------------

  val SectionHeading = "kui-clusters__section-heading"

  val Quorum = "kui-clusters__quorum"
  val QuorumSummary = "kui-clusters__quorum-summary"
  val QuorumItem = "kui-clusters__quorum-item"
  val QuorumItemLabel = "kui-clusters__quorum-item-label"
  val QuorumItemValue = "kui-clusters__quorum-item-value"
  val QuorumWarning = "kui-clusters__quorum-warning"
  val QuorumTable = "kui-clusters__quorum-table"
  val QuorumTableHeading = "kui-clusters__quorum-table-heading"
  val QuorumCaughtUp = "kui-clusters__quorum-lag"
  val QuorumBehind = "kui-clusters__quorum-lag kui-clusters__quorum-lag--behind"
}
