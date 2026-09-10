package kui.connect.domain

import kui.kernel.ConnectorName
import kui.kernel.error.KuiError

/** One Kafka Connect cluster, as this service needs to speak to it.
  *
  * The port is per *Connect cluster* and not per Kafka cluster, because a Kafka cluster has a list of them —
  * `ConnectClusterSettings`' own argument is that one organisation routinely runs one Connect cluster for
  * sources and another for sinks against the same brokers. Anything that aggregates across them belongs a
  * layer up, in the use case, which is also where "one of the two answered" is turned into a screen.
  *
  * ==Nothing here throws==
  *
  * Every method answers a `KuiError` on the left. A worker that is down, slow, rebalancing or rejecting KUI's
  * credentials arrives as a value, because the caller has to keep the rest of the screen working while one
  * upstream is broken.
  */
trait ConnectWorkerPort[F[_]] {

  /** Every connector this worker is running, with each one's tasks expanded.
    *
    * The `unreadable` half of [[ConnectorFacts]] is what a worker that answers a connector list and refuses a
    * status produces. It is not an error: the list is a real answer and the screen can draw the names, so
    * failing the whole call would replace four usable rows with one red panel.
    */
  def connectors: F[Either[KuiError, ConnectorFacts]]

  /** Ask the worker to apply one operation to one connector.
    *
    * `Unit` on the right and not a new state, because the Connect REST API answers `202 Accepted` with an
    * empty body for all three: the cluster has taken the request and will apply it when the workers have
    * agreed. A state returned from here would be the state *before* the operation, dressed up as the result
    * of it. ADR-054 §5 says what an operator sees instead while a restart is in flight.
    */
  def operate(connector: ConnectorName, operation: ConnectorOperation): F[Either[KuiError, Unit]]
}
