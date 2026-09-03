package kui.kafka

import org.apache.kafka.common.errors.{
  BrokerNotAvailableException,
  SaslAuthenticationException,
  SslAuthenticationException,
  TimeoutException
}

/** Which failures mean "this connection is finished" rather than "this request failed".
  *
  * The set is closed and it is small. Kafbat closes and recreates its admin client on *any* admin error
  * (`research/kafka/admin-capabilities.md` §0, "Invalidation"), which throws away a perfectly good connection
  * every time a user asks about a topic they are not authorized for — and then pays the reconnect cost,
  * including the SASL handshake, on the next request.
  *
  * The four classes here are the ones where the connection itself, rather than the request, is what went
  * wrong. Authentication is included on purpose even though credentials rarely become valid on their own: a
  * rotated SCRAM password or a renewed keytab has to be picked up without restarting KUI, and rebuilding the
  * client is the only way that happens.
  *
  * KAFKA-005's error mapper is asserted against this predicate rather than restating the set, so the two
  * cannot drift apart.
  */
object AdminInvalidation {

  val reconnectClasses: Set[Class[? <: Throwable]] = Set(
    classOf[TimeoutException],
    classOf[SaslAuthenticationException],
    classOf[SslAuthenticationException],
    classOf[BrokerNotAvailableException]
  )

  /** Unwraps first: these arrive wrapped in a `CompletionException`, and a check against the wrapper answers
    * `false` for every one of them.
    */
  def isReconnectClass(t: Throwable): Boolean = {
    val cause = KafkaFutures.unwrap(t)
    reconnectClasses.exists(_.isInstance(cause))
  }
}
