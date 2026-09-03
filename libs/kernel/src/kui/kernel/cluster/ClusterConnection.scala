package kui.kernel.cluster

import kui.kernel.ClusterId

/** Everything needed to open a client against one cluster, and nothing else.
  *
  * It exists so that a port method takes one parameter instead of four, and so that `ClusterProfile`
  * (CLDOM-001) has exactly one field to compose rather than four to keep in step.
  *
  * It deliberately carries the `ClusterId`: `client.id` is derived from it (KAFKA-004) and the admin client
  * pool is keyed by it, so a connection that did not know which cluster it described would need a second
  * parameter at every call site that has one today.
  */
final case class ClusterConnection(
    id: ClusterId,
    bootstrapServers: BootstrapServers,
    security: ClusterSecurity,
    /** The ADR-022 override layer: raw Kafka client properties an operator set by hand. Applied last by the
      * renderer, and winning on every key it names.
      */
    overrides: ClientProperties,
    admin: AdminTuning
) {

  /** Identity and shape, never credentials.
    *
    * The generated `toString` of a case class prints every field, and the fields here reach transitively into
    * a keystore's bytes and a SASL password. Those are all `Secret`, so they would redact themselves — but
    * the generated rendering would still be a wall of text nobody reads, and one future non-`Secret` field
    * would leak silently. Naming the four things a log line actually wants is both safer and more useful.
    */
  override def toString: String = {
    val mechanism = security.saslMechanism.fold("")(m => s", mechanism=${m.wireName}")
    val extra = if overrides.isEmpty then "" else s", overrides=[${overrides.render}]"
    s"ClusterConnection(${id.value}, ${bootstrapServers.value}, " +
      s"${security.securityProtocol}$mechanism$extra)"
  }
}

object ClusterConnection {
  given CanEqual[ClusterConnection, ClusterConnection] = CanEqual.derived
}
