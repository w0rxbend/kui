package kui.cluster.infrastructure

import kui.kernel.error.{InfrastructureError, KuiError}

/** Which failures mean "the connection is broken" and which mean "that request was refused".
  *
  * Kafbat recreates its admin client on any Kafka exception coming out of `describeCluster`
  * (`research/kafka/admin-capabilities.md` §0, row "Invalidation"). KUI does not. An authorization failure on
  * one resource says nothing about the socket, and reconnecting on it turns a user who is merely unauthorized
  * into a denial of service: every refused request would cost every other user of that cluster a fresh admin
  * client, a fresh metadata fetch and a fresh set of retry loops.
  *
  * The split is expressed once, here, at the `KuiError` level rather than at the Kafka exception level,
  * because `KafkaErrorMapper` (KAFKA-005) has already classified the exception. Re-examining
  * `org.apache.kafka.common.errors.*` in the adapter would be a second copy of that table, free to drift away
  * from the first.
  *
  * The reconnect-class set is exactly the one the research names — `TimeoutException`,
  * `SaslAuthenticationException`, `SslAuthenticationException` and `BrokerNotAvailableException` — which
  * `KafkaErrorMapper` maps onto `InfrastructureError.Timeout`, `InfrastructureError.AuthFailed` and
  * `InfrastructureError.Unreachable` respectively.
  */
object ReconnectPolicy {

  /** `true` when the right response to this failure is to throw the admin client away.
    *
    * Deliberately a match on the three named cases and not on the `InfrastructureError` trait: the trait also
    * carries `Upstream`, `CircuitOpen` and `Remote`, none of which describe *this* client's socket.
    * `Upstream` is an answer, so the connection works; `CircuitOpen` means KUI never made the call, so there
    * is nothing to learn about the client; `Remote` is a failure another KUI process had, about a connection
    * this process does not own.
    */
  def shouldInvalidate(error: KuiError): Boolean = error match {
    case _: InfrastructureError.Unreachable => true
    case _: InfrastructureError.Timeout => true
    case _: InfrastructureError.AuthFailed => true
    case _ => false
  }
}
