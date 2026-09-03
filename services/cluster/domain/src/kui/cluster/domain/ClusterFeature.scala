package kui.cluster.domain

import kui.kernel.ValidationError

/** The optional things KUI has established it can do against one cluster.
  *
  * A closed enum, probed at connect time and re-probed hourly, never inferred from a version number: managed
  * services advertise a modern version and then refuse `describeConfigs` outright. Only the members M1 reads
  * are declared — a member nothing reads cannot be wrong in a way anyone notices.
  */
enum ClusterFeature {

  /** `describeCluster(includeAuthorizedOperations)` — needs a 2.3 broker. */
  case AuthorizedOperations

  /** `DescribeConfigsOptions.includeDocumentation` — needs a 2.6 broker. */
  case ConfigDocumentation

  /** `describeConfigs(BROKER, id)` answers at all. */
  case BrokerConfigs

  /** `describeLogDirs` is neither unsupported nor refused. */
  case LogDirs

  /** `describeMetadataQuorum` succeeds — needs a 3.3 KRaft cluster. */
  case KRaftQuorum

  /** `incrementalAlterConfigs` — needs 2.3. Probed now, first used in M5. */
  case IncrementalAlterConfigs

  /** The stable wire token: the enum name, lowercase-hyphenated. It is a contract the API encodes and the
    * browser reads, so it is defined once, here.
    */
  def token: String =
    toString
      .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
      .toLowerCase(java.util.Locale.ROOT)
}

object ClusterFeature {

  /** Every member, so that a caller folding over the set cannot forget one. */
  val All: Set[ClusterFeature] = values.toSet

  private val byToken: Map[String, ClusterFeature] = values.map(f => f.token -> f).toMap

  def fromToken(raw: String): Either[ValidationError, ClusterFeature] =
    byToken.get(raw.trim.toLowerCase(java.util.Locale.ROOT)) match {
      case Some(feature) => Right(feature)
      case None =>
        Left(
          ValidationError.Format("feature", s"one of ${byToken.keys.toList.sorted.mkString(", ")}", raw)
        )
    }

  given CanEqual[ClusterFeature, ClusterFeature] = CanEqual.derived
}
