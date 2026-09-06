package kui.topic.infrastructure

import java.lang.reflect.{InvocationHandler, Method, Proxy}

import org.apache.kafka.clients.admin.Admin

/** A Kafka `Admin` that answers the calls one suite makes and refuses every other.
  *
  * A dynamic proxy rather than a written class. `Admin` declares more than sixty methods and a sweep makes
  * two of them, so a hand-written stub would be several hundred lines of `???` around the two that carry the
  * behaviour — and every Kafka upgrade that adds a method would break it. Refusing everything else is the
  * useful half: a call the fixture did not account for fails loudly here instead of quietly returning a
  * `null` the adapter would then read as an answer.
  *
  * A second copy of the cluster service's, for the reason `KuiTopicTestSynonyms` records: a test module
  * cannot see another module's test sources. Both are twenty lines and neither has behaviour to drift.
  */
object StubAdmin {

  /** @param answers
    *   keyed by method name and the arguments as they arrived. A method the partial function does not cover
    *   throws, which is the assertion that the adapter under test made no call this fixture did not expect.
    */
  def apply(answers: PartialFunction[(String, List[AnyRef]), AnyRef]): Admin = {
    val handler = new InvocationHandler {
      def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
        val received = Option(args).map(_.toList).getOrElse(Nil)

        answers.applyOrElse(
          (method.getName, received),
          (call: (String, List[AnyRef])) =>
            throw new UnsupportedOperationException(s"this Admin does not answer '${call._1}'")
        )
      }
    }

    Proxy
      .newProxyInstance(classOf[Admin].getClassLoader, Array(classOf[Admin]), handler)
      .asInstanceOf[Admin]
  }
}
