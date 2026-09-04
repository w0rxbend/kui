package kui.ui.kernel.feature

/** The features KUI can be built from.
  *
  * ## Why the service id is carried here
  *
  * The gateway's capability registry reports health per *service* (`"cluster"`, `"topic"`, …) and the shell
  * has to decide, per *feature*, whether to show it, dim it or hide it. Keeping the service name on the
  * feature makes that a field access rather than a lookup table maintained in two places — the arrangement
  * where the table and the enum drift apart and a feature silently stops reacting to its service going down.
  *
  * The two are not always the same word (`"clusters"` is a feature; `"cluster"` is the service that backs
  * it), which is exactly why guessing one from the other would not work.
  *
  * Adding a case here is the only registration step for a new microfrontend; see `docs/frontend/features.md`.
  */
enum FeatureId(val value: String, val serviceId: String) {
  case Clusters extends FeatureId("clusters", "cluster")

  /** The feature is `topics` and the service behind it is `topic`, singular. The two are not the same word,
    * which is exactly why one is not guessed from the other.
    */
  case Topics extends FeatureId("topics", "topic")
  // messages, consumers, schemas, connect, ksql, security, metrics and admin follow in their own
  // milestones.
}

object FeatureId {

  /** Reads an id back from a URL or a stored preference. `None` for anything unrecognised, which is a real
    * case: a bookmark can outlive the feature it pointed at.
    */
  def fromValue(raw: String): Option[FeatureId] = values.find(_.value == raw)

  /** The feature backed by a given service, if any. Used when a capability event arrives naming a service and
    * the shell has to find the feature it affects.
    */
  def forService(serviceId: String): Option[FeatureId] = values.find(_.serviceId == serviceId)

  given CanEqual[FeatureId, FeatureId] = CanEqual.derived
}
