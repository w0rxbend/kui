package kui.topic.application

import kui.kernel.TopicName

/** Whether a topic is internal, decided once for the whole product.
  *
  * Two definitions of "internal" disagree, and KUI takes the **union**: Kafka's own `isInternal` flag, or a
  * name beginning with the configured prefix (default `__`).
  *
  * KUI's own metadata topics are the case that settles it. `__kui_config` and `__kui_files` are ordinary
  * topics as far as Kafka is concerned — its `isInternal` flag is false for both — and they are noise as far
  * as an operator browsing a cluster is concerned. So the flag alone would show them in a list that is
  * supposed to be the operator's own topics, and the prefix alone would miss whatever a future Kafka marks
  * internal without using the prefix, `__consumer_offsets` being only today's example. Taking either one is a
  * decision to be wrong in one of those two ways (DEVPLAN §10 D3).
  *
  * The rule lives here, in one function, and the domain deliberately does not apply it: `TopicSummary`
  * carries whatever it was constructed with. One place to state the rule is one place to get it wrong.
  */
object InternalTopics {

  /** The prefix Kafka itself uses for its internal topics, and the default for `kui.topics.internalPrefix`.
    */
  val DefaultPrefix: String = "__"

  def isInternal(name: TopicName, kafkaFlag: Boolean, prefix: String): Boolean =
    kafkaFlag || (prefix.nonEmpty && name.value.startsWith(prefix))
}
