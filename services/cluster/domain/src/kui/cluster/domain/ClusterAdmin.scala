package kui.cluster.domain

import cats.data.NonEmptyList

import kui.kernel.BrokerId
import kui.kernel.error.KuiError

/** Reading one cluster's topology.
  *
  * Implemented by `services/cluster/infrastructure` over `libs/kafka`; faked by the application's suites, so
  * that every use case above it can be written and tested before an adapter exists.
  *
  * Every method takes the whole `ClusterProfile` rather than a `ClusterId`, because the adapter needs the
  * connection settings to build or look up its client and the domain has no registry to resolve an id
  * against. The caller resolves the id once and passes the profile down, which also means a profile that
  * changed mid-refresh cannot be half-used.
  *
  * `F[_]` carries no bound at all: nothing here composes effects, and the use cases that do ask for what they
  * need.
  */
trait ClusterAdmin[F[_]] {

  /** `describeCluster`, plus the controller mode. Fails only when the cluster cannot be reached or refuses
    * KUI entirely; an absent controller or an absent cluster id is a `Right`.
    */
  def describeCluster(profile: ClusterProfile): F[Either[KuiError, ClusterDescription]]

  /** The detected broker version, with the `inter.broker.protocol.version` fallback.
    *
    * `Right(None)` means the version could not be established — a legitimate answer on a managed service, and
    * not an error, because a UI that shows "unknown" is more honest than one that shows a guess.
    */
  def detectVersion(profile: ClusterProfile): F[Either[KuiError, Option[KafkaVersion]]]

  /** `describeMetadataQuorum`. `Right(None)` on a ZooKeeper cluster or when the call is unauthorized — both
    * are "there is no quorum information here", which is what the caller needs to know.
    */
  def describeQuorum(profile: ClusterProfile): F[Either[KuiError, Option[QuorumInfo]]]

  /** One broker's configuration, sorted by name.
    *
    * Returns `Left(ApplicationError.Unsupported)` — never `Right(Nil)` — when the cluster refuses the call,
    * so that the UI can say "this cluster does not expose broker configuration" instead of showing an empty
    * table that reads as a broker with no settings.
    */
  def brokerConfigs(
      profile: ClusterProfile,
      broker: BrokerId,
      docs: Boolean
  ): F[Either[KuiError, List[ConfigEntry]]]

  /** `describeLogDirs`, per broker, with per-broker partial failure.
    *
    * A `Left` means the whole call failed — unreachable, or unsupported. A `skipped` entry means one broker
    * did not answer while others did, which is the normal shape when one broker is down.
    */
  def describeLogDirs(
      profile: ClusterProfile,
      brokers: NonEmptyList[BrokerId]
  ): F[Either[KuiError, PartialResult[BrokerId, List[LogDir]]]]

  /** One `describeTopics` sweep over every topic, folded into partition counts as it goes.
    *
    * The counts a cluster screen wants — how many partitions a broker hosts, how many it leads, how many are
    * under-replicated cluster-wide — exist in no other call. `describeCluster` knows nothing about partitions
    * and `describeLogDirs` knows only what is on a disk, which is why `BrokerDto.partitionCount` and
    * `leaderCount` shipped as `Option` with nothing to fill them.
    *
    * A `Left` is a sweep that did not happen at all: the listing failed, or the cluster refused. A sweep that
    * happened but could not describe every topic it listed is a `Right` whose `unreadable` set says so, and
    * `TopicSweep.complete` is then the only way to the census — see that type for why a partial sum is worse
    * than no sum.
    *
    * It is one call in name and a batched one in fact: the adapter chunks the topic list by
    * `AdminTuning.topicChunkSize`, because a `describeTopics` of ten thousand names is one response the
    * broker has to build in memory before it can send any of it.
    */
  def sweepPartitions(profile: ClusterProfile): F[Either[KuiError, TopicSweep]]

  /** What this cluster can do, established by probing and never by inferring from a version.
    *
    * Total — it returns a value rather than an `Either` — because "the probe failed" is a third answer the
    * type already carries: those features come back in `unknown`, not in `absent`, and an unreachable cluster
    * produces `ClusterFeatures.unprobed`, which reads correctly as "KUI has established nothing here yet".
    */
  def capabilities(profile: ClusterProfile): F[ClusterFeatures]
}
