package kui.ui.kernel.feature

/** Every place in KUI where one feature's screen offers room to another feature's panel.
  *
  * ## Why the ids live here and not on the host
  *
  * A slot id is a string that two microfrontends have to agree on without either of them being able to see
  * the other: the topic page offers `"topic.tabs"`, and the messages and consumer-group features register
  * against it. Written as a literal on each side, it is the M0 review's second process finding — one string
  * typed twice in two files — with a failure mode that no test on either side can see: the guest registers,
  * the host renders, and the tab simply never appears, with no error anywhere.
  *
  * So the ids are declared once, in the kernel, which is below every feature and therefore visible to the
  * host and to every guest. A guest referencing `FeatureSlots.TopicTabs` and a host offering it cannot
  * disagree, because a typo is a compile error.
  *
  * ## What a slot is not
  *
  * A slot is not a promise that anything fills it. A guest that is not in this build, or whose feature has
  * not been downloaded, contributes nothing, and the host renders one tab fewer — never a disabled tab and
  * never a placeholder promising a later milestone (DEVPLAN §10 D13).
  */
object FeatureSlots {

  /** The topic detail screen's tab strip, beside its own Overview and Settings tabs.
    *
    * Guests: the messages feature's "Messages" tab (M3) and the consumer-group feature's "Consumers" tab
    * (M4). Its `PanelContext` carries `cluster` and, in `params`, `"topic"`.
    */
  val TopicTabs: String = "topic.tabs"

  /** The key under which a host puts the topic name into a `PanelContext` for [[TopicTabs]].
    *
    * A guest reads `context.params(FeatureSlots.TopicParam)`. Same argument as the slot ids: the host writes
    * this key and a guest it cannot see reads it, so the two have to be the same constant rather than the
    * same literal.
    */
  val TopicParam: String = "topic"

  /** Every slot this build offers. A host asserts its own id is in here, which is what keeps a slot from
    * being offered under a name nothing was told about.
    */
  val all: List[String] = List(TopicTabs)
}
