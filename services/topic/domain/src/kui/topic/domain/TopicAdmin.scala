package kui.topic.domain

import kui.kernel.{ClusterId, TopicName}

/** What one scrape produced: the rows, and what it could not read.
  *
  * `incomplete` is **not** an error channel. A scrape that read 9 998 of 10 000 topics succeeded, and a
  * caller that treated a non-empty `incomplete` as a failure would blank a screen over two unreadable topics.
  * The reason strings are display text — one sentence per topic, safe to show a user.
  */
final case class ScrapeResult(topics: List[TopicSummary], incomplete: Map[TopicName, String]) {
  def isComplete: Boolean = incomplete.isEmpty
}

object ScrapeResult {
  val empty: ScrapeResult = ScrapeResult(Nil, Map.empty)
}

/** Everything the topic context needs from a Kafka cluster, in the topic context's words.
  *
  * The parameter is a `ClusterId` and not a connection. Resolving an id to connection material is an
  * adapter's job, and a domain port that took a `ClusterConnection` would put `libs/kernel`'s security ADT —
  * and therefore the shape of a Kafka client — into a business rule.
  *
  * It shares its name with `libs/kafka`'s `TopicAdmin`, and they are different traits on purpose. That one
  * speaks Kafka's vocabulary (`TopicListing`, `TopicPartitionInfo`, `BatchResult`); this one speaks the topic
  * domain's (`TopicSummary`, `TopicDetail`, `TopicError`). Rule A5 forbids `libs/kafka` from depending on a
  * service and rule A1 forbids this module from depending on `libs/kafka`, so a single trait is not available
  * without breaking the layering. The bridge between them is one exhaustively-matched file in
  * `infrastructure`, which is where every shape a real cluster produces gets a name.
  *
  * Every method is total: a failure is a `TopicError` on the left, never a raised exception. An adapter that
  * let a `TimeoutException` escape would make every use case's type a lie, and `PortContractSuite` asserts
  * against that for every implementation.
  */
trait TopicAdmin[F[_]] {

  /** Every topic of a cluster, with its counts.
    *
    * One call, and not a list of names plus a describe per name, because this is a scrape: the chunking, the
    * parallelism, the order the admin calls have to run in and the offset arithmetic are all the adapter's
    * business (`research/kafka/admin-capabilities.md` DC-D4), and a domain that specified them would have
    * specified a Kafka client.
    *
    * Internal topics are **always** included. Hiding them is a display rule with its own definition in the
    * application layer (DEVPLAN §10 D3), and a port that filtered them here would leave `showInternal=true`
    * with nothing to show.
    */
  def scrape(cluster: ClusterId): F[Either[TopicError, ScrapeResult]]

  /** One topic, read now rather than from a snapshot.
    *
    * This is the one place in M2 where a request costs an admin call. A list is a thousand rows a user scans;
    * a detail page is one topic a user is looking at *because something is wrong with it*, and showing them a
    * minute-old partition assignment during an incident is the wrong trade.
    */
  def detail(cluster: ClusterId, topic: TopicName): F[Either[TopicError, TopicDetail]]

  /** One topic's configuration.
    *
    * `describeConfigs` on a topic KUI may see but may not describe is answered with
    * `TopicConfigView.NotPermitted` and never with `TopicError.Forbidden`: a 403 here would take the whole
    * topic page down, and the partitions the user is entitled to see would vanish with the tab they are not.
    * An `Entries` case that happens to be empty is a different statement — the broker reported no
    * configuration — and the tab says something different for each.
    */
  def config(cluster: ClusterId, topic: TopicName): F[Either[TopicError, TopicConfigView]]
}
