package kui.ui.consumers

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled, or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object ConsumersCss {
  val Page = "kui-consumers"
  val Fallback = "kui-consumers__fallback"
  val Error = "kui-consumers__error"

  val Controls = "kui-consumers__controls"
  val Count = "kui-consumers__count"
  val States = "kui-consumers__states"
  val StateChip = "kui-consumers__state-chip"
  val StateChipOn = "kui-consumers__state-chip--on"

  val GroupCell = "kui-consumers__group"
  val GroupLink = "kui-consumers__group-link"

  val Pace = "kui-consumers__pace"
  val PaceStalled = "kui-consumers__pace--stalled"
  val PaceBackwards = "kui-consumers__pace--backwards"

  val Summary = "kui-consumers__summary"
  val SummaryItem = "kui-consumers__summary-item"
  val SummaryLabel = "kui-consumers__summary-label"
  val SummaryValue = "kui-consumers__summary-value"

  val Section = "kui-consumers__section"
  val SectionHeading = "kui-consumers__section-heading"
  val Note = "kui-consumers__note"

  val TopicHeading = "kui-consumers__topic-heading"
  val TopicName = "kui-consumers__topic-name"
  val PartitionList = "kui-consumers__partitions"
  val Anomaly = "kui-consumers__anomaly"

  val DangerSection = "kui-consumers__danger"
  val DangerAction = "kui-consumers__danger-action"
  val DangerActionHeading = "kui-consumers__danger-action-heading"
  val DangerList = "kui-consumers__danger-list"
  val Receipt = "kui-consumers__receipt"

  val ResetForm = "kui-consumers__reset-form"
  val ResetPlan = "kui-consumers__reset-plan"
  val ResetWarnings = "kui-consumers__reset-warnings"
}
