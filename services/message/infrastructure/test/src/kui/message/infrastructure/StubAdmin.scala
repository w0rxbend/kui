package kui.message.infrastructure

import java.lang.reflect.{InvocationHandler, Method, Proxy}

import org.apache.kafka.clients.admin.Admin

/** A Kafka `Admin` that answers the calls one suite makes and refuses every other.
  *
  * A dynamic proxy rather than a written class, for the reason `services/cluster`'s and `services/topic`'s
  * copies give: `Admin` declares more than sixty methods, a purge makes four of them, and a hand-written
  * stub would be several hundred lines of `???` that every Kafka upgrade breaks. Refusing everything else is
  * the useful half — a call the fixture did not account for fails loudly here instead of quietly returning a
  * `null` the adapter would then read as an answer, and the refusal is itself how the `describeConfigs`
  * failure path is driven.
  *
  * A third copy, because a test module cannot see another module's test sources. Twenty lines with no
  * behaviour to drift.
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
