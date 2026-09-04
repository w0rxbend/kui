package kui.ui.topics

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled, or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object TopicsCss {
  val Page = "kui-topics"
  val Fallback = "kui-topics__fallback"
  val Error = "kui-topics__error"

  val Controls = "kui-topics__controls"
  val Toggle = "kui-topics__toggle"
  val Count = "kui-topics__count"

  val Star = "kui-topics__star"
  val StarOn = "kui-topics__star--on"

  val NameCell = "kui-topics__name"
  val NameLink = "kui-topics__name-link"
  val MessagesCell = "kui-topics__messages"

  val Indicators = "kui-topics__indicators"
  val Indicator = "kui-topics__indicator"
  val IndicatorLabel = "kui-topics__indicator-label"
  val IndicatorValue = "kui-topics__indicator-value"
  val IndicatorWarning = "kui-topics__indicator-value--warning"
  val IndicatorDanger = "kui-topics__indicator-value--danger"

  val Replicas = "kui-topics__replicas"

  val Settings = "kui-topics__settings"
  val SettingName = "kui-topics__setting-name"
  val SettingOverridden = "kui-topics__setting-name--overridden"
  val SettingDoc = "kui-topics__setting-doc"
  val SettingMasked = "kui-topics__setting-masked"
  val SettingHint = "kui-topics__setting-hint"

  /** The link from a topic's heading into the message browser. */
  val BrowseLink = "kui-topics__browse-link"

  // --- Administration (M5) ---------------------------------------------------------------------

  val Heading = "kui-topics__heading"
  val CreateWrapper = "kui-topics__create"
  val EditWrapper = "kui-topics__edit"

  val Form = "kui-topics__form"
  val FormHint = "kui-topics__form-hint"
  val FormError = "kui-topics__form-error"
  val FormSection = "kui-topics__form-section"
  val FormSectionTitle = "kui-topics__form-section-title"
  val ConfigRows = "kui-topics__config-rows"
  val ConfigRow = "kui-topics__config-row"

  val SettingsActions = "kui-topics__settings-actions"

  /** The panel holding the two changes that cannot be undone. */
  val Danger = "kui-topics__danger"
  val DangerTitle = "kui-topics__danger-title"
  val DangerSection = "kui-topics__danger-section"
  val DangerSectionTitle = "kui-topics__danger-section-title"
  val DangerControls = "kui-topics__danger-controls"

  val Plan = "kui-topics__plan"
  val PlanSummary = "kui-topics__plan-summary"
  val PlanWarnings = "kui-topics__plan-warnings"
  val PlanWarning = "kui-topics__plan-warning"
  val Receipt = "kui-topics__receipt"

  /** Applied to a control that has stopped being applicable — the partition controls of a topic that has just
    * been deleted. Hidden rather than removed, so the element keeps its state if it comes back.
    */
  val Hidden = "kui-topics__hidden"

  val Panels = "kui-topics__panels"
  val Panel = "kui-topics__panel"
}
