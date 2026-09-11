package kui.ksql.application

import fs2.Stream

import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.ksql.domain.{KsqlServerPort, KsqlStatement, QueryFrame}

/** One cluster, as this service needs to see it: a name for a screen, a flag for a refusal, and whether an
  * operator configured a ksqlDB for it.
  *
  * No address and no credentials. The adapter that builds clients holds those, and keeping them out of the
  * type the application layer sees is what keeps a password out of a use case, an audit record and a log line
  * by construction rather than by review — `ConfiguredProfileSource`'s argument, four services over.
  *
  * `configured` is a `Boolean` rather than a list, where the connect service's profile carries the names of
  * its Connect clusters, because `KsqlSettings` is singular: a Kafka cluster has at most one ksqlDB, so there
  * is nothing to name and nothing for a caller to pick between.
  */
final case class KsqlProfileView(
    cluster: ClusterId,
    displayName: String,
    readOnly: Boolean,
    configured: Boolean
)

object KsqlProfileView {
  given CanEqual[KsqlProfileView, KsqlProfileView] = CanEqual.derived
}

/** A push query, which is the one thing this service does that has no end.
  *
  * It is declared here rather than beside `KsqlServerPort` in the domain because its answer is an
  * `fs2.Stream` and rule A1 keeps fs2 out of a domain module — `services/message` splits its browse from its
  * reads for exactly this reason. Everything whose answer is a *value* is in the domain's port; this is the
  * one whose answer is a sequence that arrives over time.
  */
trait KsqlQueryStream[F[_]] {

  /** The frames of one push query, in order: the header once, then a row per record.
    *
    * `Left` is **terminal**: the adapter emits it as the last element and stops. It is a value rather than a
    * raised error because a failure that happens after the response headers have gone has to reach the
    * browser as an `error` event rather than as a connection that simply closes (ADR-035), and a typed left
    * is the only shape the `api` layer can turn into one without catching everything.
    *
    * The stream is not started until it is pulled, and cancelling it closes the request to ksqlDB: the
    * cancellation chain is browser abort → gateway relay cancelled → this fiber cancelled → the HTTP response
    * body closed, which is what ends the query on the server rather than leaving it running for a tab that is
    * gone (ADR-055 §5).
    */
  def rows(statement: KsqlStatement): Stream[F, Either[KuiError, QueryFrame]]
}

/** Everything one ksqlDB cluster can be asked, in one value.
  *
  * One trait over the two ports rather than two lookups, because there is exactly one client per cluster and
  * a caller that had to fetch it twice would be a caller that could hold two different ones.
  */
trait KsqlClient[F[_]] extends KsqlServerPort[F] with KsqlQueryStream[F]

/** How this service learns which clusters exist, which of them have a ksqlDB, and how to reach one (ADR-036,
  * ADR-046).
  *
  * There is no `changes` here and no snapshot. Everything this service reports is read from a server at the
  * moment it is asked, so there is no cached state for a configuration change to invalidate; the only moment
  * a statically configured list can change is a restart.
  */
trait ClusterKsqlSource[F[_]] {

  def profileOf(cluster: ClusterId): F[Either[KuiError, KsqlProfileView]]

  /** Every cluster this service is serving, in id order. The capability report is built from it, which is why
    * a cluster with no ksqlDB at all is still in it: a cluster missing from the report reads as a service
    * that has never heard of it, which the browser draws as a service being down.
    */
  def all: F[List[KsqlProfileView]]

  /** The client for one cluster's ksqlDB.
    *
    * `None` means this deployment configured no ksqlDB for it — or configured one and could not build a
    * client, which is a wiring failure rather than a deployment choice and is why the caller of this
    * distinguishes the two by asking the profile first.
    */
  def client(cluster: ClusterId): F[Option[KsqlClient[F]]]
}
