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
}
