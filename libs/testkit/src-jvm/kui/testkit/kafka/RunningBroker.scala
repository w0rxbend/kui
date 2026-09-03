package kui.testkit.kafka

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** The PKCS12 stores a *client* needs to reach a TLS broker: the CA to trust, and its own certificate. */
final case class TlsMaterials(
    truststore: Path,
    truststorePassword: String,
    keystore: Path,
    keystorePassword: String,
    keyPassword: String
)

/** The same, for the broker itself. Kept apart from the client's so that a suite cannot accidentally present
  * the broker's own key as a client certificate and prove nothing.
  */
final case class BrokerTlsMaterials(
    keystore: Path,
    truststore: Path,
    storePassword: String,
    keyPassword: String
)

/** A SASL user and its password. */
final case class ScramCredentials(username: String, password: String)

/** A broker that is up, and everything a client needs to reach it.
  *
  * @param topology
  *   which security configuration it was started in
  * @param bootstrapServers
  *   `localhost:<mapped port>` — the address a client on this machine can actually reach
  * @param clientProperties
  *   Kafka client properties that reach this broker, already correct. A suite that wants to talk to the
  *   broker directly, without going through KUI, hands these straight to an `AdminClient`. Anything KUI's own
  *   code path produces has to agree with them, which is what makes a parity failure visible.
  * @param materials
  *   where the generated PKCS12 stores live, for the TLS mode; `None` otherwise
  * @param credentials
  *   the provisioned SCRAM user, for the SASL mode; `None` otherwise
  * @param logs
  *   the container's log so far, for a failure message. A function rather than a string, because it is read
  *   only when something has already gone wrong.
  */
final case class RunningBroker(
    topology: KafkaTopology,
    bootstrapServers: String,
    clientProperties: Map[String, String],
    materials: Option[TlsMaterials],
    credentials: Option[ScramCredentials],
    logs: () => String
) {

  /** A name no other suite sharing this broker will use.
    *
    * One broker is shared across the suites that need its topology (twenty seconds per container is worth
    * avoiding), so anything a test creates on it — a topic, a SCRAM user — has to be namespaced or two suites
    * will fight over it in a way that only shows up when they happen to interleave.
    */
  def uniqueName(prefix: String): String = s"$prefix-${RunningBroker.counter.incrementAndGet()}"

  /** Identity and shape, never the SASL password or a store password. */
  override def toString: String =
    s"RunningBroker(${topology.label} at $bootstrapServers)"
}

object RunningBroker {
  private val counter: AtomicInteger = AtomicInteger(0)
}
