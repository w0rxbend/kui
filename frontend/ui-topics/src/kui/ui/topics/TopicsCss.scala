package kui.ui.topics

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled, or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object TopicsCss {
  val Page = "kui-topics"
  val Fallback = "kui-topics__fallback"
}
