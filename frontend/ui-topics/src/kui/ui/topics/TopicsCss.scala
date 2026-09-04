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

  val Panels = "kui-topics__panels"
  val Panel = "kui-topics__panel"
}
